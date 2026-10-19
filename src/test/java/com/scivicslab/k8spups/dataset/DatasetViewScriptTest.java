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
}
