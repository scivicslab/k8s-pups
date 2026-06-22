package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.tool.*;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.*;
import java.util.logging.Logger;

/**
 * REST API for tool registry management.
 *
 * Admin-only endpoints for:
 * - List available tools and their pull status
 * - Pull tool images and extract descriptors
 * - Configure tool parameters
 */
@Path("/api/admin/tools")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class ToolManagementResource {

    private static final Logger LOG = Logger.getLogger(ToolManagementResource.class.getName());

    @Inject
    K8sPupsActorSystem actorSystem;

    /**
     * List all registered tools with their metadata and pull status.
     */
    @GET
    public Response listTools() {
        try {
            var k8sApiClient = actorSystem.getK8sClient();
            var namespace = actorSystem.getControllerNamespace();

            var registry = k8sApiClient.getToolRegistry(namespace);
            List<Map<String, Object>> toolList = new ArrayList<>();

            for (var entry : registry.tools()) {
                Map<String, Object> toolInfo = new HashMap<>();
                toolInfo.put("name", entry.name());
                toolInfo.put("image", entry.image());
                toolInfo.put("roles", entry.roles());
                toolInfo.put("pulled", false); // TODO: Check if descriptor exists in tool-catalog ConfigMap
                toolInfo.put("configured", false); // TODO: Check if all required params are in tool-config
                toolList.add(toolInfo);
            }

            return Response.ok(Map.of("tools", toolList)).build();
        } catch (Exception e) {
            LOG.severe("Failed to list tools: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", "Failed to list tools")).build();
        }
    }

    /**
     * Get descriptor and schema for a specific tool.
     */
    @GET
    @Path("/{toolName}")
    public Response getTool(@PathParam("toolName") String toolName) {
        try {
            var k8sApiClient = actorSystem.getK8sClient();
            var namespace = actorSystem.getControllerNamespace();

            var registry = k8sApiClient.getToolRegistry(namespace);
            var entry = registry.getTool(toolName);

            if (entry == null) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Tool not found")).build();
            }

            // TODO: Load descriptor from tool-catalog ConfigMap
            Map<String, Object> result = new HashMap<>();
            result.put("name", entry.name());
            result.put("image", entry.image());
            result.put("roles", entry.roles());
            result.put("descriptor", null); // TODO: Load from ConfigMap
            result.put("configuration", k8sApiClient.getToolConfig(namespace, toolName));

            return Response.ok(result).build();
        } catch (Exception e) {
            LOG.severe("Failed to get tool: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", "Failed to get tool")).build();
        }
    }

    /**
     * Pull a tool image and extract its descriptor.
     *
     * POST /api/admin/tools/pull?toolName=ocr-file-manager
     */
    @POST
    @Path("/pull")
    public Response pullTool(@QueryParam("toolName") String toolName) {
        try {
            var k8sApiClient = actorSystem.getK8sClient();
            var namespace = actorSystem.getControllerNamespace();

            var registry = k8sApiClient.getToolRegistry(namespace);
            var entry = registry.getTool(toolName);

            if (entry == null) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Tool not found in registry")).build();
            }

            // Extract descriptor from image
            var extractor = new ToolImageExtractor();
            var descriptor = extractor.extractDescriptor(entry.image());

            if (descriptor == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Failed to extract descriptor from image")).build();
            }

            // Save descriptor to tool-catalog ConfigMap
            k8sApiClient.saveToolCatalog(namespace, toolName, descriptor);

            Map<String, Object> result = new HashMap<>();
            result.put("name", toolName);
            result.put("image", entry.image());
            result.put("descriptor", descriptor);
            result.put("message", "Tool descriptor extracted and saved");

            return Response.ok(result).build();
        } catch (Exception e) {
            LOG.severe("Failed to pull tool: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", "Failed to pull tool")).build();
        }
    }

    /**
     * Save configuration for a tool (parameter values).
     *
     * POST /api/admin/tools/{toolName}/config
     * Body: {"PARAM_NAME": "value", "API_KEY": "secret_value", ...}
     */
    @POST
    @Path("/{toolName}/config")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveToolConfig(@PathParam("toolName") String toolName, Map<String, String> config) {
        try {
            var k8sApiClient = actorSystem.getK8sClient();
            var namespace = actorSystem.getControllerNamespace();

            k8sApiClient.saveToolConfig(namespace, toolName, config);

            return Response.ok(Map.of(
                "message", "Tool configuration saved",
                "tool", toolName,
                "parameters", config.keySet()
            )).build();
        } catch (Exception e) {
            LOG.severe("Failed to save tool config: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", "Failed to save tool config")).build();
        }
    }

    /**
     * Enable a tool so it appears in user dashboards.
     * Sets toolName.enable=true in tool-config ConfigMap.
     *
     * POST /api/admin/tools/{toolName}/enable
     */
    @POST
    @Path("/{toolName}/enable")
    public Response enableTool(@PathParam("toolName") String toolName) {
        try {
            var k8sApiClient = actorSystem.getK8sClient();
            var namespace = actorSystem.getControllerNamespace();

            var registry = k8sApiClient.getToolRegistry(namespace);
            if (registry.getTool(toolName) == null) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Tool not found in registry")).build();
            }

            k8sApiClient.saveToolEnable(namespace, toolName, true);

            return Response.ok(Map.of(
                "message", "Tool enabled",
                "tool", toolName,
                "enabled", true
            )).build();
        } catch (Exception e) {
            LOG.severe("Failed to enable tool: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", "Failed to enable tool")).build();
        }
    }

    /**
     * Disable a tool so it no longer appears in user dashboards.
     * Sets toolName.enable=false in tool-config ConfigMap.
     *
     * POST /api/admin/tools/{toolName}/disable
     */
    @POST
    @Path("/{toolName}/disable")
    public Response disableTool(@PathParam("toolName") String toolName) {
        try {
            var k8sApiClient = actorSystem.getK8sClient();
            var namespace = actorSystem.getControllerNamespace();

            k8sApiClient.saveToolEnable(namespace, toolName, false);

            return Response.ok(Map.of(
                "message", "Tool disabled",
                "tool", toolName,
                "enabled", false
            )).build();
        } catch (Exception e) {
            LOG.severe("Failed to disable tool: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", "Failed to disable tool")).build();
        }
    }
}
