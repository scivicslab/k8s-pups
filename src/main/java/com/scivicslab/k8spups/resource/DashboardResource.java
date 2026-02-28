package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.actor.SessionManagerActor;
import com.scivicslab.k8spups.actor.SessionStatus;
import com.scivicslab.k8spups.actor.SessionSummary;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import com.scivicslab.k8spups.plugin.UserParameter;
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

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/pups")
    String basePath;

    @ConfigProperty(name = "k8spups.session-oidc.issuer")
    String oidcIssuer;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String index() {
        return ("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>k8s-pups - K8s Per-User Pod Service</title>
            <style>
                *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
                    background: #F8FAFC; color: #1E293B;
                    display: flex; align-items: center; justify-content: center;
                    min-height: 100vh;
                }
                .landing {
                    text-align: center; max-width: 440px; padding: 24px;
                }
                .logo {
                    font-size: 64px; margin-bottom: 12px; line-height: 1;
                }
                h1 {
                    font-size: 32px; font-weight: 800; letter-spacing: -.5px;
                    margin-bottom: 6px;
                }
                .tagline {
                    font-size: 15px; color: #64748B; margin-bottom: 32px;
                    line-height: 1.6;
                }
                .btn-login {
                    display: inline-flex; align-items: center; gap: 8px;
                    padding: 14px 36px; border-radius: 10px;
                    background: linear-gradient(135deg, #EA580C 0%, #F97316 100%);
                    color: white; text-decoration: none;
                    font-size: 16px; font-weight: 700;
                    box-shadow: 0 4px 14px rgba(249,115,22,.3);
                    transition: all .15s ease;
                }
                .btn-login:hover {
                    transform: translateY(-2px);
                    box-shadow: 0 6px 22px rgba(249,115,22,.4);
                }
                .features {
                    display: flex; gap: 24px; margin-top: 40px;
                    justify-content: center; flex-wrap: wrap;
                }
                .feature {
                    font-size: 13px; color: #64748B; display: flex;
                    align-items: center; gap: 6px;
                }
                .feature::before {
                    content: ''; width: 6px; height: 6px;
                    border-radius: 50%; background: #F97316; flex-shrink: 0;
                }
            </style>
            </head>
            <body>
            <div class="landing">
                <div class="logo">\uD83D\uDC3E</div>
                <h1>k8s-pups</h1>
                <p class="tagline">
                    Launch isolated tool environments &mdash; IDEs, notebooks, desktops
                    &mdash; each in its own Kubernetes Pod.
                </p>
                <a href="{{BASE}}/dashboard" class="btn-login">Log in to Dashboard</a>
                <div class="features">
                    <span class="feature">Jupyter Lab</span>
                    <span class="feature">Remote Desktop</span>
                    <span class="feature">Coding Agents</span>
                </div>
            </div>
            </body>
            </html>
            """).replace("{{BASE}}", basePath);
    }

    @GET
    @Path("/logout")
    @Authenticated
    public Response logout() {
        String endSessionUrl = oidcIssuer + "/protocol/openid-connect/logout";
        String postLogoutUri = URLEncoder.encode(
            "https://192.168.5.25" + basePath + "/",
            StandardCharsets.UTF_8);
        String rawToken = idToken != null ? idToken.getRawToken() : "";
        String redirectUrl = endSessionUrl
            + "?client_id=k8s-pups"
            + "&id_token_hint=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8)
            + "&post_logout_redirect_uri=" + postLogoutUri;
        return Response.seeOther(URI.create(redirectUrl))
            .cookie(new jakarta.ws.rs.core.NewCookie.Builder("q_session")
                .path(basePath).maxAge(0).build())
            .cookie(new jakarta.ws.rs.core.NewCookie.Builder("pups-dashboard-id")
                .path("/pups").maxAge(0).build())
            .cookie(new jakarta.ws.rs.core.NewCookie.Builder("pups-dashboard-id")
                .path("/").maxAge(0).build())
            .build();
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

        List<SessionStatus> userSessions;
        try {
            userSessions = sm.ask(mgr -> mgr.getUserSessions(userId)).get();
        } catch (Exception e) {
            userSessions = Collections.emptyList();
        }

        SessionSummary summary;
        try {
            summary = sm.ask(SessionManagerActor::getSessionSummary).get();
        } catch (Exception e) {
            summary = new SessionSummary(0, 0, 0, 0);
        }

        // Storage settings
        String userStoragePref = null;
        Map<String, String> pvcInfo = Map.of("exists", "false");
        try {
            userStoragePref = sm.ask(mgr -> mgr.getUserStoragePreference(userId)).get();
            pvcInfo = sm.ask(mgr -> mgr.getUserPvcInfo(userId)).get();
        } catch (Exception e) {
            LOG.warning("Failed to load storage info: " + e.getMessage());
        }

        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("tools", tools);
        data.put("sessions", userSessions);
        data.put("summary", summary);
        data.put("storageSizeOptions", actorSystem.getStorageSizeOptions());
        data.put("currentStorageSize", userStoragePref != null ? userStoragePref : actorSystem.getDefaultStorageSize());
        data.put("pvcInfo", pvcInfo);

        return dashboard.data(data).render();
    }

    @POST
    @Path("/session/start")
    @Authenticated
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response startSession(@FormParam("tool") String toolName,
                                  @FormParam("profile") String profile,
                                  @FormParam("userParam_0") String userParam0,
                                  @FormParam("userParam_1") String userParam1,
                                  @FormParam("userParam_2") String userParam2) {
        String userId = getCurrentUsername();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();

        // Resolve user-provided parameters to env var name -> value map
        Map<String, String> userParams = resolveUserParams(
            toolName, userParam0, userParam1, userParam2);

        try {
            SessionStatus status = sm.ask(mgr ->
                mgr.createSession(sm, userId, toolName, Collections.emptyList(), null, profile, userParams)
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
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response stopSession(@FormParam("sessionId") String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Response.seeOther(URI.create("/dashboard?error=missing_session_id")).build();
        }
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        sm.tell(mgr -> mgr.destroySession(sessionId));
        return Response.seeOther(URI.create("/dashboard")).build();
    }

    @POST
    @Path("/session/memo")
    @Authenticated
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateMemo(@FormParam("sessionId") String sessionId,
                               @FormParam("memo") String memo) {
        if (sessionId == null || sessionId.isBlank()) {
            return Response.seeOther(URI.create("/dashboard")).build();
        }
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        sm.tell(mgr -> mgr.updateMemo(sessionId, memo != null ? memo.strip() : ""));
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
            List<SessionStatus> statuses = sm.ask(mgr -> mgr.getUserSessions(userId)).get();
            if (statuses == null || statuses.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            sm.tell(mgr -> mgr.touchUserSessions(userId));
            return Response.ok(statuses).build();
        } catch (Exception e) {
            return Response.serverError().build();
        }
    }

    @POST
    @Path("/storage/save")
    @Authenticated
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response saveStoragePreference(@FormParam("storageSize") String storageSize) {
        String userId = getCurrentUsername();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();

        if (storageSize == null || !actorSystem.getStorageSizeOptions().contains(storageSize)) {
            return Response.seeOther(URI.create("/dashboard?error=invalid_storage_size")).build();
        }

        try {
            sm.tell(mgr -> mgr.saveUserStoragePreference(userId, storageSize));
            sm.tell(mgr -> mgr.expandUserPvc(userId, storageSize));
        } catch (Exception e) {
            LOG.severe("Failed to save storage preference: " + e.getMessage());
            return Response.seeOther(URI.create("/dashboard?error=storage_save_failed")).build();
        }

        return Response.seeOther(URI.create("/dashboard")).build();
    }

    /**
     * Maps form field values (userParam_0, _1, _2) to env var names
     * defined in the tool's userParameters().
     */
    private Map<String, String> resolveUserParams(String toolName,
                                                   String... values) {
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        List<ToolPlugin> tools;
        try {
            tools = sm.ask(SessionManagerActor::listAvailableTools).get();
        } catch (Exception e) {
            return Collections.emptyMap();
        }

        ToolPlugin plugin = null;
        for (ToolPlugin t : tools) {
            if (t.name().equals(toolName)) {
                plugin = t;
                break;
            }
        }
        if (plugin == null) {
            return Collections.emptyMap();
        }

        List<UserParameter> params = plugin.userParameters();
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < params.size() && i < values.length; i++) {
            if (values[i] != null && !values[i].isBlank()) {
                result.put(params.get(i).envVarName(), values[i]);
            }
        }
        return result;
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
