package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.settings.SettingsService;
import com.scivicslab.k8spups.settings.SystemSettings;

import io.quarkus.qute.Template;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Administrator page for the settings shared by the tools (GPU broker URL, default model). A saved value reaches the
 * tools that start afterwards; running sessions keep the values they started with.
 */
@Path("/settings")
@Blocking
public class SettingsAdminResource {

    private static final Logger LOG = Logger.getLogger(SettingsAdminResource.class.getName());

    @Inject
    K8sPupsActorSystem actorSystem;

    @Inject
    AdminAccess access;

    @Inject
    Template settings;

    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/pups")
    String basePath;

    @GET
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public Response page(@QueryParam("error") String error, @QueryParam("saved") String saved) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        SystemSettings current = call(SettingsService::load);
        String defaultUrl = call(SettingsService::defaultBrokerUrl);
        // The models come from the broker that applies: the saved URL, else the deployment's own.
        String effectiveUrl = current.gpuBrokerUrl().isEmpty() ? defaultUrl : current.gpuBrokerUrl();
        List<String> models = new ArrayList<>(call(d -> d.listModels(effectiveUrl)));
        if (!current.defaultModel().isEmpty() && !models.isEmpty() && !models.contains(current.defaultModel())) {
            models.add(0, current.defaultModel());   // a saved name the broker does not list stays selectable
        }
        Map<String, Object> data = new HashMap<>();
        data.put("basePath", basePath);
        data.put("error", error);
        data.put("saved", saved != null);
        data.put("gpuBrokerUrl", current.gpuBrokerUrl());
        data.put("defaultBrokerUrl", defaultUrl);
        data.put("defaultModel", current.defaultModel());
        data.put("models", models);
        data.put("hasModels", !models.isEmpty());
        return Response.ok(settings.data(data).render()).build();
    }

    @POST
    @Authenticated
    public Response save(@FormParam("gpuBrokerUrl") String gpuBrokerUrl, @FormParam("defaultModel") String defaultModel) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        if (!SystemSettings.isValidUrl(gpuBrokerUrl)) return back("invalid_url", false);
        if (!SystemSettings.isValidModel(defaultModel)) return back("invalid_model", false);
        SystemSettings next = new SystemSettings(gpuBrokerUrl, defaultModel);
        call(s -> { s.save(next); return null; });
        LOG.info("Shared settings saved by " + currentUser() + ": gpuBrokerUrl=" + next.gpuBrokerUrl()
            + " defaultModel=" + next.defaultModel());
        String admin = currentUser();
        try {
            actorSystem.getDatasetManager().ask(d -> { d.auditAdminChange("settings.change", admin, null,
                "gpuBrokerUrl=" + next.gpuBrokerUrl() + " defaultModel=" + next.defaultModel()); return null; }).get();
        } catch (Exception e) {
            LOG.warning("The audit event settings.change could not be written: " + e.getMessage());
        }
        return back(null, true);
    }

    private <T> T call(Function<SettingsService, T> operation) {
        try {
            return actorSystem.getSettingsManager().ask(operation::apply).get();
        } catch (Exception e) {
            throw new IllegalStateException("Settings operation failed: " + e.getMessage(), e);
        }
    }

    private Response back(String error, boolean saved) {
        String query = error != null ? "?error=" + error : saved ? "?saved=1" : "";
        return Response.seeOther(URI.create("/settings" + query)).build();
    }

    /** Checked on every request, not only hidden in the page: a request without the link is still a request. */
    private Response requireAdmin() {
        return access.denyUnlessAdmin("Settings");
    }

    private String currentUser() {
        return access.user();
    }

    private List<String> roles() {
        return access.roles();
    }
}
