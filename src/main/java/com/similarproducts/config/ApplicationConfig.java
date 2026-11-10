package com.similarproducts.config;

import com.similarproducts.application.GetSimilarProducts;
import com.similarproducts.domain.ProductRepository;
import com.similarproducts.infrastructure.productclient.CachedProductRepository;
import com.similarproducts.infrastructure.productclient.CircuitBreakerProductRepository;
import com.similarproducts.infrastructure.productclient.HttpProductRepository;
import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/** Composition root: the only place that knows every concrete adapter. */
@Configuration
public class ApplicationConfig {

    /** One shared client: a single connection pool for every request. */
    @Bean
    WebClient productsApiWebClient(WebClient.Builder builder, SimilarProductsProperties properties) {
        ConnectionProvider pool = ConnectionProvider.builder("products-api")
                .maxConnections(properties.upstreamMaxConnections())
                .pendingAcquireTimeout(properties.upstreamTimeout())
                .build();
        HttpClient httpClient = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.upstreamConnectTimeout().toMillis())
                .responseTimeout(properties.upstreamTimeout());
        return builder
                .baseUrl(properties.productsApiBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /** Decorators around the same port: cache -> circuit breaker -> HTTP. */
    @Bean
    ProductRepository productRepository(WebClient productsApiWebClient, SimilarProductsProperties properties) {
        return new CachedProductRepository(
                new CircuitBreakerProductRepository(
                        new HttpProductRepository(productsApiWebClient),
                        properties.circuitFailureThreshold(),
                        properties.circuitResetTimeout()),
                properties.cacheTtl(),
                properties.cacheNotFoundTtl(),
                properties.cacheMaxEntries());
    }

    @Bean
    GetSimilarProducts getSimilarProducts(ProductRepository productRepository, SimilarProductsProperties properties) {
        return new GetSimilarProducts(productRepository, properties.detailTimeout());
    }
}
