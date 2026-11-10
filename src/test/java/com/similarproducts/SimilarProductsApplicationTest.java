package com.similarproducts;

import static org.assertj.core.api.Assertions.assertThat;

import com.similarproducts.domain.ProductRepository;
import com.similarproducts.infrastructure.productclient.CachedProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The Spring context starts and the decorators are wired with the cache on the outside. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SimilarProductsApplicationTest {

    @Autowired
    private ProductRepository productRepository;

    @Test
    void contextLoadsWithCacheAsOutermostRepository() {
        assertThat(productRepository).isInstanceOf(CachedProductRepository.class);
    }
}
