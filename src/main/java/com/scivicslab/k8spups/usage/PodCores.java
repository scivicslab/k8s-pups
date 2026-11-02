package com.scivicslab.k8spups.usage;

import com.scivicslab.k8spups.plugin.ResourceProfile;

import java.util.List;
import java.util.Map;

/**
 * The CPU of a Pod as it is recorded in {@code pod_usage}: the name of the resource profile and the CPU request and
 * the CPU limit of that profile, in millicores. The CPU limit is the number of cores that is billed.
 *
 * @param profile        name of the resource profile; null when the tool has none
 * @param requestsMillis CPU request in millicores; null when the profile sets none
 * @param limitsMillis   CPU limit in millicores; null when the profile sets none
 */
public record PodCores(String profile, Integer requestsMillis, Integer limitsMillis) {

    /** Nothing is known about the CPU of the Pod (the tool has no resource profile). */
    public static final PodCores UNKNOWN = new PodCores(null, null, null);

    /**
     * The profile a session runs with: the one named {@code chosen}; when {@code chosen} is null or names no profile of
     * the tool, the first profile of the tool, which is what the Pod is built from in that case.
     *
     * @param profiles the resource profiles of the tool
     * @param chosen   the profile name the user chose; may be null
     */
    public static PodCores of(List<ResourceProfile> profiles, String chosen) {
        if (profiles == null || profiles.isEmpty()) return UNKNOWN;
        ResourceProfile profile = profiles.get(0);
        if (chosen != null) {
            for (ResourceProfile p : profiles) {
                if (p.name().equals(chosen)) { profile = p; break; }
            }
        }
        return new PodCores(profile.name(), cpu(profile.requests()), cpu(profile.limits()));
    }

    private static Integer cpu(Map<String, String> resources) {
        return resources == null ? null : CpuQuantity.toMillicores(resources.get("cpu"));
    }
}
