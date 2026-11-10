package com.similarproducts.infrastructure.productclient;

import com.similarproducts.domain.ProductDetail;
import com.similarproducts.domain.ProductRepository;
import com.similarproducts.domain.ProductUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * ProductRepository decorator with one circuit breaker per resource (e.g. "product:6").
 *
 * <p>After {@code failureThreshold} consecutive failures the circuit opens and calls fail fast for
 * {@code resetTimeout}. Then ONE trial call decides: success closes it, failure opens it again.
 * A broken product does not cut off the healthy ones. "Not found" counts as a success.
 */
public class CircuitBreakerProductRepository implements ProductRepository {

    private final ProductRepository inner;
    private final CircuitBreakerRegistry registry;

    public CircuitBreakerProductRepository(ProductRepository inner, int failureThreshold, Duration resetTimeout) {
        this.inner = inner;
        this.registry = CircuitBreakerRegistry.of(config(failureThreshold, resetTimeout));
    }

    private static CircuitBreakerConfig config(int failureThreshold, Duration resetTimeout) {
        return CircuitBreakerConfig.custom()
                // "N consecutive failures" = 100% failure rate over a window of the last N calls.
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(failureThreshold)
                .minimumNumberOfCalls(failureThreshold)
                .failureRateThreshold(100)
                .waitDurationInOpenState(resetTimeout)
                .permittedNumberOfCallsInHalfOpenState(1)
                // Only real failures count; any other outcome (e.g. not found) is recorded as a success.
                .recordExceptions(ProductUnavailableException.class)
                .build();
    }

    @Override
    public Mono<List<String>> getSimilarIds(String productId) {
        return protect("similar-ids:" + productId, productId, () -> inner.getSimilarIds(productId));
    }

    @Override
    public Mono<ProductDetail> getProduct(String productId) {
        return protect("product:" + productId, productId, () -> inner.getProduct(productId));
    }

    /** Visible for tests. */
    CircuitBreaker.State state(String resource) {
        return registry.circuitBreaker(resource).getState();
    }

    private <T> Mono<T> protect(String resource, String productId, Supplier<Mono<T>> call) {
        CircuitBreaker breaker = registry.circuitBreaker(resource);
        return Mono.defer(call)
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .onErrorMap(CallNotPermittedException.class,
                        error -> new ProductUnavailableException(productId, "circuit open", error));
    }
}
