package com.scivicslab.k8spups.plugin;

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
        return "ghcr.io/gtsteffaniak/filebrowser:stable";
    }

    @Override
    public int containerPort() {
        return 8080;
    }

    @Override
    public ConnectionType connectionType() {
        return ConnectionType.HTTP;
    }

    @Override
    public List<String> containerCommand() {
        // Write config to /tmp (read-only root filesystem).
        // $PUPS_SESSION_PATH is injected by k8s-pups (e.g. /session/abc123/).
        return List.of("/bin/sh", "-c",
            "cat > /tmp/filebrowser-config.yaml << 'FBEOF'\n"
            + "server:\n"
            + "  port: 8080\n"
            + "  baseURL: \"BASEURLPLACEHOLDER\"\n"
            + "  cacheDir: \"/tmp/fb-cache\"\n"
            + "  sources:\n"
            + "    - path: \"/srv\"\n"
            + "auth:\n"
            + "  methods:\n"
            + "    noauth: true\n"
            + "userDefaults:\n"
            + "  fileLoading:\n"
            + "    uploadChunkSizeMb: 50\n"
            + "FBEOF\n"
            + "sed -i \"s|BASEURLPLACEHOLDER|$PUPS_SESSION_PATH|g\" /tmp/filebrowser-config.yaml\n"
            + "FILEBROWSER_DATABASE=/tmp/filebrowser.db "
            + "exec /home/filebrowser/filebrowser -c /tmp/filebrowser-config.yaml");
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
    public boolean workspaceEnabled() {
        return false;
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
