package com.scivicslab.k8spups.resource;

import static org.junit.jupiter.api.Assertions.*;

import com.scivicslab.k8spups.plugin.UserParameter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What the launch form remembers of the fields a tool declares, and what it shows again. */
class FormValuesTest {

    private static final UserParameter FOLDERS = new UserParameter("PORTAL_PROJECTS", "Folders", "~/works/doc_*", false, false);
    private static final UserParameter TOKEN = new UserParameter("API_TOKEN", "Token", "", true, true);
    private static final UserParameter NOTE = new UserParameter("NOTE", "Note", "", false, false);

    @Test
    void whatWasTypedIsStoredAndShownAgain() {
        var stored = FormValues.toStore(List.of(FOLDERS), Map.of("PORTAL_PROJECTS", "~/works/doc_*"));
        assertEquals(Map.of("PORTAL_PROJECTS", "~/works/doc_*"), stored);
        var json = FormValues.toJson(stored);
        assertEquals(List.of("~/works/doc_*"), FormValues.initial(List.of(FOLDERS), FormValues.fromJson(json)));
    }

    @Test
    void aBlankFieldReplacesTheEarlierValue() {
        // the launch resolves blank fields away, so the field is absent from what was entered
        var stored = FormValues.toStore(List.of(FOLDERS), Map.of());
        assertEquals(Map.of("PORTAL_PROJECTS", ""), stored);
        assertEquals(List.of(""), FormValues.initial(List.of(FOLDERS), stored));
        assertEquals(Map.of("PORTAL_PROJECTS", ""), FormValues.toStore(List.of(FOLDERS), Map.of("PORTAL_PROJECTS", "   ")));
    }

    @Test
    void aSecretFieldIsNeverStoredNorShown() {
        var entered = new LinkedHashMap<String, String>();
        entered.put("API_TOKEN", "s3cret");
        entered.put("NOTE", "hello");
        var stored = FormValues.toStore(List.of(TOKEN, NOTE), entered);
        assertEquals(Map.of("NOTE", "hello"), stored);
        assertFalse(FormValues.toJson(stored).contains("s3cret"));
        // even a stored value of a secret field (a tool that declared it secret later) is not shown
        assertEquals(List.of("", "hello"), FormValues.initial(List.of(TOKEN, NOTE), Map.of("API_TOKEN", "old", "NOTE", "hello")));
    }

    @Test
    void theFormShowsTheFieldsInTheOrderOfTheTool() {
        assertEquals(List.of("b", "a"), FormValues.initial(List.of(NOTE, FOLDERS), Map.of("PORTAL_PROJECTS", "a", "NOTE", "b")));
        assertEquals(List.of("", ""), FormValues.initial(List.of(NOTE, FOLDERS), Map.of()));
        assertEquals(List.of(""), FormValues.initial(List.of(NOTE), null));
    }

    @Test
    void aLongValueIsNotRemembered() {
        var stored = FormValues.toStore(List.of(NOTE), Map.of("NOTE", "x".repeat(FormValues.MAX_LENGTH + 1)));
        assertEquals(Map.of(), stored);
        assertEquals(Map.of("NOTE", "x".repeat(FormValues.MAX_LENGTH)), FormValues.toStore(List.of(NOTE), Map.of("NOTE", "x".repeat(FormValues.MAX_LENGTH))));
    }

    @Test
    void anUnreadableOrMissingStoredValueIsNothing() {
        assertEquals(Map.of(), FormValues.fromJson(null));
        assertEquals(Map.of(), FormValues.fromJson(""));
        assertEquals(Map.of(), FormValues.fromJson("[1,2"));
        assertEquals(Map.of(), FormValues.fromJson("{}"));
    }
}
