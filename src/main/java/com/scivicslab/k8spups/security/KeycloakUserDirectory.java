package com.scivicslab.k8spups.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Reads the list of users of the realm from the Keycloak admin REST API with the client {@code k8s-pups-users-reader}.
 * That client's service account has the single role {@code view-users}, so this class can read accounts and nothing else.
 * It is used only by the administrator screens; the secret of the client is read from a file mounted from a Kubernetes
 * Secret and is never shown.
 */
@ApplicationScoped
public class KeycloakUserDirectory {

    private static final int PAGE = 200;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // Realm URL inside the cluster, for server-to-server requests.
    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    String realmUrl;

    @ConfigProperty(name = "k8spups.users-reader.client-id", defaultValue = "k8s-pups-users-reader")
    String clientId;

    /** The file that holds the client secret; empty when no reader client is configured. */
    @ConfigProperty(name = "k8spups.users-reader.secret-file", defaultValue = "")
    String secretFile;

    /** Whether a reader client is configured. When it is not, the administrator screens show the users of the database only. */
    public boolean configured() {
        return !secretFile.isBlank() && Files.isReadable(Path.of(secretFile));
    }

    /** All accounts of the realm, sorted by user name. Throws when Keycloak cannot be reached or refuses the client. */
    public List<KeycloakUsers.User> all() throws Exception {
        String token = accessToken();
        List<KeycloakUsers.User> users = new ArrayList<>();
        for (int first = 0; ; first += PAGE) {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(KeycloakUsers.pageUrl(realmUrl, first, PAGE)))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new IllegalStateException("the user list returned HTTP " + r.statusCode());
            List<KeycloakUsers.User> page = KeycloakUsers.parse(r.body());
            users.addAll(page);
            if (JSON.readTree(r.body()).size() < PAGE) break;
        }
        users.sort(java.util.Comparator.comparing(KeycloakUsers.User::username));
        return users;
    }

    /** The account with exactly this user name, or empty when the realm has none. */
    public java.util.Optional<KeycloakUsers.User> find(String username) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(KeycloakUsers.exactUrl(realmUrl, username)))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken())
                .header("Accept", "application/json")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("the user lookup returned HTTP " + r.statusCode());
        return KeycloakUsers.parse(r.body()).stream().filter(u -> u.username().equals(username)).findFirst();
    }

    private String accessToken() throws Exception {
        String secret = Files.readString(Path.of(secretFile)).trim();
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(KeycloakUsers.tokenUrl(realmUrl)))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(KeycloakUsers.tokenForm(clientId, secret))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("the reader client was refused: HTTP " + r.statusCode());
        return JSON.readTree(r.body()).path("access_token").asText();
    }
}
