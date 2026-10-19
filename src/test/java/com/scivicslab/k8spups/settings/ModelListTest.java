package com.scivicslab.k8spups.settings;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelListTest {

    @Test
    void readsTheIdsInAlphabeticalOrderWithoutDuplicates() {
        String json = "{\"object\":\"list\",\"data\":[{\"id\":\"b/model\"},{\"id\":\"a-model\"},{\"id\":\"b/model\"}]}";
        assertEquals(List.of("a-model", "b/model"), ModelList.parse(json));
    }

    @Test
    void dropsIdsThatCannotBeUsedAsModelNames() {
        assertEquals(List.of("ok"), ModelList.parse("{\"data\":[{\"id\":\"ok\"},{\"id\":\"a b\"},{\"id\":\"\"},{}]}"));
    }

    @Test
    void anythingElseGivesAnEmptyList() {
        assertTrue(ModelList.parse("not json").isEmpty());
        assertTrue(ModelList.parse("{\"data\":\"x\"}").isEmpty());
        assertTrue(ModelList.parse("{}").isEmpty());
    }
}
