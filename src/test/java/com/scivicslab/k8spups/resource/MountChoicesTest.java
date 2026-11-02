package com.scivicslab.k8spups.resource;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MountChoicesTest {

    static final Map<String, String> STORAGE = new LinkedHashMap<>(Map.of("posix", "Shared file storage"));

    @Test
    void choicesCoverOwnStorageAndGroupsOnly() {
        var c = MountChoices.of(STORAGE, Map.of("lab1", "ro"));
        assertEquals(List.of("posix", "group:lab1"), c.stream().map(MountChoices.Choice::value).toList());
        assertEquals("/groups/lab1", c.get(1).path());
        assertTrue(c.get(1).label().contains("read-only"));
    }

    @Test
    void rememberedChoicesThatAreNoLongerAllowedAreDroppedAndCounted() {
        String json = MountChoices.toJson("posix", List.of(
            Map.of("value", "group:lab1", "path", "/groups/lab1"),
            Map.of("value", "group:lab9", "path", "/groups/lab9")));   // membership ended
        var current = MountChoices.of(STORAGE, Map.of("lab1", "rw"));
        var r = MountChoices.remembered(json, current, STORAGE.keySet());
        assertEquals("posix", r.primary());
        assertEquals(List.of(Map.of("value", "group:lab1", "path", "/groups/lab1")), r.mounts());
        assertEquals(1, r.dropped());
    }

    @Test
    void aRememberedDatasetChoiceIsDroppedWithoutBeingCounted() {
        // The datasets are mounted by the controller now; a choice stored before that is neither offered nor reported.
        String json = MountChoices.toJson("posix", List.of(
            Map.of("value", "dataset:JGAD000001", "path", "/datasets/JGAD000001"),
            Map.of("value", "dataset-files", "path", "/dataset-files")));
        var r = MountChoices.remembered(json, MountChoices.of(STORAGE, Map.of()), STORAGE.keySet());
        assertEquals(List.of(), r.mounts());
        assertEquals(0, r.dropped());
    }

    @Test
    void aPrimaryTypeTakenAwayIsDropped() {
        var r = MountChoices.remembered(MountChoices.toJson("longhorn", List.of()), MountChoices.of(STORAGE, Map.of()), STORAGE.keySet());
        assertEquals("", r.primary());
        assertEquals(1, r.dropped());
        assertEquals(0, MountChoices.remembered("[]", List.of(), List.of()).dropped());
    }

    @Test
    void onlyStorageWithAnExistingPvcCanBeMounted() {
        Map<String, Object> pvcStates = Map.of(
            "posix", Map.of("exists", "true"),
            "longhorn", Map.of("exists", "false"));
        assertEquals(List.of("posix"), MountChoices.withExistingPvc(List.of("posix", "longhorn", "lustre"), pvcStates));
        assertEquals(List.of(), MountChoices.withExistingPvc(List.of("posix"), Map.of()));
    }
}
