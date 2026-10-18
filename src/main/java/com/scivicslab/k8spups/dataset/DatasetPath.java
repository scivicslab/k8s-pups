package com.scivicslab.k8spups.dataset;

import java.util.regex.Pattern;

/**
 * Validation of the relative paths and user names that end up in a shell script run against the
 * NFS server. Only plain path segments are accepted, so a value can neither leave the dataset
 * directory ({@code ..}) nor inject shell syntax.
 */
public final class DatasetPath {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private DatasetPath() {}

    /** Returns true when {@code path} is a relative path made of plain segments separated by '/'. */
    public static boolean isValidRelativePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 512) return false;
        for (String segment : path.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches() || segment.equals("..")) return false;
        }
        return true;
    }

    /** Returns true when {@code user} can be used as a single directory name. */
    public static boolean isValidUserName(String user) {
        return user != null && user.length() <= 64 && SEGMENT.matcher(user).matches() && !user.equals("..");
    }

    public static String requireRelativePath(String path) {
        if (!isValidRelativePath(path)) throw new IllegalArgumentException("Invalid dataset path: " + path);
        return path;
    }

    public static String requireUserName(String user) {
        if (!isValidUserName(user)) throw new IllegalArgumentException("Invalid user name: " + user);
        return user;
    }
}
