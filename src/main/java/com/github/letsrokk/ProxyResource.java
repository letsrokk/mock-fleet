package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.*;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.ClientWebApplicationException;

@Path("/{path: .*}")
public class ProxyResource {

    private static final Logger LOG = Logger.getLogger(ProxyResource.class);

    @Inject
    PodManager podManager;

    @Inject
    ProxyClientFactory proxyClientFactory;

    @GET
    public Response acceptGet(@Context ContainerRequestContext requestContext) {
        return proxyRequest(requestContext, null);
    }

    @POST
    public Response acceptPost(@Context ContainerRequestContext requestContext, byte[] body) {
        return proxyRequest(requestContext, body);
    }

    @PUT
    public Response acceptPut(@Context ContainerRequestContext requestContext, byte[] body) {
        return proxyRequest(requestContext, body);
    }

    @PATCH
    public Response acceptPatch(@Context ContainerRequestContext requestContext, byte[] body) {
        return proxyRequest(requestContext, body);
    }

    @DELETE
    public Response acceptDelete(@Context ContainerRequestContext requestContext, byte[] body) {
        return proxyRequest(requestContext, body);
    }

    @HEAD
    public Response acceptHead(@Context ContainerRequestContext requestContext) {
        return proxyRequest(requestContext, null);
    }

    @OPTIONS
    public Response acceptOptions(@Context ContainerRequestContext requestContext, byte[] body) {
        return proxyRequest(requestContext, body);
    }

    Response proxyRequest(ContainerRequestContext requestContext, byte[] body) {
        String method = requestContext.getMethod();
        String host = requestContext.getHeaderString(HttpHeaders.HOST);
        String targetBaseUrl = podManager.getUpstreamBaseUrl(host);
        MultivaluedMap<String, String> params = requestContext.getUriInfo().getQueryParameters();
        String path = requestContext.getUriInfo().getRequestUri().getRawPath();
        if (path == null) {
            path = "";
        } else if (path.startsWith("/")) {
            path = path.substring(1);
        }

        LOG.debugf("Proxying %s %s for host '%s' to upstream %s.", method, path, host, targetBaseUrl);
        return proxyRequest(method, targetBaseUrl, path, params, body);
    }

    Response proxyRequest(String method,
                          String targetBaseUrl,
                          String path,
                          MultivaluedMap<String, String> params,
                          byte[] body) {
        ProxyClient dynamicProxyClient =
                proxyClientFactory.createClient(targetBaseUrl);

        Response mockResponse;
        try {
            mockResponse = switch (method) {
                case HttpMethod.GET -> dynamicProxyClient.forwardGet(path, params);
                case "POST" -> dynamicProxyClient.forwardPost(path, params, body);
                case "PUT" -> dynamicProxyClient.forwardPut(path, params, body);
                case "PATCH" -> dynamicProxyClient.forwardPatch(path, params, body);
                case "DELETE" -> dynamicProxyClient.forwardDelete(path, params, body);
                case HttpMethod.HEAD -> dynamicProxyClient.forwardHead(path, params);
                case HttpMethod.OPTIONS -> dynamicProxyClient.forwardOptions(path, params, body);
                default -> Response.status(Response.Status.METHOD_NOT_ALLOWED).build();
            };
            LOG.debugf("Received upstream status %d for %s %s.", mockResponse.getStatus(), method, path);
        } catch (ClientWebApplicationException e) {
            LOG.debugf("Upstream returned error status %d for %s %s.", e.getResponse().getStatus(), method, path);
            mockResponse = e.getResponse();
        } catch (MockIdNotFound e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to proxy %s %s to %s.", method, path, targetBaseUrl);
            return Response.serverError().build();
        }

        return Response.fromResponse(mockResponse).build();
    }

}
