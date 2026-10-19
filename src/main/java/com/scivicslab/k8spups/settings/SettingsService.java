package com.scivicslab.k8spups.settings;

import com.scivicslab.k8spups.k8s.K8sApiClient;

import java.util.Map;

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

    private final K8sApiClient k8sClient;
    private final String namespace;

    public SettingsService(K8sApiClient k8sClient, String namespace) {
        this.k8sClient = k8sClient;
        this.namespace = namespace;
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
