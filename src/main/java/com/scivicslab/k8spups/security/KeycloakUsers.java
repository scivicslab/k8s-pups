package com.scivicslab.k8spups.security;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The accounts of the realm as the Keycloak admin REST API reports them ({@code GET /admin/realms/{realm}/users}), reduced to
 * the four items the administrator screens show: user name, full name, mail address and whether the account is enabled.
 * Credentials (TOTP, passkeys) are not read here.
 */
public final class KeycloakUsers {

    private static final ObjectMapper JSON = new ObjectMapper();

    private KeycloakUsers() {}

    /** One account of the realm. {@code name} is the first and last name joined, or empty when the account has none. */
    public record User(String username, String name, String email, boolean enabled) {}

    /** Reads the body of {@code GET /admin/realms/{realm}/users}, sorted by user name. */
    public static List<User> parse(String json) throws java.io.IOException {
        List<User> out = new ArrayList<>();
        for (JsonNode u : JSON.readTree(json)) {
            String username = u.path("username").asText("");
            if (username.isEmpty()) continue;
            String name = (u.path("firstName").asText("") + " " + u.path("lastName").asText("")).trim();
            out.add(new User(username, name, u.path("email").asText(""), u.path("enabled").asBoolean(true)));
        }
        out.sort(Comparator.comparing(User::username));
        return out;
    }

    /** The users whose user name, full name or mail address contains the text, ignoring case. All users when the text is empty. */
    public static List<User> filter(List<User> users, String text) {
        String q = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return users;
        return users.stream().filter(u -> u.username().toLowerCase(Locale.ROOT).contains(q)
                || u.name().toLowerCase(Locale.ROOT).contains(q)
                || u.email().toLowerCase(Locale.ROOT).contains(q)).toList();
    }

    /**
     * The address of one page of the user list. {@code realmUrl} is the realm address of the OpenID Connect settings
     * ({@code http://host/auth/realms/name}); the admin API of the same realm is {@code http://host/auth/admin/realms/name}.
     * The brief representation carries the four items the screens show (and the id); the full one adds the credential
     * types, the required actions and the attributes, which can hold Visas.
     */
    public static String pageUrl(String realmUrl, int first, int max) {
        return usersUrl(realmUrl) + "?first=" + first + "&max=" + max + "&briefRepresentation=true";
    }

    /** The address that finds the account with exactly this user name. */
    public static String exactUrl(String realmUrl, String username) {
        return usersUrl(realmUrl) + "?exact=true&username=" + enc(username);
    }

    private static String usersUrl(String realmUrl) {
        int at = realmUrl.lastIndexOf("/realms/");
        if (at < 0) throw new IllegalArgumentException("not a realm address: " + realmUrl);
        return realmUrl.substring(0, at) + "/admin" + realmUrl.substring(at) + "/users";
    }

    /** The address that exchanges the client credentials of the reader client for an access token. */
    public static String tokenUrl(String realmUrl) {
        return realmUrl + "/protocol/openid-connect/token";
    }

    /** The body of the client-credentials token request. */
    public static String tokenForm(String clientId, String clientSecret) {
        return "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
