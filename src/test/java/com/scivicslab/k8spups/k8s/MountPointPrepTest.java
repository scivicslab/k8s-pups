package com.scivicslab.k8spups.k8s;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mount points of the extra mounts under the home are made by the init container as the user, and the empty ones
 * of earlier sessions are removed, so that the home does not keep root-owned empty directories of datasets taken away.
 */
class MountPointPrepTest {

    @Test
    void removesEmptyOldMountPointsAndMakesTheNewOnes() {
        String s = K8sApiClient.mountPointPrep("/home/jovyan", List.of(
            "/home/jovyan/datasets/JGAD000001", "/home/jovyan/groups/teaching", "/home/jovyan/dataset-files"));
        assertTrue(s.startsWith("cd /mnt/pvc"), s);
        assertTrue(s.contains("for s in 'datasets'/*; do [ -d \"$s\" ] && rmdir \"$s\" 2>/dev/null; done"), s);
        assertTrue(s.contains("for s in 'groups'/*; do"), s);
        assertTrue(s.contains("mkdir -p 'dataset-files' 'datasets/JGAD000001' 'groups/teaching'"), s);
        assertTrue(!s.contains("rm -r"), "only empty directories are removed: " + s);
    }

    @Test
    void aSessionWithoutExtraMountsStillCleansTheOldMountPoints() {
        String s = K8sApiClient.mountPointPrep("/home/jovyan", List.of());
        assertTrue(s.contains("for s in 'datasets'/*") && s.contains("for s in 'groups'/*"), s);
        assertTrue(!s.contains("mkdir"), "nothing to make: " + s);
        String outside = K8sApiClient.mountPointPrep("/home/jovyan", List.of("/groups/teaching", "/datasets/JGAD000001"));
        assertTrue(!outside.contains("mkdir"), "mounts outside the home are left to the kubelet: " + outside);
    }

    @Test
    void aPathWithShellSyntaxIsLeftToTheKubelet() {
        String none = K8sApiClient.mountPointPrep("/home/jovyan", List.of("/home/jovyan/x;rm -rf ~", "/home/jovyan/a b", "/home/jovyan/../etc", "/home/jovyan/$(id)"));
        assertTrue(!none.contains("mkdir") && !none.contains("rm -rf"), none);
        String s = K8sApiClient.mountPointPrep("/home/jovyan", List.of("/home/jovyan/ok/dir", "/home/jovyan/x;rm -rf ~"));
        assertTrue(s.contains("mkdir -p 'ok/dir'") && !s.contains("rm -rf"), s);
    }

    @Test
    void theCommandsEndWithSuccessWhateverWasThere() {
        assertTrue(K8sApiClient.mountPointPrep("/home/jovyan", List.of()).endsWith("; true"));
        assertTrue(K8sApiClient.mountPointPrep("/home/jovyan", List.of("/home/jovyan/groups/teaching")).endsWith("; true"));
    }

    /** Runs the commands on a real directory: stale empty mount points go, the files and non-empty directories stay, the new ones appear. */
    @Test
    void runningTheCommandsCleansAndMakesTheMountPoints(@org.junit.jupiter.api.io.TempDir java.nio.file.Path pvc) throws Exception {
        java.nio.file.Files.createDirectories(pvc.resolve("datasets/JGAD000003"));          // stale, empty
        java.nio.file.Files.createDirectories(pvc.resolve("datasets/mine"));                // the user's own folder
        java.nio.file.Files.writeString(pvc.resolve("datasets/mine/notes.txt"), "keep");
        java.nio.file.Files.createDirectories(pvc.resolve("groups/old"));                   // stale, empty
        String script = K8sApiClient.mountPointPrep("/home/jovyan", List.of("/home/jovyan/datasets/JGAD000001", "/home/jovyan/groups/teaching"))
            .replace("/mnt/pvc", pvc.toString());
        Process p = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
        assertTrue(!java.nio.file.Files.exists(pvc.resolve("datasets/JGAD000003")));
        assertTrue(!java.nio.file.Files.exists(pvc.resolve("groups/old")));
        assertTrue(java.nio.file.Files.exists(pvc.resolve("datasets/mine/notes.txt")), "the user's own files stay");
        assertTrue(java.nio.file.Files.isDirectory(pvc.resolve("datasets/JGAD000001")));
        assertTrue(java.nio.file.Files.isDirectory(pvc.resolve("groups/teaching")));
    }
}
