package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.SessionManager;
import com.scivicslab.k8spups.actor.SessionStatus;
import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import com.scivicslab.pojoactor.core.ActorRef;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.HttpServerOptionsCustomizer;
import io.quarkus.vertx.http.runtime.RouteConstants;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketClientOptions;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * Relays a browser's WebSocket to the tool in the user's own session Pod. The HTTP proxy ({@code DashboardResource})
 * cannot: it answers one request with one response. A tool whose screen talks over a WebSocket, such as the code editor
 * (code-server) and the notebook kernels of JupyterLab, needs this.
 *
 * <p>The route is registered on the main router just before the REST routes, for GET requests that ask for a WebSocket.
 * Every other request goes on to the REST routes unchanged. Quarkus has already logged the user in by then, because the
 * path {@code /session/*} requires authentication: a browser that is not logged in never reaches this class.</p>
 */
@ApplicationScoped
public class SessionWebSocketProxy implements HttpServerOptionsCustomizer {

    private static final Logger LOG = Logger.getLogger(SessionWebSocketProxy.class.getName());

    /** The largest single frame the browser or the tool may send. Vert.x closes the connection (1009) above this. */
    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    /** The session's idle timer is touched this often while a connection stays open. */
    private static final long TOUCH_INTERVAL_MS = 60_000;

    /** Headers that describe one connection and must not be copied to the next. */
    private static final Set<String> NOT_COPIED = Set.of(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
        "transfer-encoding", "upgrade", "host", "content-length");

    @Inject
    K8sPupsActorSystem actorSystem;

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/pups")
    String basePath;

    @ConfigProperty(name = "k8spups.user-pods-namespace", defaultValue = "user-pods")
    String userPodsNamespace;

    private final ConcurrentHashMap<String, LongAdder> relayedBytes = new ConcurrentHashMap<>();
    private volatile WebSocketClient client;

    @Override
    public void customizeHttpServer(HttpServerOptions options) {
        options.setMaxWebSocketFrameSize(MAX_FRAME_BYTES);
        options.setMaxWebSocketMessageSize(4 * MAX_FRAME_BYTES);
    }

    void register(@Observes Router router) {
        // The router of the application is mounted under quarkus.http.root-path, so the route is written without it,
        // like the paths of the REST resources and of quarkus.http.auth.permission. The request's own path has it.
        String fullPrefix = stripTrailingSlash(basePath) + "/session/";
        router.route("/session/*")
            .method(HttpMethod.GET)
            .order(RouteConstants.ROUTE_ORDER_BEFORE_DEFAULT)
            .handler(ctx -> handle(ctx, fullPrefix));
    }

    /** Bytes relayed so far for the session, in both directions. The limit on a session's total reads this. */
    public long relayedBytes(String sessionId) {
        LongAdder adder = relayedBytes.get(sessionId);
        return adder == null ? 0 : adder.sum();
    }

    private void handle(RoutingContext ctx, String prefix) {
        HttpServerRequest request = ctx.request();
        if (!isWebSocketUpgrade(request.getHeader("Upgrade"), request.getHeader("Connection"))) {
            ctx.next();
            return;
        }

        String user = userName(ctx.user() instanceof QuarkusHttpUser q ? q.getSecurityIdentity() : null);
        if (user == null) {
            reject(ctx, 401, "Login is required");
            return;
        }
        if (!OriginCheck.allowed(request.getHeader("Origin"), request.getHeader("Host"),
                request.getHeader("X-Forwarded-Host"))) {
            LOG.warning("WebSocket refused, Origin does not match the host: user=" + user
                + " origin=" + request.getHeader("Origin") + " host=" + request.getHeader("Host"));
            reject(ctx, 403, "Cross-site WebSocket connections are refused");
            return;
        }

        String rest = request.path().substring(Math.min(prefix.length(), request.path().length()));
        int slash = rest.indexOf('/');
        String first = slash < 0 ? rest : rest.substring(0, slash);
        String subPath = slash < 0 ? "" : rest.substring(slash + 1);
        String rawQuery = request.query();
        Map<String, List<String>> inHeaders = headersOf(request.headers());
        String browserHost = request.getHeader("Host");

        // The session lookup waits on the session actor, so it runs on a worker thread, not on the event loop.
        vertx.<SessionTarget.Resolution>executeBlocking(() -> {
            ActorRef<SessionManager> sm = actorSystem.getSessionManager();
            List<SessionStatus> userSessions = sm.ask(mgr -> mgr.getUserSessions(sm, user)).get();
            ToolPlugin[] tools = sm.ask(SessionManager::listAvailableTools).get().toArray(new ToolPlugin[0]);
            SessionTarget.Resolution resolution = SessionTarget.resolve(first, subPath, rawQuery, "ws", userSessions,
                name -> {
                    for (ToolPlugin t : tools) {
                        if (t.displayName().equals(name)) {
                            return t;
                        }
                    }
                    return null;
                }, userPodsNamespace);
            if (resolution.kind() != SessionTarget.Kind.FORBIDDEN) {
                sm.tell(mgr -> mgr.touchSession(sm, resolution.sessionId()));
            }
            return resolution;
        }).onSuccess(resolution -> {
            if (resolution.kind() == SessionTarget.Kind.FORBIDDEN) {
                LOG.warning("Unauthorized WebSocket access: user=" + user + " sessionId=" + first);
                reject(ctx, 403, "You do not own this session");
                return;
            }
            open(ctx, resolution, UpstreamHeaders.of(inHeaders, browserHost));
        }).onFailure(e -> {
            LOG.severe("WebSocket session lookup failed: [" + e.getClass().getSimpleName() + "] " + e);
            reject(ctx, 500, "Session lookup failed");
        });
    }

    /** Connects to the tool first, then accepts the browser, so a tool that refuses is reported to the browser as such. */
    private void open(RoutingContext ctx, SessionTarget.Resolution target, MultiMap headers) {
        HttpServerRequest request = ctx.request();
        WebSocketConnectOptions options = new WebSocketConnectOptions()
            .setAbsoluteURI(target.url())
            .setHeaders(headers);
        client().connect(options).onFailure(e -> {
            LOG.warning("WebSocket to " + target.what() + " failed: [" + e.getClass().getSimpleName() + "] " + e.getMessage());
            reject(ctx, 502, "The tool did not accept the WebSocket connection");
        }).onSuccess(tool -> request.toWebSocket().onFailure(e -> {
            LOG.warning("WebSocket upgrade of the browser failed for " + target.what() + ": " + e);
            tool.close();
        }).onSuccess(browser -> {
            LongAdder counter = relayedBytes.computeIfAbsent(target.sessionId(), id -> new LongAdder());
            WebSocketRelay.connect(browser, tool, counter);
            long timer = vertx.setPeriodic(TOUCH_INTERVAL_MS, id -> touch(target.sessionId()));
            browser.closeHandler(v -> vertx.cancelTimer(timer));
            LOG.info("WebSocket relay opened: " + target.what() + " -> " + request.path());
        }));
    }

    private void touch(String sessionId) {
        ActorRef<SessionManager> sm = actorSystem.getSessionManager();
        sm.tell(mgr -> mgr.touchSession(sm, sessionId));
    }

    private WebSocketClient client() {
        WebSocketClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = vertx.createWebSocketClient(new WebSocketClientOptions()
                        .setMaxFrameSize(MAX_FRAME_BYTES)
                        .setMaxMessageSize(4 * MAX_FRAME_BYTES));
                    client = c;
                }
            }
        }
        return c;
    }

    private static void reject(RoutingContext ctx, int status, String message) {
        if (!ctx.response().ended()) {
            ctx.response().setStatusCode(status).putHeader("Content-Type", "text/plain; charset=utf-8").end(message);
        }
    }

    private static Map<String, List<String>> headersOf(MultiMap headers) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String name : headers.names()) {
            map.put(name, headers.getAll(name));
        }
        return map;
    }

    static String stripTrailingSlash(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /** True for a request that asks to change to the WebSocket protocol (RFC 6455, section 4.2.1). */
    static boolean isWebSocketUpgrade(String upgrade, String connection) {
        if (upgrade == null || connection == null) {
            return false;
        }
        if (!upgrade.trim().equalsIgnoreCase("websocket")) {
            return false;
        }
        for (String token : connection.split(",")) {
            if (token.trim().equalsIgnoreCase("upgrade")) {
                return true;
            }
        }
        return false;
    }

    /** The user name as the dashboard reads it: the ID token's {@code preferred_username}, else the principal's name. */
    static String userName(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return null;
        }
        if (identity.getPrincipal() instanceof JsonWebToken jwt) {
            Object claim = jwt.getClaim("preferred_username");
            if (claim != null) {
                return claim.toString();
            }
        }
        return identity.getPrincipal().getName();
    }

    /** The headers to send to the tool: the browser's, without those of the browser's connection, plus the host. */
    static final class UpstreamHeaders {
        private UpstreamHeaders() {
        }

        static MultiMap of(Map<String, List<String>> browser, String browserHost) {
            MultiMap out = MultiMap.caseInsensitiveMultiMap();
            boolean hasForwardedHost = false;
            for (Map.Entry<String, List<String>> e : browser.entrySet()) {
                String name = e.getKey().toLowerCase(Locale.ROOT);
                if (NOT_COPIED.contains(name) || name.startsWith("sec-websocket-")) {
                    continue;
                }
                if (name.equals("x-forwarded-host")) {
                    hasForwardedHost = true;
                }
                for (String value : e.getValue()) {
                    out.add(e.getKey(), value);
                }
            }
            // The tool builds absolute URLs and checks the Origin against the host it is told; tell it the browser's.
            if (!hasForwardedHost && browserHost != null) {
                out.add("X-Forwarded-Host", browserHost);
            }
            return out;
        }
    }

    /** Refuses a WebSocket opened from another site: the browser sends its cookies to it all the same. */
    static final class OriginCheck {
        private OriginCheck() {
        }

        /**
         * @return true when there is no Origin (not a browser), or when the Origin's host is the host the request was
         *         sent to (or the host the proxy in front reports)
         */
        static boolean allowed(String origin, String host, String forwardedHost) {
            if (origin == null || origin.isBlank()) {
                return true;
            }
            String originAuthority;
            String scheme;
            try {
                URI uri = URI.create(origin.trim());
                originAuthority = uri.getRawAuthority();
                scheme = uri.getScheme();
            } catch (IllegalArgumentException e) {
                return false;
            }
            if (originAuthority == null || scheme == null) {
                return false;
            }
            String want = normalize(originAuthority, scheme);
            for (String candidate : new String[] { host, firstOf(forwardedHost) }) {
                if (candidate != null && !candidate.isBlank() && normalize(candidate, scheme).equals(want)) {
                    return true;
                }
            }
            return false;
        }

        private static String firstOf(String forwardedHost) {
            if (forwardedHost == null) {
                return null;
            }
            int comma = forwardedHost.indexOf(',');
            return comma < 0 ? forwardedHost : forwardedHost.substring(0, comma);
        }

        /** Lower case, without the port that the scheme implies. */
        private static String normalize(String authority, String scheme) {
            String a = authority.trim().toLowerCase(Locale.ROOT);
            String implied = scheme.equalsIgnoreCase("https") ? ":443" : scheme.equalsIgnoreCase("http") ? ":80" : "";
            return !implied.isEmpty() && a.endsWith(implied) ? a.substring(0, a.length() - implied.length()) : a;
        }
    }
}
