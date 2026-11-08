package com.scivicslab.k8spups.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;

class TerminalPluginTest {

    @Test
    void theTerminalIsOffered() {
        var names = ServiceLoader.load(ToolPlugin.class).stream().map(p -> p.get().name()).toList();
        assertTrue(names.contains("terminal"), names.toString());
    }

    @Test
    void theShellRunsAsTheNonRootUserOnTheStorageOfTheUser() {
        var p = new TerminalPlugin();
        assertEquals("Terminal", p.displayName());
        assertEquals("/home/pod-user/works", p.userDataMountPath());
        assertEquals("/home/pod-user/works", p.extraMountsUnder());
        assertEquals(1000L, p.runAsUser());
        assertTrue(p.runAsNonRoot());
        assertTrue(p.containerImage().startsWith("${REGISTRY}/terminal:"), p.containerImage());
    }

}
