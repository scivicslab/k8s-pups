package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.actor.SessionManagerActor;
import com.scivicslab.k8spups.actor.SessionStatus;
import com.scivicslab.k8spups.actor.SessionSummary;
import com.scivicslab.k8spups.k8s.MountSpec;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import com.scivicslab.k8spups.plugin.UserParameter;
import com.scivicslab.k8spups.tool.JwtRoleExtractor;
import com.scivicslab.k8spups.tool.ToolMetadata;
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

    // External base URL this instance is reached at (e.g. https://133.39.114.45 for
    // local-llm, https://192.168.5.25 for the HAProxy-fronted instance). Used to build
    // the post-logout redirect so it matches the Keycloak client's allowed redirect URIs.
    @ConfigProperty(name = "k8spups.session-oidc.redirect-base-url")
    String oidcRedirectBaseUrl;

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
            <link rel="icon" type="image/svg+xml" href="{{BASE}}/favicon.svg">
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
            oidcRedirectBaseUrl + basePath + "/",
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
        List<String> userRoles = getCurrentUserRoles();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();

        // Fire all independent ask() calls in parallel
        var toolsFuture = sm.ask(SessionManagerActor::listAvailableTools);
        var dynamicToolsFuture = sm.ask(mgr -> mgr.listDynamicToolsForRoles(userRoles));
        var sessionsFuture = sm.ask(mgr -> mgr.getUserSessions(userId));
        var summaryFuture = sm.ask(SessionManagerActor::getSessionSummary);
        var storageInfoFuture = sm.ask(mgr -> mgr.getUserStorageInfo(userId));
        var allPvcInfoFuture = sm.ask(mgr -> mgr.getAllUserPvcInfo(userId));
        var sharedPvcsFuture = sm.ask(mgr -> mgr.listSharedPvcs(userId));

        List<ToolPlugin> tools;
        try { tools = toolsFuture.get(); }
        catch (Exception e) { tools = Collections.emptyList(); }

        List<ToolMetadata> dynamicTools;
        try { dynamicTools = dynamicToolsFuture.get(); }
        catch (Exception e) { dynamicTools = Collections.emptyList(); }

        List<SessionStatus> userSessions;
        try { userSessions = sessionsFuture.get(); }
        catch (Exception e) { userSessions = Collections.emptyList(); }

        SessionSummary summary;
        try { summary = summaryFuture.get(); }
        catch (Exception e) { summary = new SessionSummary(0, 0, 0, 0); }

        Map<String, String> storageInfo = Collections.emptyMap();
        Map<String, Object> allPvcInfo = Collections.emptyMap();
        try {
            storageInfo = storageInfoFuture.get();
            allPvcInfo = allPvcInfoFuture.get();
        } catch (Exception e) {
            LOG.warning("Failed to load storage info: " + e.getMessage());
        }

        // Cluster resource info (direct k8s call, not via actor)
        Map<String, Object> clusterResources;
        try {
            clusterResources = actorSystem.getK8sClient().getClusterResourceSummary();
        } catch (Exception e) {
            LOG.warning("Failed to load cluster resources: " + e.getMessage());
            clusterResources = Collections.emptyMap();
        }

        List<Map<String, String>> sharedPvcs = Collections.emptyList();
        try { sharedPvcs = sharedPvcsFuture.get(); }
        catch (Exception e) { LOG.warning("Failed to load shared PVCs: " + e.getMessage()); }

        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("userRoles", userRoles);
        data.put("tools", tools);
        data.put("dynamicTools", dynamicTools);
        data.put("sessions", userSessions);
        data.put("summary", summary);
        data.put("clusterResources", clusterResources);
        data.put("storageSizeOptions", actorSystem.getStorageSizeOptions());
        data.put("storageTypeOptions", actorSystem.getStorageTypeOptions());
        data.put("currentStorageSize", storageInfo.getOrDefault("longhorn.size",
            storageInfo.getOrDefault("storageSize", actorSystem.getDefaultStorageSize())));
        data.put("pvcInfo", allPvcInfo);
        data.put("sharedPvcs", sharedPvcs);
        data.put("basePath", basePath);

        return dashboard.data(data).render();
    }

    @POST
    @Path("/session/start")
    @Authenticated
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response startSession(@FormParam("tool") String toolName,
                                  @FormParam("profile") String profile,
                                  @FormParam("storageType") String storageType,
                                  @FormParam("extraType_0") String extraType0,
                                  @FormParam("extraPath_0") String extraPath0,
                                  @FormParam("extraType_1") String extraType1,
                                  @FormParam("extraPath_1") String extraPath1,
                                  @FormParam("extraType_2") String extraType2,
                                  @FormParam("extraPath_2") String extraPath2,
                                  @FormParam("userParam_0") String userParam0,
                                  @FormParam("userParam_1") String userParam1,
                                  @FormParam("userParam_2") String userParam2) {
        String userId = getCurrentUsername();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();

        // Resolve user-provided parameters to env var name -> value map
        Map<String, String> userParams = resolveUserParams(
            toolName, userParam0, userParam1, userParam2);

        // Build additional mounts from form params
        List<MountSpec> additionalMounts = resolveAdditionalMounts(
            extraType0, extraPath0, extraType1, extraPath1, extraType2, extraPath2);

        try {
            SessionStatus status = sm.ask(mgr ->
                mgr.createSession(sm, userId, toolName, Collections.emptyList(), null, profile, userParams, storageType, additionalMounts)
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

    @GET
    @Path("/session/{sessionId}/logs")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSessionLogs(
            @PathParam("sessionId") String sessionId,
            @QueryParam("lines") @DefaultValue("200") int lines) {
        String userId = getCurrentUsername();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        try {
            List<SessionStatus> statuses = sm.ask(mgr -> mgr.getUserSessions(userId)).get();
            boolean owned = statuses != null && statuses.stream()
                .anyMatch(s -> s.sessionId().equals(sessionId));
            if (!owned) return Response.status(Response.Status.FORBIDDEN).build();
            String logs = sm.ask(mgr -> mgr.getSessionLogs(sessionId, lines)).get();
            return Response.ok(Map.of("logs", logs == null ? "" : logs)).build();
        } catch (Exception e) {
            return Response.ok(Map.of("logs", "")).build();
        }
    }

    @GET
    @Path("/storage/info")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStorageInfo() {
        String userId = getCurrentUsername();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();

        // Fire all independent ask() calls in parallel
        var storageInfoFut = sm.ask(mgr -> mgr.getUserStorageInfo(userId));
        var allPvcInfoFut = sm.ask(mgr -> mgr.getAllUserPvcInfo(userId));
        var sharableVolumesFut = sm.ask(mgr -> mgr.listSharableVolumes(userId));
        var mySharedPvcsFut = sm.ask(mgr -> mgr.listSharedPvcs(userId));
        var myShareSettingsFut = sm.ask(mgr -> mgr.getShareSettings(userId));
        var sessionsFut = sm.ask(mgr -> mgr.getUserSessions(userId));

        Map<String, String> storageInfo = Collections.emptyMap();
        Map<String, Object> allPvcInfo = Collections.emptyMap();
        try {
            storageInfo = storageInfoFut.get();
            allPvcInfo = allPvcInfoFut.get();
        } catch (Exception e) {
            LOG.warning("Failed to load storage info: " + e.getMessage());
        }

        // Auto-create the default storage PVC if not yet created.
        // Triggered on Storage Settings page load so users never need to manually create it.
        String defaultStorageType = actorSystem.getDefaultStorageType();
        if (defaultStorageType != null && !defaultStorageType.isBlank()) {
            @SuppressWarnings("unchecked")
            Map<String, String> defaultPvcInfo = (Map<String, String>) allPvcInfo.get(defaultStorageType);
            if (defaultPvcInfo != null && !"true".equals(defaultPvcInfo.get("exists"))) {
                String defaultSize = actorSystem.getDefaultStorageSize();
                try {
                    LOG.info("Auto-creating " + defaultStorageType + " PVC for user=" + userId);
                    sm.ask(mgr -> mgr.createUserPvc(userId, defaultStorageType, defaultSize)).get();
                    allPvcInfo = sm.ask(mgr -> mgr.getAllUserPvcInfo(userId)).get();
                } catch (Exception e) {
                    LOG.warning("Auto-create " + defaultStorageType + " PVC failed for " + userId + ": " + e.getMessage());
                }
            }
        }

        List<Map<String, String>> sharableVolumes = Collections.emptyList();
        List<Map<String, String>> mySharedPvcs = Collections.emptyList();
        Map<String, String> myShareSettings = Collections.emptyMap();
        try {
            sharableVolumes = sharableVolumesFut.get();
            mySharedPvcs = mySharedPvcsFut.get();
            myShareSettings = myShareSettingsFut.get();
        } catch (Exception e) {
            LOG.warning("Failed to load share info: " + e.getMessage());
        }

        List<Map<String, String>> activeSessions = Collections.emptyList();
        try {
            var sessions = sessionsFut.get();
            activeSessions = sessions.stream()
                .filter(s -> s.state() == com.scivicslab.k8spups.actor.SessionState.READY
                    || s.state() == com.scivicslab.k8spups.actor.SessionState.STARTING)
                .map(s -> Map.of(
                    "toolName", s.toolName(),
                    "podName", s.podName() != null ? s.podName() : "",
                    "storageType", s.storageType() != null ? s.storageType() : "",
                    "state", s.state().name()
                ))
                .toList();
        } catch (Exception e) {
            LOG.warning("Failed to load active sessions: " + e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("storageSizeOptions", actorSystem.getStorageSizeOptions());
        result.put("storageTypeOptions", actorSystem.getStorageTypeOptions());
        result.put("currentStorageSize", storageInfo.getOrDefault("longhorn.size",
            storageInfo.getOrDefault("storageSize", actorSystem.getDefaultStorageSize())));
        result.put("pvcInfo", allPvcInfo);
        result.put("activeSessions", activeSessions);
        result.put("sharableVolumes", sharableVolumes);
        result.put("mySharedPvcs", mySharedPvcs);
        result.put("myShareSettings", myShareSettings);
        return Response.ok(result).build();
    }

    @POST
    @Path("/storage/pvc/create")
    @Authenticated
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createPvc(Map<String, String> body) {
        String userId = getCurrentUsername();
        String storageType = body != null ? body.get("storageType") : null;
        String storageSize = body != null ? body.get("storageSize") : null;
        if (storageType == null || !actorSystem.getStorageTypeOptions().contains(storageType)) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "invalid_storage_type")).build();
        }
        if ("longhorn".equals(storageType) && (storageSize == null || storageSize.isBlank())) {
            storageSize = actorSystem.getDefaultStorageSize();
        }
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        try {
            String sz = storageSize;
            String error = sm.ask(mgr -> mgr.createUserPvc(userId, storageType, sz)).get();
            if (error != null) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", error)).build();
            }
            return Response.ok(Map.of("status", "created", "storageType", storageType)).build();
        } catch (Exception e) {
            LOG.severe("PVC creation failed: " + e.getMessage());
            return Response.serverError()
                .entity(Map.of("error", "pvc_create_failed")).build();
        }
    }

    @POST
    @Path("/storage/pvc/expand")
    @Authenticated
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response expandPvc(Map<String, String> body) {
        String userId = getCurrentUsername();
        String storageSize = body != null ? body.get("storageSize") : null;
        if (storageSize == null || !actorSystem.getStorageSizeOptions().contains(storageSize)) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "invalid_storage_size")).build();
        }
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        try {
            sm.tell(mgr -> mgr.expandUserPvc(userId, storageSize));
            return Response.ok(Map.of("status", "expanded", "storageSize", storageSize)).build();
        } catch (Exception e) {
            LOG.severe("PVC expand failed: " + e.getMessage());
            return Response.serverError()
                .entity(Map.of("error", "pvc_expand_failed")).build();
        }
    }

    @POST
    @Path("/storage/pvc/delete")
    @Authenticated
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response deletePvc(Map<String, String> body) {
        String userId = getCurrentUsername();
        String storageType = body != null ? body.get("storageType") : null;
        String confirmation = body != null ? body.get("confirmation") : null;
        if (storageType == null || !actorSystem.getStorageTypeOptions().contains(storageType)) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "invalid_storage_type")).build();
        }
        if (!"DELETE".equals(confirmation)) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "confirmation_required")).build();
        }
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        try {
            String error = sm.ask(mgr -> mgr.deleteUserPvc(userId, storageType)).get();
            if (error != null) {
                return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", error)).build();
            }
            return Response.ok(Map.of("status", "deleted", "storageType", storageType)).build();
        } catch (Exception e) {
            LOG.severe("PVC deletion failed: " + e.getMessage());
            return Response.serverError()
                .entity(Map.of("error", "pvc_delete_failed")).build();
        }
    }

    @POST
    @Path("/storage/save")
    @Authenticated
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response saveStoragePreference(@FormParam("storageSize") String storageSize,
                                          @FormParam("storageType") String storageType,
                                          @HeaderParam("Accept") String accept) {
        String userId = getCurrentUsername();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        boolean wantJson = accept != null && accept.contains("application/json");

        if (storageSize == null || !actorSystem.getStorageSizeOptions().contains(storageSize)) {
            if (wantJson) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "invalid_storage_size")).build();
            }
            return Response.seeOther(URI.create("/dashboard?error=invalid_storage_size")).build();
        }
        if (storageType == null || !actorSystem.getStorageTypeOptions().contains(storageType)) {
            storageType = actorSystem.getDefaultStorageType();
        }

        try {
            String type = storageType;
            sm.tell(mgr -> mgr.saveUserStoragePreference(userId, storageSize, type));
            sm.tell(mgr -> mgr.expandUserPvc(userId, storageSize));
        } catch (Exception e) {
            LOG.severe("Failed to save storage preference: " + e.getMessage());
            if (wantJson) {
                return Response.serverError()
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "storage_save_failed")).build();
            }
            return Response.seeOther(URI.create("/dashboard?error=storage_save_failed")).build();
        }

        if (wantJson) {
            return Response.ok(Map.of("status", "saved", "storageSize", storageSize,
                "storageType", storageType)).type(MediaType.APPLICATION_JSON).build();
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

    // -- Shared NFS endpoints --

    /**
     * Returns available shared volumes and the user's own share settings.
     */
    @GET
    @Path("/storage/shares")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response getShareInfo() {
        String userId = getCurrentUsername();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        try {
            // Fire all ask() calls in parallel
            var sharableVolumesFut = sm.ask(mgr -> mgr.listSharableVolumes(userId));
            var mySharedPvcsFut = sm.ask(mgr -> mgr.listSharedPvcs(userId));
            var myShareSettingsFut = sm.ask(mgr -> mgr.getShareSettings(userId));

            Map<String, Object> result = new HashMap<>();
            result.put("sharableVolumes", sharableVolumesFut.get());
            result.put("mySharedPvcs", mySharedPvcsFut.get());
            result.put("myShareSettings", myShareSettingsFut.get());
            return Response.ok(result).build();
        } catch (Exception e) {
            LOG.severe("Failed to get share info: " + e.getMessage());
            return Response.serverError()
                .entity(Map.of("error", "share_info_failed")).build();
        }
    }

    /**
     * Creates a shared NFS PV/PVC pointing to another user's nfs-k8s directory.
     */
    @POST
    @Path("/storage/share/mount")
    @Authenticated
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response mountSharedVolume(Map<String, String> body) {
        String userId = getCurrentUsername();
        String sourceUserId = body != null ? body.get("sourceUserId") : null;
        if (sourceUserId == null || sourceUserId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "sourceUserId is required")).build();
        }
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        try {
            String error = sm.ask(mgr -> mgr.createSharedNfsPvPvc(userId, sourceUserId)).get();
            if (error != null) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", error)).build();
            }
            return Response.ok(Map.of("status", "mounted", "sourceUserId", sourceUserId)).build();
        } catch (Exception e) {
            LOG.severe("Failed to mount shared volume: " + e.getMessage());
            return Response.serverError()
                .entity(Map.of("error", "share_mount_failed")).build();
        }
    }

    /**
     * Deletes a shared NFS PV/PVC.
     */
    @POST
    @Path("/storage/share/unmount")
    @Authenticated
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response unmountSharedVolume(Map<String, String> body) {
        String userId = getCurrentUsername();
        String sourceUserId = body != null ? body.get("sourceUserId") : null;
        if (sourceUserId == null || sourceUserId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "sourceUserId is required")).build();
        }
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        try {
            String error = sm.ask(mgr -> mgr.deleteSharedNfsPvPvc(userId, sourceUserId)).get();
            if (error != null) {
                return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", error)).build();
            }
            return Response.ok(Map.of("status", "unmounted", "sourceUserId", sourceUserId)).build();
        } catch (Exception e) {
            LOG.severe("Failed to unmount shared volume: " + e.getMessage());
            return Response.serverError()
                .entity(Map.of("error", "share_unmount_failed")).build();
        }
    }

    /**
     * Updates the user's own share settings (enable/disable sharing, allow list, mode).
     */
    @POST
    @Path("/storage/share/settings")
    @Authenticated
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateShareSettings(Map<String, String> body) {
        String userId = getCurrentUsername();
        if (body == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "body is required")).build();
        }
        boolean enabled = "true".equals(body.get("enabled"));
        String allowList = body.getOrDefault("allow", "");
        String mode = body.getOrDefault("mode", "ro");
        if (!"ro".equals(mode) && !"rw".equals(mode)) {
            mode = "ro";
        }

        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        try {
            String m = mode;
            sm.tell(mgr -> mgr.saveShareSettings(userId, enabled, allowList, m));
            return Response.ok(Map.of("status", "saved")).build();
        } catch (Exception e) {
            LOG.severe("Failed to save share settings: " + e.getMessage());
            return Response.serverError()
                .entity(Map.of("error", "share_settings_failed")).build();
        }
    }

    private List<MountSpec> resolveAdditionalMounts(String... typePathPairs) {
        List<MountSpec> mounts = new ArrayList<>();
        // typePathPairs are: type0, path0, type1, path1, type2, path2
        for (int i = 0; i + 1 < typePathPairs.length; i += 2) {
            String type = typePathPairs[i];
            String path = typePathPairs[i + 1];
            if (type != null && !type.isBlank() && path != null && !path.isBlank()) {
                // Ensure mount path is absolute
                if (!path.startsWith("/")) {
                    path = "/" + path;
                }
                // Parse shared mount format: "nfs-k8s-shared::sourceUserId"
                String sharedFrom = null;
                if (type.startsWith("nfs-k8s-shared::")) {
                    sharedFrom = type.substring("nfs-k8s-shared::".length());
                    type = "nfs-k8s-shared";
                }
                mounts.add(new MountSpec(type, path, sharedFrom));
            }
        }
        return mounts;
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

    /** Extract user roles from Keycloak realm_access and resource_access claims. */
    private List<String> getCurrentUserRoles() {
        try {
            if (idToken == null) {
                return List.of();
            }
            Map<String, Object> realmAccess = idToken.getClaim("realm_access");
            Map<String, Object> resourceAccess = idToken.getClaim("resource_access");
            return JwtRoleExtractor.extractRoles(realmAccess, resourceAccess);
        } catch (Exception e) {
            LOG.warning("Failed to extract roles from token: " + e.getMessage());
            return List.of();
        }
    }
}
