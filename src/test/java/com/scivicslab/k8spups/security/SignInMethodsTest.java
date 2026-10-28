package com.scivicslab.k8spups.security;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SignInMethodsTest {

    // The shape of Keycloak's GET {realm}/account/credentials.
    static final String BODY = """
        [
          {"type":"password","category":"basic-authentication","displayName":"password-display-name",
           "updateAction":"UPDATE_PASSWORD","removeable":false,
           "userCredentialMetadatas":[{"credential":{"id":"pw-1","type":"password","createdDate":1761600000000}}]},
          {"type":"otp","category":"two-factor","displayName":"otp-display-name",
           "createAction":"CONFIGURE_TOTP","removeable":true,
           "userCredentialMetadatas":[{"credential":{"id":"otp-1","type":"otp","userLabel":"phone","createdDate":1761600000000}}]},
          {"type":"webauthn","category":"two-factor","displayName":"webauthn-display-name",
           "createAction":"webauthn-register","removeable":true,"userCredentialMetadatas":[]},
          {"type":"webauthn-passwordless","category":"passwordless","displayName":"webauthn-passwordless-display-name",
           "createAction":"webauthn-register-passwordless","removeable":true,"userCredentialMetadatas":[]}
        ]
        """;

    @Test
    void listsTheAuthenticatorAppAndPasskeysOnly() throws Exception {
        SignInMethods m = SignInMethods.parse(BODY);
        assertEquals(2, m.methods().size());
        assertEquals("Authenticator app (TOTP)", m.methods().get(0).name());
        assertEquals("Passkey", m.methods().get(1).name());
        SignInMethods.Credential otp = m.methods().get(0).credentials().get(0);
        assertEquals("phone", otp.label());
        assertEquals("2025-10-27", otp.created());
    }

    @Test
    void createActionOnlyForListedTypes() throws Exception {
        SignInMethods m = SignInMethods.parse(BODY);
        assertEquals(Optional.of("webauthn-register-passwordless"), m.createAction("webauthn-passwordless"));
        assertEquals(Optional.empty(), m.createAction("password"));
        assertEquals(Optional.empty(), m.createAction("webauthn"));   // never asked for by the login flow
        assertEquals(Optional.empty(), m.createAction("anything"));
    }

    @Test
    void deleteActionOnlyForTheUsersOwnRemovableCredentials() throws Exception {
        SignInMethods m = SignInMethods.parse(BODY);
        assertEquals(Optional.of("delete_credential:otp-1"), m.deleteAction("otp-1"));
        assertEquals(Optional.empty(), m.deleteAction("pw-1"));          // the password is not listed
        assertEquals(Optional.empty(), m.deleteAction("someone-else"));  // not one of the user's
    }

    @Test
    void actionUrlCarriesTheActionAndReturnsInTheFragment() {
        String url = SignInMethods.actionUrl("https://kc.example/realms/r/protocol/openid-connect/auth", "k8s-pups",
                "https://portal.example/pups/security", "delete_credential:otp-1");
        Map<String, String> q = new HashMap<>();
        for (String kv : URI.create(url).getRawQuery().split("&")) {
            String[] p = kv.split("=", 2);
            q.put(p[0], URLDecoder.decode(p[1], StandardCharsets.UTF_8));
        }
        assertTrue(url.startsWith("https://kc.example/realms/r/protocol/openid-connect/auth?"));
        assertEquals("k8s-pups", q.get("client_id"));
        assertEquals("https://portal.example/pups/security", q.get("redirect_uri"));
        assertEquals("fragment", q.get("response_mode"));
        assertEquals("delete_credential:otp-1", q.get("kc_action"));
        assertNotNull(q.get("state"));
    }
}
