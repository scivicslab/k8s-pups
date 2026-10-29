package com.scivicslab.k8spups.k8s;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The init container of a tool that runs as the Pod ID copies /etc/passwd and /etc/group and adds the entry of
 * the Pod's UID and GID. The test runs the script with an {@code id} that knows the numbers but no names, which is
 * what the image sees for the cluster's IDs, and with the identity directory moved into a temporary one.
 */
class UserEntryScriptTest {

    @TempDir Path dir;

    private List<String> runWithStub(boolean namesKnown) throws Exception {
        Path bin = Files.createDirectory(dir.resolve("bin"));
        Path id = bin.resolve("id");
        Files.writeString(id, "#!/bin/sh\ncase \"$1\" in -u) echo 30420;; -g) echo 30102;; *) "
            + (namesKnown ? "echo known;;" : "exit 1;;") + " esac\n");
        Files.setPosixFilePermissions(id, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path out = Files.createDirectory(dir.resolve("identity"));
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", K8sApiClient.USER_ENTRY_SCRIPT.replace("/identity", out.toString()));
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("PUPS_HOME", "/home/user");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String log = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), log);
        return List.of(Files.readString(out.resolve("passwd")), Files.readString(out.resolve("group")));
    }

    @Test
    void addsTheEntryOfAnIdThatTheImageDoesNotKnow() throws Exception {
        List<String> files = runWithStub(false);
        assertTrue(files.get(0).contains("pups:x:30420:30102:k8s-pups user:/home/user:/bin/sh"), files.get(0));
        assertTrue(files.get(1).contains("pups:x:30102:"), files.get(1));
        assertEquals(Files.readString(Path.of("/etc/passwd")) + "pups:x:30420:30102:k8s-pups user:/home/user:/bin/sh\n", files.get(0));
    }

    @Test
    void leavesTheFilesAloneWhenTheImageKnowsTheId() throws Exception {
        List<String> files = runWithStub(true);
        assertEquals(Files.readString(Path.of("/etc/passwd")), files.get(0));
        assertEquals(Files.readString(Path.of("/etc/group")), files.get(1));
    }
}
