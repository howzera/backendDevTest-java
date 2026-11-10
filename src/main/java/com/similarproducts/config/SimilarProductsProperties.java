package com.similarproducts.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Runtime configuration (application.yml, overridable with environment variables).
 *
 * @param productsApiBaseUrl     base URL of the existing APIs (existingApis.yaml)
 * @param detailTimeout          max time a request waits for one similar product before leaving it out
 * @param upstreamTimeout        hard limit for any single upstream HTTP call
 * @param upstreamConnectTimeout max time to open a TCP connection to the upstream
 * @param upstreamMaxConnections size of the shared connection pool
 */
@ConfigurationProperties("similar-products")
public record SimilarProductsProperties(
        @DefaultValue("http://localhost:3001") String productsApiBaseUrl,
        @DefaultValue("1500ms") Duration detailTimeout,
        @DefaultValue("10s") Duration upstreamTimeout,
        @DefaultValue("2s") Duration upstreamConnectTimeout,
        @DefaultValue("500") int upstreamMaxConnections,
        @DefaultValue("300s") Duration cacheTtl,
        @DefaultValue("60s") Duration cacheNotFoundTtl,
        @DefaultValue("10000") long cacheMaxEntries,
        @DefaultValue("5") int circuitFailureThreshold,
        @DefaultValue("10s") Duration circuitResetTimeout) {}
