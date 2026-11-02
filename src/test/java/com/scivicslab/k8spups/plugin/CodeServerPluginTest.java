package com.scivicslab.k8spups.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;

class CodeServerPluginTest {

    @Test
    void theEditorIsOfferedUnderItsOwnNameNextToCodeRaptor() {
        var names = ServiceLoader.load(ToolPlugin.class).stream().map(p -> p.get().name()).toList();
        assertTrue(names.contains("code-server"), names.toString());
        assertTrue(names.contains("code-raptor"), "other deployments keep code-raptor: " + names);
    }

    @Test
    void theEditorOpensTheStorageOfTheUserAsTheNonRootUser() {
        var p = new CodeServerPlugin();
        assertEquals("Code Editor", p.displayName());
        assertEquals("/home/devteam/works", p.userDataMountPath());
        assertEquals(1000L, p.runAsUser());
        assertTrue(p.runAsNonRoot());
        assertEquals("/healthz", p.readinessProbePath());
        // the editor writes under HOME/.config and HOME/.local, which the user of the Pod may not own
        assertEquals(java.util.List.of("/home/devteam/.config", "/home/devteam/.local"), p.writablePaths());
        assertTrue(p.containerImage().startsWith("${REGISTRY}/code-server:"), p.containerImage());
    }

    @Test
    void theCardHasAPlaceInTheLaunchGrid() {
        assertTrue(com.scivicslab.k8spups.resource.ToolCardOrder.rank("Code Editor") < com.scivicslab.k8spups.resource.ToolCardOrder.OTHER);
    }
}
