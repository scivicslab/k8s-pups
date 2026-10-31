package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.dataset.DatasetPath;
import com.scivicslab.k8spups.dataset.DatasetRepository;
import com.scivicslab.k8spups.dataset.DatasetService;
import com.scivicslab.k8spups.security.KeycloakUserDirectory;
import com.scivicslab.k8spups.security.KeycloakUsers;
import com.scivicslab.k8spups.storage.StorageAllocationService;

import io.quarkus.qute.Template;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Administrator pages organised by user: the list of every account of the Keycloak realm with what the controller gives
 * each of them, and (see {@code UserAdminResource}) one page per user that holds the storage, the groups and the
 * dataset access of that user. The list is read from Keycloak with a client that can only read accounts; when Keycloak
 * cannot be read, the users the database knows are listed instead.
 */
@Path("/users")
@Blocking
@Authenticated
public class UsersAdminResource {

    private static final Logger LOG = Logger.getLogger(UsersAdminResource.class.getName());

    @Inject
    K8sPupsActorSystem actorSystem;

    @Inject
    AdminAccess access;

    @Inject
    KeycloakUserDirectory directory;

    @Inject
    Template users;

    @Inject
    Template userDetail;

    @Inject
    UserStoragePanel storagePanel;

    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/pups")
    String basePath;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response list(@QueryParam("q") String q) {
        Response denied = access.denyUnlessAdmin("Users");
        if (denied != null) return denied;
        String filter = q == null ? "" : q.trim();

        List<KeycloakUsers.User> keycloak = null;
        String error = null;
        if (!directory.configured()) {
            error = "No Keycloak reader client is configured: only the users known to the database are listed.";
        } else {
            try {
                keycloak = directory.all();
            } catch (Exception e) {
                LOG.warning("The Keycloak user list could not be read: " + e.getMessage());
                error = "The user list could not be read from Keycloak: only the users known to the database are listed.";
            }
        }

        TreeSet<String> names = new TreeSet<>(storage(StorageAllocationService::knownUsers));
        var rows = UserRows.build(keycloak, names, filter, storage(StorageAllocationService::logins),
                storage(StorageAllocationService::allActive), storage(StorageAllocationService::allMemberships),
                datasets(DatasetService::grantCounts));

        Map<String, Object> data = new HashMap<>();
        data.put("basePath", basePath);
        data.put("rows", rows);
        data.put("q", filter);
        data.put("error", error);
        return Response.ok(users.data(data).render()).build();
    }

    /** How many names a pick list offers. A catalog can hold more; the server checks every name it receives. */
    private static final int CANDIDATES = 1000;
    private static final int PAGE_SIZE = 50;

    /**
     * One user: the storage, the groups and the dataset access of that user on one page. Each change is a POST under
     * this page (below) and returns here.
     */
    @GET
    @Path("/u/{user}")
    @Produces(MediaType.TEXT_HTML)
    public Response user(@PathParam("user") String user, @QueryParam("error") String error, @QueryParam("done") String done,
                         @QueryParam("msg") String msg, @QueryParam("page") int page, @QueryParam("fileset") String fileset) {
        Response denied = access.denyUnlessAdmin("Users");
        if (denied != null) return denied;
        if (!DatasetPath.isValidUserName(user)) return Response.status(Response.Status.NOT_FOUND).entity("No such user").build();

        // Names, mail addresses and access of a person are shown: opening the page is recorded. The page that follows a
        // change (it carries the message of the change) is the same view again and is not recorded a second time.
        if (error == null && done == null && msg == null) {
            String admin = access.user();
            datasets(d -> { d.auditAdminChange("user.view", admin, user, ""); return null; });
        }
        Map<String, Object> data = new HashMap<>(storagePanel.data(user));
        data.put("basePath", basePath);
        data.put("user", user);
        data.put("error", error);
        data.put("done", done);
        data.put("msg", msg);

        // The account of Keycloak (name, mail, enabled), when the reader client can be used.
        KeycloakUsers.User account = null;
        boolean accountKnown = false;
        if (directory.configured()) {
            try {
                account = directory.find(user).orElse(null);
                accountKnown = true;
            } catch (Exception e) {
                LOG.warning("The Keycloak account of " + user + " could not be read: " + e.getMessage());
            }
        }
        data.put("accountKnown", accountKnown);
        data.put("account", account);
        data.put("notInKeycloak", accountKnown && account == null);

        // Groups: the user's memberships, and the groups an administrator can add the user to.
        var memberships = storage(s -> s.memberships(user));
        List<Map<String, String>> memberRows = new ArrayList<>();
        memberships.forEach((group, mode) -> memberRows.add(Map.of("name", group, "mode", mode)));
        List<String> groupNames = new ArrayList<>();
        for (var g : storage(StorageAllocationService::groups)) if (g.active()) groupNames.add(g.name());
        data.put("memberships", memberRows);
        data.put("groupNames", groupNames);

        // Datasets: whole datasets, single files, and the names to pick from.
        boolean available = datasets(DatasetService::isAvailable);
        data.put("datasetsAvailable", available);
        if (available) {
            var exceptions = datasets(d -> d.exceptionsOfUser(user, Math.max(page, 0), PAGE_SIZE));
            data.put("access", datasets(d -> d.accessOfUser(user)));
            data.put("result", exceptions);
            data.put("exceptions", exceptions.items());
            data.put("prev", exceptions.page() - 1);
            data.put("next", exceptions.page() + 1);
            List<String> candidates = new ArrayList<>();
            for (DatasetRepository.DatasetRow row : datasets(d -> d.datasets("", 0, CANDIDATES)).items()) candidates.add(row.name());
            data.put("datasetNames", candidates);
            String chosen = fileset != null && DatasetPath.isValidRelativePath(fileset) && !fileset.contains("/") ? fileset : "";
            data.put("fileset", chosen);
            data.put("fileNames", chosen.isEmpty() ? List.<String>of() : datasets(d -> d.files(chosen, "", 0, CANDIDATES)).items());
        }
        return Response.ok(userDetail.data(data).render()).build();
    }

    // ── changes of one user ────────────────────────────────────────────────────
    // Every change of the storage, the groups and the dataset access of a user is a POST under /users/u/{user}; each answers
    // with a redirect back to the page of the user. The address of the redirect is built here from the checked user name.

    @POST
    @Path("/u/{user}/storage/allocate")
    public Response allocate(@PathParam("user") String user, @FormParam("type") String type, @FormParam("size") String size) {
        Response denied = checked(user);
        if (denied != null) return denied;
        String admin = access.user(), sz = size == null ? null : size.trim();
        return backWithResult(user, storage(s -> s.allocate(admin, user, type, sz)), "Storage given");
    }

    @POST
    @Path("/u/{user}/storage/{id}/resize")
    public Response resize(@PathParam("user") String user, @PathParam("id") long id, @FormParam("size") String size) {
        Response denied = checked(user);
        if (denied != null) return denied;
        String admin = access.user(), sz = size == null ? null : size.trim();
        return backWithResult(user, storage(s -> s.resize(admin, id, sz)), "Storage made larger");
    }

    @POST
    @Path("/u/{user}/storage/{id}/revoke")
    public Response revoke(@PathParam("user") String user, @PathParam("id") long id) {
        Response denied = checked(user);
        if (denied != null) return denied;
        String admin = access.user();
        return backWithResult(user, storage(s -> s.revoke(admin, id)), "Storage taken away");
    }

    @POST
    @Path("/u/{user}/storage/{id}/restore")
    public Response restore(@PathParam("user") String user, @PathParam("id") long id) {
        Response denied = checked(user);
        if (denied != null) return denied;
        String admin = access.user();
        return backWithResult(user, storage(s -> s.restore(admin, id)), "Storage given back");
    }

    /** Adds the user to a group, or changes the mode of the user in it. */
    @POST
    @Path("/u/{user}/group/{name}")
    public Response putMember(@PathParam("user") String user, @PathParam("name") String name, @FormParam("mode") String mode) {
        Response denied = checked(user);
        if (denied != null) return denied;
        String admin = access.user();
        String error = storage(x -> x.putMember(admin, name, user, mode));
        enforceGroupAccess();   // a member made read-only loses a read-write mount
        return backWithResult(user, error, "Member saved");
    }

    @POST
    @Path("/u/{user}/group/{name}/remove")
    public Response removeMember(@PathParam("user") String user, @PathParam("name") String name) {
        Response denied = checked(user);
        if (denied != null) return denied;
        String admin = access.user();
        String error = storage(x -> x.removeMember(admin, name, user));
        enforceGroupAccess();
        return backWithResult(user, error, "Member removed; their tools that mounted this storage are stopped");
    }

    @POST
    @Path("/u/{user}/dataset/grant")
    public Response grantDataset(@PathParam("user") String user, @FormParam("dataset") String dataset,
                                 @FormParam("expiresOn") String expiresOn) {
        Response denied = checked(user);
        if (denied != null) return denied;
        if (dataset == null || !DatasetPath.isValidRelativePath(dataset) || dataset.contains("/")
                || !datasets(d -> d.datasetExists(dataset))) return backWithMessage(user, "no such dataset");
        Instant expires;
        try { expires = lastDay(expiresOn); } catch (Exception e) { return backWithMessage(user, "invalid date"); }
        String admin = access.user();
        datasets(d -> d.grantDataset(user, dataset, expires, admin));
        LOG.info("Dataset access granted by " + admin + ": user=" + user + " dataset=" + dataset + " expires=" + expires);
        return backWithMessage(user, "granted " + dataset);
    }

    @POST
    @Path("/u/{user}/dataset/revoke")
    public Response revokeDataset(@PathParam("user") String user, @FormParam("dataset") String dataset) {
        Response denied = checked(user);
        if (denied != null) return denied;
        String admin = access.user();
        datasets(d -> d.revokeDataset(user, dataset, admin));
        enforceDatasetAccess();
        LOG.info("Dataset access revoked by " + admin + ": user=" + user + " dataset=" + dataset);
        return backWithMessage(user, "revoked " + dataset);
    }

    @POST
    @Path("/u/{user}/file/grant")
    public Response grantFile(@PathParam("user") String user, @FormParam("path") String path,
                              @FormParam("expiresOn") String expiresOn) {
        Response denied = checked(user);
        if (denied != null) return denied;
        if (!DatasetPath.isValidRelativePath(path)) return backWithMessage(user, "invalid file path");
        Instant expires;
        try { expires = lastDay(expiresOn); } catch (Exception e) { return backWithMessage(user, "invalid date"); }
        String admin = access.user();
        boolean ok = datasets(d -> d.grantFile(user, path, expires, admin));
        LOG.info("File access granted by " + admin + ": user=" + user + " file=" + path + " expires=" + expires);
        return backWithMessage(user, ok ? "granted " + path : "the file is not in the catalog");
    }

    @POST
    @Path("/u/{user}/file/revoke")
    public Response revokeFile(@PathParam("user") String user, @FormParam("path") String path) {
        Response denied = checked(user);
        if (denied != null) return denied;
        if (!DatasetPath.isValidRelativePath(path)) return backWithMessage(user, "invalid file path");
        String admin = access.user();
        datasets(d -> d.revokeFile(user, path, admin));
        LOG.info("File access revoked by " + admin + ": user=" + user + " file=" + path);
        return backWithMessage(user, "revoked " + path);
    }

    /** Null when the caller is an administrator and the name is a valid user name; otherwise the response to return. */
    private Response checked(String user) {
        Response denied = access.denyUnlessAdmin("Users");
        if (denied != null) return denied;
        if (!DatasetPath.isValidUserName(user)) return Response.status(Response.Status.NOT_FOUND).entity("No such user").build();
        return null;
    }

    /** Back to the page of the user with the message of a storage or group change: an error if there is one, else done. */
    private Response backWithResult(String user, String error, String done) {
        if (error != null) LOG.warning("Change for " + user + " refused: " + error);
        String q = error != null ? "?error=" + enc(error) : "?done=" + enc(done);
        return Response.seeOther(URI.create("/users/u/" + enc(user) + q)).build();
    }

    /** Back to the page of the user with the message of a dataset change. */
    private Response backWithMessage(String user, String msg) {
        return Response.seeOther(URI.create("/users/u/" + enc(user) + "?msg=" + enc(msg))).build();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** The last day of validity as the instant it ends, or null for no expiry. */
    private static Instant lastDay(String expiresOn) {
        if (expiresOn == null || expiresOn.isBlank()) return null;
        return LocalDate.parse(expiresOn.trim()).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /** After access was taken away: stop the sessions that still mount a dataset their user may no longer read. */
    private void enforceDatasetAccess() {
        var sm = actorSystem.getSessionManager();
        sm.tell(m -> m.enforceDatasetAccess(sm));
    }

    /** After a group change: stop the sessions that mount group storage that their user may no longer use that way. */
    private void enforceGroupAccess() {
        var sm = actorSystem.getSessionManager();
        sm.tell(m -> m.enforceStorageAccess(sm));
    }

    private <T> T storage(Function<StorageAllocationService, T> operation) {
        try {
            return actorSystem.getStorageAllocations().ask(operation::apply).get();
        } catch (Exception e) {
            throw new IllegalStateException("Storage allocation failed: " + e.getMessage(), e);
        }
    }

    private <T> T datasets(Function<DatasetService, T> operation) {
        try {
            return actorSystem.getDatasetManager().ask(operation::apply).get();
        } catch (Exception e) {
            throw new IllegalStateException("Dataset operation failed: " + e.getMessage(), e);
        }
    }
}
