package com.github.letsrokk;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

public interface ProxyClient {

    @GET
    @Path("{path}")
    Response forwardGet(
            @Encoded @PathParam("path") String path,
            @Encoded @QueryParam("") MultivaluedMap<String, String> queryParams);

    @POST
    @Path("{path}")
    Response forwardPost(
            @Encoded @PathParam("path") String path,
            @Encoded @QueryParam("") MultivaluedMap<String, String> queryParams,
            byte[] requestBody);

    @PUT
    @Path("{path}")
    Response forwardPut(
            @Encoded @PathParam("path") String path,
            @Encoded @QueryParam("") MultivaluedMap<String, String> queryParams,
            byte[] requestBody);

    @PATCH
    @Path("{path}")
    Response forwardPatch(
            @Encoded @PathParam("path") String path,
            @Encoded @QueryParam("") MultivaluedMap<String, String> queryParams,
            byte[] requestBody);

    @DELETE
    @Path("{path}")
    Response forwardDelete(
            @Encoded @PathParam("path") String path,
            @Encoded @QueryParam("") MultivaluedMap<String, String> queryParams,
            byte[] requestBody);

    @HEAD
    @Path("{path}")
    Response forwardHead(
            @Encoded @PathParam("path") String path,
            @Encoded @QueryParam("") MultivaluedMap<String, String> queryParams);

    @OPTIONS
    @Path("{path}")
    Response forwardOptions(
            @Encoded @PathParam("path") String path,
            @Encoded @QueryParam("") MultivaluedMap<String, String> queryParams,
            byte[] requestBody);

}

