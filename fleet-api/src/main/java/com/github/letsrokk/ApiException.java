package com.github.letsrokk;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

final class ApiException extends WebApplicationException {

    ApiException(Response.Status status, ApiError error) {
        super(error.message(), Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(error)
                .build());
    }

    static ApiException badRequest(String code, String message, Map<String, Object> details) {
        return new ApiException(Response.Status.BAD_REQUEST,
                new ApiError(code, message, false, false, details));
    }
}
