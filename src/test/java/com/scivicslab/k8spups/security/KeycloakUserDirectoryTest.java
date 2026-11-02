package com.scivicslab.k8spups.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class KeycloakUserDirectoryTest {

    @Test
    @DisplayName("Without a secret file the reader client is not configured, so a deployment that sets none still starts")
    void notConfigured() {
        KeycloakUserDirectory directory = new KeycloakUserDirectory();
        assertEquals(Optional.empty(), directory.secretFile, "no value is the default");
        assertFalse(directory.configured());
        directory.secretFile = Optional.of("  ");
        assertFalse(directory.configured());
    }

    @Test
    @DisplayName("A secret file that cannot be read is not a configured reader client")
    void unreadableFile(@TempDir Path dir) {
        KeycloakUserDirectory directory = new KeycloakUserDirectory();
        directory.secretFile = Optional.of(dir.resolve("missing").toString());
        assertFalse(directory.configured());
    }

    @Test
    @DisplayName("A readable secret file makes the reader client configured")
    void readableFile(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("secret"), "s3cret\n");
        KeycloakUserDirectory directory = new KeycloakUserDirectory();
        directory.secretFile = Optional.of(file.toString());
        assertTrue(directory.configured());
    }
}
