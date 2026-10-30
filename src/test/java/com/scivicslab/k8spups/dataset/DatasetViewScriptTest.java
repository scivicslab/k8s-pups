package com.scivicslab.k8spups.dataset;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatasetViewScriptTest {

    @Test
    void listsExactlyTheEntitledFilesAndLinksOnlyThose() {
        String s = DatasetViewScript.build("original", "view", "alice",
            List.of("JGAD000001/sample2.txt", "JGAD000001/sample1.txt"));
        assertTrue(s.contains("V=$B/view/alice\n"));
        assertTrue(s.contains("O=$B/original\n"));
        assertTrue(s.contains("<<'EOF_WANTED'\nJGAD000001/sample1.txt\nJGAD000001/sample2.txt\nEOF_WANTED\n"));
        assertFalse(s.contains("JGAD000002"));
        assertTrue(s.contains("ln -f \"$O/$p\" \"$V/$p\""), "files are hardlinked from the original directory");
        assertFalse(s.contains("rm -rf"), "the view is updated in place, never emptied");
    }

    @Test
    void removesLinksOfFilesThatAreNoLongerEntitled() {
        String s = DatasetViewScript.build("original", "view", "alice", List.of("JGAD000001/sample1.txt"));
        assertTrue(s.contains("comm -13 /tmp/wanted /tmp/have"));
        assertTrue(s.contains("rm -f \"$V/$p\""));
    }

    @Test
    void neverChangesTheOwnerOfAFile() {
        String s = DatasetViewScript.build("original", "view", "alice", List.of("JGAD000001/sample1.txt"));
        assertFalse(s.contains("chown"), "a hardlink is the original file: chown would change the original's owner");
        assertFalse(s.contains("chmod"));
    }

    @Test
    void emptyEntitlementProducesAnEmptyWantedList() {
        String s = DatasetViewScript.build("original", "view", "bob", List.of());
        assertTrue(s.contains("<<'EOF_WANTED'\nEOF_WANTED\n"));
        assertTrue(s.contains("mkdir -p $V\n"));
    }

    @Test
    void rejectsPathsThatEscapeOrInjectShell() {
        for (String bad : List.of("../etc/passwd", "a/../b", "/abs", "a b", "a;rm -rf /", "a$(x)", "a/", "", ".hidden")) {
            assertThrows(IllegalArgumentException.class,
                () -> DatasetViewScript.build("original", "view", "alice", List.of(bad)), bad);
        }
    }

    @Test
    void rejectsUnsafeUserNames() {
        for (String bad : List.of("..", "a/b", "a b", "a;b", "", "$(x)")) {
            assertThrows(IllegalArgumentException.class,
                () -> DatasetViewScript.build("original", "view", bad, List.of()), bad);
        }
    }

    @Test
    void anEmptyOriginalDirectoryMeansTheTopOfTheMountedBase() {
        String s = DatasetViewScript.build("", "pups-view", "alice", List.of("JGAD000001/sample1.txt"));
        assertTrue(s.contains("O=$B\n"));
        assertTrue(s.contains("V=$B/pups-view/alice\n"));
    }

    /** Runs the script on a real directory: the datasets are at the top (the dataset root PVC), the view is inside it. */
    @Test
    void theViewInsideTheDatasetRootHoldsHardlinksOfExactlyTheEntitledFiles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path base) throws Exception {
        java.nio.file.Files.createDirectories(base.resolve("JGAD000005"));
        java.nio.file.Files.createDirectories(base.resolve("JGAD000002"));
        java.nio.file.Files.writeString(base.resolve("JGAD000005/sample1.txt"), "one");
        java.nio.file.Files.writeString(base.resolve("JGAD000005/sample2.txt"), "two");
        java.nio.file.Files.writeString(base.resolve("JGAD000002/sample1.txt"), "other");

        run(base, DatasetViewScript.build("", "pups-view", "alice", List.of("JGAD000005/sample1.txt")));
        java.nio.file.Path view = base.resolve("pups-view/alice");
        assertEquals(List.of("JGAD000005/sample1.txt"), files(view));
        assertEquals(java.nio.file.Files.getAttribute(base.resolve("JGAD000005/sample1.txt"), "unix:ino"),
            java.nio.file.Files.getAttribute(view.resolve("JGAD000005/sample1.txt"), "unix:ino"), "a hardlink of the original");

        // The grant of that file ends and another is given: the view follows, and nothing else appears.
        run(base, DatasetViewScript.build("", "pups-view", "alice", List.of("JGAD000005/sample2.txt")));
        assertEquals(List.of("JGAD000005/sample2.txt"), files(view));
        assertEquals("one", java.nio.file.Files.readString(base.resolve("JGAD000005/sample1.txt")), "the original stays");
    }

    private static void run(java.nio.file.Path base, String script) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", script.replace("/mnt/nfs-base", base.toString())).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
    }

    private static List<String> files(java.nio.file.Path view) throws Exception {
        try (var w = java.nio.file.Files.walk(view)) {
            return w.filter(java.nio.file.Files::isRegularFile).map(f -> view.relativize(f).toString()).sorted().toList();
        }
    }
}
