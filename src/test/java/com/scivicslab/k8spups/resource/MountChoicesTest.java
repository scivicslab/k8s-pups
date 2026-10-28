package com.scivicslab.k8spups.resource;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MountChoicesTest {

    static final Map<String, String> STORAGE = new LinkedHashMap<>(Map.of("lustre-csi", "Shared file storage"));

    @Test
    void choicesCoverOwnStorageGroupsDatasetsAndFiles() {
        var c = MountChoices.of(STORAGE, Map.of("lab1", "ro"), List.of("JGAD000001"), true);
        assertEquals(List.of("lustre-csi", "group:lab1", "dataset:JGAD000001", "dataset-files"),
            c.stream().map(MountChoices.Choice::value).toList());
        assertEquals("/groups/lab1", c.get(1).path());
        assertTrue(c.get(1).label().contains("read-only"));
        assertEquals("/datasets/JGAD000001", c.get(2).path());
    }

    @Test
    void rememberedChoicesThatAreNoLongerAllowedAreDroppedAndCounted() {
        String json = MountChoices.toJson("lustre-csi", List.of(
            Map.of("value", "group:lab1", "path", "/groups/lab1"),
            Map.of("value", "dataset:JGAD000009", "path", "/datasets/JGAD000009")));   // grant ended
        var current = MountChoices.of(STORAGE, Map.of("lab1", "rw"), List.of(), false);
        var r = MountChoices.remembered(json, current, STORAGE.keySet());
        assertEquals("lustre-csi", r.primary());
        assertEquals(List.of(Map.of("value", "group:lab1", "path", "/groups/lab1")), r.mounts());
        assertEquals(1, r.dropped());
    }

    @Test
    void aPrimaryTypeTakenAwayIsDropped() {
        var r = MountChoices.remembered(MountChoices.toJson("longhorn", List.of()), MountChoices.of(STORAGE, Map.of(),
            List.of(), false), STORAGE.keySet());
        assertEquals("", r.primary());
        assertEquals(1, r.dropped());
        assertEquals(0, MountChoices.remembered("[]", List.of(), List.of()).dropped());
    }
}
