package com.similarproducts.infrastructure.web;

import com.similarproducts.domain.ProductDetail;
import java.math.BigDecimal;

/** ProductDetail as defined in similarProducts.yaml. HTTP shape, separate from the domain model. */
public record ProductDetailResponse(String id, String name, BigDecimal price, boolean availability) {

    static ProductDetailResponse from(ProductDetail product) {
        return new ProductDetailResponse(product.id(), product.name(), product.price(), product.availability());
    }
}
