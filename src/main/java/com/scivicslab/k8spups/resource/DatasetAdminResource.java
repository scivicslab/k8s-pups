package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.dataset.DatasetPath;
import com.scivicslab.k8spups.dataset.DatasetRepository;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    DatasetRepository repository;

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
        boolean available = repository.isAvailable();
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("basePath", basePath);
        data.put("available", available);
        data.put("error", error);
        data.put("files", available ? repository.listFiles() : List.of());
        data.put("grants", available ? repository.listGrants() : List.of());
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
        repository.addFile(dataset, path);
        LOG.info("Dataset file registered by " + currentUser() + ": " + dataset + " " + path);
        return back(null);
    }

    @POST
    @Path("/file/delete")
    @Authenticated
    public Response deleteFile(@FormParam("fileId") long fileId) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        Set<String> affected = usersOfFile(fileId);
        repository.deleteFile(fileId);
        LOG.info("Dataset file " + fileId + " deleted by " + currentUser());
        return resync(affected);
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
        repository.grant(user, fileId, expiresAt, currentUser());
        LOG.info("Dataset grant by " + currentUser() + ": user=" + user + " file=" + fileId + " expires=" + expiresAt);
        return resync(Set.of(user));
    }

    @POST
    @Path("/grant/revoke")
    @Authenticated
    public Response revoke(@FormParam("user") String user, @FormParam("fileId") long fileId) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        if (!DatasetPath.isValidUserName(user)) return back("invalid_user");
        repository.revoke(user, fileId);
        LOG.info("Dataset grant revoked by " + currentUser() + ": user=" + user + " file=" + fileId);
        return resync(Set.of(user));
    }

    /** Rebuilds the views of the given users; the page is shown again with an error if one fails. */
    private Response resync(Set<String> users) {
        boolean ok = true;
        for (String user : users) {
            ok &= actorSystem.getK8sClient().syncDatasetView(user, repository.activePaths(user));
        }
        return back(ok ? null : "sync_failed");
    }

    private Set<String> usersOfFile(long fileId) {
        Set<String> users = new LinkedHashSet<>();
        for (var g : repository.listGrants()) {
            if (g.fileId() == fileId) users.add(g.user());
        }
        return users;
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
