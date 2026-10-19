package com.scivicslab.k8spups.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Reads the model ids out of the answer of an OpenAI-compatible {@code GET /v1/models}. Pure logic. */
public final class ModelList {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelList() {}

    /** The model ids in alphabetical order; empty when {@code json} is not a models answer. */
    public static List<String> parse(String json) {
        TreeSet<String> ids = new TreeSet<>();
        try {
            JsonNode data = MAPPER.readTree(json).path("data");
            if (data.isArray()) {
                for (JsonNode m : data) {
                    String id = m.path("id").asText("");
                    if (SystemSettings.isValidModel(id) && !id.isEmpty()) ids.add(id);
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        return new ArrayList<>(ids);
    }
}
