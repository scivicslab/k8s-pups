package com.scivicslab.k8spups.plugin;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves the container image for a {@link ToolPlugin}, allowing the compiled-in
 * default ({@code plugin.containerImage()}) to be overridden at runtime.
 *
 * <p>The motivation is to decouple a tool's image version from the controller's source
 * and lifecycle. With the image hardcoded in the plugin class, bumping one tool's image
 * forced a controller rebuild and redeploy, which also severs every active user's
 * in-flight proxied connection. See the design note ToolImageReferencing_260630_oo01.</p>
 *
 * <p>Overrides are read from a properties file (a mounted ConfigMap) keyed by the
 * plugin's {@link ToolPlugin#name()}:</p>
 *
 * <pre>
 *   quarkus-exdb2=${REGISTRY}/quarkus-exdb2:1.6.1-2606301200
 *   guacamole=${REGISTRY}/guacamole:2.0.0-2606010000
 * </pre>
 *
 * <p>The file is read on every {@link #resolve} call (which happens only at Pod creation,
 * not on a hot path), so updating the mounted ConfigMap takes effect on the next session
 * launch without restarting the controller. Any missing file, unreadable file, or missing
 * key falls back to the plugin's compiled-in default, so the resolver is safe even when no
 * ConfigMap is mounted.</p>
 */
public final class ToolImageResolver {

    private static final Logger LOG = Logger.getLogger(ToolImageResolver.class.getName());

    private final Path overridesFile;
    private final String registry;

    /**
     * @param overridesFilePath path to the overrides properties file (a mounted
     *                          ConfigMap), or null/blank to disable overrides entirely
     * @param registry          container registry host (e.g. {@code registry.example:5000}) substituted
     *                          for the {@code ${REGISTRY}} placeholder in a plugin's default image, so the
     *                          registry address is deployment config (k8spups.registry / K8SPUPS_REGISTRY)
     *                          and never hardcoded in a plugin class. Blank leaves the placeholder as-is.
     */
    public ToolImageResolver(String overridesFilePath, String registry) {
        this.overridesFile = (overridesFilePath == null || overridesFilePath.isBlank())
                ? null : Path.of(overridesFilePath);
        this.registry = registry == null ? "" : registry.trim();
    }

    /**
     * Resolve the image for a plugin: the override from the mounted file if present and
     * non-blank, otherwise the plugin's compiled-in default. In both cases the
     * {@code ${REGISTRY}} placeholder is replaced with the configured registry host.
     */
    public String resolve(ToolPlugin plugin) {
        String override = lookup(plugin.name());
        String image = (override != null && !override.isBlank())
                ? override.trim() : plugin.containerImage();
        return (image == null || registry.isBlank()) ? image : image.replace("${REGISTRY}", registry);
    }

    /** Read one tool's override from the file, or null if absent/unreadable. */
    private String lookup(String toolName) {
        if (overridesFile == null || !Files.isReadable(overridesFile)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(overridesFile)) {
            Properties props = new Properties();
            props.load(in);
            return props.getProperty(toolName);
        } catch (Exception e) {
            // Never let a bad overrides file break Pod creation; fall back to the default.
            LOG.log(Level.WARNING, "Could not read tool image overrides from " + overridesFile, e);
            return null;
        }
    }
}
