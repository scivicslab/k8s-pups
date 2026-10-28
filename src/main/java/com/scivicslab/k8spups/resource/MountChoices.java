package com.scivicslab.k8spups.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The mounts a user can choose in the launch form, and the remembered choices of a tool checked against them.
 *
 * A choice is a value of the form field {@code extraType_<n>}: an allocated storage type ({@code lustre-csi}),
 * {@code group:<name>} for a group's storage, {@code dataset:<name>} for a dataset, or {@code dataset-files} for the
 * files granted one by one. Each has a default mount path. The remembered choices of a tool are stored as
 * {@code {"primary": <type>, "mounts": [{"value": <choice>, "path": <mount path>}]}}; a remembered choice that is
 * not among the current choices (storage taken away, membership or grant ended) is left out and counted.
 */
public final class MountChoices {

    public static final String DATASET = "dataset:";
    public static final String DATASET_FILES = "dataset-files";
    public static final String GROUP = "group:";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One choice of the launch form. */
    public record Choice(String value, String label, String path) {}

    /** The remembered choices of one tool, restricted to the current choices; {@code dropped} = choices left out. */
    public record Remembered(String primary, List<Map<String, String>> mounts, int dropped) {}

    private MountChoices() {}

    /**
     * @param storageNames type to display name of the user's allocated storage
     * @param groups       group name to mode ("rw" or "ro")
     * @param datasets     the datasets the user may read now
     * @param fileView     whether files are granted one by one
     */
    public static List<Choice> of(Map<String, String> storageNames, Map<String, String> groups,
                                  List<String> datasets, boolean fileView) {
        List<Choice> out = new ArrayList<>();
        storageNames.forEach((type, name) -> out.add(new Choice(type, name, "/data")));
        groups.forEach((g, mode) -> out.add(new Choice(GROUP + g,
            "Group storage " + g + ("ro".equals(mode) ? " (read-only)" : ""), "/groups/" + g)));
        for (String d : datasets) out.add(new Choice(DATASET + d, "Dataset " + d + " (read-only)", "/datasets/" + d));
        if (fileView) out.add(new Choice(DATASET_FILES, "Granted dataset files (read-only)", "/dataset-files"));
        return out;
    }

    /** The stored JSON of a tool's choices, as {@code {"primary": ..., "mounts": [...]}}. */
    public static String toJson(String primary, List<Map<String, String>> mounts) {
        try {
            return JSON.writeValueAsString(Map.of("primary", primary == null ? "" : primary, "mounts", mounts));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The stored choices of a tool, keeping only the values among {@code current}. */
    public static Remembered remembered(String json, List<Choice> current, Iterable<String> primaryTypes) {
        List<String> values = current.stream().map(Choice::value).toList();
        List<String> primaries = new ArrayList<>();
        primaryTypes.forEach(primaries::add);
        String primary = "";
        List<Map<String, String>> mounts = new ArrayList<>();
        int dropped = 0;
        try {
            JsonNode root = JSON.readTree(json == null || json.isBlank() ? "{}" : json);
            String p = root.path("primary").asText("");
            if (!p.isEmpty()) {
                if (primaries.contains(p)) primary = p; else dropped++;
            }
            for (JsonNode m : root.path("mounts")) {
                String v = m.path("value").asText(""), path = m.path("path").asText("");
                if (values.contains(v) && path.startsWith("/")) mounts.add(Map.of("value", v, "path", path));
                else dropped++;
            }
        } catch (Exception e) {
            dropped++;
        }
        return new Remembered(primary, mounts, dropped);
    }
}
