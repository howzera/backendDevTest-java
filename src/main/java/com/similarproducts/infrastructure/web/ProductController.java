package com.similarproducts.infrastructure.web;

import com.similarproducts.application.GetSimilarProducts;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Inbound adapter: exposes the contract of similarProducts.yaml. */
@RestController
public class ProductController {

    private final GetSimilarProducts getSimilarProducts;

    public ProductController(GetSimilarProducts getSimilarProducts) {
        this.getSimilarProducts = getSimilarProducts;
    }

    @GetMapping("/product/{productId}/similar")
    public Mono<List<ProductDetailResponse>> getSimilarProducts(@PathVariable String productId) {
        return getSimilarProducts.execute(productId)
                .map(products -> products.stream().map(ProductDetailResponse::from).toList());
    }
}
