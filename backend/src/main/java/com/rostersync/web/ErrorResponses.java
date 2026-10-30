package com.rostersync.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rostersync.support.AppException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** The one error shape: {"error": {"code", "message", "requestId", "details"?}}. Never internals, never personal data. */
@Component
public class ErrorResponses {

    private final ObjectMapper json;

    public ErrorResponses(ObjectMapper json) {
        this.json = json;
    }

    public static Map<String, Object> body(AppException error, HttpServletRequest request) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("code", error.code());
        inner.put("message", error.getMessage());
        inner.put("requestId", RequestIdFilter.requestId(request));
        if (error.details() != null) inner.put("details", error.details());
        return Map.of("error", inner);
    }

    /** For filters, which run outside Spring MVC's exception handling. */
    public void write(HttpServletRequest request, HttpServletResponse response, AppException error) throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(error.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body(error, request));
    }
}
