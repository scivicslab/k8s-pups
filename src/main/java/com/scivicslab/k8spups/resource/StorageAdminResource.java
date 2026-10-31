package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.security.KeycloakUserDirectory;
import com.scivicslab.k8spups.security.KeycloakUsers;
import com.scivicslab.k8spups.storage.StorageAllocationService;
import com.scivicslab.pojoactor.core.ActorRef;

import io.quarkus.qute.Template;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Administrator pages for the storage shared by a group: the list of groups, and one page per group to enlarge, take
 * away and give back its storage and to choose its members. The storage of one user is given on the page of that user
 * (see {@link UsersAdminResource}); users cannot create storage themselves.
 */
@Path("/storage-admin")
@Blocking
@Authenticated
public class StorageAdminResource {

    private static final Logger LOG = Logger.getLogger(StorageAdminResource.class.getName());

    @Inject
    K8sPupsActorSystem actorSystem;

    @Inject
    AdminAccess access;

    @Inject
    KeycloakUserDirectory directory;

    @Inject
    Template storageAdminGroups;

    @Inject
    Template storageAdminGroup;

    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/pups")
    String basePath;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response groups(@QueryParam("error") String error, @QueryParam("done") String done) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var g : call(StorageAllocationService::groups)) {
            Map<String, Object> row = new HashMap<>();
            row.put("name", g.name());
            row.put("storageSize", g.size());
            row.put("state", g.active() ? "active" : "taken away");
            row.put("memberCount", call(x -> x.members(g.name())).size());
            rows.add(row);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("basePath", basePath);
        data.put("rows", rows);
        data.put("error", error);
        data.put("done", done);
        return Response.ok(storageAdminGroups.data(data).render()).build();
    }

    @POST
    @Path("/create")
    public Response createGroup(@FormParam("name") String name, @FormParam("size") String size) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        String admin = currentUser();
        String n = name == null ? "" : name.trim(), sz = size == null ? "" : size.trim();
        String error = call(x -> x.createGroup(admin, n, sz));
        if (error != null) return Response.seeOther(URI.create("/storage-admin?error=" + enc(error))).build();
        return backToGroup(n, null, "Group created");
    }

    @GET
    @Path("/g/{name}")
    @Produces(MediaType.TEXT_HTML)
    public Response group(@PathParam("name") String name, @QueryParam("error") String error,
                          @QueryParam("done") String done) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        var g = call(x -> x.group(name)).orElse(null);
        if (g == null) return Response.status(Response.Status.NOT_FOUND).entity("No such group").build();
        Map<String, Object> data = new HashMap<>();
        data.put("basePath", basePath);
        data.put("name", g.name());
        data.put("storageSize", g.size());
        data.put("active", g.active());
        data.put("restorable", !g.active() && g.pv().isPresent());
        data.put("revoked", g.active() ? "" : g.revokedBy() + ", " + (g.revokedAt() == null ? ""
            : g.revokedAt().toString().substring(0, 16).replace('T', ' ')));
        String pvcName = actorSystem.getK8sClient().groupPvcName(g.name());
        List<String> pods = g.active() ? actorSystem.getK8sClient().podsUsingGroupPvc(g.name()) : List.of();
        data.put("pvc", !g.active() ? "" : actorSystem.getK8sClient().pvcExists(pvcName)
            ? (pods.isEmpty() ? "idle" : "in use by " + String.join(", ", pods)) : "not created yet");
        data.put("members", call(x -> x.members(name)));
        data.put("userNames", userNames());
        data.put("error", error);
        data.put("done", done);
        return Response.ok(storageAdminGroup.data(data).render()).build();
    }

    /** The user names of the Keycloak realm, to choose a member from; empty when Keycloak cannot be read. */
    private List<String> userNames() {
        if (!directory.configured()) return List.of();
        try {
            return directory.all().stream().map(KeycloakUsers.User::username).toList();
        } catch (Exception e) {
            LOG.warning("The Keycloak user list could not be read: " + e.getMessage());
            return List.of();
        }
    }

    @POST
    @Path("/g/{name}/member")
    public Response putMember(@PathParam("name") String name, @FormParam("user") String user,
                              @FormParam("mode") String mode) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        String admin = currentUser();
        String error = call(x -> x.putMember(admin, name, user, mode));
        enforceGroupAccess();   // a member made read-only loses a read-write mount
        return backToGroup(name, error, "Member saved");
    }

    @POST
    @Path("/g/{name}/member/remove")
    public Response removeMember(@PathParam("name") String name, @FormParam("user") String user) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        String admin = currentUser();
        String error = call(x -> x.removeMember(admin, name, user));
        enforceGroupAccess();
        return backToGroup(name, error, "Member removed; their tools that mounted this storage are stopped");
    }

    @POST
    @Path("/g/{name}/resize")
    public Response resizeGroup(@PathParam("name") String name, @FormParam("size") String size) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        String admin = currentUser();
        String sz = size == null ? "" : size.trim();
        return backToGroup(name, call(x -> x.resizeGroup(admin, name, sz)), "Storage made larger");
    }

    @POST
    @Path("/g/{name}/revoke")
    public Response revokeGroup(@PathParam("name") String name) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        String admin = currentUser();
        return backToGroup(name, call(x -> x.revokeGroup(admin, name)), "Storage taken away");
    }

    @POST
    @Path("/g/{name}/restore")
    public Response restoreGroup(@PathParam("name") String name) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        String admin = currentUser();
        return backToGroup(name, call(x -> x.restoreGroup(admin, name)), "Storage given back");
    }

    private void enforceGroupAccess() {
        var sm = actorSystem.getSessionManager();
        sm.tell(m -> m.enforceStorageAccess(sm));
    }

    private Response backToGroup(String name, String error, String done) {
        String q = error != null ? "?error=" + enc(error) : "?done=" + enc(done);
        if (error != null) LOG.warning("Group storage change for " + name + " refused: " + error);
        return Response.seeOther(URI.create("/storage-admin/g/" + enc(name) + q)).build();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private <T> T call(Function<StorageAllocationService, T> operation) {
        try {
            ActorRef<StorageAllocationService> alloc = actorSystem.getStorageAllocations();
            return alloc.ask(operation::apply).get();
        } catch (Exception e) {
            throw new IllegalStateException("Storage allocation failed: " + e.getMessage(), e);
        }
    }

    /** Checked on every request, not only hidden in the page: a request without the link is still a request. */
    private Response requireAdmin() {
        return access.denyUnlessAdmin("Storage administration");
    }

    private String currentUser() {
        return access.user();
    }
}
