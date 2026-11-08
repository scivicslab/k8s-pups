package com.scivicslab.k8spups.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The folders of the user's storage that the Documents tool reads as Docusaurus projects, as the user writes them in the
 * launch form: one or more patterns separated by commas, for example {@code ~/works/doc_*} or
 * {@code ~/works/doc_*,~/works/groups/lab/doc_*}.
 *
 * <p>A pattern names folders inside the storage that the Pod mounts at {@code ~/works}, and nothing else. It is a path
 * of segments separated by "/", each made of letters, digits and {@code _ - . + @}, where {@code *} stands for any
 * characters and {@code ?} for one. It begins with {@code ~/works/} or is written relative to it. Anything that could
 * leave the storage ({@code ..}, an absolute path, {@code ~user}) or that a shell would read as more than a name
 * (spaces, quotes, {@code $ ` ; | & < > ( ) [ ] { } \}) is refused. The image matches the patterns itself, without a
 * shell, and refuses the same things again.
 */
public final class DocumentFolderPatterns {

    private DocumentFolderPatterns() {}

    /** What the Pod mounts the storage at; a pattern may begin with it. */
    public static final String HOME_WORKS = "~/works/";

    static final int MAX_PATTERNS = 10;
    static final int MAX_DEPTH = 6;
    static final int MAX_LENGTH = 200;

    private static final Pattern SEGMENT = Pattern.compile("[\\p{L}\\p{N}_.+@*?-]+");

    /** Why {@code raw} is not accepted, or null when it is. */
    public static String check(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (raw.length() > MAX_LENGTH) return "longer than " + MAX_LENGTH + " characters";
        String[] parts = raw.split(",", -1);
        if (parts.length > MAX_PATTERNS) return "more than " + MAX_PATTERNS + " patterns";
        for (String part : parts) {
            String why = checkOne(part.trim());
            if (why != null) return why + " in [" + part.trim() + "]";
        }
        return null;
    }

    private static String checkOne(String p) {
        if (p.isEmpty()) return "an empty pattern";
        String rel = relative(p);
        if (rel.startsWith("/")) return "an absolute path";
        if (rel.startsWith("~")) return "a path that does not begin with ~/works/";
        if (rel.isEmpty()) return "no folder below ~/works";
        String[] segments = rel.split("/", -1);
        if (segments.length > MAX_DEPTH) return "deeper than " + MAX_DEPTH + " levels";
        for (String s : segments) {
            if (s.isEmpty()) return "an empty path segment";
            if (s.equals(".") || s.equals("..")) return "a '" + s + "' segment";
            if (s.startsWith(".")) return "a hidden folder";
            if (!SEGMENT.matcher(s).matches()) return "a character that is not allowed";
        }
        return null;
    }

    /** The pattern below the storage root: {@code ~/works/doc_*} and {@code doc_*} are both {@code doc_*}. */
    static String relative(String p) {
        if (p.equals("~/works") || p.equals("~/works/")) return "";
        return p.startsWith(HOME_WORKS) ? p.substring(HOME_WORKS.length()).replaceAll("/+$", "") : p.replaceAll("/+$", "");
    }

    /** The accepted patterns below the storage root, in the order written. Empty for a blank value; throws for a refused one. */
    public static List<String> normalize(String raw) {
        String why = check(raw);
        if (why != null) throw new IllegalArgumentException(why);
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",", -1)) out.add(relative(part.trim()));
        return out;
    }
}
