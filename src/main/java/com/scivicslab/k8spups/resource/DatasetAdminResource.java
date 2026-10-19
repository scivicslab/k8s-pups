package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.dataset.DatasetPath;
import com.scivicslab.k8spups.dataset.DatasetService;
import com.scivicslab.k8spups.tool.JwtRoleExtractor;

import io.quarkus.oidc.IdToken;
import io.quarkus.qute.Template;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Administrator page for the dataset entitlement list: which files exist and which user may read
 * which file. Every change rebuilds the affected users' view directories at once, so a revoked
 * file disappears from running tools without waiting for the next session.
 */
@Path("/datasets")
@Blocking
public class DatasetAdminResource {

    private static final Logger LOG = Logger.getLogger(DatasetAdminResource.class.getName());

    @Inject
    K8sPupsActorSystem actorSystem;

    @Inject
    @IdToken
    JsonWebToken idToken;

    @Inject
    SecurityIdentity identity;

    @Inject
    Template datasets;

    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/pups")
    String basePath;

    @GET
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public Response page(@QueryParam("error") String error) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("basePath", basePath);
        data.put("available", call(DatasetService::isAvailable));
        data.put("error", error);
        data.put("files", call(DatasetService::listFiles));
        data.put("grants", call(DatasetService::listGrants));
        return Response.ok(datasets.data(data).render()).build();
    }

    @POST
    @Path("/file")
    @Authenticated
    public Response addFile(@FormParam("dataset") String dataset, @FormParam("path") String path) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        if (!DatasetPath.isValidRelativePath(dataset) || !DatasetPath.isValidRelativePath(path)) {
            return back("invalid_path");
        }
        call(d -> { d.addFile(dataset, path); return null; });
        LOG.info("Dataset file registered by " + currentUser() + ": " + dataset + " " + path);
        return back(null);
    }

    @POST
    @Path("/file/delete")
    @Authenticated
    public Response deleteFile(@FormParam("fileId") long fileId) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        boolean ok = call(d -> d.deleteFile(fileId));
        LOG.info("Dataset file " + fileId + " deleted by " + currentUser());
        return back(ok ? null : "sync_failed");
    }

    @POST
    @Path("/grant")
    @Authenticated
    public Response grant(@FormParam("user") String user, @FormParam("fileId") long fileId,
                          @FormParam("expiresOn") String expiresOn) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        if (!DatasetPath.isValidUserName(user)) return back("invalid_user");
        Instant expiresAt = null;
        if (expiresOn != null && !expiresOn.isBlank()) {
            try {
                // Valid through the end of the given day (UTC).
                expiresAt = LocalDate.parse(expiresOn.trim()).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (Exception e) {
                return back("invalid_date");
            }
        }
        Instant expires = expiresAt;
        String admin = currentUser();
        boolean ok = call(d -> d.grant(user, fileId, expires, admin));
        LOG.info("Dataset grant by " + admin + ": user=" + user + " file=" + fileId + " expires=" + expires);
        return back(ok ? null : "sync_failed");
    }

    @POST
    @Path("/grant/revoke")
    @Authenticated
    public Response revoke(@FormParam("user") String user, @FormParam("fileId") long fileId) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        if (!DatasetPath.isValidUserName(user)) return back("invalid_user");
        boolean ok = call(d -> d.revoke(user, fileId));
        LOG.info("Dataset grant revoked by " + currentUser() + ": user=" + user + " file=" + fileId);
        return back(ok ? null : "sync_failed");
    }

    /** Runs an operation on the dataset-manager actor and waits for its result. */
    private <T> T call(Function<DatasetService, T> operation) {
        try {
            return actorSystem.getDatasetManager().ask(operation::apply).get();
        } catch (Exception e) {
            throw new IllegalStateException("Dataset operation failed: " + e.getMessage(), e);
        }
    }

    private Response back(String error) {
        return Response.seeOther(URI.create("/datasets" + (error == null ? "" : "?error=" + error))).build();
    }

    /** Checked on every request, not only hidden in the page: a request without the link is still a request. */
    private Response requireAdmin() {
        if (!actorSystem.isAdmin(currentUser(), roles())) {
            LOG.warning("Dataset administration refused for user " + currentUser());
            return Response.status(Response.Status.FORBIDDEN).entity("Administrators only").build();
        }
        return null;
    }

    private String currentUser() {
        try {
            if (idToken != null) {
                String username = idToken.getClaim("preferred_username");
                if (username != null) return username;
            }
        } catch (Exception e) {
            // Fall back to the security identity.
        }
        return identity != null && !identity.isAnonymous() ? identity.getPrincipal().getName() : null;
    }

    private List<String> roles() {
        try {
            if (idToken == null) return List.of();
            Map<String, Object> realmAccess = idToken.getClaim("realm_access");
            Map<String, Object> resourceAccess = idToken.getClaim("resource_access");
            return JwtRoleExtractor.extractRoles(realmAccess, resourceAccess);
        } catch (Exception e) {
            return List.of();
        }
    }
}
