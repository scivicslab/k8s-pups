package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.actor.SessionManagerActor;
import com.scivicslab.k8spups.actor.SessionStatus;
import com.scivicslab.k8spups.actor.SessionSummary;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import com.scivicslab.pojoactor.core.ActorRef;

import io.quarkus.oidc.IdToken;
import io.quarkus.qute.Template;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.jwt.JsonWebToken;

import java.net.URI;
import java.util.*;
import java.util.logging.Logger;

@Path("/")
public class DashboardResource {

    private static final Logger LOG = Logger.getLogger(DashboardResource.class.getName());

    @Inject
    K8sPupsActorSystem actorSystem;

    @Inject
    @IdToken
    JsonWebToken idToken;

    @Inject
    SecurityIdentity identity;

    @Inject
    Template dashboard;

    @GET
    public Response index() {
        return Response.seeOther(URI.create("/dashboard")).build();
    }

    @GET
    @Path("/dashboard")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public String showDashboard() {
        String userId = getCurrentUsername();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();

        List<ToolPlugin> tools;
        try {
            tools = sm.ask(SessionManagerActor::listAvailableTools).get();
        } catch (Exception e) {
            tools = Collections.emptyList();
        }

        SessionStatus status;
        try {
            status = sm.ask(mgr -> mgr.getSessionStatus(userId)).get();
        } catch (Exception e) {
            status = null;
        }

        SessionSummary summary;
        try {
            summary = sm.ask(SessionManagerActor::getSessionSummary).get();
        } catch (Exception e) {
            summary = new SessionSummary(0, 0, 0, 0);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("tools", tools);
        data.put("session", status);
        data.put("summary", summary);

        return dashboard.data(data).render();
    }

    @POST
    @Path("/session/start")
    @Authenticated
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response startSession(@FormParam("tool") String toolName,
                                  @FormParam("profile") String profile) {
        String userId = getCurrentUsername();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();

        try {
            SessionStatus status = sm.ask(mgr ->
                mgr.createSession(sm, userId, toolName, Collections.emptyList(), null, profile)
            ).get();

            if (status == null) {
                LOG.warning("Session creation rejected: user=" + userId + ", tool=" + toolName);
                return Response.seeOther(URI.create("/dashboard?error=create_failed")).build();
            }
        } catch (Exception e) {
            LOG.severe("Failed to start session: " + e.getMessage());
            return Response.seeOther(URI.create("/dashboard?error=start_failed")).build();
        }

        return Response.seeOther(URI.create("/dashboard")).build();
    }

    @POST
    @Path("/session/stop")
    @Authenticated
    public Response stopSession() {
        String userId = getCurrentUsername();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();

        sm.tell(mgr -> mgr.destroySession(userId));

        return Response.seeOther(URI.create("/dashboard")).build();
    }

    @GET
    @Path("/session/status")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSessionStatus() {
        String userId = getCurrentUsername();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();

        try {
            SessionStatus status = sm.ask(mgr -> mgr.getSessionStatus(userId)).get();
            if (status == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            sm.tell(mgr -> mgr.touchSession(userId));
            return Response.ok(status).build();
        } catch (Exception e) {
            return Response.serverError().build();
        }
    }

    private String getCurrentUsername() {
        try {
            if (idToken != null) {
                String username = idToken.getClaim("preferred_username");
                if (username != null) {
                    return username;
                }
            }
        } catch (Exception e) {
            // Fallback to SecurityIdentity
        }
        if (identity != null && !identity.isAnonymous()) {
            return identity.getPrincipal().getName();
        }
        return null;
    }
}
