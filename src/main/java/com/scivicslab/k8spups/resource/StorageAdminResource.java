package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.k8s.StorageTypes;
import com.scivicslab.k8spups.storage.StorageAllocationRepository;
import com.scivicslab.k8spups.storage.StorageAllocationService;
import com.scivicslab.k8spups.storage.StoragePolicy;
import com.scivicslab.k8spups.tool.JwtRoleExtractor;
import com.scivicslab.pojoactor.core.ActorRef;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Administrator pages for the storage given to users: the list of users with their storage, and one page per user
 * to give, enlarge, take away and give back storage. Users get storage only from here (and the default allocation
 * at their first login); they cannot create it themselves.
 */
@Path("/storage-admin")
@Blocking
@Authenticated
public class StorageAdminResource {

    private static final Logger LOG = Logger.getLogger(StorageAdminResource.class.getName());

    @Inject
    K8sPupsActorSystem actorSystem;

    @Inject
    @IdToken
    JsonWebToken idToken;

    @Inject
    SecurityIdentity identity;

    @Inject
    Template storageAdmin;

    @Inject
    Template storageAdminUser;

    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/pups")
    String basePath;

    /** One row of the user list. */
    public record UserRow(String user, String storage) {}

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response list(@QueryParam("q") String q) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        String filter = q == null ? "" : q.trim();
        Map<String, List<String>> byUser = new LinkedHashMap<>();
        String error = null;
        TreeSet<String> users = new TreeSet<>();
        try {
            users.addAll(actorSystem.listUsers());
        } catch (Exception e) {
            error = "The user list could not be read from LDAP: " + e.getMessage();
        }
        List<StorageAllocationRepository.Allocation> active = call(StorageAllocationService::allActive);
        for (var a : active) users.add(a.user());
        for (var a : active) {
            byUser.computeIfAbsent(a.user(), k -> new ArrayList<>()).add(StorageTypes.name(a.type()) + " " + a.size());
        }
        List<UserRow> rows = new ArrayList<>();
        for (String u : users) {
            if (!filter.isEmpty() && !u.contains(filter)) continue;
            rows.add(new UserRow(u, String.join(", ", byUser.getOrDefault(u, List.of()))));
        }
        Map<String, Object> data = new HashMap<>();
        data.put("basePath", basePath);
        data.put("rows", rows);
        data.put("q", filter);
        data.put("error", error);
        return Response.ok(storageAdmin.data(data).render()).build();
    }

    @GET
    @Path("/u/{user}")
    @Produces(MediaType.TEXT_HTML)
    public Response user(@PathParam("user") String user, @QueryParam("error") String error,
                         @QueryParam("done") String done) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        List<StorageAllocationRepository.Allocation> history = call(s -> s.history(user));
        List<Map<String, Object>> active = new ArrayList<>();
        List<Map<String, Object>> revoked = new ArrayList<>();
        for (var a : history) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", a.id());
            row.put("type", a.type());
            row.put("name", StorageTypes.name(a.type()));
            row.put("storageSize", a.size());   // not "size": Qute reads map.size as the number of entries
            row.put("grantedBy", a.grantedBy());
            row.put("grantedAt", a.grantedAt() == null ? "" : a.grantedAt().toString().substring(0, 16).replace('T', ' '));
            if (a.active()) {
                Map<String, String> pvc = actorSystem.getK8sClient().getUserPvcInfo(user, a.type());
                row.put("pvc", "true".equals(pvc.get("exists"))
                    ? pvc.getOrDefault("phase", "") + ("true".equals(pvc.get("inUse")) ? ", in use" : "")
                    : "not created yet");
                active.add(row);
            } else {
                row.put("revokedBy", a.revokedBy());
                row.put("revokedAt", a.revokedAt() == null ? "" : a.revokedAt().toString().substring(0, 16).replace('T', ' '));
                row.put("restorable", StoragePolicy.restorable(a.type())
                    && ("nfs-k8s".equals(a.type()) || a.pv().isPresent()));
                revoked.add(row);
            }
        }
        List<Map<String, String>> types = new ArrayList<>();
        for (String t : actorSystem.getStorageTypeOptions()) {
            if (StoragePolicy.allocatable(t, actorSystem.getStorageTypeOptions())) {
                types.add(Map.of("type", t, "name", StorageTypes.name(t)));
            }
        }
        Map<String, Object> data = new HashMap<>();
        data.put("basePath", basePath);
        data.put("user", user);
        data.put("active", active);
        data.put("revoked", revoked);
        data.put("types", types);
        data.put("error", error);
        data.put("done", done);
        return Response.ok(storageAdminUser.data(data).render()).build();
    }

    @POST
    @Path("/u/{user}/allocate")
    public Response allocate(@PathParam("user") String user, @FormParam("type") String type,
                             @FormParam("size") String size) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        String admin = currentUser();
        String size1 = size == null ? null : size.trim();
        return back(user, call(s -> s.allocate(admin, user, type, size1)), "Storage given");
    }

    @POST
    @Path("/a/{id}/resize")
    public Response resize(@PathParam("id") long id, @FormParam("user") String user, @FormParam("size") String size) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        String admin = currentUser();
        String size1 = size == null ? null : size.trim();
        return back(user, call(s -> s.resize(admin, id, size1)), "Storage made larger");
    }

    @POST
    @Path("/a/{id}/revoke")
    public Response revoke(@PathParam("id") long id, @FormParam("user") String user) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        String admin = currentUser();
        return back(user, call(s -> s.revoke(admin, id)), "Storage taken away");
    }

    @POST
    @Path("/a/{id}/restore")
    public Response restore(@PathParam("id") long id, @FormParam("user") String user) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        String admin = currentUser();
        return back(user, call(s -> s.restore(admin, id)), "Storage given back");
    }

    private Response back(String user, String error, String done) {
        String q = error != null ? "?error=" + enc(error) : "?done=" + enc(done);
        if (error != null) LOG.warning("Storage change for " + user + " refused: " + error);
        return Response.seeOther(URI.create("/storage-admin/u/" + enc(user) + q)).build();
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
        if (!actorSystem.isAdmin(currentUser(), roles())) {
            LOG.warning("Storage administration refused for user " + currentUser());
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
