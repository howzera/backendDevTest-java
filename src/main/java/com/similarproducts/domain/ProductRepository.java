package com.similarproducts.domain;

import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Output port: what the domain needs from the outside world to get products.
 *
 * <p>Implementations must signal {@link ProductNotFoundException} when the product does not exist
 * and {@link ProductUnavailableException} when the source fails.
 */
public interface ProductRepository {

    /** Ids of the products similar to {@code productId}, ordered by similarity. */
    Mono<List<String>> getSimilarIds(String productId);

    /** Detail of a single product. */
    Mono<ProductDetail> getProduct(String productId);
}
