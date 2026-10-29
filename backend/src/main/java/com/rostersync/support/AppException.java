package com.rostersync.support;

import java.util.Map;

/**
 * Every error that crosses the HTTP boundary: a stable machine code, a human message that
 * never contains personal data, and an HTTP status. Subclasses name the common cases.
 */
public class AppException extends RuntimeException {

    private final String code;
    private final int status;
    private final Map<String, Object> details;

    public AppException(String code, String message, int status, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details;
    }

    public AppException(String code, String message, int status) {
        this(code, message, status, null);
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static class ValidationException extends AppException {
        public ValidationException(String code, String message) {
            super(code, message, 400);
        }

        public ValidationException(String code, String message, Map<String, Object> details) {
            super(code, message, 400, details);
        }
    }

    public static class UnauthenticatedException extends AppException {
        public UnauthenticatedException(String message) {
            super("UNAUTHENTICATED", message, 401);
        }

        public UnauthenticatedException() {
            this("Please sign in to continue.");
        }
    }

    public static class ForbiddenException extends AppException {
        public ForbiddenException() {
            super("FORBIDDEN", "Your role doesn't allow this action.", 403);
        }
    }

    public static class NotFoundException extends AppException {
        public NotFoundException(String what) {
            super("NOT_FOUND", what + " not found.", 404);
        }
    }

    public static class ConflictException extends AppException {
        public ConflictException(String code, String message) {
            super(code, message, 409);
        }

        public ConflictException(String code, String message, Map<String, Object> details) {
            super(code, message, 409, details);
        }
    }

    public static class PayloadTooLargeException extends AppException {
        public PayloadTooLargeException(String message) {
            super("PAYLOAD_TOO_LARGE", message, 413);
        }
    }

    public static class UnsupportedMediaTypeException extends AppException {
        public UnsupportedMediaTypeException(String message) {
            super("UNSUPPORTED_MEDIA_TYPE", message, 415);
        }
    }

    public static class TooManyRequestsException extends AppException {
        public TooManyRequestsException() {
            super("RATE_LIMITED", "Too many attempts. Please wait a minute and try again.", 429);
        }
    }
}
