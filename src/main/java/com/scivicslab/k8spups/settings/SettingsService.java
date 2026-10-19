package com.scivicslab.k8spups.settings;

import com.scivicslab.k8spups.k8s.K8sApiClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads and saves the shared settings (ConfigMap {@code k8s-pups-settings} in the controller namespace). A plain
 * POJO; the controller runs one instance as the actor {@code settings-manager}, so saves happen one at a time and
 * a session that starts reads either the old or the new settings, never a mix.
 */
public class SettingsService {

    /** Name the controller registers this service under in the actor system. */
    public static final String ACTOR_NAME = "settings-manager";

    static final String CONFIG_MAP = "k8s-pups-settings";
    static final String KEY_URL = "gpu-broker-url";
    static final String KEY_MODEL = "default-model";

    private static final Logger LOG = Logger.getLogger(SettingsService.class.getName());

    private final K8sApiClient k8sClient;
    private final String namespace;
    private final String defaultBrokerUrl;
    // HTTP/1.1 only: h2c upgrade requests break some Python servers
    private final HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * @param defaultBrokerUrl base URL of the GPU broker shared service of this deployment (from the shared
     *                         services configuration); offered on the Settings page when nothing is saved
     */
    public SettingsService(K8sApiClient k8sClient, String namespace, String defaultBrokerUrl) {
        this.k8sClient = k8sClient;
        this.namespace = namespace;
        this.defaultBrokerUrl = defaultBrokerUrl == null ? "" : defaultBrokerUrl;
    }

    /** The GPU broker URL the deployment itself is configured with ("" when it has no GPU broker shared service). */
    public String defaultBrokerUrl() {
        return defaultBrokerUrl;
    }

    /**
     * The models the GPU broker at {@code brokerUrl} offers ({@code GET <url>/v1/models}); empty when the URL is
     * empty or the broker does not answer, so the page falls back to a text field.
     */
    public List<String> listModels(String brokerUrl) {
        String base = SystemSettings.normalizeUrl(brokerUrl);
        if (base.isEmpty() || !SystemSettings.isValidUrl(base)) return List.of();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/models"))
                .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? ModelList.parse(response.body()) : List.of();
        } catch (Exception e) {
            LOG.warning("Model list not read from " + base + ": " + e.getMessage());
            return List.of();
        }
    }

    public SystemSettings load() {
        Map<String, String> data = k8sClient.readConfigMapData(namespace, CONFIG_MAP);
        return new SystemSettings(data.getOrDefault(KEY_URL, ""), data.getOrDefault(KEY_MODEL, ""));
    }

    public void save(SystemSettings settings) {
        k8sClient.replaceConfigMapData(namespace, CONFIG_MAP,
            Map.of(KEY_URL, settings.gpuBrokerUrl(), KEY_MODEL, settings.defaultModel()));
    }
}
