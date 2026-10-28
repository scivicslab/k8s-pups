package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.security.SignInMethods;

import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.qute.Template;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * The user's own sign-in methods: the authenticator app (TOTP) and passkeys. The list is read from the Keycloak account
 * REST API with the user's own access token, so the portal holds no Keycloak administrator credentials and can see
 * nobody else's. Registering and deleting are done by Keycloak (see {@link SignInMethods}).
 */
@Path("/security")
@Blocking
@Authenticated
public class SecurityResource {

    private static final Logger LOG = Logger.getLogger(SecurityResource.class.getName());

    // HTTP/1.1 only: an h2c upgrade attempt breaks some servers.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Inject
    AccessTokenCredential accessToken;

    @Inject
    Template security;

    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/pups")
    String basePath;

    // Realm URL inside the cluster, for server-to-server requests.
    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    String realmUrl;

    @ConfigProperty(name = "quarkus.oidc.client-id")
    String clientId;

    // Browser-facing authorization endpoint and base URL; the browser is sent there and comes back here.
    @ConfigProperty(name = "k8spups.session-oidc.authorization-endpoint")
    String authorizationEndpoint;

    @ConfigProperty(name = "k8spups.session-oidc.redirect-base-url")
    String redirectBaseUrl;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String page() {
        Map<String, Object> data = new HashMap<>();
        data.put("basePath", basePath);
        try {
            data.put("methods", load().methods());
            data.put("error", null);
        } catch (Exception e) {
            LOG.warning("Sign-in methods could not be read: " + e.getMessage());
            data.put("methods", java.util.List.of());
            data.put("error", "The sign-in methods could not be read from Keycloak. Try again later.");
        }
        return security.data(data).render();
    }

    /** Sends the browser to Keycloak to register a method of this type. */
    @GET
    @Path("/add")
    public Response add(@QueryParam("type") String type) throws Exception {
        return toKeycloak(type == null ? Optional.empty() : load().createAction(type));
    }

    /** Sends the browser to Keycloak, which asks the user to confirm and deletes the credential. */
    @GET
    @Path("/delete")
    public Response delete(@QueryParam("id") String id) throws Exception {
        return toKeycloak(id == null ? Optional.empty() : load().deleteAction(id));
    }

    // The action is taken from the user's own list, never from the request, so a crafted request can neither name an
    // arbitrary required action nor another user's credential.
    private Response toKeycloak(Optional<String> action) {
        if (action.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("No such sign-in method").build();
        }
        String back = redirectBaseUrl + basePath + "/security";
        return Response.seeOther(URI.create(SignInMethods.actionUrl(authorizationEndpoint, clientId, back, action.get())))
                .build();
    }

    private SignInMethods load() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(realmUrl + "/account/credentials"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + accessToken.getToken())
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("account/credentials returned HTTP " + response.statusCode());
        }
        return SignInMethods.parse(response.body());
    }
}
