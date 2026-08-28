package com.github.letsrokk;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {

    @Override
    public Response toResponse(WebApplicationException exception) {
        Response original = exception.getResponse();
        if (original.getEntity() instanceof ApiError) {
            return original;
        }
        int status = original.getStatus();
        String message = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Request failed."
                : exception.getMessage();
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ApiError("HTTP_" + status, message, status >= 500, false, Map.of()))
                .build();
    }
}
