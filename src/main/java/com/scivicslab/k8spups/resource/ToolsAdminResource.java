package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.actor.SessionManager;
import com.scivicslab.k8spups.actor.SessionStatus;
import com.scivicslab.k8spups.plugin.ToolPlugin;

import io.quarkus.qute.Template;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Administrator page for the tools: the tools of this deployment with their images, and the running sessions of every user,
 * each of which an administrator can stop. Which tools a deployment offers is fixed when the controller starts
 * (K8SPUPS_ENABLED_PLUGINS), so the page shows the set and does not offer to change it.
 */
@Path("/tools")
@Blocking
@Authenticated
public class ToolsAdminResource {

    private static final Logger LOG = Logger.getLogger(ToolsAdminResource.class.getName());
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9-]{1,40}");

    @Inject
    K8sPupsActorSystem actorSystem;

    @Inject
    AdminAccess access;

    @Inject
    Template tools;

    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/pups")
    String basePath;

    /** One tool of the deployment: its name, the name shown to users and the image (with its tag) the Pod runs. */
    public record ToolRow(String name, String displayName, String image) {}

    /** One running session. */
    public record SessionRow(String sessionId, String user, String tool, String state, String pod, String storage) {}

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response page(@QueryParam("error") String error, @QueryParam("done") String done) {
        Response denied = access.denyUnlessAdmin("Tools");
        if (denied != null) return denied;
        var sm = actorSystem.getSessionManager();
        List<ToolRow> toolRows;
        List<SessionRow> sessionRows;
        try {
            toolRows = sm.ask(SessionManager::listAvailableTools).get().stream()
                .sorted(Comparator.comparing(ToolPlugin::name))
                .map(t -> new ToolRow(t.name(), t.displayName(), actorSystem.getK8sClient().resolvedImage(t))).toList();
            sessionRows = sm.ask(m -> m.getAllSessions(sm)).get().stream()
                .sorted(Comparator.comparing(SessionStatus::userId).thenComparing(SessionStatus::toolName))
                .map(s -> new SessionRow(s.sessionId(), s.userId(), s.toolName(), String.valueOf(s.state()),
                    s.podName() == null ? "" : s.podName(), s.storageType() == null ? "" : s.storageType())).toList();
        } catch (Exception e) {
            throw new IllegalStateException("The tools could not be read: " + e.getMessage(), e);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("basePath", basePath);
        data.put("tools", toolRows);
        data.put("sessions", sessionRows);
        data.put("error", error);
        data.put("done", done);
        return Response.ok(tools.data(data).render()).build();
    }

    /** Stops a session of any user. The stop is written to the audit trail (session.admin-stop) with the administrator as the actor. */
    @POST
    @Path("/sessions/{sessionId}/stop")
    public Response stop(@PathParam("sessionId") String sessionId) {
        Response denied = access.denyUnlessAdmin("Tools");
        if (denied != null) return denied;
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) return back("error", "invalid session id");
        String admin = access.user();
        var sm = actorSystem.getSessionManager();
        try {
            SessionStatus session = sm.ask(m -> m.getAllSessions(sm)).get().stream()
                .filter(s -> s.sessionId().equals(sessionId)).findFirst().orElse(null);
            if (session == null) return back("error", "no such session");
            // The stop of the session itself is written to the audit trail only when it mounts a dataset; this event is
            // written for every stop by an administrator.
            actorSystem.getDatasetManager().ask(d -> { d.auditAdminChange("session.admin-stop", admin, session.userId(),
                session.toolName() + " session " + sessionId); return null; }).get();
            sm.ask(m -> { m.destroySession(sm, sessionId, "stopped by the administrator " + admin); return null; }).get();
            LOG.info("Session " + sessionId + " stopped by the administrator " + admin);
            return back("done", "session " + sessionId + " stopped");
        } catch (Exception e) {
            LOG.warning("Session " + sessionId + " could not be stopped by " + admin + ": " + e.getMessage());
            return back("error", "the session could not be stopped");
        }
    }

    private Response back(String key, String text) {
        return Response.seeOther(URI.create("/tools?" + key + "=" + URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"))).build();
    }
}
