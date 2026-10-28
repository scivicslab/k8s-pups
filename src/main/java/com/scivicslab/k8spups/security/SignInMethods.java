package com.scivicslab.k8spups.security;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The sign-in methods of one user as the Keycloak account REST API reports them ({@code GET {realm}/account/credentials}),
 * and the Keycloak addresses that change them.
 *
 * The portal changes no credential itself. Registering a passkey has to happen on a Keycloak page, because the browser
 * binds a passkey to the site that checks it later, which is Keycloak; deleting is done there too, so that Keycloak
 * asks the user to confirm. The portal sends the browser to Keycloak's authorization endpoint with {@code kc_action}
 * (an application-initiated action), and Keycloak returns to the portal when the action is done.
 *
 * Only the methods the login flow asks for are listed: the authenticator app as the second factor, and the passkey,
 * which signs in alone. The password belongs to the LDAP directory and is not changed here; a security key as a
 * second factor ("webauthn") is offered by Keycloak but never asked for, so registering one would mislead the user.
 */
public final class SignInMethods {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final Map<String, String> NAMES = Map.of(
            "otp", "Authenticator app (TOTP)",
            "webauthn-passwordless", "Passkey");

    /** One registered credential. */
    public record Credential(String id, String label, String created) {}

    /**
     * One kind of sign-in method. {@code createAction} is the Keycloak required action that registers one, or null when
     * the user cannot add one; {@code removable} tells whether the user may delete one.
     */
    public record Method(String type, String name, String createAction, boolean removable, List<Credential> credentials) {}

    private final List<Method> methods;

    private SignInMethods(List<Method> methods) {
        this.methods = methods;
    }

    public List<Method> methods() {
        return methods;
    }

    /** Reads the body of {@code GET {realm}/account/credentials}. */
    public static SignInMethods parse(String json) throws java.io.IOException {
        List<Method> out = new ArrayList<>();
        for (JsonNode m : JSON.readTree(json)) {
            String type = m.path("type").asText();
            if (!NAMES.containsKey(type)) continue;
            List<Credential> creds = new ArrayList<>();
            for (JsonNode meta : m.path("userCredentialMetadatas")) {
                JsonNode c = meta.path("credential");
                String label = c.path("userLabel").asText("");
                long created = c.path("createdDate").asLong(0);
                creds.add(new Credential(c.path("id").asText(), label.isEmpty() ? "(no name)" : label,
                        created > 0 ? DAY.format(Instant.ofEpochMilli(created)) : ""));
            }
            String create = m.hasNonNull("createAction") ? m.get("createAction").asText() : null;
            out.add(new Method(type, NAMES.get(type), create, m.path("removeable").asBoolean(false), creds));
        }
        return new SignInMethods(out);
    }

    /** The required action that registers a method of this type, if the user may register one. */
    public Optional<String> createAction(String type) {
        return methods.stream().filter(m -> m.type().equals(type) && m.createAction() != null)
                .map(Method::createAction).findFirst();
    }

    /** The required action that deletes this credential, if it is one of the user's and may be deleted. */
    public Optional<String> deleteAction(String credentialId) {
        return methods.stream().filter(Method::removable)
                .flatMap(m -> m.credentials().stream())
                .filter(c -> c.id().equals(credentialId))
                .map(c -> "delete_credential:" + c.id()).findFirst();
    }

    /**
     * The Keycloak address that runs one required action and then returns to {@code returnUri}. The answer comes back
     * in the fragment ({@code response_mode=fragment}), which the browser does not send to the portal: the portal's
     * OIDC login does not see an authorization code it did not ask for, and the page reads {@code kc_action_status}
     * from the fragment.
     */
    public static String actionUrl(String authorizationEndpoint, String clientId, String returnUri, String action) {
        return authorizationEndpoint
                + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(returnUri)
                + "&response_type=code&response_mode=fragment&scope=openid"
                + "&state=" + UUID.randomUUID()
                + "&kc_action=" + enc(action);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
