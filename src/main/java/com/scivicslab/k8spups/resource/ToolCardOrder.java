package com.scivicslab.k8spups.resource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The order of the cards in the "Launch a Tool" grid of the dashboard. The cards come from three sources (tool plugins,
 * shared services, dynamic tools), so the order is given to each card as a CSS order value.
 * The named cards come first, in this order; every other card follows in the order the page builds them.
 */
public final class ToolCardOrder {

    /** The CSS order of every card that is not named below. */
    public static final int OTHER = 100;

    private static final List<String> NAMED = List.of(
            "Storage Settings", "File Browser",
            "Remote Desktop", "Jupyter Lab", "Jupyter AI",
            "Chat UI with Audit Trail (Local LLM)", "AI Chat", "Program Search & Edit");

    private ToolCardOrder() {
    }

    /** Card title to CSS order value, 1 for the first named card. */
    public static Map<String, Integer> ranks() {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (int i = 0; i < NAMED.size(); i++) {
            ranks.put(NAMED.get(i), i + 1);
        }
        return ranks;
    }

    public static int rank(String cardTitle) {
        return ranks().getOrDefault(cardTitle, OTHER);
    }
}
