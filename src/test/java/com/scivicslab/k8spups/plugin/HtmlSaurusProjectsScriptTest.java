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
        project("groups/lab2/doc_G2");
        project("groups/lab3/doc_G3");
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

    /** The notice the portal page shows for the patterns, as the image writes it: what list_projects collects, then write_notice. */
    private String notice(String patterns) throws Exception {
        Path lines = Files.createTempFile("notice-lines", ".txt");
        Path out = Files.createTempFile("notice", ".txt");
        try {
            var pb = new ProcessBuilder("sh", "-c",
                ". \"$1\"; NOTICE_LINES_FILE=\"$4\"; export NOTICE_LINES_FILE; n=$(list_projects \"$2\" \"$3\" 2>/dev/null | wc -l); write_notice \"$4\" \"$n\" \"$5\"",
                "sh", SCRIPT.toString(), storage.toString(), patterns, lines.toString(), out.toString());
            Process p = pb.start();
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, p.waitFor(), err);
            return Files.readString(out);
        } finally {
            Files.deleteIfExists(lines);
            Files.deleteIfExists(out);
        }
    }

    @Test
    void noPatternMatchesAnything_thePageSaysSoAndWhatToDo() throws Exception {
        String n = notice("~/works/nothing_*");
        assertEquals("No folder matches `~/works/nothing_*`.\nStart Documents again with another pattern.\n", n);
    }

    @Test
    void aMatchingFolderThatIsNotAProject_isSaidApart() throws Exception {
        Files.createDirectories(storage.resolve("only_plain/docs"));
        String n = notice("only_plain");
        assertTrue(n.contains("`only_plain` matches folders, but none of them is a Docusaurus project"), n);
        assertTrue(n.endsWith("Start Documents again with another pattern.\n"), n);
    }

    @Test
    void onlySomePatternsMatch_thePageNamesTheOnesThatDidNot() throws Exception {
        String n = notice("~/works/doc_*, ~/works/missing_a, ~/works/missing_b");
        assertTrue(n.contains("No folder matches `~/works/missing_a`.") && n.contains("No folder matches `~/works/missing_b`."), n);
        assertFalse(n.contains("doc_"), "the pattern that matched is not mentioned: " + n);
        assertTrue(n.endsWith("The other folders were read as usual. To change the folders, start Documents again with other patterns.\n"), n);
    }

    @Test
    void everyPatternMatches_thereIsNothingToSay() throws Exception {
        assertEquals("", notice("~/works/doc_*,notes"));
    }

    @Test
    void aPatternThatIsRefusedIsSaidToo() throws Exception {
        String n = notice("~/works/../etc,doc_*");
        assertTrue(n.contains("is not accepted as a folder pattern."), n);
    }

    @Test
    void noPatternAndNoProject_thePageAsksForTheFolders() throws Exception {
        Path empty = Files.createTempDirectory("emptystorage");
        Path lines = Files.createTempFile("notice-lines", ".txt");
        Path out = Files.createTempFile("notice", ".txt");
        try {
            var pb = new ProcessBuilder("sh", "-c", ". \"$1\"; write_notice \"$2\" 0 \"$3\"", "sh", SCRIPT.toString(), lines.toString(), out.toString());
            assertEquals(0, pb.start().waitFor());
            assertTrue(Files.readString(out).contains("enter the folders that hold documents, for example `~/works/doc_*`"));
        } finally {
            Files.deleteIfExists(lines);
            Files.deleteIfExists(out);
            Files.deleteIfExists(empty);
        }
    }

    @Test
    void thePatternSelectsOnlyTheMatchingProjects() throws Exception {
        var r = list("~/works/doc_*");
        assertEquals(List.of("doc_A", "doc_B"), r.out());
        assertTrue(r.err().contains("doc_plain") && r.err().contains("not a Docusaurus project"), r.err());
    }

    @Test
    void severalPatternsAndTheGroupStorage() throws Exception {
        assertEquals(List.of("doc_A", "groups/lab/doc_G", "groups/lab2/doc_G2", "groups/lab3/doc_G3", "notes"),
            list("~/works/doc_A, ~/works/groups/*/doc_*,notes").out(), "every group is walked, not only the first");
    }

    @Test
    void noMatchIsSaidAndNothingIsServed() throws Exception {
        var r = list("~/works/nothing_*");
        assertEquals(List.of(), r.out());
        assertTrue(r.err().contains("No folder matches ~/works/nothing_*"), r.err());
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
