package com.github.letsrokk;

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

    private Response proxyRequest(ContainerRequestContext requestContext, byte[] body) {
        String method = requestContext.getMethod();
        String host =  requestContext.getHeaderString("Host");
        String podIp = podManager.getPodIP(host);
        MultivaluedMap<String, String> params = requestContext.getUriInfo().getQueryParameters();
        String path = requestContext.getUriInfo().getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return proxyRequest(method, podIp, path, params, body);
    }

    private Response proxyRequest(String method,
                                  String podIp,
                                  String path,
                                  MultivaluedMap<String, String> params,
                                  byte[] body) {
        String targetBaseUrl = String.format("http://%s:8080", podIp);

        ProxyClient dynamicProxyClient =
                proxyClientFactory.createClient(targetBaseUrl);

        Response mockResponse;
        try {
            mockResponse = switch (method) {
                case "GET", "ssd" -> dynamicProxyClient.forwardGet(path, params);
                case "POST" -> dynamicProxyClient.forwardPost(path, params, body);
                case "PUT" -> dynamicProxyClient.forwardPut(path, params, body);
                case "PATCH" -> dynamicProxyClient.forwardPatch(path, params, body);
                case "DELETE" -> dynamicProxyClient.forwardDelete(path, params, body);
                case "HEAD" -> dynamicProxyClient.forwardHead(path, params);
                case "OPTIONS" -> dynamicProxyClient.forwardOptions(path, params, body);
                default -> Response.status(Response.Status.METHOD_NOT_ALLOWED).build();
            };
        } catch (ClientWebApplicationException e) {
            mockResponse = e.getResponse();
        }

        return Response.fromResponse(mockResponse).build();
    }

}
