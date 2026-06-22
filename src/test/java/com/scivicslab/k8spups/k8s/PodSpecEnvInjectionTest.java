package com.scivicslab.k8spups.k8s;

import com.scivicslab.k8spups.plugin.ConnectionType;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import io.fabric8.kubernetes.api.model.EnvVar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TR.002.3 — Pod Spec への env 注入
 * SessionEnvBuilder.build() が toolConfigEnv を env に含め、
 * plugin env と競合したとき toolConfigEnv が優先されることを検証する。
 */
@Tag("TR.002.3")
@DisplayName("TR.002.3 — Pod Spec Env Injection")
class PodSpecEnvInjectionTest {

    private SessionEnvBuilder envBuilder;

    @BeforeEach
    void setup() {
        envBuilder = new SessionEnvBuilder("/base", "http://controller");
    }

    private SessionInfo buildSession(Map<String, String> pluginEnv,
                                     Map<String, String> toolConfigEnv) {
        ToolPlugin plugin = new ToolPlugin() {
            @Override public String name() { return "ocr-file-manager"; }
            @Override public String displayName() { return "OCR"; }
            @Override public String containerImage() { return "registry/ocr:latest"; }
            @Override public int containerPort() { return 8080; }
            @Override public ConnectionType connectionType() { return ConnectionType.HTTP; }
            @Override public Map<String, String> environmentVariables() { return pluginEnv; }
        };
        return new SessionInfo("sess-001", "user1", plugin,
            List.of(), null, "default",
            Collections.emptyMap(), null, null, null,
            Collections.emptyList(), toolConfigEnv);
    }

    @Test
    @DisplayName("toolConfigEnv の各エントリが env に含まれる")
    void build_toolConfigEnv_isIncluded() {
        Map<String, String> toolEnv = Map.of("OCR_SERVER_URL", "http://192.168.5.17:8013");
        SessionInfo info = buildSession(Collections.emptyMap(), toolEnv);
        List<EnvVar> envVars = envBuilder.build(info);
        assertTrue(envVars.stream().anyMatch(e ->
            e.getName().equals("OCR_SERVER_URL") &&
            e.getValue().equals("http://192.168.5.17:8013")));
    }

    @Test
    @DisplayName("toolConfigEnv が plugin env と同名キーで競合したとき toolConfigEnv が優先される")
    void build_toolConfigEnvOverridesPluginEnv() {
        Map<String, String> pluginEnv = Map.of("OCR_SERVER_URL", "http://old-server");
        Map<String, String> toolEnv = Map.of("OCR_SERVER_URL", "http://new-server");
        SessionInfo info = buildSession(pluginEnv, toolEnv);
        List<EnvVar> envVars = envBuilder.build(info);

        List<EnvVar> matches = envVars.stream()
            .filter(e -> e.getName().equals("OCR_SERVER_URL"))
            .toList();
        assertEquals(1, matches.size(), "OCR_SERVER_URL must appear exactly once");
        assertEquals("http://new-server", matches.get(0).getValue());
    }

    @Test
    @DisplayName("toolConfigEnv が空のとき plugin env のみが env に入る")
    void build_emptyToolConfigEnv_onlyPluginEnv() {
        Map<String, String> pluginEnv = Map.of("PLUGIN_KEY", "plugin-value");
        SessionInfo info = buildSession(pluginEnv, Collections.emptyMap());
        List<EnvVar> envVars = envBuilder.build(info);
        assertTrue(envVars.stream().anyMatch(e -> e.getName().equals("PLUGIN_KEY")));
    }

    @Test
    @DisplayName("PUPS_SESSION_PATH が常に含まれる")
    void build_pupsSessionPath_alwaysPresent() {
        SessionInfo info = buildSession(Collections.emptyMap(), Collections.emptyMap());
        List<EnvVar> envVars = envBuilder.build(info);
        assertTrue(envVars.stream().anyMatch(e -> e.getName().equals("PUPS_SESSION_PATH")));
    }
}
