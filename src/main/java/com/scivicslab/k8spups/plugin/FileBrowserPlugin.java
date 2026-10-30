package com.scivicslab.k8spups.plugin;

import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Tool plugin for File Browser Quantum — a browser-based file manager.
 *
 * Mounts the user's PVC at /srv and serves it on port 80.
 * Authentication is handled entirely by the Envoy Gateway SecurityPolicy
 * (Keycloak OIDC + preferred_username claim check); FileBrowser runs in noauth mode.
 *
 * The baseURL must match the session path (/session/{id}/) so that FileBrowser
 * generates correct asset and API URLs. It is read from $PUPS_SESSION_PATH at
 * container startup via a shell command.
 */
public class FileBrowserPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "file-browser";
    }

    @Override
    public String displayName() {
        return "File Browser";
    }

    @Override
    public String description() {
        return "Upload, download, and manage files in your persistent storage directly from the browser.";
    }

    @Override
    public String containerImage() {
        return "${REGISTRY}/filebrowser:v1.4.0-stable";
    }

    @Override
    public int containerPort() {
        return 8080;
    }

    @Override
    public ConnectionType connectionType() {
        return ConnectionType.HTTP;
    }

    /**
     * Whether this deployment shows files without letting them out: no download, no content in the viewer, no upload,
     * no change. Set with K8SPUPS_FILE_BROWSER_VIEW_ONLY=true; the default keeps the full File Browser.
     *
     * <p>File Browser in no-auth mode runs every request as an administrator with all permissions, whatever the
     * defaults say, so the permissions of that user are removed through its own API once the server is up. The source
     * is also read-only, so a change is refused even if a permission is missed.
     */
    private static boolean viewOnly() {
        return ConfigProvider.getConfig()
            .getOptionalValue("k8spups.file-browser.view-only", Boolean.class).orElse(false);
    }

    @Override
    public List<String> containerCommand() {
        boolean viewOnly = viewOnly();
        // Write config to /tmp (read-only root filesystem).
        // $PUPS_SESSION_PATH is injected by k8s-pups (e.g. /session/abc123/).
        StringBuilder script = new StringBuilder(
            "cat > /tmp/filebrowser-config.yaml << 'FBEOF'\n"
            + "server:\n"
            + "  port: 8080\n"
            + "  baseURL: \"BASEURLPLACEHOLDER\"\n"
            + "  cacheDir: \"/tmp/fb-cache\"\n"
            + "  sources:\n"
            + "    - path: \"/srv\"\n"
            + (viewOnly ? "      config:\n        readOnly: true\n" : "")
            + "auth:\n"
            + "  methods:\n"
            + "    noauth: true\n"
            + "userDefaults:\n"
            + "  fileLoading:\n"
            + "    uploadChunkSizeMb: 50\n"
            + "FBEOF\n"
            + "sed -i \"s|BASEURLPLACEHOLDER|$PUPS_SESSION_PATH|g\" /tmp/filebrowser-config.yaml\n"
            + "export FILEBROWSER_DATABASE=/tmp/filebrowser.db\n");
        if (!viewOnly) {
            script.append("exec /home/filebrowser/filebrowser -c /tmp/filebrowser-config.yaml");
            return List.of("/bin/sh", "-c", script.toString());
        }
        // Start the server, wait for it, remove the permissions of the user over its own API (BusyBox wget cannot send
        // PUT, so the request is written to nc), then stay in the foreground with the server.
        script.append(
            "/home/filebrowser/filebrowser -c /tmp/filebrowser-config.yaml &\n"
            + "FB=$!\n"
            + "i=0; while [ $i -lt 60 ]; do wget -q --spider \"http://127.0.0.1:8080$PUPS_SESSION_PATH\" && break; i=$((i+1)); sleep 1; done\n"
            + "BODY='{\"which\":[\"permissions\"],\"data\":{\"id\":1,\"permissions\":{\"api\":false,\"admin\":false,"
            + "\"modify\":false,\"share\":false,\"realtime\":false,\"delete\":false,\"create\":false,\"download\":false}}}'\n"
            + "printf 'PUT %sapi/users?id=1 HTTP/1.1\\r\\nHost: 127.0.0.1\\r\\nContent-Type: application/json\\r\\n"
            + "Content-Length: %d\\r\\nConnection: close\\r\\n\\r\\n%s' \"$PUPS_SESSION_PATH\" \"${#BODY}\" \"$BODY\" | nc 127.0.0.1 8080 > /tmp/fb-lock.txt\n"
            + "wait $FB");
        return List.of("/bin/sh", "-c", script.toString());
    }

    @Override
    public String readinessProbePath() {
        // With a base URL the server answers /health at the root with a redirect (3xx counts as ready) once it listens.
        return "/health";
    }

    @Override
    public int readinessProbeInitialDelay() {
        return 2;
    }

    @Override
    public boolean passthroughPath() {
        // FileBrowser generates absolute URLs using its base URL.
        // Pass /session/{id}/* through without rewriting so assets resolve correctly.
        return true;
    }

    @Override
    public String userDataMountPath() {
        return "/srv";
    }

    @Override
    public String extraMountsUnder() {
        // The screen of this tool shows the home only: the extra mounts are found there.
        return userDataMountPath();
    }

    @Override
    public boolean readOnlyRootFilesystem() {
        return true;
    }

    @Override
    public Long runAsUser() {
        return 1000L;
    }

    @Override
    public boolean runAsNonRoot() {
        return true;
    }

    @Override
    public String seccompProfileType() {
        return "RuntimeDefault";
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "50m", "memory", "128Mi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "200m", "memory", "256Mi");
    }

    @Override
    public List<String> writablePaths() {
        return Collections.emptyList();
    }

    @Override
    public List<ResourceProfile> resourceProfiles() {
        return List.of(new ResourceProfile("default", "Default",
            resourceRequests(), resourceLimits()));
    }
}
