package com.scivicslab.k8spups.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The order of the cards on the dashboard: alphabetical by the name shown. */
class ToolCardOrderTest {

    private static List<String> inRankOrder(List<String> titles) {
        var ranks = ToolCardOrder.ranks(titles);
        return titles.stream().sorted(java.util.Comparator.comparingInt(ranks::get)).toList();
    }

    @Test
    void cardsAreInAlphabeticalOrderWhateverTheCase() {
        var shuffled = List.of("Terminal", "code editor", "AI Chat", "Documents", "Jupyter Lab", "File Browser", "galaxy", "AI Agent (Local LLM)",
            "Remote Desktop (Ubuntu MATE 24.04)", "Storage Settings", "Jupyter AI");
        assertEquals(List.of("AI Agent (Local LLM)", "AI Chat", "code editor", "Documents", "File Browser", "galaxy", "Jupyter AI", "Jupyter Lab",
            "Remote Desktop (Ubuntu MATE 24.04)", "Storage Settings", "Terminal"), inRankOrder(shuffled));
    }

    @Test
    void aNewToolTakesItsPlaceWithNoEntryAnywhere() {
        var before = List.of("AI Chat", "Terminal");
        var after = List.of("AI Chat", "Terminal", "Blast Search");
        assertEquals(List.of("AI Chat", "Blast Search", "Terminal"), inRankOrder(after));
        assertEquals(2, ToolCardOrder.ranks(before).get("Terminal"));
        assertEquals(3, ToolCardOrder.ranks(after).get("Terminal"));
    }

    @Test
    void ranksStartAtOneAndEveryTitleHasOne() {
        var ranks = ToolCardOrder.ranks(List.of("b", "a", "c"));
        assertEquals(1, ranks.get("a"));
        assertEquals(3, ranks.get("c"));
        assertEquals(3, ranks.size());
        assertTrue(ranks.values().stream().allMatch(r -> r < ToolCardOrder.OTHER));
    }

    @Test
    void sameTitleTwiceHasOneRankAndBlankOrMissingTitlesAreSkipped() {
        var ranks = ToolCardOrder.ranks(java.util.Arrays.asList("Terminal", "Terminal", null, " "));
        assertEquals(1, ranks.size());
        assertEquals(1, ranks.get("Terminal"));
    }
}
