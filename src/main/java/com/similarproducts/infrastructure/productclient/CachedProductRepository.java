package com.similarproducts.infrastructure.productclient;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.similarproducts.domain.ProductDetail;
import com.similarproducts.domain.ProductNotFoundException;
import com.similarproducts.domain.ProductRepository;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * ProductRepository decorator: in-memory cache with TTL, max size and single-flight.
 *
 * <p>Concurrent requests for the same key share one upstream call (Caffeine stores the in-flight
 * future). The call is detached from its callers, so a caller that times out does not cancel it and
 * its result is still cached ("late completion"). Successes and "not found" are cached; failures
 * are not.
 */
public class CachedProductRepository implements ProductRepository {

    /** What the cache stores: a value, the fact that the product does not exist, or a failure. */
    private sealed interface Lookup permits Found, Missing, Failed {}

    private record Found(Object value) implements Lookup {}

    private record Missing(String productId) implements Lookup {}

    /** Zero TTL: shared by the callers already waiting on the load, never served to later ones. */
    private record Failed(Throwable error) implements Lookup {}

    private final ProductRepository inner;
    private final AsyncCache<String, Lookup> cache;

    public CachedProductRepository(ProductRepository inner, Duration ttl, Duration notFoundTtl, long maxEntries) {
        this(inner, ttl, notFoundTtl, maxEntries, Ticker.systemTicker());
    }

    /** The ticker is injectable so TTL tests do not need to sleep. */
    CachedProductRepository(ProductRepository inner, Duration ttl, Duration notFoundTtl, long maxEntries, Ticker ticker) {
        this.inner = inner;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfter(new LookupExpiry(ttl, notFoundTtl))
                .ticker(ticker)
                .buildAsync();
    }

    @Override
    public Mono<List<String>> getSimilarIds(String productId) {
        return getOrLoad("similar-ids:" + productId, () -> inner.getSimilarIds(productId));
    }

    @Override
    public Mono<ProductDetail> getProduct(String productId) {
        return getOrLoad("product:" + productId, () -> inner.getProduct(productId));
    }

    @SuppressWarnings("unchecked")
    private <T> Mono<T> getOrLoad(String key, Supplier<Mono<T>> load) {
        return Mono.defer(() -> {
            CompletableFuture<Lookup> shared = cache.get(key, (k, executor) -> load.get()
                    .<Lookup>map(Found::new)
                    .onErrorResume(error -> Mono.just(error instanceof ProductNotFoundException notFound
                            ? new Missing(notFound.productId())
                            : new Failed(error)))
                    .toFuture()); // subscribes now: the load runs on its own, independent of any caller
            // suppressCancel: a caller cancelling (e.g. its timeout) must not cancel the shared load.
            return Mono.fromFuture(shared, true).flatMap(lookup -> {
                if (lookup instanceof Missing missing) {
                    return Mono.error(new ProductNotFoundException(missing.productId()));
                }
                if (lookup instanceof Failed failed) {
                    return Mono.error(failed.error());
                }
                return Mono.just((T) ((Found) lookup).value());
            });
        });
    }

    /** "Not found" lives less than real values; failures are not cached at all. */
    private record LookupExpiry(Duration ttl, Duration notFoundTtl) implements Expiry<String, Lookup> {

        @Override
        public long expireAfterCreate(String key, Lookup value, long currentTime) {
            if (value instanceof Failed) {
                return 0;
            }
            return (value instanceof Missing ? notFoundTtl : ttl).toNanos();
        }

        @Override
        public long expireAfterUpdate(String key, Lookup value, long currentTime, long currentDuration) {
            return expireAfterCreate(key, value, currentTime);
        }

        @Override
        public long expireAfterRead(String key, Lookup value, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }
}
