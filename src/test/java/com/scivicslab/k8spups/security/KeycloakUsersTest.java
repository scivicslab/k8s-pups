package com.scivicslab.k8spups.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class KeycloakUsersTest {

    // The shape of Keycloak's GET /admin/realms/{realm}/users (only the items the screens use are kept).
    static final String BODY = """
        [
          {"id":"2","username":"testuser2","firstName":"Test","lastName":"User Two","email":"testuser2@nig.ac.jp","enabled":true},
          {"id":"1","username":"e2eadmin","email":"e2eadmin@nig.ac.jp","enabled":true},
          {"id":"3","username":"gone","firstName":"Old","enabled":false},
          {"id":"4","firstName":"No","lastName":"Username"}
        ]
        """;

    @Test
    void readsTheFourItemsAndSortsByUserName() throws Exception {
        List<KeycloakUsers.User> users = KeycloakUsers.parse(BODY);
        assertEquals(List.of("e2eadmin", "gone", "testuser2"), users.stream().map(KeycloakUsers.User::username).toList());
        KeycloakUsers.User t = users.get(2);
        assertEquals("Test User Two", t.name());
        assertEquals("testuser2@nig.ac.jp", t.email());
        assertTrue(t.enabled());
    }

    @Test
    void anAccountWithoutNameOrMailHasEmptyTextAndADisabledAccountIsKept() throws Exception {
        List<KeycloakUsers.User> users = KeycloakUsers.parse(BODY);
        assertEquals("", users.get(0).name());
        assertEquals("Old", users.get(1).name());
        assertFalse(users.get(1).enabled());
        assertEquals("", users.get(1).email());
    }

    @Test
    void filtersByUserNameFullNameOrMailIgnoringCase() throws Exception {
        List<KeycloakUsers.User> users = KeycloakUsers.parse(BODY);
        assertEquals(3, KeycloakUsers.filter(users, "").size());
        assertEquals(3, KeycloakUsers.filter(users, null).size());
        assertEquals(List.of("testuser2"), KeycloakUsers.filter(users, "USER TWO").stream().map(KeycloakUsers.User::username).toList());
        assertEquals(List.of("e2eadmin"), KeycloakUsers.filter(users, "e2eadmin@").stream().map(KeycloakUsers.User::username).toList());
        assertTrue(KeycloakUsers.filter(users, "nobody").isEmpty());
    }

    @Test
    void theAdminApiAddressIsDerivedFromTheRealmAddress() {
        String realm = "http://keycloak.keycloak-with-audit.svc:8080/pups-with-audit-auth/realms/pups-with-audit";
        assertEquals("http://keycloak.keycloak-with-audit.svc:8080/pups-with-audit-auth/admin/realms/pups-with-audit/users"
                + "?first=200&max=200&briefRepresentation=true", KeycloakUsers.pageUrl(realm, 200, 200));
        assertEquals(realm + "/protocol/openid-connect/token", KeycloakUsers.tokenUrl(realm));
        assertThrows(IllegalArgumentException.class, () -> KeycloakUsers.pageUrl("http://host/nothing", 0, 10));
    }

    @Test
    void theExactLookupAddressEncodesTheUserName() {
        String realm = "http://kc:8080/auth/realms/r";
        assertEquals("http://kc:8080/auth/admin/realms/r/users?exact=true&username=a%2Bb", KeycloakUsers.exactUrl(realm, "a+b"));
    }

    @Test
    void theTokenFormEncodesTheSecret() {
        assertEquals("grant_type=client_credentials&client_id=k8s-pups-users-reader&client_secret=a%2Bb%26c",
                KeycloakUsers.tokenForm("k8s-pups-users-reader", "a+b&c"));
    }
}
