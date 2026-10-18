package com.scivicslab.storagesettings;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/**
 * Returns runtime config to the frontend.
 * PUPS_API_BASE env var is injected by k8s-pups when starting the session pod.
 */
@Path("/config")
public class ConfigResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> config() {
        String apiBase = System.getenv().getOrDefault("PUPS_API_BASE", "/pups");
        return Map.of("apiBase", apiBase);
    }
}
