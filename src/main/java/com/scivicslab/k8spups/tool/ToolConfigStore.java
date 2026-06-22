package com.scivicslab.k8spups.tool;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Key naming logic for tool-config ConfigMap and Secret.
 *
 * Keys are stored as "toolName.PARAM_NAME" to allow multiple tools
 * to coexist in a single ConfigMap/Secret.
 * This class handles prefix addition (on save) and removal (on get).
 */
public class ToolConfigStore {

    private ToolConfigStore() {}

    /** Build the ConfigMap key for a tool parameter: "toolName.paramName". */
    public static String configMapKey(String toolName, String paramName) {
        return toolName + "." + paramName;
    }

    /** Build the enable flag key for a tool: "toolName.enable". */
    public static String enableKey(String toolName) {
        return toolName + ".enable";
    }

    /**
     * Partition parameters into ConfigMap data and Secret data (Base64-encoded),
     * adding the tool-name prefix to each key.
     *
     * @param toolName    the tool name used as key prefix
     * @param params      raw parameter map (paramName → value)
     * @param descriptor  tool descriptor used to determine which params are secret
     *                    (may be null, in which case all params go to ConfigMap)
     * @param cmOut       output map for ConfigMap entries
     * @param secretOut   output map for Secret entries (Base64-encoded values)
     */
    public static void partition(String toolName, Map<String, String> params,
                                 ToolDescriptor descriptor,
                                 Map<String, String> cmOut,
                                 Map<String, String> secretOut) {
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String prefixedKey = configMapKey(toolName, entry.getKey());
            boolean isSecret = descriptor != null
                && descriptor.getParameter(entry.getKey()) != null
                && descriptor.getParameter(entry.getKey()).secret();

            if (isSecret) {
                secretOut.put(prefixedKey,
                    Base64.getEncoder().encodeToString(entry.getValue().getBytes()));
            } else {
                cmOut.put(prefixedKey, entry.getValue());
            }
        }
    }

    /**
     * Extract and strip prefix from ConfigMap entries belonging to toolName.
     *
     * @param toolName tool name whose entries to extract
     * @param cmData   full ConfigMap data map
     * @return map with prefix stripped (e.g. "ocr-file-manager.OCR_SERVER_URL" → "OCR_SERVER_URL")
     */
    public static Map<String, String> extractFromConfigMap(String toolName, Map<String, String> cmData) {
        Map<String, String> result = new HashMap<>();
        String prefix = toolName + ".";
        for (Map.Entry<String, String> entry : cmData.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return result;
    }

    /**
     * Extract and strip prefix from Secret entries belonging to toolName.
     * Values are Base64-decoded.
     *
     * @param toolName   tool name whose entries to extract
     * @param secretData full Secret data map (Base64-encoded values)
     * @return map with prefix stripped and values decoded
     */
    public static Map<String, String> extractFromSecret(String toolName, Map<String, String> secretData) {
        Map<String, String> result = new HashMap<>();
        String prefix = toolName + ".";
        for (Map.Entry<String, String> entry : secretData.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                String decoded = new String(Base64.getDecoder().decode(entry.getValue()));
                result.put(entry.getKey().substring(prefix.length()), decoded);
            }
        }
        return result;
    }
}
