package com.similarproducts.infrastructure.web;

import com.similarproducts.domain.ProductNotFoundException;
import com.similarproducts.domain.ProductUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Translates domain errors into consistent HTTP error responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ProductNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(ProductNotFoundException error) {
        return new ErrorResponse(error.getMessage());
    }

    @ExceptionHandler(ProductUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ErrorResponse handleUnavailable(ProductUnavailableException error) {
        log.warn("Similar products for '{}' unavailable: {}", error.productId(), error.reason());
        return new ErrorResponse("Similar products for '" + error.productId() + "' are temporarily unavailable");
    }
}
