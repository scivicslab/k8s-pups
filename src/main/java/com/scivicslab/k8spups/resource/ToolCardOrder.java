package com.scivicslab.k8spups.resource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The order of the cards in the "Launch a Tool" grid of the dashboard: alphabetical by the name the card shows, upper and
 * lower case not told apart. The cards come from three sources (tool plugins, shared services, dynamic tools), so the order
 * is given to each card as a CSS order value. A tool that is added later takes its place in the alphabet with no entry here.
 */
public final class ToolCardOrder {

    /** The CSS order of a card whose name is not among those ranked (a card is ranked from the names the page shows). */
    public static final int OTHER = 10_000;

    private ToolCardOrder() {
    }

    /** Card title to CSS order value for the given titles, 1 for the first in alphabetical order. */
    public static Map<String, Integer> ranks(Collection<String> titles) {
        List<String> sorted = new ArrayList<>(new TreeSet<>(titles.stream().filter(t -> t != null && !t.isBlank()).toList()));
        sorted.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(java.util.Comparator.naturalOrder()));
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            ranks.put(sorted.get(i), i + 1);
        }
        return ranks;
    }
}
