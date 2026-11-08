package com.scivicslab.k8spups.resource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.k8spups.plugin.UserParameter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The values a user typed into the fields of a tool's launch form ({@code ToolPlugin.userParameters()}), as the dashboard
 * remembers them for the next launch. Pure logic: what is stored, and what the form shows again.
 *
 * <p>A field the tool declares secret is never stored. A blank field is stored as blank, so that a launch with an empty
 * field replaces the earlier value instead of leaving it to come back.
 */
public final class FormValues {

    private FormValues() {}

    /** A value longer than this is not remembered: the form fields are for short values such as folder patterns. */
    static final int MAX_LENGTH = 1000;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What to store after a successful launch: env var name to value, for the non-secret fields only; blank as "". */
    public static Map<String, String> toStore(List<UserParameter> params, Map<String, String> entered) {
        Map<String, String> out = new LinkedHashMap<>();
        for (UserParameter p : params) {
            if (p.secret()) continue;
            String v = entered == null ? null : entered.get(p.envVarName());
            if (v == null || v.isBlank()) out.put(p.envVarName(), "");
            else if (v.length() <= MAX_LENGTH) out.put(p.envVarName(), v);
        }
        return out;
    }

    /** The initial value of each field of the form, in the order of {@code params}; "" where nothing is remembered. */
    public static List<String> initial(List<UserParameter> params, Map<String, String> stored) {
        List<String> out = new ArrayList<>();
        for (UserParameter p : params) {
            String v = p.secret() || stored == null ? null : stored.get(p.envVarName());
            out.add(v == null ? "" : v);
        }
        return out;
    }

    public static String toJson(Map<String, String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The stored map; empty when there is none or it cannot be read. */
    public static Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
