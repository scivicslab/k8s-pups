package com.scivicslab.k8spups.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure unit test of the tool image override logic: an entry in the mounted file wins,
 * a missing entry or missing file falls back to the plugin's compiled-in default.
 * Uses a stub ToolPlugin and a temp file (no Quarkus, no k8s).
 */
class ToolImageResolverTest {

    /** Minimal ToolPlugin: only the fields the resolver reads matter. */
    private static ToolPlugin plugin(String name, String defaultImage) {
        return new ToolPlugin() {
            @Override public String name() { return name; }
            @Override public String displayName() { return name; }
            @Override public String containerImage() { return defaultImage; }
            @Override public int containerPort() { return 8080; }
            @Override public ConnectionType connectionType() { return ConnectionType.HTTP; }
        };
    }

    @Test
    void resolve_overrideFilePresentWithKey_usesOverride(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tool-images.properties");
        Files.writeString(file, "quarkus-exdb2=registry/quarkus-exdb2:9.9.9-2606301200\n");
        ToolImageResolver resolver = new ToolImageResolver(file.toString(), "");
        assertEquals("registry/quarkus-exdb2:9.9.9-2606301200",
                resolver.resolve(plugin("quarkus-exdb2", "registry/quarkus-exdb2:1.0.0")));
    }

    @Test
    void resolve_keyMissingFromFile_fallsBackToPluginDefault(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tool-images.properties");
        Files.writeString(file, "guacamole=registry/guacamole:2.0.0\n");
        ToolImageResolver resolver = new ToolImageResolver(file.toString(), "");
        assertEquals("registry/quarkus-exdb2:1.0.0",
                resolver.resolve(plugin("quarkus-exdb2", "registry/quarkus-exdb2:1.0.0")));
    }

    @Test
    void resolve_fileDoesNotExist_fallsBackToPluginDefault(@TempDir Path dir) {
        ToolImageResolver resolver = new ToolImageResolver(dir.resolve("absent.properties").toString(), "");
        assertEquals("registry/quarkus-exdb2:1.0.0",
                resolver.resolve(plugin("quarkus-exdb2", "registry/quarkus-exdb2:1.0.0")));
    }

    @Test
    void resolve_nullPath_fallsBackToPluginDefault() {
        ToolImageResolver resolver = new ToolImageResolver(null, "");
        assertEquals("registry/quarkus-exdb2:1.0.0",
                resolver.resolve(plugin("quarkus-exdb2", "registry/quarkus-exdb2:1.0.0")));
    }

    @Test
    void resolve_blankOverrideValue_fallsBackToPluginDefault(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tool-images.properties");
        Files.writeString(file, "quarkus-exdb2=   \n");
        ToolImageResolver resolver = new ToolImageResolver(file.toString(), "");
        assertEquals("registry/quarkus-exdb2:1.0.0",
                resolver.resolve(plugin("quarkus-exdb2", "registry/quarkus-exdb2:1.0.0")));
    }

    @Test
    void resolve_registryPlaceholder_substitutedInDefault() {
        ToolImageResolver resolver = new ToolImageResolver(null, "reg.example:5000");
        assertEquals("reg.example:5000/quarkus-exdb2:1.0.0",
                resolver.resolve(plugin("quarkus-exdb2", "${REGISTRY}/quarkus-exdb2:1.0.0")));
    }

    @Test
    void resolve_registryPlaceholder_substitutedInOverride(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tool-images.properties");
        Files.writeString(file, "quarkus-exdb2=${REGISTRY}/quarkus-exdb2:9.9.9\n");
        ToolImageResolver resolver = new ToolImageResolver(file.toString(), "reg.example:5000");
        assertEquals("reg.example:5000/quarkus-exdb2:9.9.9",
                resolver.resolve(plugin("quarkus-exdb2", "${REGISTRY}/quarkus-exdb2:1.0.0")));
    }
}
