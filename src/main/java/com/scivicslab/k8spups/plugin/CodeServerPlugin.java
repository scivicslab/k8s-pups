package com.scivicslab.k8spups.plugin;

import java.util.List;
import java.util.Map;

/**
 * Tool plugin for the code editor alone: code-server (VS Code in the browser) opened on the user's {@code ~/works}, with
 * its terminal. It is what the tool code-raptor runs next to its search portal and AI chat, without them.
 *
 * <p>Access is decided by the controller (login and ownership of the session), so the editor has no password of its own.
 * The gateway removes the session path prefix; the editor builds its URLs relative to the page, so it works under that
 * prefix, and the controller relays its WebSocket ({@code SessionWebSocketProxy}). Downloads and uploads through the
 * editor are closed in the image: the data of the session must not leave through its screen.
 */
public class CodeServerPlugin implements ToolPlugin {

    /** The directory the editor opens, and where the user's storage is mounted. */
    static final String WORKSPACE = "/home/devteam/works";

    @Override
    public String name() {
        return "code-server";
    }

    @Override
    public String displayName() {
        return "Code Editor";
    }

    @Override
    public String description() {
        return "Edit and run your programs in a code editor in the browser (VS Code), with a terminal, on your own storage.";
    }

    @Override
    public String containerImage() {
        return "${REGISTRY}/code-server:4.141.0-2611021914";
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
    public Map<String, String> environmentVariables() {
        return Map.of("HOME", "/home/devteam");
    }

    @Override
    public String readinessProbePath() {
        return "/healthz";
    }

    @Override
    public int readinessProbeInitialDelay() {
        return 5;
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "250m", "memory", "1Gi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "2", "memory", "4Gi");
    }

    /**
     * Where the editor keeps its settings and its extensions. A tool Pod runs as the number of the cluster (on a007 not the
     * 1000 that owns /home/devteam in the image), so the editor cannot create these in the image; each gets a directory of
     * the Pod that every user can write. They last as long as the session; the files of the user are in the workspace.
     */
    @Override
    public List<String> writablePaths() {
        return List.of("/home/devteam/.config", "/home/devteam/.local");
    }

    @Override
    public String userDataMountPath() {
        return WORKSPACE;
    }

    @Override
    public String extraMountsUnder() {
        // The editor opens this directory: the datasets and the group storage are found in its file tree.
        return WORKSPACE;
    }

    @Override
    public boolean readOnlyRootFilesystem() {
        return false;
    }

    @Override
    public Long runAsUser() {
        return 1000L;
    }

    @Override
    public boolean runAsNonRoot() {
        return true;
    }
}
