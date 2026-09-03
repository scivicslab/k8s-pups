package com.scivicslab.k8spups.plugin;

import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Tool plugin for English Toolkit (quarkus-english-toolkit).
 *
 * The same program also runs on its own outside Kubernetes; everything that differs between the two
 * is passed in from here. The dictionaries it reads are identical for every user, so they are held
 * once on NFS and mounted read-only rather than copied into each user's storage, while the review
 * history stays in the user's own workspace.
 *
 * The application serves its pages at the container root and builds page URLs from
 * PUPS_SESSION_PATH, so the session prefix is stripped before the request arrives
 * (passthroughPath stays false).
 */
public class EnglishToolkitPlugin implements ToolPlugin {

    /** Where the shared dictionaries appear inside the container. */
    private static final String DICTIONARY_MOUNT = "/dictionaries";

    @Override
    public String name() {
        return "quarkus-english-toolkit";
    }

    @Override
    public String displayName() {
        return "English Toolkit";
    }

    @Override
    public String description() {
        return "Learn English from your own material: spaced-repetition drills, three dictionaries, "
                + "corpus examples filtered by meaning, and PDF or video import.";
    }

    @Override
    public String containerImage() {
        return "${REGISTRY}/quarkus-english-toolkit:1.0.0-2609031415";
    }

    @Override
    public int containerPort() {
        return 28200;
    }

    @Override
    public ConnectionType connectionType() {
        return ConnectionType.HTTP;
    }

    @Override
    public String readinessProbePath() {
        return "/";
    }

    @Override
    public int readinessProbeInitialDelay() {
        return 15;
    }

    @Override
    public String userDataMountPath() {
        return "/home/ubuntu";
    }

    @Override
    public boolean workspaceEnabled() {
        return true;
    }

    @Override
    public boolean readOnlyRootFilesystem() {
        // The H2 database and the imported material are written under the user's home.
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

    /**
     * The dictionaries every user reads, held once and mounted read-only. Which server and path hold
     * them is deployment config, so the public source carries no internal address.
     */
    @Override
    public List<NfsVolumeSpec> nfsVolumes() {
        var cfg = ConfigProvider.getConfig();
        String server = cfg.getOptionalValue("k8spups.english-toolkit.dictionary-nfs-server", String.class)
                .orElse("");
        String path = cfg.getOptionalValue("k8spups.english-toolkit.dictionary-nfs-path", String.class)
                .orElse("");
        if (server.isBlank() || path.isBlank()) {
            // Without the share the application still starts; the dictionary screens are then empty.
            return List.of();
        }
        return List.of(new NfsVolumeSpec(server, path, DICTIONARY_MOUNT, true));
    }

    /**
     * Where the dictionaries are, and which services do the work that needs a GPU. Real addresses are
     * injected per deployment (K8SPUPS_ENGLISH_TOOLKIT_* env); the defaults are neutral placeholders.
     */
    @Override
    public Map<String, String> environmentVariables() {
        var cfg = ConfigProvider.getConfig();
        return Map.of(
            "ENGLISH_COBUILD_PATH", DICTIONARY_MOUNT + "/cobuild.json",
            "ENGLISH_COBUILD_OCR_DIR", DICTIONARY_MOUNT + "/cobuild-ocr",
            "ENGLISH_ACTIVATOR_OCR_DIR", DICTIONARY_MOUNT + "/activator-ocr",
            "ENGLISH_KWIC_BASE_URL",
                cfg.getOptionalValue("k8spups.english-toolkit.kwic-url", String.class)
                    .orElse("http://wiki-kwic:8080"),
            "GPU_BROKER_URL",
                cfg.getOptionalValue("k8spups.english-toolkit.gpu-broker-url", String.class)
                    .orElse("http://gpu-broker:28005")
        );
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "500m", "memory", "2Gi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "4", "memory", "8Gi");
    }
}
