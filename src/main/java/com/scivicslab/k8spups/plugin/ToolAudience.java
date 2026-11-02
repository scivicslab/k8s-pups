package com.scivicslab.k8spups.plugin;

/**
 * Who the dashboard offers a tool to, and who may start it. The levels are nested: an administrator may use
 * everything a developer may use, and a developer everything that is offered to everyone.
 */
public enum ToolAudience {

    /** Every user who is logged in. */
    EVERYONE,

    /** The developers of k8s-pups and of the tools on it, and the administrators. */
    DEVELOPERS,

    /** The administrators only. */
    ADMINISTRATORS;

    /**
     * Reads the name of an audience from a setting. A name that is not one of the three is refused instead of
     * guessed: a misspelt {@code developers} must not make a tool for developers available to everyone.
     *
     * @throws IllegalArgumentException when the text names no audience
     */
    public static ToolAudience parse(String text) {
        if (text != null) {
            switch (text.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "everyone" -> { return EVERYONE; }
                case "developers" -> { return DEVELOPERS; }
                case "administrators" -> { return ADMINISTRATORS; }
                default -> { }
            }
        }
        throw new IllegalArgumentException("Not an audience (everyone, developers, administrators): " + text);
    }

    /** The narrower of two audiences: the one that fewer users belong to. */
    public ToolAudience narrower(ToolAudience other) {
        return ordinal() >= other.ordinal() ? this : other;
    }
}
