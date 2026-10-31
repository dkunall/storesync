package com.storesync.shared.error;

import java.util.Map;

/** A failure reported to API clients as problem+json. {@code code} is stable; clients can switch on it. */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "not-found", what + " not found");
    }

    public static ApiException invalid(String message) {
        return new ApiException(400, "invalid-request", message);
    }

    public static ApiException invalid(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(403, code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(422, code, message);
    }

    public static ApiException tooMany(String code, String message, long retryAfterSeconds) {
        return new ApiException(429, code, message, Map.of("retryAfterSeconds", retryAfterSeconds));
    }
}
