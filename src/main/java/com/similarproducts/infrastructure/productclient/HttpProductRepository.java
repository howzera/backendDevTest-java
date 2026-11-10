package com.similarproducts.infrastructure.productclient;

import com.similarproducts.domain.ProductDetail;
import com.similarproducts.domain.ProductNotFoundException;
import com.similarproducts.domain.ProductRepository;
import com.similarproducts.domain.ProductUnavailableException;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

/**
 * ProductRepository adapter for the existing product APIs (see existingApis.yaml).
 *
 * <p>404 -> ProductNotFoundException; any other error, timeout or unexpected body ->
 * ProductUnavailableException. Only the status code is used: the upstream 404 body is sometimes
 * plain text and the 500 body is empty.
 */
public class HttpProductRepository implements ProductRepository {

    private static final ParameterizedTypeReference<List<String>> STRING_LIST = new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public HttpProductRepository(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<List<String>> getSimilarIds(String productId) {
        // The upstream returns numbers ([2, 3, 4]) although the contract says strings: Jackson coerces them.
        return get("/product/{id}/similarids", productId, response -> response.bodyToMono(STRING_LIST));
    }

    @Override
    public Mono<ProductDetail> getProduct(String productId) {
        return get("/product/{id}", productId, response -> response.bodyToMono(ProductPayload.class))
                .map(payload -> payload.toDomain(productId));
    }

    private <T> Mono<T> get(String uriTemplate, String productId, Function<ClientResponse, Mono<T>> bodyReader) {
        return webClient.get()
                // URI template: the id is encoded, so it can never change the upstream path.
                .uri(uriTemplate, productId)
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status == HttpStatus.NOT_FOUND.value()) {
                        return response.releaseBody().then(Mono.error(new ProductNotFoundException(productId)));
                    }
                    if (response.statusCode().isError()) {
                        ProductUnavailableException error = new ProductUnavailableException(productId, "upstream status " + status);
                        return response.releaseBody().then(Mono.error(error));
                    }
                    return bodyReader.apply(response);
                })
                .switchIfEmpty(Mono.error(() -> new ProductUnavailableException(productId, "empty body")))
                .onErrorMap(error -> !isDomainError(error), error -> toUnavailable(productId, error));
    }

    private static boolean isDomainError(Throwable error) {
        return error instanceof ProductNotFoundException || error instanceof ProductUnavailableException;
    }

    private static ProductUnavailableException toUnavailable(String productId, Throwable error) {
        if (isTimeout(error)) {
            return new ProductUnavailableException(productId, "timeout", error);
        }
        if (error instanceof WebClientRequestException) {
            return new ProductUnavailableException(productId, "connection error: " + error.getMessage(), error);
        }
        return new ProductUnavailableException(productId, "invalid body: " + error.getMessage(), error);
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException || current instanceof io.netty.handler.timeout.TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** Upstream body. Separate from the domain model so a malformed body never reaches the core. */
    record ProductPayload(String id, String name, BigDecimal price, Boolean availability) {

        ProductDetail toDomain(String requestedId) {
            if (id == null || name == null || price == null || availability == null) {
                throw new ProductUnavailableException(requestedId, "invalid body: missing fields");
            }
            return new ProductDetail(id, name, price, availability);
        }
    }
}
