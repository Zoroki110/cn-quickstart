package com.digitalasset.quickstart.common;

/**
 * Base type for domain-level errors.
 */
public abstract class DomainError {

    private final String code;
    private final String message;
    private final int httpStatus;

    protected DomainError(final String code, final String message, final int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
