package com.scivicslab.k8spups.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * projects.sh of the Documents image decides which folders of the storage the portal serves. It runs here on a folder
 * tree of the test, with no container: it touches nothing but that tree.
 */
class HtmlSaurusProjectsScriptTest {

    private static final Path SCRIPT = Path.of("docker/html-saurus/projects.sh").toAbsolutePath();

    @TempDir
    Path storage;

    record Result(List<String> out, String err) {}

    private void project(String rel) throws IOException {
        Path p = storage.resolve(rel);
        Files.createDirectories(p.resolve("docs"));
        Files.writeString(p.resolve("docusaurus.config.js"), "module.exports = {};");
    }

    @BeforeEach
    void tree() throws IOException {
        project("doc_A");
        project("doc_B");
        project("notes");
        Files.createDirectories(storage.resolve("doc_plain/docs"));      // matches doc_* but has no config
        project("groups/lab/doc_G");
        project("deep/er/est/p");
        project(".hidden/doc_H");
    }

    private Result list(String patterns) throws Exception {
        var pb = new ProcessBuilder("sh", "-c", ". \"$1\"; list_projects \"$2\" \"$3\"", "sh", SCRIPT.toString(), storage.toString(), patterns);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), err);
        List<String> lines = out.isBlank() ? List.of() : out.strip().lines().map(l -> storage.relativize(Path.of(l)).toString()).sorted().toList();
        return new Result(lines, err);
    }

    @Test
    void thePatternSelectsOnlyTheMatchingProjects() throws Exception {
        var r = list("~/works/doc_*");
        assertEquals(List.of("doc_A", "doc_B"), r.out());
        assertTrue(r.err().contains("doc_plain") && r.err().contains("not a Docusaurus project"), r.err());
    }

    @Test
    void severalPatternsAndTheGroupStorage() throws Exception {
        assertEquals(List.of("doc_A", "groups/lab/doc_G", "notes"), list("~/works/doc_A, ~/works/groups/*/doc_*,notes").out());
    }

    @Test
    void noMatchIsSaidAndNothingIsServed() throws Exception {
        var r = list("~/works/nothing_*");
        assertEquals(List.of(), r.out());
        assertTrue(r.err().contains("no Docusaurus project matches nothing_*"), r.err());
    }

    @Test
    void blankLooksNearTheTopOfTheStorage() throws Exception {
        // the configuration file may be up to three levels down, so a project is at most two folders below the storage
        assertEquals(List.of(".hidden/doc_H", "doc_A", "doc_B", "notes"), list("").out());
    }

    @Test
    void hiddenFoldersAreNeverMatched() throws Exception {
        assertEquals(List.of(), list("*/doc_H").out());
        assertEquals(List.of(), list(".hidden/doc_H").out());
    }

    @Test
    void whatLeavesTheStorageMatchesNothing() throws Exception {
        Path outside = Files.createTempDirectory("outside");
        try {
            Files.createDirectories(outside.resolve("docs"));
            Files.writeString(outside.resolve("docusaurus.config.js"), "x");
            Files.createSymbolicLink(storage.resolve("doc_link"), outside);
            for (String bad : new String[] {"../" + outside.getFileName(), "~/works/../x", "/etc", "doc_$(touch " + outside + "/pwned)", "doc_;touch"}) {
                var r = list(bad);
                assertEquals(List.of(), r.out(), bad);
            }
            assertEquals(List.of("doc_A", "doc_B"), list("doc_*").out(), "a link out of the storage is not followed");
            assertFalse(Files.exists(outside.resolve("pwned")));
        } finally {
            Files.deleteIfExists(storage.resolve("doc_link"));
        }
    }
}
