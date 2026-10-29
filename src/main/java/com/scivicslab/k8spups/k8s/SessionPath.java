package com.scivicslab.k8spups.k8s;

import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Where a tool session is served in the browser: {@code <prefix>/session/<id>/}.
 *
 * <p>The prefix is empty by default, so a deployment that owns the host root serves sessions at
 * {@code /session/<id>/}. When several deployments sit behind one proxy that routes by path (for example
 * {@code /pups} and {@code /pups-with-audit}), the bare {@code /session} path can lead to only one of them;
 * a deployment then sets {@code K8SPUPS_SESSION_PATH_PREFIX} to its own path ({@code /pups-with-audit})
 * so that its sessions are routed to its own gateway.
 */
public final class SessionPath {

    private SessionPath() {}

    /** The configured prefix without a trailing slash; empty when none is set. */
    public static String prefix() {
        String p = ConfigProvider.getConfig()
            .getOptionalValue("k8spups.session-path-prefix", String.class).orElse("").trim();
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    /** {@code <prefix>/session/<id>} with no trailing slash; {@code id} may carry a sub-tool suffix. */
    public static String of(String id) {
        return prefix() + "/session/" + id;
    }

    /** {@code template} with {@link com.scivicslab.k8spups.plugin.ToolPlugin#SESSION_PATH_PLACEHOLDER} replaced by the session path. */
    public static String expand(String template, String id) {
        return template.replace(com.scivicslab.k8spups.plugin.ToolPlugin.SESSION_PATH_PLACEHOLDER, of(id));
    }
}
