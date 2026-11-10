package com.similarproducts.domain;

/** The requested product does not exist. A valid business answer, not a failure. */
public class ProductNotFoundException extends RuntimeException {

    private final String productId;

    public ProductNotFoundException(String productId) {
        // No stack trace: this is an expected outcome, thrown often under load.
        super("Product '" + productId + "' not found", null, false, false);
        this.productId = productId;
    }

    public String productId() {
        return productId;
    }
}
