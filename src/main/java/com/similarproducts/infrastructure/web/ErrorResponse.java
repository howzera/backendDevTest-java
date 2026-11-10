package com.similarproducts.infrastructure.web;

/** Error body, same shape for every error: {"detail": "..."}. */
public record ErrorResponse(String detail) {}
