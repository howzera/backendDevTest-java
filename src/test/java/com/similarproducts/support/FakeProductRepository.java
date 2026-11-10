package com.similarproducts.support;

import com.similarproducts.domain.ProductDetail;
import com.similarproducts.domain.ProductNotFoundException;
import com.similarproducts.domain.ProductRepository;
import com.similarproducts.domain.ProductUnavailableException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;

/** In-memory ProductRepository that can simulate missing, failing and slow products. */
public class FakeProductRepository implements ProductRepository {

    public final Map<String, List<String>> similarIds = new HashMap<>();
    public final Map<String, ProductDetail> products = new HashMap<>();
    public final Set<String> failing = new HashSet<>();
    public final Set<String> failingSimilarIds = new HashSet<>();
    public final Map<String, Duration> delays = new HashMap<>();
    /** Upstream calls actually made (counted on subscription, not on method call). */
    public final List<String> productCalls = new CopyOnWriteArrayList<>();
    public final List<String> similarCalls = new CopyOnWriteArrayList<>();

    public static ProductDetail product(String id) {
        return new ProductDetail(id, "Product " + id, new BigDecimal("10.0"), true);
    }

    public FakeProductRepository withSimilar(String id, String... similar) {
        similarIds.put(id, List.of(similar));
        return this;
    }

    public FakeProductRepository withProducts(String... ids) {
        for (String id : ids) {
            products.put(id, product(id));
        }
        return this;
    }

    public FakeProductRepository withFailing(String id) {
        failing.add(id);
        return this;
    }

    public FakeProductRepository withDelay(String id, Duration delay) {
        delays.put(id, delay);
        return this;
    }

    @Override
    public Mono<List<String>> getSimilarIds(String productId) {
        return Mono.defer(() -> {
            similarCalls.add(productId);
            if (failingSimilarIds.contains(productId)) {
                return Mono.error(new ProductUnavailableException(productId, "simulated failure"));
            }
            List<String> ids = similarIds.get(productId);
            return ids == null ? Mono.error(new ProductNotFoundException(productId)) : Mono.just(ids);
        });
    }

    @Override
    public Mono<ProductDetail> getProduct(String productId) {
        return Mono.defer(() -> {
            productCalls.add(productId);
            Mono<ProductDetail> result;
            if (failing.contains(productId)) {
                result = Mono.error(new ProductUnavailableException(productId, "simulated failure"));
            } else if (!products.containsKey(productId)) {
                result = Mono.error(new ProductNotFoundException(productId));
            } else {
                result = Mono.just(products.get(productId));
            }
            Duration delay = delays.get(productId);
            return delay == null ? result : Mono.delay(delay).then(result);
        });
    }
}
