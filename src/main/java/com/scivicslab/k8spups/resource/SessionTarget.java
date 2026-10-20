package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.SessionStatus;
import com.scivicslab.k8spups.k8s.SessionPath;
import com.scivicslab.k8spups.plugin.ToolPlugin;

import java.util.List;
import java.util.function.Function;

/**
 * Decides where a request for {@code /session/{id}/...} goes and whether the user may send it. The HTTP proxy and the
 * WebSocket proxy both ask here, so the ownership rule is written once.
 */
final class SessionTarget {

    /** What the request names: a session of the user, a tool started inside one of the user's sessions, or neither. */
    enum Kind { SESSION, SUB_TOOL, FORBIDDEN }

    /**
     * @param kind       what the first path segment names
     * @param sessionId  the session whose idle timer to touch (null when forbidden)
     * @param url        the address inside the cluster to send the request to (null when forbidden)
     * @param what       the target in words, for the log
     */
    record Resolution(Kind kind, String sessionId, String url, String what) {
        static Resolution forbidden() {
            return new Resolution(Kind.FORBIDDEN, null, null, null);
        }
    }

    private SessionTarget() {
    }

    /**
     * The JAX-RS path parameter drops the trailing slash of the request, but a tool whose screen loads its files by
     * relative paths (the code editor at {@code editor/}) needs it: without it the files are looked up one directory up.
     *
     * @param subPath  the path after the session id, as the path parameter gave it
     * @param rawPath  the path of the request as it was sent
     */
    static String keepTrailingSlash(String subPath, String rawPath) {
        return rawPath != null && rawPath.endsWith("/") && !subPath.isEmpty() && !subPath.endsWith("/")
            ? subPath + "/" : subPath;
    }

    /**
     * @param first          the first segment after {@code /session/}: a session id, or {@code {sessionId}-{tool}-{port}}
     * @param subPath        the rest of the path, without the leading slash
     * @param rawQuery       the query string as sent, or null
     * @param scheme         {@code http} or {@code ws}
     * @param userSessions   the sessions of the logged-in user
     * @param toolByName     finds the tool plugin by its display name
     * @param podsNamespace  the namespace of the users' Pods
     */
    static Resolution resolve(String first, String subPath, String rawQuery, String scheme,
                              List<SessionStatus> userSessions,
                              Function<String, ToolPlugin> toolByName, String podsNamespace) {
        String query = rawQuery != null && !rawQuery.isEmpty() ? "?" + rawQuery : "";

        SessionStatus session = userSessions.stream()
            .filter(s -> s.sessionId().equals(first))
            .findFirst()
            .orElse(null);
        if (session == null) {
            // Not a session id: perhaps a tool started inside one of this user's session Pods,
            // named {sessionId}-{toolName}-{port} by SubToolResource.
            DashboardResource.SubTool subTool = DashboardResource.SubTool.parse(first, userSessions);
            if (subTool == null) {
                return Resolution.forbidden();
            }
            String url = scheme + "://" + subTool.serviceName() + "." + podsNamespace
                + ".svc.cluster.local:" + subTool.port() + "/" + subPath + query;
            return new Resolution(Kind.SUB_TOOL, subTool.sessionId(), url, "sub-tool " + first);
        }

        // The tool plugin's settings decide whether the session prefix stays in the path, and the port.
        ToolPlugin tool = toolByName.apply(session.toolName());
        boolean passthrough = tool != null && tool.passthroughPath();
        int containerPort = tool != null ? tool.containerPort() : 8080;

        String targetPath = passthrough
            ? SessionPath.of(first) + "/" + subPath
            : "/" + subPath;
        String url = scheme + "://pups-svc-" + first + "." + podsNamespace + ".svc.cluster.local:" + containerPort
            + targetPath + query;
        return new Resolution(Kind.SESSION, first, url, "session " + first);
    }
}
