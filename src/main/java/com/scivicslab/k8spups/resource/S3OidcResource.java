package com.scivicslab.k8spups.resource;

import java.util.Map;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.passport.S3Access;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The two public documents that let an S3 server (MinIO) trust the tokens of the controller: the OpenID Connect
 * discovery document and the JSON Web Key Set of the controller's S3 token key. They hold no secret. They exist only
 * when the S3 access is on ({@code k8spups.passport.s3.enabled}).
 */
@Path("/s3-oidc")
@Produces(MediaType.APPLICATION_JSON)
public class S3OidcResource {

    @Inject
    K8sPupsActorSystem actorSystem;

    @GET
    @Path(".well-known/openid-configuration")
    public Response discovery() {
        return s3().map(a -> Response.ok(a.tokens().discovery()).build()).orElse(Response.status(404).build());
    }

    @GET
    @Path("jwks.json")
    public Response jwks() {
        return s3().map(a -> Response.ok((Map<String, Object>) a.tokens().jwks()).build()).orElse(Response.status(404).build());
    }

    private java.util.Optional<S3Access> s3() {
        return actorSystem.s3Access();
    }
}
