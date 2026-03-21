package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

@Provider
public class MockIdNotFoundMapper implements ExceptionMapper<MockIdNotFound> {

    private static final Logger LOG = Logger.getLogger(MockIdNotFoundMapper.class);

    @Override
    public Response toResponse(MockIdNotFound exception) {
        LOG.debugf("Rejecting request with invalid host header: %s", exception.getMessage());
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.TEXT_PLAIN_TYPE)
                .entity(exception.getMessage())
                .build();
    }
}
