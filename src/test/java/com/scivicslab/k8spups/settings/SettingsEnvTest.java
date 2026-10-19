package com.scivicslab.k8spups.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsEnvTest {

    private static final SystemSettings S = new SystemSettings("http://broker:28005/v1/", " m/x ");

    @Test
    void normalizesTheBrokerUrlAndTheModel() {
        assertEquals("http://broker:28005", S.gpuBrokerUrl());
        assertEquals("m/x", S.defaultModel());
    }

    @Test
    void openAiStyleToolsGetTheV1Url() {
        assertEquals("http://broker:28005/v1", SettingsEnv.forTool("ai-chat", S).get("AI_CHAT_BASE_URL"));
        assertEquals("m/x", SettingsEnv.forTool("ai-chat", S).get("AI_CHAT_DEFAULT_MODEL"));
        assertEquals("http://broker:28005/v1", SettingsEnv.forTool("jupyter-ai", S).get("JUPYTER_AI_BASE_URL"));
        assertEquals("m/x", SettingsEnv.forTool("jupyter-ai", S).get("JUPYTER_AI_MODEL"));
    }

    @Test
    void brokerStyleToolsGetTheBareUrl() {
        var chat = SettingsEnv.forTool("chat-ui-with-audit-trail-local-llm", S);
        assertEquals("http://broker:28005", chat.get("CHAT_UI_SERVERS"));
        assertEquals("m/x", chat.get("CHAT_UI_DEFAULT_MODEL"));
        var raptor = SettingsEnv.forTool("code-raptor", S);
        assertEquals("http://broker:28005", raptor.get("CODE_RAPTOR_BROKER_URL"));
        assertEquals("m/x", raptor.get("CODE_RAPTOR_MODEL"));
    }

    @Test
    void emptySettingsOverrideNothing() {
        for (String tool : new String[] {"ai-chat", "jupyter-ai", "chat-ui-with-audit-trail-local-llm", "code-raptor"})
            assertTrue(SettingsEnv.forTool(tool, SystemSettings.EMPTY).isEmpty(), tool);
    }

    @Test
    void otherToolsAreUntouched() {
        assertTrue(SettingsEnv.forTool("file-browser", S).isEmpty());
    }

    @Test
    void validation() {
        assertTrue(SystemSettings.isValidUrl(""));
        assertTrue(SystemSettings.isValidUrl("http://gpu-broker.gpu-broker.svc:28005"));
        assertFalse(SystemSettings.isValidUrl("javascript:alert(1)"));
        assertFalse(SystemSettings.isValidUrl("http://a b"));
        assertFalse(SystemSettings.isValidUrl("http://x/$(id)"));
        assertTrue(SystemSettings.isValidModel("google/gemma-4-26B-A4B-it"));
        assertFalse(SystemSettings.isValidModel("a b"));
        assertFalse(SystemSettings.isValidModel("a;b"));
    }
}
