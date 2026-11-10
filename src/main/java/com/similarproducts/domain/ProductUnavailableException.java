package com.similarproducts.domain;

/** The product source failed (error, timeout, open circuit). The product may exist. */
public class ProductUnavailableException extends RuntimeException {

    private final String productId;
    private final String reason;

    public ProductUnavailableException(String productId, String reason) {
        this(productId, reason, null);
    }

    public ProductUnavailableException(String productId, String reason, Throwable cause) {
        super("Product '" + productId + "' unavailable: " + reason, cause, false, false);
        this.productId = productId;
        this.reason = reason;
    }

    public String productId() {
        return productId;
    }

    public String reason() {
        return reason;
    }
}
