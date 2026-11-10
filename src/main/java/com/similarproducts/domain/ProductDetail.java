package com.similarproducts.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** Product detail (immutable value object). BigDecimal: a price must not suffer floating point rounding. */
public record ProductDetail(String id, String name, BigDecimal price, boolean availability) {

    public ProductDetail {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(price, "price");
    }
}
