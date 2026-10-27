package com.scivicslab.k8spups.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class UserKeyTest {

    @Test
    void plainLowercaseNamesAreUnchanged() {
        assertEquals("alice", UserKey.encode("alice"));
        assertEquals("user01", UserKey.encode("user01"));
    }

    @Test
    void namesThatCollidedUnderTheOldMappingGetDistinctKeys() {
        String dot = UserKey.encode("a.b");
        String underscore = UserKey.encode("a_b");
        String hyphen = UserKey.encode("a-b");
        assertEquals("a-u002eb", dot);
        assertEquals("a-u005fb", underscore);
        assertEquals("a-u002db", hyphen);
        assertNotEquals(dot, underscore);
        assertNotEquals(dot, hyphen);
        assertNotEquals(underscore, hyphen);
    }

    @Test
    void aLiteralNameThatLooksLikeAnEscapeDoesNotCollideWithTheEscapedName() {
        // "a-u002eb" as a user name contains "-", which is escaped, so it cannot equal encode("a.b").
        assertNotEquals(UserKey.encode("a.b"), UserKey.encode("a-u002eb"));
    }

    @Test
    void decodeRestoresTheLowercasedName() {
        for (String name : List.of("alice", "a.b", "a_b", "a-b", "first.last@example.org", "a-u002eb", "yamada太郎")) {
            assertEquals(name.toLowerCase(), UserKey.decode(UserKey.encode(name)));
        }
    }

    @Test
    void keysUseOnlyCharactersAllowedInKubernetesNames() {
        for (String name : List.of("alice", "a.b", "first.last@example.org", "yamada太郎")) {
            assertTrue(UserKey.encode(name).matches("[a-z0-9-]+"), name);
        }
    }

    @Test
    void uppercaseIsFoldedBecauseKeycloakUserNamesAreCaseInsensitive() {
        assertEquals(UserKey.encode("alice"), UserKey.encode("Alice"));
    }

    @Test
    void emptyNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> UserKey.encode(""));
    }

    @Test
    void aNameStartingWithASymbolIsRejectedBecauseALabelValueMustStartAlphanumeric() {
        assertThrows(IllegalArgumentException.class, () -> UserKey.encode(".alice"));
        assertThrows(IllegalArgumentException.class, () -> UserKey.encode("@alice"));
    }

    @Test
    void aNameWhoseKeyExceedsTheLabelLimitIsRejected() {
        assertEquals(63, UserKey.encode("a".repeat(63)).length());
        assertThrows(IllegalArgumentException.class, () -> UserKey.encode("a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> UserKey.encode("a.b.c.d.e.f.g.h.i.j.k.l"));
    }

    @Test
    void keysAreValidLabelValues() {
        for (String name : List.of("alice", "first.last@example.org", "a-b", "x_y")) {
            assertTrue(UserKey.encode(name).matches("[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?"), name);
        }
    }
}
