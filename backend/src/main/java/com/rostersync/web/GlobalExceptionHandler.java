package com.rostersync.web;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.rostersync.support.AppException;
import com.rostersync.support.AppException.ForbiddenException;
import com.rostersync.support.AppException.NotFoundException;
import com.rostersync.support.AppException.UnauthenticatedException;
import com.rostersync.support.AppException.UnsupportedMediaTypeException;
import com.rostersync.support.AppException.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** The only place exceptions become HTTP responses. 5xx bodies never include internals. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    ResponseEntity<Map<String, Object>> app(AppException e, HttpServletRequest request) {
        log.info("Request rejected {} {}", kv("code", e.code()), kv("status", e.status()));
        return respond(e, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpServletRequest request) {
        return respond(new ValidationException("INVALID_JSON", "The request body is not valid JSON."), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> mediaType(HttpServletRequest request) {
        return respond(new UnsupportedMediaTypeException("Send the request body as application/json."), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> typeMismatch(HttpServletRequest request) {
        return respond(new ValidationException("INVALID_QUERY", "Check the filters and try again."), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> denied(HttpServletRequest request) {
        return respond(new ForbiddenException(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Map<String, Object>> unauthenticated(HttpServletRequest request) {
        return respond(new UnauthenticatedException(), request);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<Map<String, Object>> notFound(HttpServletRequest request) {
        return respond(new NotFoundException("Route"), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        // Exception class and our own stack only: driver messages can echo row values.
        log.error("Unhandled error {} {}", kv("error", e.getClass().getName()), kv("path", request.getRequestURI()), e);
        return respond(new AppException("INTERNAL_ERROR", "Something went wrong on our side. Quote the request id if you contact support.", 500), request);
    }

    private static ResponseEntity<Map<String, Object>> respond(AppException e, HttpServletRequest request) {
        return ResponseEntity.status(e.status()).body(ErrorResponses.body(e, request));
    }
}
