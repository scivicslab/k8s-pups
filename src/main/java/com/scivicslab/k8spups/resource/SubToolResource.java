package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.logging.Logger;

/**
 * REST API for dynamic sub-tool routing registration.
 *
 * Called by quarkus-service-portal (running inside a user Pod) when a child
 * process (e.g. quarkus-chat-ui) becomes READY or stops. k8s-pups creates or
 * deletes a Service + HTTPRoute so the sub-tool is reachable from the browser.
 *
 * Authentication: no OIDC required (called service-to-service inside the cluster).
 * The sessionId path parameter implicitly scopes the operation to that session's Pod.
 */
@Path("/api/sub-tool")
public class SubToolResource {

    private static final Logger LOG = Logger.getLogger(SubToolResource.class.getName());

    @Inject
    K8sPupsActorSystem actorSystem;

    @POST
    @Path("{sessionId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response register(@PathParam("sessionId") String sessionId, SubToolRegisterRequest req) {
        if (req == null || req.toolName() == null || req.toolName().isBlank() || req.port() <= 0) {
            return Response.status(400).entity("toolName and port are required").build();
        }
        try {
            actorSystem.getK8sClient().createSubToolService(sessionId, req.toolName(), req.port());
            actorSystem.getK8sClient().createSubToolHTTPRoute(sessionId, req.toolName(), req.port());
            String accessUrl = "/session/" + sessionId + "-" + req.toolName() + "-" + req.port() + "/";
            LOG.info("Sub-tool registered: " + sessionId + "/" + req.toolName() + ":" + req.port()
                + " → " + accessUrl);
            return Response.ok(new SubToolRegisterResponse(accessUrl)).build();
        } catch (Exception e) {
            LOG.warning("Failed to register sub-tool " + sessionId + "/" + req.toolName()
                + ": " + e.getMessage());
            return Response.status(500).entity("Registration failed: " + e.getMessage()).build();
        }
    }

    @DELETE
    @Path("{sessionId}/{toolName}/{port}")
    public Response deregister(@PathParam("sessionId") String sessionId,
                               @PathParam("toolName") String toolName,
                               @PathParam("port") int port) {
        try {
            actorSystem.getK8sClient().deleteSubToolResources(sessionId, toolName, port);
            LOG.info("Sub-tool deregistered: " + sessionId + "/" + toolName + ":" + port);
            return Response.noContent().build();
        } catch (Exception e) {
            LOG.warning("Failed to deregister sub-tool " + sessionId + "/" + toolName
                + ": " + e.getMessage());
            return Response.status(500).entity("Deregistration failed: " + e.getMessage()).build();
        }
    }
}
