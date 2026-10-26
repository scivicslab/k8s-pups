package com.scivicslab.k8spups.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The order of the cards on the dashboard. */
class ToolCardOrderTest {

    @Test
    void namedCardsComeInTheFixedOrder() {
        List<String> inOrder = List.of("Storage Settings", "File Browser", "Datasets",
                "Remote Desktop", "Jupyter Lab", "Jupyter AI",
                "Chat UI with Audit Trail (Local LLM)", "AI Chat", "Program Search & Edit");
        for (int i = 1; i < inOrder.size(); i++) {
            assertTrue(ToolCardOrder.rank(inOrder.get(i - 1)) < ToolCardOrder.rank(inOrder.get(i)),
                    inOrder.get(i - 1) + " must come before " + inOrder.get(i));
        }
    }

    @Test
    void everyOtherCardComesAfterTheNamedOnes() {
        int last = ToolCardOrder.rank("Program Search & Edit");
        assertEquals(ToolCardOrder.OTHER, ToolCardOrder.rank("English Toolkit"));
        assertEquals(ToolCardOrder.OTHER, ToolCardOrder.rank(null));
        assertTrue(last < ToolCardOrder.OTHER);
    }

    @Test
    void theRankMapHasOnlyTheNamedCards() {
        assertEquals(9, ToolCardOrder.ranks().size());
        assertEquals(1, ToolCardOrder.ranks().get("Storage Settings"));
    }
}
