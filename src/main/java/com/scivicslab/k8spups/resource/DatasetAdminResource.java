package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.dataset.DatasetPath;
import com.scivicslab.k8spups.dataset.DatasetRepository;
import com.scivicslab.k8spups.dataset.DatasetService;
import com.scivicslab.k8spups.tool.JwtRoleExtractor;

import io.quarkus.oidc.IdToken;
import io.quarkus.qute.Location;
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
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.BufferedReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Administrator pages for the dataset entitlement. The pages never list everything: datasets and users are searched
 * and paged, and a dataset or a user is opened to see its files or its access. Catalog and access are imported from
 * CSV files. Every change goes through the dataset-manager actor.
 */
@Path("/datasets")
@Blocking
public class DatasetAdminResource {

    private static final Logger LOG = Logger.getLogger(DatasetAdminResource.class.getName());
    private static final int PAGE_SIZE = 50;
    private static final int IMPORT_CHUNK_LINES = 20_000;
    private static final int MAX_REPORTED_ERRORS = 5;

    @Inject
    K8sPupsActorSystem actorSystem;

    @Inject
    @IdToken
    JsonWebToken idToken;

    @Inject
    SecurityIdentity identity;

    @Inject
    @Location("datasets")
    Template datasetsPage;

    @Inject
    @Location("dataset")
    Template datasetPage;

    @Inject
    @Location("dataset-users")
    Template usersPage;

    @Inject
    @Location("dataset-user")
    Template userPage;

    @Inject
    @Location("dataset-audit")
    Template auditPage;

    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/pups")
    String basePath;

    // ── pages ──────────────────────────────────────────────────────────────────

    @GET
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public Response datasets(@QueryParam("q") String q, @QueryParam("page") int page, @QueryParam("msg") String msg) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        Map<String, Object> data = base(msg);
        boolean available = call(DatasetService::isAvailable);
        data.put("available", available);
        data.put("q", q == null ? "" : q);
        data.put("qEnc", enc(q));
        if (available) {
            var result = call(d -> d.datasets(q, Math.max(page, 0), PAGE_SIZE));
            data.put("result", result);
            data.put("rows", result.items());
            data.put("prev", result.page() - 1);
            data.put("next", result.page() + 1);
        }
        return html(datasetsPage, data);
    }

    @GET
    @Path("/d/{name}")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public Response dataset(@PathParam("name") String name, @QueryParam("q") String q, @QueryParam("page") int page,
                            @QueryParam("msg") String msg) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        if (!DatasetPath.isValidRelativePath(name) || name.contains("/") || !call(d -> d.datasetExists(name)))
            return Response.status(Response.Status.NOT_FOUND).entity("No such dataset").build();
        Map<String, Object> data = base(msg);
        var files = call(d -> d.files(name, q, Math.max(page, 0), PAGE_SIZE));
        data.put("name", name);
        data.put("q", q == null ? "" : q);
        data.put("qEnc", enc(q));
        data.put("result", files);
        data.put("files", files.items());
        data.put("prev", files.page() - 1);
        data.put("next", files.page() + 1);
        data.put("access", call(d -> d.accessOfDataset(name)));
        return html(datasetPage, data);
    }

    @GET
    @Path("/users")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public Response users(@QueryParam("q") String q, @QueryParam("page") int page, @QueryParam("msg") String msg) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        Map<String, Object> data = base(msg);
        var result = call(d -> d.users(q, Math.max(page, 0), PAGE_SIZE));
        data.put("q", q == null ? "" : q);
        data.put("qEnc", enc(q));
        data.put("result", result);
        data.put("rows", result.items());
        data.put("prev", result.page() - 1);
        data.put("next", result.page() + 1);
        return html(usersPage, data);
    }

    @GET
    @Path("/u/{user}")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public Response user(@PathParam("user") String user, @QueryParam("page") int page, @QueryParam("msg") String msg) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        if (!DatasetPath.isValidUserName(user)) return Response.status(Response.Status.NOT_FOUND).entity("No such user").build();
        Map<String, Object> data = base(msg);
        var exceptions = call(d -> d.exceptionsOfUser(user, Math.max(page, 0), PAGE_SIZE));
        data.put("user", user);
        data.put("access", call(d -> d.accessOfUser(user)));
        data.put("result", exceptions);
        data.put("exceptions", exceptions.items());
        data.put("prev", exceptions.page() - 1);
        data.put("next", exceptions.page() + 1);
        return html(userPage, data);
    }

    @GET
    @Path("/audit")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public Response audit(@QueryParam("user") String user, @QueryParam("dataset") String dataset,
                          @QueryParam("type") String type, @QueryParam("page") int page, @QueryParam("msg") String msg) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        Map<String, Object> data = base(msg);
        var result = call(d -> d.audit(user, dataset, type, Math.max(page, 0), PAGE_SIZE));
        data.put("user", user == null ? "" : user);
        data.put("dataset", dataset == null ? "" : dataset);
        data.put("type", type == null ? "" : type);
        data.put("filter", "user=" + enc(user) + "&dataset=" + enc(dataset) + "&type=" + enc(type));
        data.put("result", result);
        data.put("rows", result.items());
        data.put("prev", result.page() - 1);
        data.put("next", result.page() + 1);
        return html(auditPage, data);
    }

    // ── imports ────────────────────────────────────────────────────────────────

    /** Catalog import: a CSV file with {@code dataset,path} or {@code path} per line, read in chunks. */
    @POST
    @Path("/import/catalog")
    @Authenticated
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response importCatalog(@RestForm("file") FileUpload file) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        if (file == null) return back("/datasets", "no file chosen");
        long read = 0, added = 0, errorCount = 0;
        List<String> errors = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file.uploadedFile(), StandardCharsets.UTF_8)) {
            StringBuilder chunk = new StringBuilder();
            int lines = 0;
            String line;
            long offset = 0;
            while ((line = in.readLine()) != null) {
                chunk.append(line).append('\n');
                if (++lines == IMPORT_CHUNK_LINES) {
                    var r = importChunk(chunk, offset, errors);
                    read += r.linesRead(); added += r.filesAdded(); errorCount += r.errors().size();
                    offset += lines; lines = 0; chunk.setLength(0);
                }
            }
            if (lines > 0) {
                var r = importChunk(chunk, offset, errors);
                read += r.linesRead(); added += r.filesAdded(); errorCount += r.errors().size();
            }
        } catch (Exception e) {
            return back("/datasets", "the catalog file could not be read: " + e.getMessage());
        }
        String admin = currentUser();
        long linesRead = read, filesAdded = added, errorsFound = errorCount;
        call(d -> { d.auditCatalogImport(admin, linesRead, filesAdded, errorsFound); return null; });
        LOG.info("Catalog imported by " + admin + ": lines=" + read + " added=" + added + " errors=" + errorCount);
        return back("/datasets", summary("Catalog import", read, added, "files added", errorCount, errors));
    }

    private DatasetService.CatalogImport importChunk(StringBuilder chunk, long lineOffset, List<String> errors) {
        String text = chunk.toString();
        var r = call(d -> d.importCatalog(text));
        for (String e : r.errors()) {
            if (errors.size() < MAX_REPORTED_ERRORS) errors.add(shiftLine(e, lineOffset));
        }
        return r;
    }

    /** Access import: {@code user,dataset[,YYYY-MM-DD]} per line (a file or pasted text). */
    @POST
    @Path("/import/access")
    @Authenticated
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response importAccess(@RestForm("file") FileUpload file, @RestForm("text") String text) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        String content;
        try {
            content = file != null && file.size() > 0
                ? Files.readString(file.uploadedFile(), StandardCharsets.UTF_8) : (text == null ? "" : text);
        } catch (Exception e) {
            return back("/datasets/users", "the access file could not be read: " + e.getMessage());
        }
        String admin = currentUser();
        var r = call(d -> d.importAccess(content, admin));
        enforceAccess();
        LOG.info("Access imported by " + admin + ": lines=" + r.linesRead() + " granted=" + r.granted() + " errors=" + r.errors().size());
        return back("/datasets/users", summary("Access import", r.linesRead(), r.granted(), "grants set", r.errors().size(), r.errors()));
    }

    // ── changes ────────────────────────────────────────────────────────────────

    @POST
    @Path("/d/{name}/grant")
    @Authenticated
    public Response grantDataset(@PathParam("name") String name, @FormParam("user") String user,
                                 @FormParam("expiresOn") String expiresOn) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        String page = "/datasets/d/" + name;
        if (!DatasetPath.isValidUserName(user)) return back(page, "invalid user name");
        Instant expires;
        try { expires = lastDay(expiresOn); } catch (Exception e) { return back(page, "invalid date"); }
        String admin = currentUser();
        boolean ok = call(d -> d.grantDataset(user, name, expires, admin));
        LOG.info("Dataset access granted by " + admin + ": user=" + user + " dataset=" + name + " expires=" + expires);
        return back(page, ok ? "granted" : "granted, but the user's file view could not be rebuilt");
    }

    @POST
    @Path("/d/{name}/revoke")
    @Authenticated
    public Response revokeDataset(@PathParam("name") String name, @FormParam("user") String user) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        String page = "/datasets/d/" + name;
        if (!DatasetPath.isValidUserName(user)) return back(page, "invalid user name");
        String admin = currentUser();
        boolean ok = call(d -> d.revokeDataset(user, name, admin));
        enforceAccess();
        LOG.info("Dataset access revoked by " + currentUser() + ": user=" + user + " dataset=" + name);
        return back(page, ok ? "revoked" : "revoked, but the user's file view could not be rebuilt");
    }

    @POST
    @Path("/d/{name}/delete")
    @Authenticated
    public Response deleteDataset(@PathParam("name") String name) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        if (!DatasetPath.isValidRelativePath(name) || name.contains("/")) return back("/datasets", "invalid dataset name");
        String admin = currentUser();
        boolean ok = call(d -> d.deleteDataset(name, admin));
        enforceAccess();
        LOG.info("Dataset " + name + " deleted by " + currentUser());
        return back("/datasets", ok ? "dataset " + name + " deleted" : "deleted, but a user's file view could not be rebuilt");
    }

    @POST
    @Path("/u/{user}/dataset/grant")
    @Authenticated
    public Response grantDatasetToUser(@PathParam("user") String user, @FormParam("dataset") String dataset,
                                       @FormParam("expiresOn") String expiresOn) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        String page = "/datasets/u/" + user;
        if (!DatasetPath.isValidUserName(user)) return back("/datasets/users", "invalid user name");
        if (dataset == null || !DatasetPath.isValidRelativePath(dataset) || dataset.contains("/") || !call(d -> d.datasetExists(dataset)))
            return back(page, "no such dataset");
        Instant expires;
        try { expires = lastDay(expiresOn); } catch (Exception e) { return back(page, "invalid date"); }
        String admin = currentUser();
        call(d -> d.grantDataset(user, dataset, expires, admin));
        LOG.info("Dataset access granted by " + admin + ": user=" + user + " dataset=" + dataset + " expires=" + expires);
        return back(page, "granted " + dataset);
    }

    @POST
    @Path("/u/{user}/dataset/revoke")
    @Authenticated
    public Response revokeDatasetOfUser(@PathParam("user") String user, @FormParam("dataset") String dataset) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        if (!DatasetPath.isValidUserName(user)) return back("/datasets/users", "invalid user name");
        String admin = currentUser();
        call(d -> d.revokeDataset(user, dataset, admin));
        enforceAccess();
        LOG.info("Dataset access revoked by " + currentUser() + ": user=" + user + " dataset=" + dataset);
        return back("/datasets/u/" + user, "revoked " + dataset);
    }

    @POST
    @Path("/u/{user}/file/grant")
    @Authenticated
    public Response grantFile(@PathParam("user") String user, @FormParam("path") String path,
                              @FormParam("expiresOn") String expiresOn) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        String page = "/datasets/u/" + user;
        if (!DatasetPath.isValidUserName(user)) return back("/datasets/users", "invalid user name");
        if (!DatasetPath.isValidRelativePath(path)) return back(page, "invalid file path");
        Instant expires;
        try { expires = lastDay(expiresOn); } catch (Exception e) { return back(page, "invalid date"); }
        String admin = currentUser();
        boolean ok = call(d -> d.grantFile(user, path, expires, admin));
        LOG.info("File access granted by " + admin + ": user=" + user + " file=" + path + " expires=" + expires);
        return back(page, ok ? "granted " + path : "the file is not in the catalog");
    }

    @POST
    @Path("/u/{user}/file/revoke")
    @Authenticated
    public Response revokeFile(@PathParam("user") String user, @FormParam("path") String path) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        if (!DatasetPath.isValidUserName(user) || !DatasetPath.isValidRelativePath(path)) return back("/datasets/users", "invalid request");
        String admin = currentUser();
        call(d -> d.revokeFile(user, path, admin));
        LOG.info("File access revoked by " + currentUser() + ": user=" + user + " file=" + path);
        return back("/datasets/u/" + user, "revoked " + path);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Map<String, Object> base(String msg) {
        Map<String, Object> data = new HashMap<>();
        data.put("basePath", basePath);
        data.put("msg", msg);
        return data;
    }

    private Response html(Template template, Map<String, Object> data) {
        return Response.ok(template.data(data).render()).build();
    }

    private static String enc(String q) {
        return URLEncoder.encode(q == null ? "" : q, StandardCharsets.UTF_8);
    }

    private static Instant lastDay(String expiresOn) {
        if (expiresOn == null || expiresOn.isBlank()) return null;
        return LocalDate.parse(expiresOn.trim()).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static String shiftLine(String error, long offset) {
        // "line 7: ..." counts from the start of the chunk
        if (error.startsWith("line ")) {
            int colon = error.indexOf(':');
            try { return "line " + (Long.parseLong(error.substring(5, colon)) + offset) + error.substring(colon); }
            catch (Exception ignored) { /* keep as is */ }
        }
        return error;
    }

    private static String summary(String what, long lines, long done, String doneWhat, long errorCount, List<String> errors) {
        StringBuilder sb = new StringBuilder(what + ": " + lines + " lines, " + done + " " + doneWhat + ", " + errorCount + " errors");
        for (String e : errors) sb.append(" | ").append(e);
        return sb.toString();
    }

    private Response back(String page, String msg) {
        return Response.seeOther(URI.create(page + "?msg=" + enc(msg))).build();
    }

    /** After access was taken away: stop the sessions that still mount a dataset their user may no longer read. */
    private void enforceAccess() {
        var sm = actorSystem.getSessionManager();
        sm.tell(m -> m.enforceDatasetAccess(sm));
    }

    private <T> T call(Function<DatasetService, T> operation) {
        try {
            return actorSystem.getDatasetManager().ask(operation::apply).get();
        } catch (Exception e) {
            throw new IllegalStateException("Dataset operation failed: " + e.getMessage(), e);
        }
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
