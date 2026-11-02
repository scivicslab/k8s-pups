package com.scivicslab.k8spups.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolAudienceTest {

    @Test
    @DisplayName("The three audiences are read from their names, whatever the case and the blanks")
    void parse() {
        assertEquals(ToolAudience.EVERYONE, ToolAudience.parse("everyone"));
        assertEquals(ToolAudience.DEVELOPERS, ToolAudience.parse(" Developers "));
        assertEquals(ToolAudience.ADMINISTRATORS, ToolAudience.parse("ADMINISTRATORS"));
    }

    @Test
    @DisplayName("A name that is no audience is refused, so a misspelt developers cannot open a tool to everyone")
    void parseRefusesUnknown() {
        assertThrows(IllegalArgumentException.class, () -> ToolAudience.parse("developer"));
        assertThrows(IllegalArgumentException.class, () -> ToolAudience.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ToolAudience.parse(null));
    }

    @Test
    @DisplayName("The narrower of two audiences is the one that fewer users belong to")
    void narrower() {
        assertEquals(ToolAudience.DEVELOPERS, ToolAudience.EVERYONE.narrower(ToolAudience.DEVELOPERS));
        assertEquals(ToolAudience.DEVELOPERS, ToolAudience.DEVELOPERS.narrower(ToolAudience.EVERYONE));
        assertEquals(ToolAudience.ADMINISTRATORS, ToolAudience.DEVELOPERS.narrower(ToolAudience.ADMINISTRATORS));
        assertEquals(ToolAudience.EVERYONE, ToolAudience.EVERYONE.narrower(ToolAudience.EVERYONE));
    }
}
