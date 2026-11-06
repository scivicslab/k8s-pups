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
        assertEquals(List.of(Map.of("value", "group:lab1")), r.mounts());
        assertEquals(1, r.dropped());
    }

    @Test
    void eachChoiceIsMountedAtItsOwnPlaceUnderTheDirectoryTheToolShows() {
        assertEquals("/home/pod-user/works/groups/lab1", MountChoices.pathOf("group:lab1", "/home/pod-user/works/"));
        assertEquals("/home/pod-user/works/storage/longhorn", MountChoices.pathOf("longhorn", "/home/pod-user/works"));
        assertEquals("/groups/lab1", MountChoices.pathOf("group:lab1", null));
        assertEquals("/storage/posix", MountChoices.of(STORAGE, Map.of()).get(0).path());
    }

    @Test
    void aChoiceMadeTwiceIsMountedOnce() {
        assertEquals(List.of("group:lab1", "posix"), MountChoices.distinctValues("group:lab1", null, " ", "posix", "group:lab1"));
    }

    @Test
    void datasetsAreChoicesLikeGroupStorage() {
        var c = MountChoices.of(STORAGE, Map.of("lab1", "ro"),
            List.of(new MountChoices.Dataset("JGAD000003", false), new MountChoices.Dataset("JGAS000001", true)), true, "jga");
        assertEquals(List.of("posix", "group:lab1", "dataset:JGAD000003", "s3:JGAS000001", "dataset-files"),
            c.stream().map(MountChoices.Choice::value).toList());
        assertEquals("/datasets/JGAD000003", c.get(2).path());
        assertEquals("jga/JGAS000001/", c.get(3).path());
        assertTrue(c.get(3).s3());
        assertEquals("/home/pod-user/works/datasets/JGAD000003", MountChoices.pathOf("dataset:JGAD000003", "/home/pod-user/works"));
        assertEquals("/home/pod-user/works/datasets/granted-files", MountChoices.pathOf("dataset-files", "/home/pod-user/works"));
    }

    @Test
    void aRememberedDatasetIsKeptWhileTheUserMayReadItAndCountedWhenNot() {
        String json = MountChoices.toJson("posix", List.of(Map.of("value", "dataset:JGAD000003"), Map.of("value", "s3:JGAS000009")));
        var current = MountChoices.of(STORAGE, Map.of(), List.of(new MountChoices.Dataset("JGAD000003", false)), false, "jga");
        var r = MountChoices.remembered(json, current, STORAGE.keySet());
        assertEquals(List.of(Map.of("value", "dataset:JGAD000003")), r.mounts());
        assertEquals(1, r.dropped());
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
