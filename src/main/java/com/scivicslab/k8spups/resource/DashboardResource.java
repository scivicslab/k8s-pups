package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.k8s.SessionPath;
import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.actor.SessionManager;
import com.scivicslab.k8spups.actor.SessionStatus;
import com.scivicslab.k8spups.actor.SessionSummary;
import com.scivicslab.k8spups.k8s.MountSpec;
import com.scivicslab.k8spups.k8s.StorageTypes;
import com.scivicslab.k8spups.storage.StorageAllocationService;
import com.scivicslab.k8spups.k8s.K8sApiClient;
import com.scivicslab.k8spups.plugin.SharedService;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import org.eclipse.microprofile.jwt.JsonWebToken;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.common.annotation.Blocking;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Locale;
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

    // External base URL this instance is reached at (e.g. https://203.0.113.10 for
    // local-llm, https://10.0.0.25 for the HAProxy-fronted instance). Used to build
    // the post-logout redirect so it matches the Keycloak client's allowed redirect URIs.
    @ConfigProperty(name = "k8spups.session-oidc.redirect-base-url")
    String oidcRedirectBaseUrl;

    @ConfigProperty(name = "k8spups.user-pods-namespace", defaultValue = "user-pods")
    String userPodsNamespace;

    // Name shown in the page title and the header, so that several deployments of this
    // controller (for example /pups and /pups-with-audit) can be told apart in the browser.
    @ConfigProperty(name = "k8spups.display-name", defaultValue = "k8s-pups")
    String displayName;

    // S3 endpoint the users reach with their own S3 tools; unset when the deployment offers users no S3 storage.
    @ConfigProperty(name = "k8spups.object-storage-endpoint")
    Optional<String> objectStorageEndpoint;

    @Context
    HttpHeaders httpHeaders;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String index() {
        return ("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>{{NAME}} - K8s Per-User Pod Service</title>
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
                <h1>{{NAME}}</h1>
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
            """).replace("{{BASE}}", basePath).replace("{{NAME}}", displayName);
    }

    @GET
    @Path("/logout")
    @Authenticated
    public Response logout() {
        String userId = getCurrentUsername();
        String endSessionUrl = oidcIssuer + "/protocol/openid-connect/logout";
        String postLogoutUri = URLEncoder.encode(
            oidcRedirectBaseUrl + basePath + "/",
            StandardCharsets.UTF_8);
        String rawToken = idToken != null ? idToken.getRawToken() : "";
        String redirectUrl = endSessionUrl
            + "?client_id=k8s-pups"
            + "&id_token_hint=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8)
            + "&post_logout_redirect_uri=" + postLogoutUri;

        LOG.info("Logout initiated: user=" + userId);

        // Clear all Quarkus OIDC session cookies found in the request.
        // In Quarkus 3.x the session may be stored as q_session_chunk_1, q_session_chunk_2, etc.
        // (not as a single q_session cookie), all set at path "/".
        Response.ResponseBuilder rb = Response.seeOther(URI.create(redirectUrl));
        httpHeaders.getCookies().keySet().stream()
            .filter(name -> name.startsWith("q_session"))
            .forEach(name -> rb.cookie(
                new jakarta.ws.rs.core.NewCookie.Builder(name).path("/").maxAge(0).build()));
        rb.cookie(new jakarta.ws.rs.core.NewCookie.Builder("pups-dashboard-id")
            .path("/").maxAge(0).build());
        return rb.build();
    }

    @GET
    @Path("/dashboard")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public String showDashboard() {
        String userId = getCurrentUsername();
        List<String> userRoles = getCurrentUserRoles();
        ActorRef<SessionManager> sm = actorSystem.getSessionManager();

        // Fire all independent ask() calls in parallel
        var toolsFuture = sm.ask(SessionManager::listAvailableTools);
        var dynamicToolsFuture = sm.ask(mgr -> mgr.listDynamicToolsForRoles(userRoles));
        var sessionsFuture = sm.ask(mgr -> mgr.getUserSessions(sm, userId));
        var summaryFuture = sm.ask(mgr -> mgr.getSessionSummary(sm));
        // The user's storage comes from an administrator's allocation; prepare it (the default allocation at the
        // first login, the PVCs of the allocations) before the PVC states are read.
        Map<String, String> allocated = Collections.emptyMap();
        try {
            ActorRef<StorageAllocationService> alloc = actorSystem.getStorageAllocations();
            alloc.ask(a -> { a.ensureStorage(userId); return null; }).get();
            allocated = alloc.ask(a -> a.activeTypes(userId)).get();
        } catch (Exception e) {
            LOG.warning("Failed to prepare the storage of " + userId + ": " + e.getMessage());
        }
        var storageInfoFuture = sm.ask(mgr -> mgr.getUserStorageInfo(userId));
        var allPvcInfoFuture = sm.ask(mgr -> mgr.getAllUserPvcInfo(userId));

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


        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("userRoles", userRoles);
        data.put("tools", tools);
        data.put("cardOrder", ToolCardOrder.ranks());
        data.put("otherCardOrder", ToolCardOrder.OTHER);
        data.put("dynamicTools", dynamicTools);
        data.put("sessions", userSessions);
        data.put("summary", summary);
        data.put("clusterResources", clusterResources);
        data.put("storageSizeOptions", actorSystem.getStorageSizeOptions());
        // Only allocated storage can be chosen at launch
        List<String> allocatedTypes = List.copyOf(allocated.keySet());
        data.put("storageTypeOptions", allocatedTypes);
        data.put("storageTypeNames", StorageTypes.names(allocatedTypes));
        data.put("currentStorageSize", storageInfo.getOrDefault("longhorn.size",
            storageInfo.getOrDefault("storageSize", actorSystem.getDefaultStorageSize())));
        data.put("pvcInfo", allPvcInfo);
        data.put("basePath", basePath);
        data.put("displayName", displayName);

        // Shared services: state read now, Launch / Stop only for administrators.
        List<SharedServiceView> sharedServices = new ArrayList<>();
        for (SharedService svc : actorSystem.getSharedServices()) {
            sharedServices.add(SharedServiceView.of(svc,
                actorSystem.getK8sClient().sharedServiceState(svc.namespace(), svc.deployment())));
        }
        data.put("sharedServices", sharedServices);
        data.put("isAdmin", actorSystem.isAdmin(userId, userRoles));

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
        ActorRef<SessionManager> sm = actorSystem.getSessionManager();

        // Resolve user-provided parameters to env var name -> value map
        Map<String, String> userParams = resolveUserParams(
            toolName, userParam0, userParam1, userParam2);

        // Build additional mounts from form params
        List<MountSpec> additionalMounts = resolveAdditionalMounts(
            extraType0, extraPath0, extraType1, extraPath1, extraType2, extraPath2);

        try {
            // Visas in the UserInfo response of the login (claim ga4gh_passport_v1), when the Passport support takes them from there.
            // Read on the request thread: the identity is not available on the thread of the actor.
            List<String> visas = currentVisas();
            sm.ask(mgr -> mgr.supplyVisas(sm, userId, visas)).get();
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
        ActorRef<SessionManager> sm = actorSystem.getSessionManager();
        sm.tell(mgr -> mgr.destroySession(sm, sessionId));
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
        ActorRef<SessionManager> sm = actorSystem.getSessionManager();
        sm.tell(mgr -> mgr.updateMemo(sm, sessionId, memo != null ? memo.strip() : ""));
        return Response.seeOther(URI.create("/dashboard")).build();
    }

    @GET
    @Path("/session/status")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSessionStatus() {
        String userId = getCurrentUsername();
        ActorRef<SessionManager> sm = actorSystem.getSessionManager();

        try {
            List<SessionStatus> statuses = sm.ask(mgr -> mgr.getUserSessions(sm, userId)).get();
            if (statuses == null || statuses.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            sm.tell(mgr -> mgr.touchUserSessions(sm, userId));
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
        ActorRef<SessionManager> sm = actorSystem.getSessionManager();
        try {
            List<SessionStatus> statuses = sm.ask(mgr -> mgr.getUserSessions(sm, userId)).get();
            boolean owned = statuses != null && statuses.stream()
                .anyMatch(s -> s.sessionId().equals(sessionId));
            if (!owned) return Response.status(Response.Status.FORBIDDEN).build();
            String logs = sm.ask(mgr -> mgr.getSessionLogs(sm, sessionId, lines)).get();
            return Response.ok(Map.of("logs", logs == null ? "" : logs)).build();
        } catch (Exception e) {
            return Response.ok(Map.of("logs", "")).build();
        }
    }

    // ── Session proxy ──────────────────────────────────────────────────────────
    // All traffic to /session/{id}/... reaches the controller via the catch-all
    // HTTPRoute in gateway.yaml. The controller verifies ownership, then proxies
    // the request to the pod Service (http://pups-svc-{id}:8080/...).
    //
    // passthroughPath=true  (FileBrowser, JupyterLab): pod expects full path
    //   /session/{id}/sub/path → pups-svc-{id}:8080/session/{id}/sub/path
    // passthroughPath=false (Guacamole, etc.): pod expects stripped path
    //   /session/{id}/sub/path → pups-svc-{id}:8080/sub/path

    /**
     * A tool that a session Pod started on its own (quarkus-ai-workspace launching html-saurus,
     * chat-ui, ...) and registered through {@code SubToolResource}, which named it
     * {@code {sessionId}-{toolName}-{port}} and created a Service {@code pups-subtool-} + that name.
     *
     * <p>Its traffic comes through this same proxy, under the same login and the same ownership
     * check as the session it belongs to. It used to get its own HTTPRoute straight from the
     * gateway to the Service, which carried no authentication at all: anyone who knew the URL
     * reached the tool without logging in.</p>
     */
    record SubTool(String sessionId, String toolName, int port) {

        /** The Service {@code SubToolResource} created for it, in the users' namespace. */
        String serviceName() {
            return "pups-subtool-" + sessionId + "-" + toolName + "-" + port;
        }

        /**
         * Reads {@code {sessionId}-{toolName}-{port}}, accepting only a session the user owns.
         * The session id is matched as a prefix because tool names carry hyphens themselves.
         */
        static SubTool parse(String name, List<SessionStatus> userSessions) {
            int dash = name.lastIndexOf('-');
            if (dash <= 0 || dash == name.length() - 1) {
                return null;
            }
            int port;
            try {
                port = Integer.parseInt(name.substring(dash + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            String head = name.substring(0, dash);            // {sessionId}-{toolName}
            for (SessionStatus s : userSessions) {
                String prefix = s.sessionId() + "-";
                if (head.startsWith(prefix) && head.length() > prefix.length()) {
                    return new SubTool(s.sessionId(), head.substring(prefix.length()), port);
                }
            }
            return null;
        }
    }

    private static final java.util.Set<String> HOP_BY_HOP = java.util.Set.of(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailer", "transfer-encoding", "upgrade", "host",
        // content-length is omitted so the framework can use chunked encoding for streaming
        "content-length"
    );

    /** First bytes of every proxied event stream: a comment line, which EventSource ignores. */
    private static final byte[] SSE_OPEN_COMMENT = ": open\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** True when {@code contentType} is a Server-Sent Events stream (parameters such as charset are ignored). */
    static boolean isEventStream(String contentType) {
        return contentType != null
            && contentType.trim().toLowerCase(Locale.ROOT).startsWith("text/event-stream");
    }

    private static final HttpClient SESSION_HTTP_CLIENT = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    @GET
    @Path("/session/{sessionId}/{subPath: .*}")
    @Authenticated
    @Blocking
    public Response proxySessionGet(
            @PathParam("sessionId") String sessionId,
            @PathParam("subPath") String subPath,
            @Context HttpHeaders inHeaders,
            @Context jakarta.ws.rs.core.UriInfo uriInfo) {
        return proxySession(sessionId, subPath, "GET", null, inHeaders, uriInfo);
    }

    @POST
    @Path("/session/{sessionId}/{subPath: .*}")
    @Authenticated
    @Blocking
    public Response proxySessionPost(
            @PathParam("sessionId") String sessionId,
            @PathParam("subPath") String subPath,
            @Context HttpHeaders inHeaders,
            @Context jakarta.ws.rs.core.UriInfo uriInfo,
            byte[] body) {
        return proxySession(sessionId, subPath, "POST", body, inHeaders, uriInfo);
    }

    @PUT
    @Path("/session/{sessionId}/{subPath: .*}")
    @Authenticated
    @Blocking
    public Response proxySessionPut(
            @PathParam("sessionId") String sessionId,
            @PathParam("subPath") String subPath,
            @Context HttpHeaders inHeaders,
            @Context jakarta.ws.rs.core.UriInfo uriInfo,
            byte[] body) {
        return proxySession(sessionId, subPath, "PUT", body, inHeaders, uriInfo);
    }

    @DELETE
    @Path("/session/{sessionId}/{subPath: .*}")
    @Authenticated
    @Blocking
    public Response proxySessionDelete(
            @PathParam("sessionId") String sessionId,
            @PathParam("subPath") String subPath,
            @Context HttpHeaders inHeaders,
            @Context jakarta.ws.rs.core.UriInfo uriInfo,
            byte[] body) {
        // DELETE carries a body in some tool APIs (file-browser sends the list of items
        // to remove to /api/resources/bulk). Dropping it made the tool answer 400 and
        // the file stayed, so forward it like the other methods.
        return proxySession(sessionId, subPath, "DELETE", body, inHeaders, uriInfo);
    }

    @PATCH
    @Path("/session/{sessionId}/{subPath: .*}")
    @Authenticated
    @Blocking
    public Response proxySessionPatch(
            @PathParam("sessionId") String sessionId,
            @PathParam("subPath") String subPath,
            @Context HttpHeaders inHeaders,
            @Context jakarta.ws.rs.core.UriInfo uriInfo,
            byte[] body) {
        return proxySession(sessionId, subPath, "PATCH", body, inHeaders, uriInfo);
    }

    private Response proxySession(String sessionId, String subPath, String method,
                                   byte[] body, HttpHeaders inHeaders,
                                   jakarta.ws.rs.core.UriInfo uriInfo) {
        String userId = getCurrentUsername();
        ActorRef<SessionManager> sm = actorSystem.getSessionManager();

        // Ownership check
        List<SessionStatus> userSessions;
        try {
            userSessions = sm.ask(mgr -> mgr.getUserSessions(sm, userId)).get();
        } catch (Exception e) {
            LOG.severe("Failed to look up sessions for proxy: " + e.getMessage());
            return Response.serverError().build();
        }
        SessionTarget.Resolution target = SessionTarget.resolve(
            sessionId, SessionTarget.keepTrailingSlash(subPath, uriInfo.getRequestUri().getRawPath()),
            uriInfo.getRequestUri().getRawQuery(), "http",
            userSessions, toolLookup(sm, sessionId), userPodsNamespace);
        if (target.kind() == SessionTarget.Kind.FORBIDDEN) {
            LOG.warning("Unauthorized session access: user=" + userId + " sessionId=" + sessionId);
            return Response.status(Response.Status.FORBIDDEN)
                .entity("You do not own this session").build();
        }

        // Update idle timer
        sm.tell(mgr -> mgr.touchSession(sm, target.sessionId()));

        return forward(target.url(), method, body, inHeaders, target.what());
    }

    /** Finds a tool plugin by display name; a lookup that fails answers null, which means the default settings. */
    private java.util.function.Function<String, ToolPlugin> toolLookup(ActorRef<SessionManager> sm, String sessionId) {
        return toolName -> {
            try {
                return sm.ask(SessionManager::listAvailableTools).get().stream()
                    .filter(t -> t.displayName().equals(toolName))
                    .findFirst()
                    .orElse(null);
            } catch (Exception e) {
                LOG.warning("Could not determine tool settings for " + sessionId + ": " + e.getMessage());
                return null;
            }
        };
    }

    /**
     * Forward one request to {@code targetUrl} and stream the answer back. Shared by the session
     * proxy and the shared-service proxy; {@code what} names the target in the log.
     */
    private Response forward(String targetUrl, String method, byte[] body, HttpHeaders inHeaders, String what) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder().uri(URI.create(targetUrl));
            HttpRequest.BodyPublisher publisher = (body != null && body.length > 0)
                ? HttpRequest.BodyPublishers.ofByteArray(body)
                : HttpRequest.BodyPublishers.noBody();
            rb.method(method, publisher);

            inHeaders.getRequestHeaders().forEach((name, values) -> {
                if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                    try {
                        rb.header(name, String.join(", ", values));
                    } catch (IllegalArgumentException ignored) {}
                }
            });

            // HOP_BY_HOP drops Host, so the tool would build absolute URLs (Gradio's config root, redirects) from the
            // Pod's service name, which the browser cannot reach. Hand the browser-facing host on instead.
            String browserHost = inHeaders.getHeaderString("Host");
            if (browserHost != null && !inHeaders.getRequestHeaders().containsKey("X-Forwarded-Host")) {
                rb.header("X-Forwarded-Host", browserHost);
            }

            // Stream the response body via StreamingOutput with explicit flush() on each chunk.
            // Returning InputStream directly risks Vert.x write-buffer batching, which delays
            // Guacamole's nop keepalives (every ~3s) until the buffer fills — Apache/the browser
            // then sees no data for 60s and closes the connection ("Guacamole internal error").
            // StreamingOutput + flush() pushes each chunk through the network immediately.
            HttpResponse<InputStream> resp = SESSION_HTTP_CLIENT.send(
                rb.build(), HttpResponse.BodyHandlers.ofInputStream());

            Response.ResponseBuilder responseBuilder = Response.status(resp.statusCode());
            resp.headers().map().forEach((name, vals) -> {
                if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT)) && !":status".equals(name)) {
                    vals.forEach(v -> responseBuilder.header(name, v));
                }
            });

            InputStream bodyStream = resp.body();
            boolean eventStream = isEventStream(resp.headers().firstValue("content-type").orElse(""));
            StreamingOutput so = (OutputStream output) -> {
                byte[] buf = new byte[8192];
                int n;
                try {
                    if (eventStream) {
                        // The response headers go out with the first bytes of the body. A stream that stays silent until
                        // something happens (the Chat UI's) would leave the browser without headers, so its EventSource
                        // never opens and the screen shows "disconnected". An SSE comment line is ignored by EventSource.
                        output.write(SSE_OPEN_COMMENT);
                        output.flush();
                    }
                    while ((n = bodyStream.read(buf)) != -1) {
                        output.write(buf, 0, n);
                        output.flush();
                    }
                } finally {
                    bodyStream.close();
                }
            };
            responseBuilder.entity(so);
            return responseBuilder.build();

        } catch (Exception e) {
            LOG.severe("Proxy error for " + what + " path=" + targetUrl
                + ": [" + e.getClass().getSimpleName() + "] " + e);
            return Response.serverError().entity("Proxy error: " + e).build();
        }
    }

    // ── Shared services (cluster-wide Deployments; see SharedService) ────────

    /** One shared service as the dashboard card shows it. */
    public record SharedServiceView(SharedService service, String state, String badge,
                                    boolean running, boolean starting) {
        static SharedServiceView of(SharedService svc, K8sApiClient.SharedServiceState st) {
            String badge = switch (st) {
                case RUNNING -> "READY";
                case STARTING -> "STARTING";
                case DOWN -> "STOPPED";
                case UNKNOWN -> "FAILED";
            };
            return new SharedServiceView(svc, st.name(), badge,
                st == K8sApiClient.SharedServiceState.RUNNING,
                st == K8sApiClient.SharedServiceState.STARTING);
        }
    }

    /** Open: proxy {@code /service/{name}/...} to the service's ClusterIP for any logged-in user. */
    @GET
    @Path("/service/{name}/{subPath: .*}")
    @Authenticated
    @Blocking
    public Response proxySharedServiceGet(@PathParam("name") String name,
                                          @PathParam("subPath") String subPath,
                                          @Context HttpHeaders inHeaders,
                                          @Context jakarta.ws.rs.core.UriInfo uriInfo) {
        return proxySharedService(name, subPath, "GET", null, inHeaders, uriInfo);
    }

    @POST
    @Path("/service/{name}/{subPath: .*}")
    @Authenticated
    @Blocking
    public Response proxySharedServicePost(@PathParam("name") String name,
                                           @PathParam("subPath") String subPath,
                                           @Context HttpHeaders inHeaders,
                                           @Context jakarta.ws.rs.core.UriInfo uriInfo,
                                           byte[] body) {
        return proxySharedService(name, subPath, "POST", body, inHeaders, uriInfo);
    }

    private Response proxySharedService(String name, String subPath, String method, byte[] body,
                                        HttpHeaders inHeaders, jakarta.ws.rs.core.UriInfo uriInfo) {
        SharedService svc = actorSystem.getSharedService(name);
        if (svc == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Unknown shared service").build();
        }
        // The service's own launch/stop paths are the dashboard's, never forwarded.
        if ("launch".equals(subPath) || "stop".equals(subPath)) {
            return Response.status(Response.Status.METHOD_NOT_ALLOWED).build();
        }
        String rawQuery = uriInfo.getRequestUri().getRawQuery();
        String targetUrl = svc.clusterUrl() + "/" + subPath
            + (rawQuery != null && !rawQuery.isEmpty() ? "?" + rawQuery : "");
        return forward(targetUrl, method, body, inHeaders, "shared service " + name);
    }

    /** Launch: scale the Deployment to 1. Administrators only; idempotent when already running. */
    @POST
    @Path("/service/{name}/launch")
    @Authenticated
    public Response launchSharedService(@PathParam("name") String name) {
        return scaleSharedService(name, 1);
    }

    /** Stop: scale the Deployment to 0. Administrators only. Every tool that uses the service loses it. */
    @POST
    @Path("/service/{name}/stop")
    @Authenticated
    public Response stopSharedService(@PathParam("name") String name) {
        return scaleSharedService(name, 0);
    }

    private Response scaleSharedService(String name, int replicas) {
        String userId = getCurrentUsername();
        SharedService svc = actorSystem.getSharedService(name);
        if (svc == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Unknown shared service").build();
        }
        // Checked here, not only hidden in the page: a request without the button is still a request.
        if (!actorSystem.isAdmin(userId, getCurrentUserRoles())) {
            LOG.warning("Shared service " + name + " scale to " + replicas + " refused for user " + userId);
            return Response.status(Response.Status.FORBIDDEN)
                .entity("Only an administrator may launch or stop a shared service").build();
        }
        try {
            actorSystem.getSharedServiceControl().ask(c -> { c.scale(svc.namespace(), svc.deployment(), replicas); return null; }).get();
            LOG.info("Shared service " + name + " scaled to " + replicas + " by " + userId);
        } catch (Exception e) {
            LOG.severe("Shared service " + name + " scale failed: " + e.getMessage());
            return Response.seeOther(URI.create("/dashboard?error=shared_service_scale_failed")).build();
        }
        return Response.seeOther(URI.create("/dashboard")).build();
    }

    // ── Storage endpoints ─────────────────────────────────────────────────────

    @GET
    @Path("/storage/info")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStorageInfo() {
        // The storage an administrator gave the user, and the state of each PVC. Nothing is created here except the
        // PVCs of the user's allocations (and the default allocation at the first use), as on the dashboard.
        String userId = getCurrentUsername();
        ActorRef<SessionManager> sm = actorSystem.getSessionManager();
        ActorRef<StorageAllocationService> alloc = actorSystem.getStorageAllocations();
        Map<String, String> allocated = Collections.emptyMap();
        Map<String, Object> allPvcInfo = Collections.emptyMap();
        List<Map<String, String>> activeSessions = Collections.emptyList();
        try {
            alloc.ask(a -> { a.ensureStorage(userId); return null; }).get();
            allocated = alloc.ask(a -> a.activeTypes(userId)).get();
            allPvcInfo = sm.ask(mgr -> mgr.getAllUserPvcInfo(userId)).get();
            activeSessions = sm.ask(mgr -> mgr.getUserSessions(sm, userId)).get().stream()
                .filter(s -> s.state() == com.scivicslab.k8spups.actor.SessionState.READY
                    || s.state() == com.scivicslab.k8spups.actor.SessionState.STARTING)
                .map(s -> Map.of(
                    "toolName", s.toolName(),
                    "podName", s.podName() != null ? s.podName() : "",
                    "storageType", s.storageType() != null ? s.storageType() : "",
                    "state", s.state().name()))
                .toList();
        } catch (Exception e) {
            LOG.warning("Failed to load storage info: " + e.getMessage());
        }
        List<String> types = List.copyOf(allocated.keySet());
        Map<String, Object> pvcInfo = new HashMap<>();
        for (String t : types) pvcInfo.put(t, allPvcInfo.getOrDefault(t, Map.of("exists", "false")));

        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("allocated", allocated);
        result.put("storageTypeNames", StorageTypes.names(types));
        result.put("storageTypeDescriptions", StorageTypes.descriptions(types));
        result.put("objectStorageEndpoint", objectStorageEndpoint.orElse(""));
        result.put("pvcInfo", pvcInfo);
        result.put("activeSessions", activeSessions);
        return Response.ok(result).build();
    }

    /**
     * Maps form field values (userParam_0, _1, _2) to env var names
     * defined in the tool's userParameters().
     */
    private Map<String, String> resolveUserParams(String toolName,
                                                   String... values) {
        ActorRef<SessionManager> sm = actorSystem.getSessionManager();
        List<ToolPlugin> tools;
        try {
            tools = sm.ask(SessionManager::listAvailableTools).get();
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
                // The type is checked against the user's allocations when the session is created
                mounts.add(new MountSpec(type, path, null));
            }
        }
        return mounts;
    }

    /**
     * The Visas that the UserInfo response of the user's login carries in the claim ga4gh_passport_v1: an array of Visa
     * JWTs. Empty when the response has no such claim or the login has no UserInfo (quarkus.oidc.authentication.user-info-required).
     * The Visas are not trusted here; the Passport support checks the signature of each.
     */
    private List<String> currentVisas() {
        List<String> visas = new ArrayList<>();
        try {
            if (identity != null && identity.getAttribute("userinfo") instanceof io.quarkus.oidc.UserInfo userInfo
                    && userInfo.getArray("ga4gh_passport_v1") != null) {
                userInfo.getArray("ga4gh_passport_v1").forEach(v -> {
                    if (v instanceof jakarta.json.JsonString s) visas.add(s.getString());
                });
            }
        } catch (Exception e) {
            LOG.warning("The Visas of the UserInfo response could not be read: " + e.getMessage());
        }
        return visas;
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
