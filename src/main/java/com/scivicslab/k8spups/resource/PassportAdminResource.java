package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.dataset.DatasetService;
import com.scivicslab.k8spups.dataset.DatasetPath;
import com.scivicslab.k8spups.passport.PassportGrants;
import com.scivicslab.k8spups.security.KeycloakUserDirectory;
import com.scivicslab.k8spups.security.KeycloakUsers;

import io.quarkus.qute.Template;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Administrator page for the Passport: the settings that decide how Visas are accepted, and, for a user the administrator
 * chooses, why each Visa the user presented was accepted or discarded. The decisions are the last ones made since the
 * controller started (the Visas come with the login of the user). The Visas themselves are not shown, only what they say
 * about themselves (dataset, authority, source, algorithm, issuer).
 */
@Path("/passport")
@Blocking
@Authenticated
public class PassportAdminResource {

    private static final Logger LOG = Logger.getLogger(PassportAdminResource.class.getName());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    @Inject
    K8sPupsActorSystem actorSystem;

    @Inject
    AdminAccess access;

    @Inject
    KeycloakUserDirectory directory;

    @Inject
    Template passport;

    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/pups")
    String basePath;

    /** One row of the settings table: the name shown, the value, and the variable of the manifest that sets it. */
    public record SettingRow(String name, String value, String variable) {}

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response page(@QueryParam("user") String user) {
        Response denied = access.denyUnlessAdmin("Passport");
        if (denied != null) return denied;
        Map<String, Object> data = new HashMap<>();
        data.put("basePath", basePath);
        var description = datasets(DatasetService::passportDescription);
        data.put("enabled", description.isPresent());
        if (description.isPresent()) {
            PassportGrants.Description d = description.get();
            data.put("settings", List.of(
                new SettingRow("Where Visas come from", String.join(", ", new TreeSet<>(d.sources())), "K8SPUPS_PASSPORT_SOURCES"),
                new SettingRow("Trusted issuer", d.issuer(), "K8SPUPS_PASSPORT_TRUSTED_ISSUER"),
                new SettingRow("Key set (JWKS) of the issuer", d.jwksUri(), "K8SPUPS_PASSPORT_JWKS_URI"),
                new SettingRow("Dataset URL prefix", d.datasetPrefix(), "K8SPUPS_PASSPORT_DATASET_PREFIX"),
                new SettingRow("Accepted authorities (by)", String.join(", ", new TreeSet<>(d.acceptedBy())), "K8SPUPS_PASSPORT_ACCEPTED_BY"),
                new SettingRow("Trusted sources", d.trustedSources().isEmpty() ? "any" : String.join(", ", new TreeSet<>(d.trustedSources())),
                    "K8SPUPS_PASSPORT_TRUSTED_SOURCES"),
                new SettingRow("Passport URL (source issuer)", d.passportUrl().isEmpty() ? "not used" : d.passportUrl(), "K8SPUPS_PASSPORT_URL"),
                new SettingRow("S3 access for the datasets Visas grant", datasets(DatasetService::s3Enabled) ? "on" : "off", "K8SPUPS_PASSPORT_S3_ENABLED")));
        }
        String chosen = user == null ? "" : user.trim();
        data.put("user", chosen);
        data.put("userNames", userNames());
        data.put("hasDiagnosis", false);
        if (!chosen.isEmpty() && description.isPresent()) {
            if (!DatasetPath.isValidUserName(chosen)) {
                data.put("invalidUser", true);
            } else {
                var diagnosis = datasets(d -> d.passportDiagnosis(chosen));
                data.put("hasDiagnosis", diagnosis.isPresent());
                diagnosis.ifPresent(g -> {
                    data.put("how", g.how());
                    data.put("at", TIME.format(Instant.ofEpochSecond(g.at())));
                    data.put("decisions", g.decisions());
                    data.put("granted", g.datasets().isEmpty() ? "none" : String.join(", ", new TreeSet<>(g.datasets())));
                });
            }
        }
        return Response.ok(passport.data(data).render()).build();
    }

    /** The user names of the Keycloak realm, to choose a user from; empty when Keycloak cannot be read. */
    private List<String> userNames() {
        if (!directory.configured()) return List.of();
        try {
            return directory.all().stream().map(KeycloakUsers.User::username).toList();
        } catch (Exception e) {
            LOG.warning("The Keycloak user list could not be read: " + e.getMessage());
            return List.of();
        }
    }

    private <T> T datasets(java.util.function.Function<DatasetService, T> operation) {
        try {
            return actorSystem.getDatasetManager().ask(operation::apply).get();
        } catch (Exception e) {
            throw new IllegalStateException("Dataset operation failed: " + e.getMessage(), e);
        }
    }
}
