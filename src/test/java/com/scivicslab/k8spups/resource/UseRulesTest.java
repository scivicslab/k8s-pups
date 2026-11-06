package com.scivicslab.k8spups.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** The traffic in these tests is what an untouched page of each tool sent, measured on a007 (2026-11-06). */
class UseRulesTest {

    @Test
    void whatJupyterPollsByItselfIsNotUse() {
        for (String tool : new String[] {"jupyter-lab", "jupyter-ai"}) {
            for (String p : new String[] {"api/terminals", "api/kernels", "api/sessions", "api/contents", "api/contents/work/data",
                    "lab/api/workspaces", "api/kernelspecs", "api/me"}) {
                assertFalse(UseRules.httpIsUse(tool, "GET", p, "cors"), tool + " GET " + p);
            }
            assertFalse(UseRules.httpIsUse(tool, "PUT", "lab/api/workspaces/default", "cors"));
            // what the user does: saving a notebook, starting a kernel, opening the page
            assertTrue(UseRules.httpIsUse(tool, "PUT", "api/contents/analysis.ipynb", "cors"));
            assertTrue(UseRules.httpIsUse(tool, "POST", "api/sessions", "cors"));
            assertTrue(UseRules.httpIsUse(tool, "GET", "lab", "navigate"));
        }
    }

    @Test
    void galaxyAndTheAiAgentPollTheirState() {
        assertFalse(UseRules.httpIsUse("galaxy", "GET", "history/current_history_json", "cors"));
        assertTrue(UseRules.httpIsUse("galaxy", "POST", "api/tools/fetch", "cors"));
        assertTrue(UseRules.httpIsUse("galaxy", "POST", "api/chat", "cors"));
        assertFalse(UseRules.httpIsUse("chat-ui-with-audit-trail-local-llm", "GET", "api/actors", "cors"));
        assertTrue(UseRules.httpIsUse("chat-ui-with-audit-trail-local-llm", "POST", "api/chat", "cors"));
    }

    @Test
    void aToolWithoutARuleCountsEverything() {
        assertTrue(UseRules.httpIsUse("file-browser", "GET", "api/resources/", "cors"));
        assertTrue(UseRules.httpIsUse(null, "GET", "api/kernels", "cors"));
        assertTrue(UseRules.webSocketIsUse("terminal", "0x", null));
        assertTrue(UseRules.webSocketIsUse(null, "anything", null));
    }

    @Test
    void theRemoteDesktopsProtocolMessagesAreNotUseButKeysAndTheMouseAre() {
        for (String own : new String[] {"3.nop;", "4.sync,11.18816529009;", "3.ack,1.1,2.OK,1.0;", "0.,4.ping,13.1793924279382;"}) {
            assertFalse(UseRules.webSocketIsUse("guacamole", own, null), own);
        }
        assertTrue(UseRules.webSocketIsUse("guacamole", "5.mouse,3.512,3.384,1.1;", null));
        assertTrue(UseRules.webSocketIsUse("guacamole", "3.key,5.65307,1.1;", null));
        assertTrue(UseRules.webSocketIsUse("fedora-desktop", "9.clipboard,1.0,10.text/plain;", null));
        assertEquals("sync", UseRules.guacamoleOpcode("4.sync,11.18816529009;"));
        assertEquals("", UseRules.guacamoleOpcode("0.,4.ping,13.1793924279382;"));
    }

    private static byte[] vscode(int type, String body) {
        byte[] b = body.getBytes(StandardCharsets.ISO_8859_1);
        byte[] m = new byte[13 + b.length];
        m[0] = (byte) type;
        System.arraycopy(b, 0, m, 13, b.length);
        return m;
    }

    @Test
    void theCodeEditorsKeepAliveAndTelemetryPingAreNotUse() {
        assertFalse(UseRules.webSocketIsUse("code-server", null, vscode(9, "")));                    // keep-alive
        assertFalse(UseRules.webSocketIsUse("code-server", null, vscode(3, "")));                    // ack
        assertFalse(UseRules.webSocketIsUse("code-server", null, vscode(1, "\u0000\u0000\u0000d\u0000\u0000\u0000\u0009telemetry\u0000\u0004ping\u0000")));
        assertTrue(UseRules.webSocketIsUse("code-server", null,
            vscode(1, "\u0000\u0000\u0000Y$executeContributedCommand\u0000\u0000\u0000$[\"continue.rejectVerticalDiffBlock\"]")));
        assertTrue(UseRules.webSocketIsUse("code-server", null, vscode(1, "x".repeat(40))));      // typing in the terminal
    }
}
