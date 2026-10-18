package com.scivicslab.k8spups.dataset;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DatasetPathTest {

    @Test
    void acceptsPlainRelativePaths() {
        assertTrue(DatasetPath.isValidRelativePath("JGAD000001/sample1.bam"));
        assertTrue(DatasetPath.isValidRelativePath("a-b_c.d/e.vcf.gz"));
    }

    @Test
    void rejectsEscapesAndSyntax() {
        assertFalse(DatasetPath.isValidRelativePath("../x"));
        assertFalse(DatasetPath.isValidRelativePath("a//b"));
        assertFalse(DatasetPath.isValidRelativePath("a\nb"));
        assertFalse(DatasetPath.isValidRelativePath(null));
    }

    @Test
    void userNameIsOneSegment() {
        assertTrue(DatasetPath.isValidUserName("testuser1"));
        assertFalse(DatasetPath.isValidUserName("a/b"));
    }
}
