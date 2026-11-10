package com.similarproducts.infrastructure.web;

import com.similarproducts.application.GetSimilarProducts;
import com.similarproducts.support.FakeProductRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

class ProductControllerTest {

    private static WebTestClient client(FakeProductRepository repository) {
        GetSimilarProducts useCase = new GetSimilarProducts(repository, Duration.ofMillis(200));
        return WebTestClient.bindToController(new ProductController(useCase))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static FakeProductRepository repository() {
        return new FakeProductRepository().withSimilar("1", "2", "3").withSimilar("4", "1", "5").withProducts("1", "2", "3");
    }

    @Test
    void returnsContractShape() {
        client(repository()).get().uri("/product/1/similar").exchange()
                .expectStatus().isOk()
                .expectBody().json("""
                        [
                          {"id": "2", "name": "Product 2", "price": 10.0, "availability": true},
                          {"id": "3", "name": "Product 3", "price": 10.0, "availability": true}
                        ]""", true);
    }

    @Test
    void returnsPartialListWhenASimilarProductIsMissing() {
        client(repository()).get().uri("/product/4/similar").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo("1");
    }

    @Test
    void returns404WhenProductDoesNotExist() {
        client(repository()).get().uri("/product/999/similar").exchange()
                .expectStatus().isNotFound()
                .expectBody().json("{\"detail\": \"Product '999' not found\"}", true);
    }

    @Test
    void returns503WhenSimilarIdsSourceIsUnavailable() {
        FakeProductRepository down = new FakeProductRepository();
        down.failingSimilarIds.add("1");

        client(down).get().uri("/product/1/similar").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().json("{\"detail\": \"Similar products for '1' are temporarily unavailable\"}", true);
    }
}
