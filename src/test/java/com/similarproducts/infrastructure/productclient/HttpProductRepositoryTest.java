package com.similarproducts.infrastructure.productclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.similarproducts.domain.ProductDetail;
import com.similarproducts.domain.ProductNotFoundException;
import com.similarproducts.domain.ProductUnavailableException;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

/** Real HTTP against a local server replaying the upstream responses (shared/simulado/mocks.json and curl). */
class HttpProductRepositoryTest {

    private static final String JSON = "application/json";

    private MockWebServer server;
    private HttpProductRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return switch (request.getPath()) {
                    case "/product/1/similarids" -> json(200, "[2,3,4]");
                    case "/product/6/similarids" -> new MockResponse().setResponseCode(404).setBody("Not Found");
                    case "/product/1" -> json(200, "{\"id\":\"1\",\"name\":\"Shirt\",\"price\":9.99,\"availability\":true}");
                    case "/product/5" -> json(404, "{\"message\":\"Product not found\"}");
                    case "/product/6" -> new MockResponse().setResponseCode(500).setHeader("Content-Type", JSON);
                    case "/product/7" -> json(200, "{\"id\":\"7\",\"name\":\"No price\"}");
                    case "/product/8" -> new MockResponse().setHeader("Content-Type", "text/html").setBody("<html>oops</html>");
                    case "/product/9" -> json(200, "{}").setHeadersDelay(2, TimeUnit.SECONDS);
                    case "/product/a%2Fb" -> json(200, "{\"id\":\"a/b\",\"name\":\"Encoded\",\"price\":1,\"availability\":false}");
                    default -> new MockResponse().setResponseCode(418);
                };
            }
        });
        server.start();
        repository = new HttpProductRepository(webClient(server.url("/").toString()));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", JSON).setBody(body);
    }

    private static WebClient webClient(String baseUrl) {
        HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofMillis(300));
        return WebClient.builder().baseUrl(baseUrl).clientConnector(new ReactorClientHttpConnector(httpClient)).build();
    }

    @Test
    void similarIdsAreConvertedToStrings() {
        assertThat(repository.getSimilarIds("1").block()).containsExactly("2", "3", "4");
    }

    @Test
    void similarIdsPlainText404FailsWithNotFound() {
        StepVerifier.create(repository.getSimilarIds("6")).expectError(ProductNotFoundException.class).verify();
    }

    @Test
    void productIsMappedToDomain() {
        assertThat(repository.getProduct("1").block())
                .isEqualTo(new ProductDetail("1", "Shirt", new BigDecimal("9.99"), true));
    }

    @Test
    void product404FailsWithNotFound() {
        StepVerifier.create(repository.getProduct("5")).expectError(ProductNotFoundException.class).verify();
    }

    @Test
    void product500WithEmptyBodyFailsWithUnavailable() {
        StepVerifier.create(repository.getProduct("6"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(ProductUnavailableException.class)
                        .hasMessageContaining("status 500"))
                .verify();
    }

    @Test
    void productWithMissingFieldsFailsWithUnavailable() {
        assertUnavailable("7", "invalid body");
    }

    @Test
    void productWithNonJsonBodyFailsWithUnavailable() {
        assertUnavailable("8", "invalid body");
    }

    @Test
    void timeoutFailsWithUnavailable() {
        assertUnavailable("9", "timeout");
    }

    @Test
    void connectionErrorFailsWithUnavailable() throws IOException {
        server.shutdown();

        assertUnavailable("1", "connection error");
    }

    @Test
    void productIdIsEncodedInThePath() {
        assertThat(repository.getProduct("a/b").block().name()).isEqualTo("Encoded");
    }

    private void assertUnavailable(String productId, String reason) {
        StepVerifier.create(repository.getProduct(productId))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(ProductUnavailableException.class)
                        .hasMessageContaining(reason))
                .verify(Duration.ofSeconds(5));
    }
}
