package com.scivicslab.k8spups.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;

class HtmlSaurusPluginTest {

    @Test
    void thePortalIsOfferedAsDocuments() {
        var names = ServiceLoader.load(ToolPlugin.class).stream().map(p -> p.get().name()).toList();
        assertTrue(names.contains("html-saurus"), names.toString());
        assertEquals("Documents", new HtmlSaurusPlugin().displayName());
        assertFalse(new HtmlSaurusPlugin().description().isBlank());
    }

    @Test
    void thePortalScansTheStorageOfTheUserAndWritesOnlyItsOwnCopy() {
        var p = new HtmlSaurusPlugin();
        assertEquals("/home/devteam/works", p.userDataMountPath());
        assertEquals(p.userDataMountPath(), p.extraMountsUnder(), "the group storage and the datasets are scanned too");
        assertEquals(java.util.List.of("/home/devteam/portal"), p.writablePaths());
        assertTrue(p.readOnlyRootFilesystem());
        assertEquals(1000L, p.runAsUser());
        assertTrue(p.runAsNonRoot());
        assertFalse(p.passthroughPath(), "the gateway removes the session path; the portal is told the prefix instead");
        assertTrue(p.containerImage().startsWith("${REGISTRY}/html-saurus:"), p.containerImage());
    }
}
