package com.scivicslab.k8spups.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A service the whole cluster shares, as opposed to a {@link ToolPlugin} that runs one Pod per user
 * session: one Deployment somewhere in the cluster (the GPU broker, for example) that every user's
 * tools depend on. The dashboard shows it as a card with its state and an Open button for
 * everyone, and Launch / Stop (scale to 1 / 0) for administrators only. It is never a session:
 * nothing here is created per user, and the per-session Stop cannot reach it.
 *
 * @param name        identifier, also the path segment under {@code /service/}
 * @param displayName card title
 * @param description card text
 * @param namespace   namespace of the Deployment and Service
 * @param deployment  Deployment name (scaled between 0 and 1)
 * @param service     Service name the dashboard proxies to
 * @param port        Service port
 */
public record SharedService(String name, String displayName, String description,
                            String namespace, String deployment, String service, int port) {

    /** {@code http://<service>.<namespace>.svc:<port>} — the in-cluster address the proxy forwards to. */
    public String clusterUrl() {
        return "http://" + service + "." + namespace + ".svc:" + port;
    }

    /**
     * Read one shared service from configuration keys {@code k8spups.shared-service.<name>.<key>}.
     * {@code lookup} resolves a full key to its value. Missing namespace, deployment, service or port
     * make the service unusable, so an empty Optional is returned for those.
     */
    public static Optional<SharedService> fromConfig(String name, Function<String, Optional<String>> lookup) {
        String prefix = "k8spups.shared-service." + name + ".";
        Optional<String> namespace = lookup.apply(prefix + "namespace");
        Optional<String> deployment = lookup.apply(prefix + "deployment");
        Optional<String> service = lookup.apply(prefix + "service");
        Optional<String> port = lookup.apply(prefix + "port");
        if (namespace.isEmpty() || deployment.isEmpty() || service.isEmpty() || port.isEmpty()) {
            return Optional.empty();
        }
        int portNumber;
        try {
            portNumber = Integer.parseInt(port.get().trim());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.of(new SharedService(name,
                lookup.apply(prefix + "display-name").orElse(name),
                lookup.apply(prefix + "description").orElse(""),
                namespace.get().trim(), deployment.get().trim(), service.get().trim(), portNumber));
    }

    /** The services named in a comma-separated list, in that order; unknown or incomplete names are skipped. */
    public static List<SharedService> allFromConfig(String names, Function<String, Optional<String>> lookup) {
        List<SharedService> out = new ArrayList<>();
        if (names == null) {
            return out;
        }
        for (String raw : names.split(",")) {
            String name = raw.trim();
            if (!name.isEmpty()) {
                fromConfig(name, lookup).ifPresent(out::add);
            }
        }
        return out;
    }
}
