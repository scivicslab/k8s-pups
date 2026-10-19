package com.scivicslab.k8spups.settings;

/**
 * Settings shared by the tools, edited by an administrator on the Settings page.
 *
 * @param gpuBrokerUrl base URL of the GPU broker, without a trailing "/v1" (e.g. {@code http://gpu-broker:28005});
 *                     empty = the deployment's own value is used
 * @param defaultModel model name the tools select by default; empty = the deployment's own value is used
 */
public record SystemSettings(String gpuBrokerUrl, String defaultModel) {

    public static final SystemSettings EMPTY = new SystemSettings("", "");

    public SystemSettings {
        gpuBrokerUrl = normalizeUrl(gpuBrokerUrl);
        defaultModel = defaultModel == null ? "" : defaultModel.trim();
    }

    /** Trims, and drops a trailing slash and a trailing "/v1" so that one value serves every tool. */
    static String normalizeUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/v1")) u = u.substring(0, u.length() - 3);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /** True when {@code url} is empty or an http(s) URL without whitespace or shell-significant characters. */
    public static boolean isValidUrl(String url) {
        String u = normalizeUrl(url);
        return u.isEmpty() || u.matches("https?://[A-Za-z0-9.\\-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/\\-]*)?");
    }

    /** True when {@code model} is empty or a model name such as {@code google/gemma-4-26B-A4B-it}. */
    public static boolean isValidModel(String model) {
        String m = model == null ? "" : model.trim();
        return m.isEmpty() || m.matches("[A-Za-z0-9][A-Za-z0-9._:/\\-]{0,199}");
    }
}
