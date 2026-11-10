package com.similarproducts.application;

import com.similarproducts.domain.ProductDetail;
import com.similarproducts.domain.ProductNotFoundException;
import com.similarproducts.domain.ProductRepository;
import com.similarproducts.domain.ProductUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Use case: details of the products similar to a given one, in similarity order.
 *
 * <p>Details are fetched concurrently. A similar product that fails, does not exist or exceeds
 * {@code detailTimeout} is left out (partial response).
 */
public class GetSimilarProducts {

    private static final Logger log = LoggerFactory.getLogger(GetSimilarProducts.class);

    private final ProductRepository repository;
    private final Duration detailTimeout;

    public GetSimilarProducts(ProductRepository repository, Duration detailTimeout) {
        this.repository = repository;
        this.detailTimeout = detailTimeout;
    }

    public Mono<List<ProductDetail>> execute(String productId) {
        return repository.getSimilarIds(productId)
                .flatMapMany(Flux::fromIterable)
                .distinct() // the contract says uniqueItems
                // Concurrent like flatMap, but emits in the input order (similarity), not arrival order.
                .flatMapSequential(this::fetchDetail)
                .collectList();
    }

    private Mono<ProductDetail> fetchDetail(String productId) {
        return repository.getProduct(productId)
                .timeout(detailTimeout)
                .onErrorResume(ProductNotFoundException.class, error -> {
                    log.debug("Similar product {} not found, skipped", productId);
                    return Mono.empty();
                })
                .onErrorResume(ProductUnavailableException.class, error -> {
                    log.debug("Similar product {} unavailable ({}), skipped", productId, error.reason());
                    return Mono.empty();
                })
                .onErrorResume(TimeoutException.class, error -> {
                    log.debug("Similar product {} exceeded {}, skipped", productId, detailTimeout);
                    return Mono.empty();
                });
    }
}
