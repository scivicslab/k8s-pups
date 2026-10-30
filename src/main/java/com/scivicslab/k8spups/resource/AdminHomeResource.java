package com.scivicslab.k8spups.resource;

import io.quarkus.qute.Template;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

/**
 * The entrance of the administrator area: what an administrator can do, one card for each area. The dashboard has one
 * card that leads here, and every administrator page has the same navigation to the areas.
 */
@Path("/admin")
@Blocking
public class AdminHomeResource {

    @Inject
    AdminAccess access;

    @Inject
    Template adminHome;

    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/pups")
    String basePath;

    @GET
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public Response page() {
        Response denied = access.denyUnlessAdmin("Administration");
        if (denied != null) return denied;
        return Response.ok(adminHome.data(Map.of("basePath", basePath)).render()).build();
    }
}
