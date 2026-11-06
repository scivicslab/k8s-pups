package com.scivicslab.k8spups.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The mounts a user can choose in the launch form, and the remembered choices of a tool checked against them.
 *
 * A choice is a value of the form field {@code extraType_<n>}: an allocated storage type ({@code posix}) or
 * {@code group:<name>} for a group's storage, {@code dataset:<name>} for a dataset read as files, {@code s3:<name>} for a
 * dataset read from the S3 server, {@code dataset-files} for the files granted one by one. The user chooses what to
 * attach, not where: each choice has one place, {@link #pathOf}, under the directory the tool shows, so that its file
 * view opens it; a dataset of the S3 server is not mounted, the tool gets credentials for it. A dataset is attached only
 * when chosen, like a group's storage. The remembered choices of a tool are stored as
 * {@code {"primary": <type>, "mounts": [{"value": <choice>}]}} (a {@code path} stored by an earlier version is ignored);
 * a remembered choice that is not among the current choices (storage taken away, membership or grant ended) is left out
 * and counted.
 */
public final class MountChoices {

    public static final String GROUP = "group:";
    public static final String DATASET = "dataset:";
    public static final String S3 = "s3:";
    public static final String GRANTED_FILES = "dataset-files";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One choice of the launch form; {@code path} is where it is mounted, or for {@code s3} where it is read. */
    public record Choice(String value, String label, String path, boolean s3) {
        public Choice(String value, String label, String path) {
            this(value, label, path, false);
        }
    }

    /** A dataset the user may read: its name and whether it is read from the S3 server. */
    public record Dataset(String name, boolean s3) {}

    /** The remembered choices of one tool, restricted to the current choices; {@code dropped} = choices left out. */
    public record Remembered(String primary, List<Map<String, String>> mounts, int dropped) {}

    private MountChoices() {}

    /**
     * @param storageNames type to display name of the user's allocated storage
     * @param groups       group name to mode ("rw" or "ro")
     */
    public static List<Choice> of(Map<String, String> storageNames, Map<String, String> groups) {
        List<Choice> out = new ArrayList<>();
        storageNames.forEach((type, name) -> out.add(new Choice(type, name, pathOf(type, null))));
        groups.forEach((g, mode) -> out.add(new Choice(GROUP + g,
            "Group storage " + g + ("ro".equals(mode) ? " (read-only)" : ""), pathOf(GROUP + g, null))));
        return out;
    }

    /**
     * As above, with the datasets the user may read and, when there are any, the files granted one by one.
     *
     * @param s3Bucket the bucket of the S3 server, shown as where a dataset of it is read
     */
    public static List<Choice> of(Map<String, String> storageNames, Map<String, String> groups, List<Dataset> datasets,
                                  boolean grantedFiles, String s3Bucket) {
        List<Choice> out = new ArrayList<>(of(storageNames, groups));
        for (Dataset d : datasets) {
            if (d.s3()) {
                out.add(new Choice(S3 + d.name(), "Dataset " + d.name() + " (S3, read-only)", s3Bucket + "/" + d.name() + "/", true));
            } else {
                out.add(new Choice(DATASET + d.name(), "Dataset " + d.name() + " (read-only)", pathOf(DATASET + d.name(), null)));
            }
        }
        if (grantedFiles) {
            out.add(new Choice(GRANTED_FILES, "Files granted to you (read-only)", pathOf(GRANTED_FILES, null)));
        }
        return out;
    }

    /**
     * Where a choice is mounted: {@code /groups/<name>} for a group's storage, {@code /storage/<type>} for the user's
     * own storage of another type, under {@code under} (the directory the tool shows, without a trailing slash) when the
     * tool has one. Each choice has its own place, so two choices never land on one path.
     */
    public static String pathOf(String value, String under) {
        String base = under == null ? "" : under.replaceAll("/+$", "");
        if (value.startsWith(GROUP)) return base + "/groups/" + value.substring(GROUP.length());
        // the places DatasetMountSpecs mounts them at
        if (value.startsWith(DATASET)) return base + "/datasets/" + value.substring(DATASET.length());
        if (value.equals(GRANTED_FILES)) return base + "/datasets/granted-files";
        return base + "/storage/" + value;
    }

    /**
     * The allocated storage types whose PVC exists. Only these can be mounted: a Pod that names a missing claim waits
     * for it for ever. {@code pvcStates} maps a type to its PVC state, a map with "exists" = "true" or "false".
     */
    public static List<String> withExistingPvc(List<String> allocatedTypes, Map<String, ?> pvcStates) {
        return allocatedTypes.stream()
            .filter(t -> pvcStates.get(t) instanceof Map<?, ?> m && "true".equals(String.valueOf(m.get("exists"))))
            .toList();
    }

    /** The chosen values of the launch form, blanks left out and each once: a choice mounted twice would collide. */
    public static List<String> distinctValues(String... values) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String v : values) if (v != null && !v.isBlank()) out.add(v.trim());
        return List.copyOf(out);
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
                String v = m.path("value").asText("");
                if (values.contains(v)) mounts.add(Map.of("value", v));
                else dropped++;
            }
        } catch (Exception e) {
            dropped++;
        }
        return new Remembered(primary, mounts, dropped);
    }
}
