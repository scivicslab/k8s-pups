package com.scivicslab.k8spups.k8s;

/**
 * Injective mapping between a Keycloak user name and the string used inside
 * Kubernetes resource names (PVC, PV, ConfigMap) and NFS directory names.
 *
 * The previous mapping replaced every character outside [a-z0-9-] with "-", so the
 * user names "a.b", "a_b" and "a-b" all became "a-b" and two users could share one
 * PVC. This mapping keeps [a-z0-9] as they are and escapes every other UTF-16 unit,
 * including "-", as "-u" followed by its code in four hexadecimal digits. Because
 * "-" never appears unescaped, the escape sequences cannot be confused with
 * literal characters, and {@link #decode} restores the original name.
 *
 * The user name is lowercased first. Keycloak treats user names case-insensitively,
 * so lowercasing never merges two distinct users.
 *
 * The key is also used as the value of the label "user" on Pods, Services, PVs,
 * PVCs and ConfigMaps. A label value must start and end with an alphanumeric and be
 * at most 63 characters, so a user name whose first character is not [a-z0-9], or whose
 * key is longer than 63 characters, is rejected here with a message instead of failing
 * later inside the Kubernetes API server.
 *
 * Examples: "alice" -> "alice", "a.b" -> "a-u002eb", "a-b" -> "a-u002db".
 */
public final class UserKey {

    private UserKey() {}

    /** Returns the key for the user name; throws if the name is empty, starts with a symbol, or is too long. */
    public static String encode(String userName) {
        if (userName == null || userName.isEmpty()) {
            throw new IllegalArgumentException("user name must not be empty");
        }
        String lower = userName.toLowerCase();
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                sb.append("-u").append(String.format("%04x", (int) c));
            }
        }
        String key = sb.toString();
        if (key.charAt(0) == '-') {
            throw new IllegalArgumentException(
                "user name must start with a letter or a digit: " + userName);
        }
        if (key.length() > 63) {
            throw new IllegalArgumentException(
                "user name is too long for a Kubernetes label (key " + key.length() + " > 63): " + userName);
        }
        return key;
    }

    /** Restores the (lowercased) user name from a key produced by {@link #encode}. */
    public static String decode(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        int i = 0;
        while (i < key.length()) {
            char c = key.charAt(i);
            if (c == '-') {
                if (i + 6 > key.length() || key.charAt(i + 1) != 'u') {
                    throw new IllegalArgumentException("malformed user key: " + key);
                }
                sb.append((char) Integer.parseInt(key.substring(i + 2, i + 6), 16));
                i += 6;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
