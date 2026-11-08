package com.scivicslab.k8spups.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/** What the user may write as the document folders of the Documents tool: folders inside the storage, nothing more. */
class DocumentFolderPatternsTest {

    @Test
    void foldersInsideTheStorageAreAccepted() {
        for (String ok : new String[] {"~/works/doc_*", "doc_*", "~/works/groups/lab/doc_*", "~/works/doc_*,~/works/groups/lab/doc_*",
                "~/works/doc_Base0?0", "~/works/文書_*", " ~/works/a , ~/works/b ", "~/works/doc_*/", "~/works/my-docs/v1.2+x@y"}) {
            assertNull(DocumentFolderPatterns.check(ok), ok);
        }
    }

    @Test
    void blankMeansNoPatterns() {
        assertNull(DocumentFolderPatterns.check(null));
        assertNull(DocumentFolderPatterns.check(""));
        assertNull(DocumentFolderPatterns.check("   "));
        assertEquals(List.of(), DocumentFolderPatterns.normalize(" "));
    }

    @Test
    void whatCouldLeaveTheStorageIsRefused() {
        for (String bad : new String[] {"/etc/*", "/home/devteam/works/doc_*", "~/works/../*", "../*", "~/works/a/../../b", "~/works/./a",
                "~root/*", "~/other/doc_*", "~/works/", "~/works", ".", "..", "~/works/.ssh", "~/works/a/.hidden", "doc_*//x", "~"}) {
            assertNotNull(DocumentFolderPatterns.check(bad), bad);
        }
    }

    @Test
    void whatAShellWouldReadAsMoreThanANameIsRefused() {
        for (String bad : new String[] {"doc_$(id)", "doc_`id`", "doc_;rm", "doc_|x", "doc_&x", "doc_>x", "doc_<x", "doc_ x", "doc_'x", "doc_\"x",
                "doc_[ab]", "doc_{a,b}", "doc_\\x", "doc_!x", "doc_#x", "doc_%x", "doc_=x", "doc_:x", "doc_^x", "doc_\nx", "doc_\tx"}) {
            assertNotNull(DocumentFolderPatterns.check(bad), bad.replace("\n", "\\n").replace("\t", "\\t"));
        }
    }

    @Test
    void emptyPatternsAndExcessAreRefused() {
        assertNotNull(DocumentFolderPatterns.check("a,,b"));
        assertNotNull(DocumentFolderPatterns.check("a,"));
        assertNotNull(DocumentFolderPatterns.check("a/b/c/d/e/f/g"));
        assertNotNull(DocumentFolderPatterns.check("x".repeat(201)));
        assertNotNull(DocumentFolderPatterns.check(String.join(",", java.util.Collections.nCopies(11, "a"))));
        assertNull(DocumentFolderPatterns.check(String.join(",", java.util.Collections.nCopies(10, "a"))));
    }

    @Test
    void normalizeGivesThePatternsBelowTheStorage() {
        assertEquals(List.of("doc_*", "groups/lab/doc_*"), DocumentFolderPatterns.normalize("~/works/doc_*, groups/lab/doc_*/"));
        assertThrows(IllegalArgumentException.class, () -> DocumentFolderPatterns.normalize("../x"));
    }
}
