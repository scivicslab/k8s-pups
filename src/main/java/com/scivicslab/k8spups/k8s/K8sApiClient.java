package com.scivicslab.k8spups.k8s;

import com.scivicslab.k8spups.plugin.ResourceProfile;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.*;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;

import jakarta.enterprise.inject.spi.CDI;

import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetricsList;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import java.util.Objects;

/**
 * Wraps fabric8 KubernetesClient to provide Pod/Service/HTTPRoute operations
 * for user session Pods.
 *
 * <p>Routing uses Gateway API HTTPRoute (Envoy Gateway) instead of Ingress.
 * Each session creates one HTTPRoute in the gateway namespace with parentRefs
 * to all configured Gateways and a cross-namespace backendRef to the session
 * Service in user-pods namespace.</p>
 */
public class K8sApiClient {

    private static final Logger LOG = Logger.getLogger(K8sApiClient.class.getName());

    private final KubernetesClient client;
    private final String userPodsNamespace;
    private final String httpRouteNamespace;
    private final List<String> gatewayNames;

    // OIDC config for Envoy Gateway SecurityPolicy
    private final String oidcIssuer;
    private final String oidcAuthorizationEndpoint;
    private final String oidcTokenEndpoint;
    private final String oidcClientId;
    private final String oidcSecretName;
    private final String oidcJwksUri;
    private final String oidcRedirectBaseUrl;

    // NFS k8s-dedicated config
    private final String posixNfsServer;
    private final String posixNfsBasePath;
    /** Storage type key of the mount of the file-level view (added by the controller, never by the user). */
    public static final String DATASET_STORAGE_TYPE = "dataset";
    /** Directory of the per-user views of the files granted one by one, at the top of the dataset root PVC. */
    public static final String PVC_VIEW_DIR = "pups-view";
    /** Storage type key of the mounts of whole datasets (subPaths of the original directory; controller only). */
    public static final String DATASET_ROOT_STORAGE_TYPE = "dataset-root";

    /** True for the storage types that only the controller adds to a session. */
    public static boolean isDatasetStorageType(String storageType) {
        return DATASET_STORAGE_TYPE.equals(storageType) || DATASET_ROOT_STORAGE_TYPE.equals(storageType);
    }

    // Dataset entitlement views (see DatasetViewScript): NFS location read from config.
    private final String datasetNfsServer;
    private final String datasetBasePath;
    /**
     * Storage class of the shared PVC that holds the dataset root (the original directory), for a cluster without an NFS server for
     * datasets (a007 keeps them on Lustre). Empty = the dataset root is an NFS volume per user (datasetNfsServer, datasetBasePath).
     */
    private final String datasetRootStorageClass;
    private final String datasetRootStorageSize;
    private final String longhornStorageClass;
    /**
     * How the shared file storage (type {@code posix}) is provided on this cluster: {@code nfs} makes a directory on an NFS
     * server and a static PV per user (W206); {@code storage-class} asks a StorageClass for a volume (a007: Lustre).
     */
    private final String posixBackend;
    /** The StorageClass of the {@code storage-class} backend. */
    private final String posixStorageClass;
    /** User ID and group ID that replace the plugin's user ID in the tool Pods (see ToolPodIds). Null = not configured. */
    private final Long toolPodUid;
    private final Long toolPodGid;
    /** Image of the short-lived script Pods (the dataset views, the NFS directories); a cluster without internet names its own registry. */
    private final String scriptPodImage;
    private final String datasetOriginalDir;
    private final String datasetViewDir;
    private final String datasetMountPath;
    private final String datasetFilesMountPath;
    /** Group that owns the restricted dataset files; added to the Pods that mount a dataset. Null = none. */
    private final Long datasetGid;

    // Node allowlist for user pods (empty = no restriction)
    private final List<String> allowedNodes;

    private final String basePath;
    private final String controllerUrl;

    // Resolves each tool's image, allowing the plugin's compiled-in default to be
    // overridden at runtime from a mounted ConfigMap (ToolImageReferencing_260630_oo01),
    // so bumping a tool image needs no controller rebuild.
    private final com.scivicslab.k8spups.plugin.ToolImageResolver imageResolver;

    public K8sApiClient(String userPodsNamespace, String httpRouteNamespace, List<String> gatewayNames,
                        String oidcIssuer, String oidcAuthorizationEndpoint, String oidcTokenEndpoint,
                        String oidcClientId, String oidcSecretName, String oidcJwksUri,
                        String oidcRedirectBaseUrl,
                        String posixNfsServer, String posixNfsBasePath,
                        List<String> allowedNodes,
                        String basePath,
                        String controllerUrl) {
        this.client = CDI.current().select(KubernetesClient.class).get();
        this.userPodsNamespace = userPodsNamespace;
        this.httpRouteNamespace = httpRouteNamespace;
        this.gatewayNames = gatewayNames;
        this.oidcIssuer = oidcIssuer;
        this.oidcAuthorizationEndpoint = oidcAuthorizationEndpoint;
        this.oidcTokenEndpoint = oidcTokenEndpoint;
        this.oidcClientId = oidcClientId;
        this.oidcSecretName = oidcSecretName;
        this.oidcJwksUri = oidcJwksUri;
        this.oidcRedirectBaseUrl = oidcRedirectBaseUrl;
        this.posixNfsServer = posixNfsServer;
        this.posixNfsBasePath = posixNfsBasePath;
        this.allowedNodes = allowedNodes;
        this.basePath = basePath;
        this.controllerUrl = controllerUrl;
        var cfg = org.eclipse.microprofile.config.ConfigProvider.getConfig();
        this.datasetNfsServer = cfg.getOptionalValue("k8spups.dataset.nfs-server", String.class).orElse("");
        this.datasetBasePath = cfg.getOptionalValue("k8spups.dataset.base-path", String.class).orElse("");
        this.datasetRootStorageClass = cfg.getOptionalValue("k8spups.dataset.root-storage-class", String.class).orElse("");
        this.datasetRootStorageSize = cfg.getOptionalValue("k8spups.dataset.root-storage-size", String.class)
            .filter(v -> !v.isBlank()).orElse("100Gi");
        this.longhornStorageClass = cfg.getOptionalValue("k8spups.longhorn.storage-class", String.class)
            .filter(v -> !v.isBlank()).orElse("longhorn-single");
        this.posixBackend = cfg.getOptionalValue("k8spups.posix.backend", String.class)
            .filter(v -> !v.isBlank()).orElse("nfs");
        if (!posixBackend.equals("nfs") && !posixBackend.equals("storage-class")) {
            throw new IllegalArgumentException("k8spups.posix.backend must be nfs or storage-class: " + posixBackend);
        }
        this.posixStorageClass = cfg.getOptionalValue("k8spups.posix.storage-class", String.class)
            .filter(v -> !v.isBlank()).orElse("");
        if (posixBackend.equals("storage-class") && posixStorageClass.isEmpty()) {
            throw new IllegalArgumentException("k8spups.posix.storage-class is required when k8spups.posix.backend is storage-class");
        }
        this.toolPodUid = cfg.getOptionalValue("k8spups.tool-pod.uid", String.class)
            .filter(v -> !v.isBlank()).map(Long::valueOf).orElse(null);
        this.toolPodGid = cfg.getOptionalValue("k8spups.tool-pod.gid", String.class)
            .filter(v -> !v.isBlank()).map(Long::valueOf).orElse(null);
        ToolPodIds.resolve(1000L, toolPodUid, toolPodGid);   // refuse to start when only one of the two is set
        this.scriptPodImage = cfg.getOptionalValue("k8spups.script-pod-image", String.class)
            .filter(v -> !v.isBlank()).orElse("busybox:1.36");
        this.datasetOriginalDir = cfg.getOptionalValue("k8spups.dataset.original-dir", String.class).orElse("original");
        this.datasetViewDir = cfg.getOptionalValue("k8spups.dataset.view-dir", String.class).orElse("view");
        this.datasetMountPath = cfg.getOptionalValue("k8spups.dataset.mount-path", String.class).orElse("/datasets");
        this.datasetFilesMountPath = cfg.getOptionalValue("k8spups.dataset.files-mount-path", String.class).orElse("/dataset-files");
        this.datasetGid = cfg.getOptionalValue("k8spups.dataset.gid", String.class)
            .filter(v -> !v.isBlank()).map(Long::valueOf).orElse(null);
        this.imageResolver = new com.scivicslab.k8spups.plugin.ToolImageResolver(
                cfg.getOptionalValue("k8spups.tool-images-file", String.class)
                        .orElse("/etc/k8s-pups/tool-images.properties"),
                cfg.getOptionalValue("k8spups.registry", String.class).orElse(""));
    }

    // -- Shared services (cluster-wide Deployments, see SharedService) --

    /** State of a shared service's Deployment as the dashboard shows it. */
    public enum SharedServiceState { RUNNING, STARTING, DOWN, UNKNOWN }

    /**
     * RUNNING when the Deployment has a ready replica, STARTING when it asks for replicas that are
     * not ready yet, DOWN when it is scaled to 0 or absent, UNKNOWN when the API could not be read
     * (RBAC, network). Read fresh on every call; the dashboard renders it on page load.
     */
    public SharedServiceState sharedServiceState(String namespace, String deployment) {
        try {
            io.fabric8.kubernetes.api.model.apps.Deployment d =
                client.apps().deployments().inNamespace(namespace).withName(deployment).get();
            if (d == null) {
                return SharedServiceState.DOWN;
            }
            int wanted = d.getSpec() != null && d.getSpec().getReplicas() != null ? d.getSpec().getReplicas() : 0;
            int ready = d.getStatus() != null && d.getStatus().getReadyReplicas() != null
                ? d.getStatus().getReadyReplicas() : 0;
            if (ready > 0) {
                return SharedServiceState.RUNNING;
            }
            return wanted > 0 ? SharedServiceState.STARTING : SharedServiceState.DOWN;
        } catch (Exception e) {
            LOG.warning("Cannot read Deployment " + namespace + "/" + deployment + ": " + e.getMessage());
            return SharedServiceState.UNKNOWN;
        }
    }

    /** Scale a shared service's Deployment (Launch = 1, Stop = 0). Idempotent. */
    public void scaleSharedService(String namespace, String deployment, int replicas) {
        client.apps().deployments().inNamespace(namespace).withName(deployment).scale(replicas);
        LOG.info("Scaled Deployment " + namespace + "/" + deployment + " to " + replicas);
    }

    // -- Pod operations --

    public CompletableFuture<Pod> createPod(SessionInfo info) {
        return CompletableFuture.supplyAsync(() -> {
            Pod pod = buildPodSpec(info);
            Pod created = client.pods().inNamespace(userPodsNamespace).resource(pod).create();
            LOG.info("Pod created: " + created.getMetadata().getName());
            return created;
        });
    }

    public CompletableFuture<Void> deletePod(String podName) {
        return CompletableFuture.runAsync(() -> {
            client.pods().inNamespace(userPodsNamespace).withName(podName).delete();
            LOG.info("Pod deleted: " + podName);
        });
    }

    /** Delete all pods with the given session label (used by orphan cleanup). */
    public void deleteOrphanedPodBySession(String sessionId) {
        client.pods().inNamespace(userPodsNamespace)
            .withLabel("session", sessionId)
            .delete();
        LOG.info("Orphaned pod(s) deleted for session: " + sessionId);
    }

    /** Set an annotation on a managed Pod. */
    public void setPodAnnotation(String podName, String key, String value) {
        client.pods().inNamespace(userPodsNamespace).withName(podName)
            .edit(p -> new PodBuilder(p)
                .editMetadata().addToAnnotations(key, value).endMetadata()
                .build());
    }

    public String getPodPhase(String podName) {
        Pod pod = client.pods().inNamespace(userPodsNamespace).withName(podName).get();
        if (pod == null || pod.getStatus() == null) {
            return "Unknown";
        }
        return pod.getStatus().getPhase();
    }

    public boolean isPodReady(String podName) {
        Pod pod = client.pods().inNamespace(userPodsNamespace).withName(podName).get();
        if (pod == null || pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return false;
        }
        return pod.getStatus().getConditions().stream()
            .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    /**
     * Watch a specific Pod for status changes.
     * callback receives (action, pod) on each event.
     * Returns the Watch handle for later closing.
     */
    public Watch watchPod(String podName, BiConsumer<Watcher.Action, Pod> callback) {
        return client.pods().inNamespace(userPodsNamespace).withName(podName).watch(new Watcher<>() {
            @Override
            public void eventReceived(Action action, Pod pod) {
                callback.accept(action, pod);
            }

            @Override
            public void onClose(WatcherException cause) {
                if (cause != null) {
                    LOG.warning("Pod watch closed with error: " + cause.getMessage());
                }
            }
        });
    }

    // -- Service operations --

    public Service createService(SessionInfo info) {
        Service svc = new ServiceBuilder()
            .withNewMetadata()
                .withName(info.serviceName())
                .withNamespace(userPodsNamespace)
                .withLabels(Map.of(
                    "app", "k8s-pups-user",
                    "session", info.sessionId(),
                    "user", UserKey.encode(info.userId())
                ))
            .endMetadata()
            .withNewSpec()
                .withSelector(Map.of("session", info.sessionId()))
                .addNewPort()
                    .withPort(info.toolPlugin().containerPort())
                    .withTargetPort(new IntOrString(info.toolPlugin().containerPort()))
                    .withProtocol("TCP")
                .endPort()
            .endSpec()
            .build();

        Service created = client.services().inNamespace(userPodsNamespace).resource(svc).create();
        LOG.info("Service created: " + created.getMetadata().getName());
        return created;
    }

    /**
     * Ensure Service exists for the session. Creates if missing, skips if already present.
     */
    public void ensureService(SessionInfo info) {
        Service existing = client.services().inNamespace(userPodsNamespace)
            .withName(info.serviceName()).get();
        if (existing != null) {
            return;
        }
        createService(info);
        LOG.info("Service re-created during restore: " + info.serviceName());
    }

    public void deleteService(String serviceName) {
        client.services().inNamespace(userPodsNamespace).withName(serviceName).delete();
        LOG.info("Service deleted: " + serviceName);
    }

    public String getPodLogs(String podName, int tailLines) {
        try {
            return client.pods().inNamespace(userPodsNamespace)
                .withName(podName)
                .tailingLines(tailLines)
                .getLog();
        } catch (Exception e) {
            LOG.warning("Failed to get logs for pod " + podName + ": " + e.getMessage());
            return "";
        }
    }

    // -- HTTPRoute operations (Gateway API) --

    /**
     * Create an HTTPRoute for the session in the gateway namespace.
     * Routes /session/{sessionId}/* to the session Service via URL rewrite.
     * One HTTPRoute with parentRefs to all configured Gateways.
     */
    public void createHTTPRoute(String sessionId, String serviceName, int port, boolean passthroughPath) {
        List<ParentReference> parentRefs = gatewayNames.stream()
            .map(gw -> new ParentReferenceBuilder()
                .withGroup("gateway.networking.k8s.io")
                .withKind("Gateway")
                .withName(gw)
                .build())
            .toList();

        HTTPRoute route = new HTTPRouteBuilder()
            .withNewMetadata()
                .withName("pups-session-" + sessionId)
                .withNamespace(httpRouteNamespace)
                .withLabels(Map.of(
                    "managed-by", "k8s-pups",
                    "session", sessionId
                ))
            .endMetadata()
            .withNewSpec()
                .withParentRefs(parentRefs)
                .addNewRule()
                    .addNewMatch()
                        .withNewPath()
                            .withType("PathPrefix")
                            .withValue(SessionPath.of(sessionId))
                        .endPath()
                    .endMatch()
                    // Rewrite /session/{id}/* -> /* unless passthroughPath is set.
                    // Tools that need to know their base URL (e.g. JupyterLab) use passthrough
                    // and configure themselves via the PUPS_SESSION_PATH env var instead.
                    .addAllToFilters(passthroughPath ? List.of() : List.of(
                        new HTTPRouteFilterBuilder()
                            .withType("URLRewrite")
                            .withNewUrlRewrite()
                                .withNewPath()
                                    .withType("ReplacePrefixMatch")
                                    .withReplacePrefixMatch("/")
                                .endPath()
                            .endUrlRewrite()
                            .build()
                    ))
                    .addNewFilter()
                        .withType("RequestHeaderModifier")
                        .withNewRequestHeaderModifier()
                            .addNewSet()
                                .withName("X-Forwarded-Proto")
                                .withValue("https")
                            .endSet()
                        .endRequestHeaderModifier()
                    .endFilter()
                    .addNewBackendRef()
                        .withName(serviceName)
                        .withNamespace(userPodsNamespace)
                        .withPort(port)
                    .endBackendRef()
                    .withNewTimeouts()
                        .withRequest("3600s")
                    .endTimeouts()
                .endRule()
            .endSpec()
            .build();

        client.resource(route).create();
        LOG.info("HTTPRoute created: pups-session-" + sessionId
            + " (gateways: " + String.join(", ", gatewayNames) + ")");
    }

    /**
     * Ensure HTTPRoute exists for the session. Creates if missing, skips if already present.
     */
    public void ensureHTTPRoute(String sessionId, String serviceName, int port, boolean passthroughPath) {
        String routeName = "pups-session-" + sessionId;
        HTTPRoute existing = client.resources(HTTPRoute.class)
            .inNamespace(httpRouteNamespace)
            .withName(routeName)
            .get();
        if (existing != null) {
            return;
        }
        createHTTPRoute(sessionId, serviceName, port, passthroughPath);
        LOG.info("HTTPRoute re-created during restore: " + routeName);
    }

    /**
     * Delete the HTTPRoute for the session.
     */
    public void deleteHTTPRoute(String sessionId) {
        String routeName = "pups-session-" + sessionId;
        client.resources(HTTPRoute.class)
            .inNamespace(httpRouteNamespace)
            .withName(routeName)
            .delete();
        LOG.info("HTTPRoute deleted: " + routeName);
    }

    // -- Sub-tool operations (dynamic routing for child processes inside a session Pod) --

    private static String subToolResourceName(String sessionId, String toolName, int port) {
        return "pups-subtool-" + sessionId + "-" + toolName + "-" + port;
    }

    public void createSubToolService(String sessionId, String toolName, int port) {
        String name = subToolResourceName(sessionId, toolName, port);
        Service svc = new ServiceBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(userPodsNamespace)
                .withLabels(Map.of(
                    "managed-by", "k8s-pups",
                    "parent-session", sessionId,
                    "sub-tool", toolName
                ))
            .endMetadata()
            .withNewSpec()
                .withSelector(Map.of("session", sessionId))
                .addNewPort()
                    .withPort(port)
                    .withTargetPort(new IntOrString(port))
                    .withProtocol("TCP")
                .endPort()
            .endSpec()
            .build();
        client.services().inNamespace(userPodsNamespace).resource(svc).create();
        LOG.info("SubTool Service created: " + name);
    }

    public void createSubToolHTTPRoute(String sessionId, String toolName, int port) {
        String name = subToolResourceName(sessionId, toolName, port);
        String pathPrefix = SessionPath.of(sessionId + "-" + toolName + "-" + port);

        List<ParentReference> parentRefs = gatewayNames.stream()
            .map(gw -> new ParentReferenceBuilder()
                .withGroup("gateway.networking.k8s.io")
                .withKind("Gateway")
                .withName(gw)
                .build())
            .toList();

        HTTPRoute route = new HTTPRouteBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(httpRouteNamespace)
                .withLabels(Map.of(
                    "managed-by", "k8s-pups",
                    "parent-session", sessionId,
                    "sub-tool", toolName
                ))
            .endMetadata()
            .withNewSpec()
                .withParentRefs(parentRefs)
                .addNewRule()
                    .addNewMatch()
                        .withNewPath()
                            .withType("PathPrefix")
                            .withValue(pathPrefix)
                        .endPath()
                    .endMatch()
                    .addAllToFilters(List.of(
                        new HTTPRouteFilterBuilder()
                            .withType("URLRewrite")
                            .withNewUrlRewrite()
                                .withNewPath()
                                    .withType("ReplacePrefixMatch")
                                    .withReplacePrefixMatch("/")
                                .endPath()
                            .endUrlRewrite()
                            .build(),
                        new HTTPRouteFilterBuilder()
                            .withType("RequestHeaderModifier")
                            .withNewRequestHeaderModifier()
                                .addNewSet()
                                    .withName("X-Forwarded-Proto")
                                    .withValue("https")
                                .endSet()
                            .endRequestHeaderModifier()
                            .build()
                    ))
                    .addNewBackendRef()
                        .withName(name)
                        .withNamespace(userPodsNamespace)
                        .withPort(port)
                    .endBackendRef()
                    .withNewTimeouts()
                        .withRequest("3600s")
                    .endTimeouts()
                .endRule()
            .endSpec()
            .build();

        client.resource(route).create();
        LOG.info("SubTool HTTPRoute created: " + name + " → " + pathPrefix);
    }

    public void deleteSubToolResources(String sessionId, String toolName, int port) {
        String name = subToolResourceName(sessionId, toolName, port);
        try {
            client.services().inNamespace(userPodsNamespace).withName(name).delete();
            LOG.info("SubTool Service deleted: " + name);
        } catch (Exception e) {
            LOG.warning("Failed to delete SubTool Service " + name + ": " + e.getMessage());
        }
        try {
            client.resources(HTTPRoute.class).inNamespace(httpRouteNamespace).withName(name).delete();
            LOG.info("SubTool HTTPRoute deleted: " + name);
        } catch (Exception e) {
            LOG.warning("Failed to delete SubTool HTTPRoute " + name + ": " + e.getMessage());
        }
    }

    // -- SecurityPolicy operations (Envoy Gateway OIDC) --

    private static final ResourceDefinitionContext SECURITY_POLICY_CONTEXT =
        new ResourceDefinitionContext.Builder()
            .withGroup("gateway.envoyproxy.io")
            .withVersion("v1alpha1")
            .withKind("SecurityPolicy")
            .withPlural("securitypolicies")
            .withNamespaced(true)
            .build();

    /**
     * Create an OIDC SecurityPolicy for the session HTTPRoute.
     * Enforces Keycloak OIDC authentication on the session route and restricts
     * access to the session owner only (JWT sub claim must match userId).
     */
    public void createSecurityPolicy(String sessionId, String userId) {
        String policyName = "pups-session-sp-" + sessionId;
        String targetRouteName = "pups-session-" + sessionId;
        String redirectURL = oidcRedirectBaseUrl + SessionPath.of(sessionId) + "/oauth2/callback";
        // Fixed cookie name (per-session to avoid cross-session conflicts).
        // Must be specified explicitly because the default is a randomly generated suffix,
        // which would make it impossible to reference from the jwt section below.
        String idTokenCookieName = "pups-id-" + sessionId;

        // Build spec as nested Maps (SecurityPolicy CRD is not in fabric8 built-in model)
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("targetRefs", List.of(Map.of(
            "group", "gateway.networking.k8s.io",
            "kind", "HTTPRoute",
            "name", targetRouteName
        )));

        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("issuer", oidcIssuer);
        provider.put("authorizationEndpoint", oidcAuthorizationEndpoint);
        provider.put("tokenEndpoint", oidcTokenEndpoint);

        Map<String, Object> oidc = new LinkedHashMap<>();
        oidc.put("provider", provider);
        oidc.put("clientID", oidcClientId);
        oidc.put("clientSecret", Map.of("name", oidcSecretName));
        oidc.put("redirectURL", redirectURL);
        // Trust X-Forwarded-Proto header from Apache so OIDC uses correct HTTPS scheme
        oidc.put("trustProxy", true);
        oidc.put("scopes", List.of("openid", "profile"));
        // Fix the idToken cookie name so the jwt section below can extract it by name.
        oidc.put("cookieNames", Map.of("idToken", idTokenCookieName));
        // Limit cookie scope to this session's path to avoid cross-session cookie conflicts.
        oidc.put("cookiePath", SessionPath.of(sessionId) + "/");

        spec.put("oidc", oidc);

        // JWT section: extract the OIDC ID token from the fixed-name cookie and validate it.
        // This is required to enable claim-based authorization in the authorization section.
        // Use internal cluster URL for JWKS so Envoy Gateway can fetch without TLS issues.
        String jwksUri = oidcJwksUri;
        Map<String, Object> remoteJWKS = new LinkedHashMap<>();
        remoteJWKS.put("uri", jwksUri);

        Map<String, Object> extractFrom = new LinkedHashMap<>();
        extractFrom.put("cookies", List.of(idTokenCookieName));

        Map<String, Object> jwtProvider = new LinkedHashMap<>();
        // Provider name must be unique per SecurityPolicy so that Envoy Gateway does not
        // confuse requirement_name mappings when multiple SecurityPolicies coexist under
        // the same Gateway (all policies share one merged JWT filter config internally).
        String jwtProviderName = "keycloak-" + sessionId;
        jwtProvider.put("name", jwtProviderName);
        jwtProvider.put("issuer", oidcIssuer);
        jwtProvider.put("remoteJWKS", remoteJWKS);
        jwtProvider.put("extractFrom", extractFrom);

        spec.put("jwt", Map.of("providers", List.of(jwtProvider)));

        // Authorization section: allow only the session owner (preferred_username == userId).
        // Keycloak's sub claim is a UUID, not the username, so use preferred_username.
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("name", "preferred_username");
        claim.put("values", List.of(userId));

        Map<String, Object> jwtPrincipal = new LinkedHashMap<>();
        jwtPrincipal.put("provider", jwtProviderName);
        jwtPrincipal.put("claims", List.of(claim));

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("name", "owner-only");
        rule.put("action", "Allow");
        rule.put("principal", Map.of("jwt", jwtPrincipal));

        Map<String, Object> authorization = new LinkedHashMap<>();
        authorization.put("defaultAction", "Deny");
        authorization.put("rules", List.of(rule));

        spec.put("authorization", authorization);

        GenericKubernetesResource sp = new GenericKubernetesResource();
        sp.setApiVersion("gateway.envoyproxy.io/v1alpha1");
        sp.setKind("SecurityPolicy");
        sp.setMetadata(new ObjectMetaBuilder()
            .withName(policyName)
            .withNamespace(httpRouteNamespace)
            .withLabels(Map.of(
                "managed-by", "k8s-pups",
                "session", sessionId
            ))
            .build());
        sp.setAdditionalProperty("spec", spec);

        client.genericKubernetesResources(SECURITY_POLICY_CONTEXT)
            .inNamespace(httpRouteNamespace)
            .resource(sp)
            .create();

        LOG.info("SecurityPolicy created: " + policyName + " for HTTPRoute " + targetRouteName);
    }

    /**
     * Delete the OIDC SecurityPolicy for the session.
     */
    public void deleteSecurityPolicy(String sessionId) {
        String policyName = "pups-session-sp-" + sessionId;
        client.genericKubernetesResources(SECURITY_POLICY_CONTEXT)
            .inNamespace(httpRouteNamespace)
            .withName(policyName)
            .delete();
        LOG.info("SecurityPolicy deleted: " + policyName);
    }

    // -- Orphan resource discovery (for startup reconciliation) --

    private static final String MANAGED_BY_LABEL = "managed-by=k8s-pups";

    /**
     * Returns all Pods in userPodsNamespace created by k8s-pups.
     * Used during startup reconciliation to restore or clean up sessions.
     */
    public List<Pod> listManagedPods() {
        return client.pods()
            .inNamespace(userPodsNamespace)
            .withLabelSelector(MANAGED_BY_LABEL)
            .list()
            .getItems();
    }

    /**
     * Returns sessionIds of all Pods in userPodsNamespace created by k8s-pups.
     * Used during startup reconciliation to identify orphaned resources.
     */
    public List<String> listManagedPodSessionIds() {
        return client.pods()
            .inNamespace(userPodsNamespace)
            .withLabelSelector(MANAGED_BY_LABEL)
            .list()
            .getItems()
            .stream()
            .map(pod -> pod.getMetadata().getLabels().get("session"))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Returns sessionIds of all HTTPRoutes in httpRouteNamespace created by k8s-pups.
     */
    public List<String> listManagedHTTPRouteSessionIds() {
        return client.resources(HTTPRoute.class)
            .inNamespace(httpRouteNamespace)
            .withLabelSelector(MANAGED_BY_LABEL)
            .list()
            .getItems()
            .stream()
            .map(r -> r.getMetadata().getLabels().get("session"))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Returns sessionIds of all Services in userPodsNamespace created by k8s-pups.
     */
    public List<String> listManagedServiceSessionIds() {
        return client.services()
            .inNamespace(userPodsNamespace)
            .withLabelSelector(MANAGED_BY_LABEL)
            .list()
            .getItems()
            .stream()
            .map(s -> s.getMetadata().getLabels().get("session"))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Returns sessionIds of all SecurityPolicies in httpRouteNamespace created by k8s-pups.
     */
    public List<String> listManagedSecurityPolicySessionIds() {
        return client.genericKubernetesResources(SECURITY_POLICY_CONTEXT)
            .inNamespace(httpRouteNamespace)
            .withLabelSelector(MANAGED_BY_LABEL)
            .list()
            .getItems()
            .stream()
            .map(r -> r.getMetadata().getLabels().get("session"))
            .filter(Objects::nonNull)
            .toList();
    }

    // -- PVC operations --

    /**
     * Returns the key of the user for use in k8s resource names and NFS directory names.
     * The mapping is injective (see {@link UserKey}), so two users never share a resource.
     */
    private String sanitizeUserId(String userId) {
        return UserKey.encode(userId);
    }

    /** Storage type of a mount of a group's storage ({@link MountSpec#sharedFrom} names the group). */
    public static final String GROUP_STORAGE_TYPE = "group";

    /** The PVC of a group's storage: one shared file storage PVC per group, mounted by the Pods of its members. */
    public String groupPvcName(String group) {
        return "pups-group-" + sanitizeUserId(group) + "-posix";
    }

    /**
     * Returns the PVC name for a given userId and storage type.
     * Format: pups-data-{sanitizedUserId}-{storageType}
     */
    public String userPvcName(String userId, String storageType) {
        return "pups-data-" + sanitizeUserId(userId) + "-" + storageType;
    }

    /**
     * Returns the PV name for the shared file storage of the nfs backend.
     *
     * <p>PersistentVolume is cluster-scoped while the PVC is namespaced, so the name
     * carries the user-pods namespace. Two k8s-pups instances (for example /pups and
     * /local-llm) share the cluster and can hold the same user name; without the
     * namespace both instances derive the same PV name, and the second instance's PVC
     * stays Pending with "already bound to a different claim".
     *
     * <p>PVs created before this carry the short name. They keep working: their PVC
     * already exists and stays bound, and the controller only builds a PV name when it
     * has to create one.
     */
    private String userPvName(String userId, String storageType) {
        return userPvcName(userId, storageType) + "-" + userPodsNamespace;
    }

    /**
     * Creates a Longhorn PVC for the user.
     * StorageClass: {@code k8spups.longhorn.storage-class} (default longhorn-single), AccessMode: ReadWriteOnce.
     * If the PVC already exists, this is a no-op.
     *
     * @param userId      the user identifier
     * @param storageSize the requested size (e.g. "100Gi")
     */
    public void createLonghornPvc(String userId, String storageSize) {
        String pvcName = userPvcName(userId, "longhorn");
        PersistentVolumeClaim existing = client.persistentVolumeClaims()
            .inNamespace(userPodsNamespace).withName(pvcName).get();
        if (existing != null) {
            LOG.info("Longhorn PVC already exists: " + pvcName);
            return;
        }
        PersistentVolumeClaim pvc = UserPvcs.build(pvcName, userPodsNamespace, userId, "longhorn",
            longhornStorageClass, "ReadWriteOnce", storageSize);
        client.persistentVolumeClaims().inNamespace(userPodsNamespace).resource(pvc).create();
        LOG.info("Longhorn PVC created: " + pvcName + " (" + storageSize + ")");
    }

    /** Whether the shared file storage is made of directories on an NFS server (otherwise a StorageClass provides it). */
    public boolean posixUsesNfs() {
        return posixBackend.equals("nfs");
    }

    /**
     * Creates the user's PVC of the shared file storage from the StorageClass {@code k8spups.posix.storage-class}
     * (dynamically provisioned, shared by several Pods, ReadWriteMany). If the PVC already exists, this is a no-op.
     * The StorageClass reclaims with Retain, so deleting the PVC leaves the PV and the data directory behind.
     *
     * @param userId      the user identifier
     * @param storageSize the requested size (e.g. "100Gi")
     */
    public void createStorageClassPvc(String userId, String storageSize) {
        String pvcName = userPvcName(userId, "posix");
        PersistentVolumeClaim existing = client.persistentVolumeClaims()
            .inNamespace(userPodsNamespace).withName(pvcName).get();
        if (existing != null) {
            LOG.info("Shared file storage PVC already exists: " + pvcName);
            return;
        }
        PersistentVolumeClaim pvc = UserPvcs.build(pvcName, userPodsNamespace, userId, "posix",
            posixStorageClass, "ReadWriteMany", storageSize);
        client.persistentVolumeClaims().inNamespace(userPodsNamespace).resource(pvc).create();
        LOG.info("Shared file storage PVC created: " + pvcName + " (" + storageSize + ")");
    }

    /** Creates the user's PVC of an allocated storage type, with the allocated size; a no-op if it exists. */
    public void createAllocatedPvc(String userId, String storageType, String size) {
        switch (storageType) {
            case "posix" -> { if (posixUsesNfs()) createNfsPvPvc(userId, size); else createStorageClassPvc(userId, size); }
            case "longhorn" -> createLonghornPvc(userId, size);
            default -> throw new IllegalArgumentException("storage type cannot be allocated: " + storageType);
        }
    }

    /**
     * The PV the user's PVC of this type is bound to, with the values needed to write it again; empty when the PVC
     * does not exist or is not bound yet.
     */
    public Optional<com.scivicslab.k8spups.storage.PvRecord> boundPv(String userId, String storageType) {
        return boundPvOf(userPvcName(userId, storageType));
    }

    /** {@link #boundPv} for a PVC given by name (a group's PVC). */
    public Optional<com.scivicslab.k8spups.storage.PvRecord> boundPvOf(String pvcName) {
        PersistentVolumeClaim pvc = client.persistentVolumeClaims()
            .inNamespace(userPodsNamespace).withName(pvcName).get();
        if (pvc == null || pvc.getSpec().getVolumeName() == null || pvc.getSpec().getVolumeName().isEmpty()) {
            return Optional.empty();
        }
        PersistentVolume pv = client.persistentVolumes().withName(pvc.getSpec().getVolumeName()).get();
        if (pv == null) return Optional.empty();
        Quantity cap = pv.getSpec().getCapacity() != null ? pv.getSpec().getCapacity().get("storage") : null;
        CSIPersistentVolumeSource csi = pv.getSpec().getCsi();
        return Optional.of(new com.scivicslab.k8spups.storage.PvRecord(pv.getMetadata().getName(),
            pv.getSpec().getStorageClassName(), cap != null ? cap.toString() : null,
            csi != null ? csi.getDriver() : null, csi != null ? csi.getVolumeHandle() : null,
            csi != null && csi.getVolumeAttributes() != null ? Map.copyOf(csi.getVolumeAttributes()) : Map.of()));
    }

    /**
     * Gives the user back the volume of a revoked allocation of the {@code storage-class} backend: binds a new PVC (the user's usual PVC name)
     * to the PV of the record. If the PV object still exists it is reused after its old claim is removed; if it was
     * deleted, it is written again from the record (Retain, so deleting the PVC again keeps it). Checked on a007 with
     * the Lustre CSI driver exa.csi.ddn.com.
     */
    public void restoreStorageClassPvc(String userId, com.scivicslab.k8spups.storage.PvRecord rec) {
        PersistentVolumeClaim pvc = UserPvcs.build(userPvcName(userId, "posix"), userPodsNamespace, userId,
            "posix", rec.storageClass(), "ReadWriteMany", rec.capacity());
        restorePvc(pvc, rec);
    }

    /** {@link #restoreStorageClassPvc} for a group's PVC. */
    public void restoreGroupPvc(String group, com.scivicslab.k8spups.storage.PvRecord rec) {
        restorePvc(groupPvc(group, rec.storageClass(), rec.capacity()), rec);
    }

    private void restorePvc(PersistentVolumeClaim pvc, com.scivicslab.k8spups.storage.PvRecord rec) {
        String pvcName = pvc.getMetadata().getName();
        if (client.persistentVolumeClaims().inNamespace(userPodsNamespace).withName(pvcName).get() != null) {
            throw new IllegalStateException("PVC already exists: " + pvcName);
        }
        PersistentVolume pv = client.persistentVolumes().withName(rec.name()).get();
        if (pv == null) {
            if (rec.csiDriver() == null || rec.volumeHandle() == null) {
                throw new IllegalStateException("the record of PV " + rec.name() + " has no CSI values to write it again");
            }
            pv = new PersistentVolumeBuilder()
                .withNewMetadata().withName(rec.name()).endMetadata()
                .withNewSpec()
                    .withCapacity(Map.of("storage", new Quantity(rec.capacity())))
                    .withAccessModes("ReadWriteMany")
                    .withPersistentVolumeReclaimPolicy("Retain")
                    .withStorageClassName(rec.storageClass())
                    .withVolumeMode("Filesystem")
                    .withNewCsi().withDriver(rec.csiDriver()).withVolumeHandle(rec.volumeHandle())
                        .withVolumeAttributes(rec.volumeAttributes()).endCsi()
                .endSpec().build();
            client.persistentVolumes().resource(pv).create();
            LOG.info("PV written again from its record: " + rec.name());
        } else if (pv.getSpec().getClaimRef() != null) {
            String phase = pv.getStatus() != null ? pv.getStatus().getPhase() : "";
            if (!"Released".equals(phase)) {
                throw new IllegalStateException("PV " + rec.name() + " is " + phase + ", not Released");
            }
            pv.getSpec().setClaimRef(null);
            client.persistentVolumes().resource(pv).update();
            LOG.info("Old claim removed from PV " + rec.name());
        }
        pvc.getSpec().setVolumeName(rec.name());
        client.persistentVolumeClaims().inNamespace(userPodsNamespace).resource(pvc).create();
        LOG.info("Shared file storage PVC restored: " + pvcName + " -> " + rec.name());
    }

    /** Whether a PVC of this name exists in the user Pods' namespace. */
    public boolean pvcExists(String pvcName) {
        return client.persistentVolumeClaims().inNamespace(userPodsNamespace).withName(pvcName).get() != null;
    }

    /** A group's PVC object: shared file storage, ReadWriteMany, labelled with the group instead of a user. */
    private PersistentVolumeClaim groupPvc(String group, String storageClass, String size) {
        PersistentVolumeClaim pvc = UserPvcs.build(groupPvcName(group), userPodsNamespace, group, "posix",
            storageClass, "ReadWriteMany", size);
        Map<String, String> labels = pvc.getMetadata().getLabels();
        labels.remove("user");
        labels.put("group", UserKey.encode(group));
        return pvc;
    }

    /** Creates the PVC of a group's storage; a no-op if it exists. */
    public void createGroupPvc(String group, String size) {
        if (pvcExists(groupPvcName(group))) return;
        if (posixUsesNfs()) {
            createNfsGroupPvPvc(group, size);
            return;
        }
        client.persistentVolumeClaims().inNamespace(userPodsNamespace)
            .resource(groupPvc(group, posixStorageClass, size)).create();
        LOG.info("Group PVC created: " + groupPvcName(group) + " (" + size + ")");
    }

    /** The PV of a group's storage with the nfs backend: cluster-scoped, so its name carries the namespace like a user's. */
    private String groupPvName(String group) {
        return groupPvcName(group) + "-" + userPodsNamespace;
    }

    /**
     * Makes the group's directory on the NFS server (owned by the tool Pod IDs) and the static PV and PVC for it, with the
     * nfs backend. The directory is named after the group with a prefix that no user key has, so a group and a user of the
     * same name never share it. Existing objects are left as they are.
     */
    private void createNfsGroupPvPvc(String group, String size) {
        String dir = "_group-" + sanitizeUserId(group);
        String pvName = groupPvName(group);
        String pvcName = groupPvcName(group);
        if (client.persistentVolumes().withName(pvName).get() == null) {
            ensureNfsDirectory(dir);
            PersistentVolume pv = new PersistentVolumeBuilder()
                .withNewMetadata().withName(pvName)
                    .addToLabels("app", "k8s-pups-group").addToLabels("managed-by", "k8s-pups")
                    .addToLabels("group", UserKey.encode(group)).addToLabels("storage-type", "posix")
                .endMetadata()
                .withNewSpec()
                    .withCapacity(Map.of("storage", new Quantity(size)))
                    .withAccessModes("ReadWriteMany")
                    .withPersistentVolumeReclaimPolicy("Retain")
                    .withStorageClassName("")
                    .withNewCsi().withDriver("nfs.csi.k8s.io").withVolumeHandle(pvName)
                        .addToVolumeAttributes("server", posixNfsServer)
                        .addToVolumeAttributes("share", posixNfsBasePath + "/" + dir)
                    .endCsi()
                    .withMountOptions(List.of("nfsvers=4"))
                .endSpec().build();
            client.persistentVolumes().resource(pv).create();
            LOG.info("Group PV created: " + pvName + " -> " + posixNfsServer + ":" + posixNfsBasePath + "/" + dir);
        }
        PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
            .withNewMetadata().withName(pvcName).withNamespace(userPodsNamespace)
                .addToLabels("app", "k8s-pups-user").addToLabels("managed-by", "k8s-pups")
                .addToLabels("group", UserKey.encode(group)).addToLabels("storage-type", "posix")
            .endMetadata()
            .withNewSpec().withAccessModes("ReadWriteMany").withStorageClassName("").withVolumeName(pvName)
                .withNewResources().addToRequests("storage", new Quantity(size)).endResources()
            .endSpec().build();
        client.persistentVolumeClaims().inNamespace(userPodsNamespace).resource(pvc).create();
        LOG.info("Group PVC created: " + pvcName + " (" + size + ")");
    }

    /** The Pods that mount the group's PVC. */
    public List<String> podsUsingGroupPvc(String group) {
        String pvcName = groupPvcName(group);
        List<String> out = new ArrayList<>();
        for (Pod pod : client.pods().inNamespace(userPodsNamespace).list().getItems()) {
            if (pod.getSpec() == null || pod.getSpec().getVolumes() == null) continue;
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "";
            if (!"Running".equals(phase) && !"Pending".equals(phase)) continue;
            for (Volume v : pod.getSpec().getVolumes()) {
                if (v.getPersistentVolumeClaim() != null && pvcName.equals(v.getPersistentVolumeClaim().getClaimName())) {
                    out.add(pod.getMetadata().getName());
                }
            }
        }
        return out;
    }

    /** Deletes the group's PVC; refused (false) while a Pod mounts it. */
    public boolean deleteGroupPvc(String group) {
        if (!podsUsingGroupPvc(group).isEmpty()) return false;
        client.persistentVolumeClaims().inNamespace(userPodsNamespace).withName(groupPvcName(group)).delete();
        LOG.info("Group PVC deleted: " + groupPvcName(group));
        if (posixUsesNfs()) {
            try {
                client.persistentVolumes().withName(groupPvName(group)).delete();
                LOG.info("Group PV deleted: " + groupPvName(group));
            } catch (Exception e) {
                LOG.warning("Failed to delete PV " + groupPvName(group) + ": " + e.getMessage());
            }
        }
        return true;
    }

    /** Grows the user's PVC of this type to the size (the StorageClass must allow expansion); never shrinks it. */
    public void growPvc(String userId, String storageType, String size) {
        growPvcNamed(userPvcName(userId, storageType), size);
    }

    /** {@link #growPvc} for a PVC given by name (a group's PVC). */
    public void growPvcNamed(String pvcName, String size) {
        PersistentVolumeClaim pvc = client.persistentVolumeClaims().inNamespace(userPodsNamespace).withName(pvcName).get();
        if (pvc == null) throw new IllegalStateException("PVC not found: " + pvcName);
        Quantity current = pvc.getSpec().getResources().getRequests().get("storage");
        Quantity wanted = new Quantity(size);
        if (current != null && compareSizeGi(wanted, current) <= 0) {
            throw new IllegalArgumentException("a PVC cannot be made smaller: " + current + " -> " + size);
        }
        pvc.getSpec().getResources().getRequests().put("storage", wanted);
        client.persistentVolumeClaims().inNamespace(userPodsNamespace).resource(pvc).update();
        LOG.info("PVC grown: " + pvcName + " -> " + size);
    }

    /**
     * Creates a PV + PVC pair for the user's k8s-dedicated NFS storage.
     * First ensures the user directory exists on the NFS server (owned by UID 1000),
     * then creates the PV/PVC pointing to it.
     * Uses nfs.csi.k8s.io driver with the posixNfsServer/posixNfsBasePath config.
     * If they already exist, this is a no-op.
     * Note: NFS does not enforce the declared capacity; it is used only for display.
     *
     * @param userId      the user identifier
     * @param storageSize nominal size for display (e.g. "50Gi")
     */
    public void createNfsPvPvc(String userId, String storageSize) {
        String pvName = userPvName(userId, "posix");
        String pvcName = userPvcName(userId, "posix");
        String sanitized = sanitizeUserId(userId);
        String nfsPath = posixNfsBasePath + "/" + sanitized;

        // Ensure user directory exists on NFS server before creating PV
        PersistentVolume existingPv = client.persistentVolumes().withName(pvName).get();
        if (existingPv == null) {
            ensureNfsDirectory(sanitized);

            PersistentVolume pv = new PersistentVolumeBuilder()
                .withNewMetadata()
                    .withName(pvName)
                    .addToLabels("app", "k8s-pups-user")
                    .addToLabels("managed-by", "k8s-pups")
                    .addToLabels("user", UserKey.encode(userId))
                    .addToLabels("storage-type", "posix")
                .endMetadata()
                .withNewSpec()
                    .withCapacity(Map.of("storage", new Quantity(storageSize)))
                    .withAccessModes("ReadWriteMany")
                    .withPersistentVolumeReclaimPolicy("Retain")
                    .withStorageClassName("")
                    .withNewCsi()
                        .withDriver("nfs.csi.k8s.io")
                        .withVolumeHandle(pvName)
                        .addToVolumeAttributes("server", posixNfsServer)
                        .addToVolumeAttributes("share", nfsPath)
                    .endCsi()
                    .withMountOptions(List.of("nfsvers=4"))
                .endSpec()
                .build();
            client.persistentVolumes().resource(pv).create();
            LOG.info("Shared file storage PV created: " + pvName + " -> " + posixNfsServer + ":" + nfsPath);
        }

        // Create PVC if absent
        PersistentVolumeClaim existingPvc = client.persistentVolumeClaims()
            .inNamespace(userPodsNamespace).withName(pvcName).get();
        if (existingPvc == null) {
            PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                    .withName(pvcName)
                    .withNamespace(userPodsNamespace)
                    .addToLabels("app", "k8s-pups-user")
                    .addToLabels("managed-by", "k8s-pups")
                    .addToLabels("user", UserKey.encode(userId))
                    .addToLabels("storage-type", "posix")
                .endMetadata()
                .withNewSpec()
                    .withAccessModes("ReadWriteMany")
                    .withStorageClassName("")
                    .withVolumeName(pvName)
                    .withNewResources()
                        .addToRequests("storage", new Quantity(storageSize))
                    .endResources()
                .endSpec()
                .build();
            client.persistentVolumeClaims().inNamespace(userPodsNamespace).resource(pvc).create();
            LOG.info("Shared file storage PVC created: " + pvcName + " (" + storageSize + ")");
        }
    }

    /**
     * Ensures the user directory exists on the NFS server for the shared file storage of the nfs backend.
     * Runs a temporary Pod that mounts the NFS base path and creates the user subdirectory
     * with ownership set recursively to the tool Pod IDs ({@code k8spups.tool-pod.uid} and
     * {@code k8spups.tool-pod.gid}, see ToolPodIds), or 1000:1000 when they are not configured.
     *
     * The chown is recursive (-R) so that a pre-existing, stale, or legacy root-owned
     * directory (and any root-owned skeleton files left inside it by an earlier
     * root-based seeding) is corrected to the non-root pod UID. Without -R the top
     * directory or its contents could remain root-owned, leaving the non-root tool
     * container unable to write and uploads failing with permission denied.
     */
    /** A directory name as a part of a Pod name: lower-case letters, digits and hyphens, not starting with a hyphen. */
    static String podNameToken(String directory) {
        String s = directory.toLowerCase().replaceAll("[^a-z0-9-]", "-");
        return s.startsWith("-") ? "g" + s : s;
    }

    private void ensureNfsDirectory(String sanitizedUserId) {
        ToolPodIds ids = ToolPodIds.resolve(1000L, toolPodUid, toolPodGid);
        String cmd = "mkdir -p /mnt/nfs-base/" + sanitizedUserId
            + " && chown -R " + ids.uid() + ":" + ids.gid() + " /mnt/nfs-base/" + sanitizedUserId
            + " && echo 'Directory ready'";
        runNfsScriptPod("posix-nfs-init-" + podNameToken(sanitizedUserId), "k8s-pups-nfs-init",
            posixNfsServer, posixNfsBasePath, cmd);
    }

    /**
     * Runs a script in a short-lived busybox Pod that mounts {@code basePath} of an NFS server
     * read-write at /mnt/nfs-base, waits up to 60 seconds, and deletes the Pod.
     *
     * @return true when the Pod reached the Succeeded phase
     */
    private boolean runNfsScriptPod(String namePrefix, String appLabel, String server, String basePath, String script) {
        return runScriptPod(namePrefix, appLabel, new VolumeBuilder().withName("nfs-base")
            .withNewNfs().withServer(server).withPath(basePath).endNfs().build(), script);
    }

    /** Runs a script in a short-lived Pod that mounts the claim read-write at /mnt/nfs-base (the dataset root PVC). */
    private boolean runPvcScriptPod(String namePrefix, String appLabel, String claimName, String script) {
        return runScriptPod(namePrefix, appLabel, new VolumeBuilder().withName("nfs-base")
            .withNewPersistentVolumeClaim().withClaimName(claimName).withReadOnly(false).endPersistentVolumeClaim().build(), script);
    }

    private boolean runScriptPod(String namePrefix, String appLabel, Volume baseVolume, String script) {
        String podName = namePrefix + "-" + System.currentTimeMillis() % 100000;
        Pod initPod = new PodBuilder()
            .withNewMetadata()
                .withName(podName)
                .withNamespace(userPodsNamespace)
                .addToLabels("app", appLabel)
                .addToLabels("managed-by", "k8s-pups")
            .endMetadata()
            .withNewSpec()
                .withRestartPolicy("Never")
                .withVolumes(baseVolume)
                .addNewContainer()
                    .withName("init")
                    .withImage(scriptPodImage)
                    .withCommand("sh", "-c", script)
                    .addNewVolumeMount()
                        .withName("nfs-base")
                        .withMountPath("/mnt/nfs-base")
                    .endVolumeMount()
                .endContainer()
            .endSpec()
            .build();

        boolean succeeded = false;
        try {
            client.pods().inNamespace(userPodsNamespace).resource(initPod).create();
            LOG.info("NFS script pod created: " + podName);

            // Wait for the Pod to complete (up to 60 seconds)
            for (int i = 0; i < 60; i++) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                Pod pod = client.pods().inNamespace(userPodsNamespace).withName(podName).get();
                if (pod == null) break;
                String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
                if ("Succeeded".equals(phase)) {
                    succeeded = true;
                    break;
                } else if ("Failed".equals(phase)) {
                    LOG.warning("NFS script pod failed: " + podName);
                    break;
                }
            }
        } finally {
            try {
                client.pods().inNamespace(userPodsNamespace).withName(podName).delete();
            } catch (Exception e) {
                LOG.warning("Failed to delete NFS script pod " + podName + ": " + e.getMessage());
            }
        }
        return succeeded;
    }

    // -- Dataset entitlement views --

    /** Directory in a tool container under which every granted dataset is mounted as {@code <path>/<dataset>}. */
    public String datasetMountPath() {
        return datasetMountPath;
    }

    /** Mount path of the user's file-level view (the files granted one by one) inside a tool container. */
    public String datasetFilesMountPath() {
        return datasetFilesMountPath;
    }

    /** PVC name of the user's read-only volume on the original directory (whole datasets are subPaths of it). */
    public String datasetRootPvcName(String userId) {
        return datasetRootClaimName(datasetRootStorageClass, sanitizeUserId(userId));
    }

    /** The shared claim when the dataset root is a PVC of a storage class; otherwise the user's own NFS claim. */
    static String datasetRootClaimName(String rootStorageClass, String sanitizedUserId) {
        return rootStorageClass == null || rootStorageClass.isBlank()
            ? "pups-dataset-root-" + sanitizedUserId
            : "pups-dataset-root";
    }

    private String datasetRootPvName(String userId) {
        return datasetRootPvcName(userId) + "-" + userPodsNamespace;
    }

    /** Creates the user's read-only PV/PVC on the original directory if absent (no Pod is needed: nothing is built). */
    public void ensureDatasetRootPvPvc(String userId) {
        if (!datasetRootStorageClass.isBlank()) {
            ensureDatasetRootClaim();
            return;
        }
        String pvName = datasetRootPvName(userId);
        String pvcName = datasetRootPvcName(userId);
        String path = datasetBasePath + "/" + datasetOriginalDir;
        if (client.persistentVolumes().withName(pvName).get() == null) {
            PersistentVolume pv = new PersistentVolumeBuilder()
                .withNewMetadata().withName(pvName)
                    .addToLabels("app", "k8s-pups-user").addToLabels("managed-by", "k8s-pups")
                    .addToLabels("user", UserKey.encode(userId)).addToLabels("storage-type", "dataset-root")
                .endMetadata()
                .withNewSpec()
                    .withCapacity(Map.of("storage", new Quantity("1Ti")))
                    .withAccessModes("ReadOnlyMany")
                    .withPersistentVolumeReclaimPolicy("Retain")
                    .withStorageClassName("")
                    .withNewCsi().withDriver("nfs.csi.k8s.io").withVolumeHandle(pvName).withReadOnly(true)
                        .addToVolumeAttributes("server", datasetNfsServer).addToVolumeAttributes("share", path)
                    .endCsi()
                    .withMountOptions(List.of("nfsvers=4", "ro"))
                .endSpec().build();
            client.persistentVolumes().resource(pv).create();
            LOG.info("Dataset root PV created: " + pvName + " -> " + datasetNfsServer + ":" + path);
        }
        if (client.persistentVolumeClaims().inNamespace(userPodsNamespace).withName(pvcName).get() == null) {
            PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata().withName(pvcName).withNamespace(userPodsNamespace)
                    .addToLabels("app", "k8s-pups-user").addToLabels("managed-by", "k8s-pups")
                    .addToLabels("user", UserKey.encode(userId)).addToLabels("storage-type", "dataset-root")
                .endMetadata()
                .withNewSpec().withAccessModes("ReadOnlyMany").withStorageClassName("").withVolumeName(pvName)
                    .withNewResources().addToRequests("storage", new Quantity("1Ti")).endResources()
                .endSpec().build();
            client.persistentVolumeClaims().inNamespace(userPodsNamespace).resource(pvc).create();
            LOG.info("Dataset root PVC created: " + pvcName);
        }
    }

    /** Creates the shared PVC of the dataset root (dynamic volume of datasetRootStorageClass) if absent. */
    private void ensureDatasetRootClaim() {
        String pvcName = datasetRootClaimName(datasetRootStorageClass, "");
        if (client.persistentVolumeClaims().inNamespace(userPodsNamespace).withName(pvcName).get() != null) return;
        PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
            .withNewMetadata().withName(pvcName).withNamespace(userPodsNamespace)
                .addToLabels("app", "k8s-pups-dataset-root").addToLabels("managed-by", "k8s-pups")
            .endMetadata()
            .withNewSpec().withAccessModes("ReadWriteMany").withStorageClassName(datasetRootStorageClass)
                .withNewResources().addToRequests("storage", new Quantity(datasetRootStorageSize)).endResources()
            .endSpec().build();
        client.persistentVolumeClaims().inNamespace(userPodsNamespace).resource(pvc).create();
        LOG.info("Dataset root PVC created: " + pvcName + " (" + datasetRootStorageClass + ", " + datasetRootStorageSize + ")");
    }

    /** PVC name of the user's dataset view. */
    public String datasetPvcName(String userId) {
        return "pups-dataset-" + sanitizeUserId(userId);
    }

    private String datasetPvName(String userId) {
        return datasetPvcName(userId) + "-" + userPodsNamespace;
    }

    /**
     * Rebuilds the user's view directory on the NFS server so that it holds hardlinks to exactly
     * {@code paths} (relative to the original directory), then makes sure the user's read-only
     * PV/PVC for that directory exists.
     *
     * @return true when the view is in the requested state; false when it could not be built
     *         (the caller must then not mount it, because it may still list revoked files)
     */
    /**
     * Whether the file-level view of datasets can be built: on the NFS server of the datasets (it needs that
     * location), or inside the dataset root PVC when the dataset root is a PVC of a storage class (Lustre on a007).
     */
    public static boolean datasetViewSupported(String rootStorageClass, String nfsServer, String basePath) {
        return !rootStorageClass.isBlank() || (!nfsServer.isBlank() && !basePath.isBlank());
    }

    /** {@link #datasetViewSupported} for this deployment; the launch form offers the granted files only when true. */
    public boolean canBuildDatasetView() {
        return datasetViewSupported(datasetRootStorageClass, datasetNfsServer, datasetBasePath);
    }

    /**
     * Builds the user's view of the files granted one by one. On NFS it is a directory next to the original files, mounted
     * through the user's own PV. Where the dataset root is a PVC it is the directory {@link #PVC_VIEW_DIR}/{user} inside
     * that PVC, built in place; a tool Pod mounts only that directory (a subPath of the shared claim).
     */
    public boolean syncDatasetView(String userId, List<String> paths) {
        if (!datasetRootStorageClass.isBlank()) {
            ensureDatasetRootClaim();
            String user = sanitizeUserId(userId);
            String script = com.scivicslab.k8spups.dataset.DatasetViewScript.build("", PVC_VIEW_DIR, user, paths);
            return runPvcScriptPod("dataset-view-" + user, "k8s-pups-dataset-sync", datasetRootClaimName(datasetRootStorageClass, ""), script);
        }
        if (datasetNfsServer.isBlank() || datasetBasePath.isBlank()) {
            LOG.warning("Dataset NFS location is not configured; view not built for " + userId);
            return false;
        }
        String user = sanitizeUserId(userId);
        String script = com.scivicslab.k8spups.dataset.DatasetViewScript.build(
            datasetOriginalDir, datasetViewDir, user, paths);
        if (!runNfsScriptPod("dataset-view-" + user, "k8s-pups-dataset-sync",
                datasetNfsServer, datasetBasePath, script)) {
            return false;
        }
        ensureDatasetPvPvc(userId);
        return true;
    }

    /** Creates the user's read-only PV/PVC for the view directory if absent. */
    private void ensureDatasetPvPvc(String userId) {
        String pvName = datasetPvName(userId);
        String pvcName = datasetPvcName(userId);
        String viewPath = datasetBasePath + "/" + datasetViewDir + "/" + sanitizeUserId(userId);

        if (client.persistentVolumes().withName(pvName).get() == null) {
            PersistentVolume pv = new PersistentVolumeBuilder()
                .withNewMetadata()
                    .withName(pvName)
                    .addToLabels("app", "k8s-pups-user")
                    .addToLabels("managed-by", "k8s-pups")
                    .addToLabels("user", UserKey.encode(userId))
                    .addToLabels("storage-type", "dataset")
                .endMetadata()
                .withNewSpec()
                    .withCapacity(Map.of("storage", new Quantity("1Ti")))
                    .withAccessModes("ReadOnlyMany")
                    .withPersistentVolumeReclaimPolicy("Retain")
                    .withStorageClassName("")
                    .withNewCsi()
                        .withDriver("nfs.csi.k8s.io")
                        .withVolumeHandle(pvName)
                        .withReadOnly(true)
                        .addToVolumeAttributes("server", datasetNfsServer)
                        .addToVolumeAttributes("share", viewPath)
                    .endCsi()
                    .withMountOptions(List.of("nfsvers=4", "ro"))
                .endSpec()
                .build();
            client.persistentVolumes().resource(pv).create();
            LOG.info("Dataset PV created: " + pvName + " -> " + datasetNfsServer + ":" + viewPath);
        }

        if (client.persistentVolumeClaims().inNamespace(userPodsNamespace).withName(pvcName).get() == null) {
            PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                    .withName(pvcName)
                    .withNamespace(userPodsNamespace)
                    .addToLabels("app", "k8s-pups-user")
                    .addToLabels("managed-by", "k8s-pups")
                    .addToLabels("user", UserKey.encode(userId))
                    .addToLabels("storage-type", "dataset")
                .endMetadata()
                .withNewSpec()
                    .withAccessModes("ReadOnlyMany")
                    .withStorageClassName("")
                    .withVolumeName(pvName)
                    .withNewResources()
                        .addToRequests("storage", new Quantity("1Ti"))
                    .endResources()
                .endSpec()
                .build();
            client.persistentVolumeClaims().inNamespace(userPodsNamespace).resource(pvc).create();
            LOG.info("Dataset PVC created: " + pvcName);
        }
    }

    /**
     * Deletes a user's PVC (and PV for NFS types).
     * Refuses to delete if the PVC is currently mounted by a Pod.
     *
     * @return true if deleted, false if in-use or not found
     */
    public boolean deleteUserPvc(String userId, String storageType) {
        String pvcName = userPvcName(userId, storageType);
        PersistentVolumeClaim pvc = client.persistentVolumeClaims()
            .inNamespace(userPodsNamespace).withName(pvcName).get();
        if (pvc == null) {
            LOG.info("PVC not found for deletion: " + pvcName);
            return false;
        }
        if (isUserPvcInUse(userId, storageType)) {
            LOG.warning("Cannot delete PVC " + pvcName + ": currently mounted by a Pod");
            return false;
        }

        // Delete PVC
        client.persistentVolumeClaims().inNamespace(userPodsNamespace).withName(pvcName).delete();
        LOG.info("PVC deleted: " + pvcName);

        // With the nfs backend the PV is ours (static), so it goes with the PVC
        if ("posix".equals(storageType) && posixUsesNfs()) {
            String pvName = userPvName(userId, storageType);
            try {
                client.persistentVolumes().withName(pvName).delete();
                LOG.info("PV deleted: " + pvName);
            } catch (Exception e) {
                LOG.warning("Failed to delete PV " + pvName + ": " + e.getMessage());
            }
        }
        return true;
    }

    /**
     * Returns the names of Pods currently mounting a user's PVC.
     */
    public List<String> findPodsUsingPvc(String userId, String storageType) {
        String pvcName = userPvcName(userId, storageType);
        List<String> podNames = new ArrayList<>();
        var pods = client.pods().inNamespace(userPodsNamespace)
            .withLabel("managed-by", "k8s-pups").list().getItems();
        for (var pod : pods) {
            if (pod.getSpec() == null || pod.getSpec().getVolumes() == null) continue;
            // Only count Running/Pending pods (not terminated)
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "";
            if (!"Running".equals(phase) && !"Pending".equals(phase)) continue;
            for (var vol : pod.getSpec().getVolumes()) {
                if (vol.getPersistentVolumeClaim() != null
                        && pvcName.equals(vol.getPersistentVolumeClaim().getClaimName())) {
                    podNames.add(pod.getMetadata().getName());
                }
            }
        }
        return podNames;
    }

    /**
     * Checks if a user's PVC is currently mounted by any Pod.
     */
    public boolean isUserPvcInUse(String userId, String storageType) {
        return !findPodsUsingPvc(userId, storageType).isEmpty();
    }

    /**
     * Returns PVC info for a specific storage type.
     */
    public Map<String, String> getUserPvcInfo(String userId, String storageType) {
        String pvcName = userPvcName(userId, storageType);
        PersistentVolumeClaim pvc = client.persistentVolumeClaims()
            .inNamespace(userPodsNamespace).withName(pvcName).get();
        if (pvc == null) {
            return Map.of("exists", "false");
        }
        Quantity size = pvc.getSpec().getResources().getRequests().get("storage");
        String phase = pvc.getStatus() != null && pvc.getStatus().getPhase() != null
            ? pvc.getStatus().getPhase() : "Unknown";
        String sc = pvc.getSpec().getStorageClassName() != null
            ? pvc.getSpec().getStorageClassName() : "";
        List<String> usingPods = findPodsUsingPvc(userId, storageType);
        boolean inUse = !usingPods.isEmpty();
        Map<String, String> result = new HashMap<>();
        result.put("exists", "true");
        result.put("size", size != null ? size.toString() : "0");
        result.put("phase", phase);
        result.put("storageClass", sc);
        result.put("inUse", String.valueOf(inUse));
        result.put("usedBy", String.join(",", usingPods));
        return result;
    }

    /**
     * Returns PVC info for all storage types (posix, longhorn).
     */
    public Map<String, Object> getAllUserPvcInfo(String userId) {
        Map<String, Object> result = new HashMap<>();
        for (String type : List.of("posix", "longhorn")) {
            result.put(type, getUserPvcInfo(userId, type));
        }
        return result;
    }

    /**
     * Reads the user's storage preferences from their ConfigMap.
     * Returns all ConfigMap data including activeStorageType.
     */
    public Map<String, String> getUserStorageInfo(String userId) {
        String cmName = userPrefsConfigMapName(userId);
        ConfigMap cm = client.configMaps()
            .inNamespace(userPodsNamespace)
            .withName(cmName)
            .get();
        if (cm == null || cm.getData() == null) {
            return Collections.emptyMap();
        }
        return new HashMap<>(cm.getData());
    }

    /**
     * Updates a single key in the user's preferences ConfigMap.
     * Creates the ConfigMap if it does not exist.
     */
    private void updateUserPref(String userId, String key, String value) {
        String cmName = userPrefsConfigMapName(userId);
        ConfigMap existing = client.configMaps()
            .inNamespace(userPodsNamespace)
            .withName(cmName)
            .get();
        if (existing != null) {
            if (existing.getData() == null) {
                existing.setData(new HashMap<>());
            }
            existing.getData().put(key, value);
            client.configMaps().inNamespace(userPodsNamespace)
                .resource(existing).update();
        } else {
            ConfigMap cm = new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(cmName)
                    .withNamespace(userPodsNamespace)
                    .addToLabels("app", "k8s-pups-user")
                    .addToLabels("managed-by", "k8s-pups")
                    .addToLabels("user", UserKey.encode(userId))
                .endMetadata()
                .addToData(key, value)
                .build();
            client.configMaps().inNamespace(userPodsNamespace).resource(cm).create();
        }
    }

    private String userPrefsConfigMapName(String userId) {
        return "pups-prefs-" + sanitizeUserId(userId);
    }

    /**
     * Compares two Quantity values in GiB.
     * Returns positive if a > b, negative if a < b, zero if equal.
     */
    private int compareSizeGi(Quantity a, Quantity b) {
        return Double.compare(
            a.getNumericalAmount().doubleValue(),
            b.getNumericalAmount().doubleValue()
        );
    }

    // -- Internal --

    private ResourceProfile resolveProfile(ToolPlugin plugin, String profileName) {
        List<ResourceProfile> profiles = plugin.resourceProfiles();
        if (profileName != null) {
            for (ResourceProfile p : profiles) {
                if (p.name().equals(profileName)) {
                    return p;
                }
            }
        }
        return profiles.get(0);
    }

    private static final java.util.regex.Pattern PLAIN_DIR = java.util.regex.Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");

    /**
     * The shell commands that make ready the mount points of the extra mounts that lie under the tool's home, run by the
     * init container as the user. A mount point that the kubelet makes itself is owned by root and stays in the home after
     * the session, where the user cannot remove it. So: the empty directories that an earlier session left under
     * {@code datasets/}, {@code groups/} and the like are removed (empty ones only), and this session's mount points are
     * made as the user. Paths with anything but plain segments are left to the kubelet (they end up in a shell script).
     *
     * @param home       the directory the tool mounts the user's storage at (the root of the storage in the Pod)
     * @param mountPaths the paths of the extra mounts
     * @return the commands (the cleaning is done even when there is no extra mount now)
     */
    static String mountPointPrep(String home, java.util.List<String> mountPaths) {
        String prefix = home.endsWith("/") ? home : home + "/";
        // The default parents of the launch form are always cleaned: a session without extra mounts must not show the mount
        // points of the last one.
        java.util.TreeSet<String> parents = new java.util.TreeSet<>(java.util.List.of("datasets", "groups"));
        java.util.TreeSet<String> dirs = new java.util.TreeSet<>();
        for (String path : mountPaths) {
            if (!path.startsWith(prefix)) continue;
            String rel = path.substring(prefix.length());
            if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
            if (rel.isEmpty() || !PLAIN_DIR.matcher(rel).matches() || rel.contains("..")) continue;
            dirs.add(rel);
            int slash = rel.indexOf('/');
            if (slash > 0) parents.add(rel.substring(0, slash));
        }
        StringBuilder sb = new StringBuilder("cd /mnt/pvc");
        for (String parent : parents) {
            sb.append("; for s in '").append(parent).append("'/*; do [ -d \"$s\" ] && rmdir \"$s\" 2>/dev/null; done");
        }
        sb.append("; rmdir 'dataset-files' 'data' 2>/dev/null");
        if (!dirs.isEmpty()) {
            sb.append("; mkdir -p");
            for (String d : dirs) sb.append(" '").append(d).append("'");
        }
        // The init container must succeed whatever was or was not there to remove (the exit status is that of the last command).
        sb.append("; true");
        return sb.toString();
    }

    /**
     * Copies the image's /etc/passwd and /etc/group to /identity and adds the Pod's own UID and GID when the image
     * has no entry for them (it never has: the ID is the cluster's, not the image's). The tool container mounts the
     * result over /etc/passwd and /etc/group, so this works for every image, with a read-only root filesystem too.
     */
    static final String USER_ENTRY_SCRIPT =
        "set -e; cp /etc/passwd /identity/passwd; cp /etc/group /identity/group; "
        + "uid=$(id -u); gid=$(id -g); "
        + "id -un >/dev/null 2>&1 || echo \"pups:x:$uid:$gid:k8s-pups user:${PUPS_HOME:-/}:/bin/sh\" >> /identity/passwd; "
        + "id -gn >/dev/null 2>&1 || echo \"pups:x:$gid:\" >> /identity/group";

    private SecurityContext buildContainerSecurityContext(ToolPlugin plugin, boolean readOnlyRoot) {
        CapabilitiesBuilder caps = new CapabilitiesBuilder().addToDrop("ALL");
        for (String cap : plugin.capabilities()) {
            caps.addToAdd(cap);
        }
        return new SecurityContextBuilder()
            .withAllowPrivilegeEscalation(false)
            .withReadOnlyRootFilesystem(readOnlyRoot)
            .withCapabilities(caps.build())
            .build();
    }

    private Probe buildReadinessProbe(ToolPlugin plugin, String sessionId) {
        if (plugin.readinessProbePath() == null) {
            return null;
        }
        return new ProbeBuilder()
            .withNewHttpGet()
                .withPath(SessionPath.expand(plugin.readinessProbePath(), sessionId))
                .withPort(new IntOrString(plugin.containerPort()))
            .endHttpGet()
            .withInitialDelaySeconds(plugin.readinessProbeInitialDelay())
            .withPeriodSeconds(plugin.readinessProbePeriod())
            .withFailureThreshold(15)
            .build();
    }

    /** Delegates to SessionEnvBuilder. Kept for call sites inside this class. */
    List<EnvVar> buildEnvVars(SessionInfo info) {
        return new SessionEnvBuilder(basePath, controllerUrl).build(info);
    }

    private Pod buildPodSpec(SessionInfo info) {
        ToolPlugin plugin = info.toolPlugin();
        List<EnvVar> envVars = buildEnvVars(info);

        // Build resource requirements from selected profile
        ResourceProfile profile = resolveProfile(plugin, info.resourceProfile());
        Map<String, Quantity> profileRequests = new HashMap<>();
        profile.requests().forEach((k, v) -> profileRequests.put(k, new Quantity(v)));
        Map<String, Quantity> profileLimits = new HashMap<>();
        profile.limits().forEach((k, v) -> profileLimits.put(k, new Quantity(v)));
        LOG.info("Pod " + info.podName() + " using resource profile: " + profile.name()
            + " (" + profile.displayName() + ")");

        // Storage type determines what gets mounted at userDataMountPath:
        //   longhorn  -> Longhorn PVC (RWO block storage)
        //   posix     -> shared file storage PVC (RWX)
        // Session resolves the storage type and writes it into SessionInfo before
        // creating the Pod. This fallback only covers callers that build a Pod without
        // going through Session, and must agree with Session.resolveStorageType():
        // a hardcoded "longhorn" here mounted a PVC that was never provisioned.
        String storageType = info.userStorageType() != null && !info.userStorageType().isBlank()
            ? info.userStorageType()
            : System.getenv().getOrDefault("K8SPUPS_DEFAULT_STORAGE_TYPE", "posix");

        Map<String, String> labels = Map.of(
            "app", "k8s-pups-user",
            "managed-by", "k8s-pups",
            "tool", plugin.name(),
            "session", info.sessionId(),
            "user", UserKey.encode(info.userId()),
            "storage-type", storageType
        );

        return buildSingleContainerPod(info, plugin, envVars, profileRequests, profileLimits,
            storageType, labels);
    }

    /**
     * The group that owns the dataset files, for a Pod that mounts a dataset (whole or file-level); nothing for other
     * Pods. The files are readable by this group only, so a Pod without a dataset mount cannot read them even if it
     * reached the files.
     */
    List<Long> datasetSupplementalGroups(SessionInfo info) {
        if (datasetGid == null || info.additionalMounts() == null) return List.of();
        boolean hasDataset = info.additionalMounts().stream().anyMatch(m -> isDatasetStorageType(m.storageType()));
        return hasDataset ? List.of(datasetGid) : List.of();
    }

    /**
     * Builds the single-container Pod of a session.
     * The user PVC of the storage type (posix / longhorn) is mounted at userDataMountPath.
     */
    private Pod buildSingleContainerPod(SessionInfo info, ToolPlugin plugin,
            List<EnvVar> envVars, Map<String, Quantity> requests, Map<String, Quantity> limits,
            String storageType, Map<String, String> labels) {

        // Volume mounts: /tmp always + plugin-specific writable paths + storage volume
        List<VolumeMount> mounts = new ArrayList<>();
        mounts.add(new VolumeMountBuilder().withName("tmp").withMountPath("/tmp").build());
        for (int i = 0; i < plugin.writablePaths().size(); i++) {
            mounts.add(new VolumeMountBuilder()
                .withName("writable-" + i)
                .withMountPath(plugin.writablePaths().get(i))
                .build());
        }
        if (plugin.userDataMountPath() != null) {
            // Mount the user PVC at userDataMountPath
            var userDataMount = new VolumeMountBuilder()
                .withName("user-data")
                .withMountPath(plugin.userDataMountPath());
            if (plugin.userDataSubPath() != null) userDataMount.withSubPath(plugin.userDataSubPath());
            mounts.add(userDataMount.build());
        }

        List<Volume> volumes = new ArrayList<>();
        volumes.add(new VolumeBuilder().withName("tmp")
            .withNewEmptyDir().withSizeLimit(new Quantity("1Gi")).endEmptyDir().build());
        for (int i = 0; i < plugin.writablePaths().size(); i++) {
            volumes.add(new VolumeBuilder().withName("writable-" + i)
                .withNewEmptyDir().withSizeLimit(new Quantity("500Mi")).endEmptyDir().build());
        }
        ToolPodIds ids = ToolPodIds.resolve(plugin.runAsUser(), toolPodUid, toolPodGid);
        Long runAsUid = ids.uid();
        Long runAsGid = ids.gid();

        // A tool Pod runs as a number (the cluster's own when one is configured), which the image's /etc/passwd
        // may not list. /etc/passwd and /etc/group then get that ID added, so that whoami, git, ssh, dbus and the
        // like find the user. See USER_ENTRY_SCRIPT.
        boolean addsUserEntry = runAsUid != null;
        if (addsUserEntry) {
            volumes.add(new VolumeBuilder().withName("identity")
                .withNewEmptyDir().withSizeLimit(new Quantity("1Mi")).endEmptyDir().build());
            mounts.add(new VolumeMountBuilder().withName("identity").withMountPath("/etc/passwd").withSubPath("passwd").build());
            mounts.add(new VolumeMountBuilder().withName("identity").withMountPath("/etc/group").withSubPath("group").build());
        }
        if (plugin.userDataMountPath() != null) {
            // The type-specific user PVC
            volumes.add(new VolumeBuilder().withName("user-data")
                .withNewPersistentVolumeClaim()
                    .withClaimName(userPvcName(info.userId(), storageType))
                    .withReadOnly(false)
                .endPersistentVolumeClaim()
                .build());
        }

        // Additional mounts (secondary PVCs at user-specified paths). A claim gets one volume, however many paths it is
        // mounted at (the home storage again, or several datasets of the same dataset root): a second volume of the
        // same claim hangs the Pod on a CSI driver that mounts a claim once per node (Lustre on a007).
        if (info.additionalMounts() != null) {
            java.util.Map<String, String> volumeOfClaim = new java.util.HashMap<>();
            if (plugin.userDataMountPath() != null) volumeOfClaim.put(userPvcName(info.userId(), storageType), "user-data");
            int idx = 0;
            for (MountSpec extra : info.additionalMounts()) {
                boolean dataset = isDatasetStorageType(extra.storageType());
                String pvcName = DATASET_ROOT_STORAGE_TYPE.equals(extra.storageType()) ? datasetRootPvcName(info.userId())
                    : dataset ? datasetPvcName(info.userId())
                    : GROUP_STORAGE_TYPE.equals(extra.storageType()) ? groupPvcName(extra.sharedFrom())
                    : userPvcName(info.userId(), extra.storageType());
                boolean readOnly = dataset || extra.readOnly();
                String subPath = extra.subPath();
                if (DATASET_STORAGE_TYPE.equals(extra.storageType()) && !datasetRootStorageClass.isBlank()) {
                    // The view of the granted files is a directory of the shared dataset root claim (see syncDatasetView).
                    pvcName = datasetRootPvcName(info.userId());
                    subPath = PVC_VIEW_DIR + "/" + sanitizeUserId(info.userId());
                }

                String volName = volumeOfClaim.get(pvcName);
                if (volName == null) {
                    volName = "extra-data-" + idx;
                    volumeOfClaim.put(pvcName, volName);
                    volumes.add(new VolumeBuilder().withName(volName)
                        .withNewPersistentVolumeClaim()
                            .withClaimName(pvcName)
                            .withReadOnly(readOnly)
                        .endPersistentVolumeClaim()
                        .build());
                }
                var mountBuilder = new VolumeMountBuilder()
                    .withName(volName)
                    .withMountPath(extra.mountPath())
                    .withReadOnly(readOnly);
                if (subPath != null) mountBuilder.withSubPath(subPath);
                mounts.add(mountBuilder.build());
                idx++;
            }
        }

        // Plugin-defined NFS volumes (e.g. build output directory)
        int nfsIdx = 0;
        for (var nfsVol : plugin.nfsVolumes()) {
            String volName = "nfs-plugin-" + nfsIdx;
            mounts.add(new VolumeMountBuilder()
                .withName(volName)
                .withMountPath(nfsVol.mountPath())
                .withReadOnly(nfsVol.readOnly())
                .build());
            volumes.add(new VolumeBuilder().withName(volName)
                .withNewNfs()
                    .withServer(nfsVol.server())
                    .withPath(nfsVol.path())
                    .withReadOnly(nfsVol.readOnly())
                .endNfs()
                .build());
            nfsIdx++;
        }


        // Init container: seed PVC with /etc/skel if empty.
        // Runs as the pod-level UID (same as the main container).
        // NFS directory ownership is set to the tool Pod IDs at PVC creation time by
        // ensureNfsDirectory(), so the pod-level UID can write here without root.
        List<io.fabric8.kubernetes.api.model.Container> initContainers = new ArrayList<>();
        if (plugin.userDataMountPath() != null) {
            String seedCmd =
                "if [ -z \"$(ls -A /mnt/pvc 2>/dev/null | sed '/^lost+found$/d')\" ]; then "
                + "echo 'PVC is empty, seeding from /etc/skel'; "
                + "cp -a /etc/skel/. /mnt/pvc/ 2>/dev/null || true; "
                + "echo 'Seed complete'; "
                + "else echo 'PVC already has data, skipping seed'; fi";
            // A tool with a data directory of its own (userDataSubPath) needs that directory, not the skeleton of a home.
            if (plugin.userDataSubPath() != null) seedCmd = "mkdir -p '/mnt/pvc/" + plugin.userDataSubPath() + "'";
            else if (plugin.extraMountsUnder() != null) {
                java.util.List<String> extraPaths = info.additionalMounts() == null ? java.util.List.of()
                    : info.additionalMounts().stream().map(MountSpec::mountPath).toList();
                seedCmd = seedCmd + "; " + mountPointPrep(plugin.userDataMountPath(), extraPaths);
            }
            initContainers.add(new io.fabric8.kubernetes.api.model.ContainerBuilder()
                .withName("seed-home")
                .withImage(imageResolver.resolve(plugin))
                .withCommand("sh", "-c", seedCmd)
                .withVolumeMounts(new VolumeMountBuilder()
                    .withName("user-data")
                    .withMountPath("/mnt/pvc")
                    .build())
                .withNewSecurityContext()
                    .withAllowPrivilegeEscalation(false)
                    .withNewCapabilities().addToDrop("ALL").endCapabilities()
                .endSecurityContext()
                .withNewResources()
                    .addToRequests("cpu", new Quantity("50m"))
                    .addToRequests("memory", new Quantity("64Mi"))
                    .addToLimits("cpu", new Quantity("200m"))
                    .addToLimits("memory", new Quantity("128Mi"))
                .endResources()
                .build());
        }

        if (addsUserEntry) {
            String home = plugin.environmentVariables().getOrDefault("HOME", "/");
            initContainers.add(new io.fabric8.kubernetes.api.model.ContainerBuilder()
                .withName("user-entry")
                .withImage(imageResolver.resolve(plugin))
                .withCommand("sh", "-c", USER_ENTRY_SCRIPT)
                .withEnv(new EnvVarBuilder().withName("PUPS_HOME").withValue(home).build())
                .withVolumeMounts(new VolumeMountBuilder().withName("identity").withMountPath("/identity").build())
                .withNewSecurityContext()
                    .withAllowPrivilegeEscalation(false)
                    .withNewCapabilities().addToDrop("ALL").endCapabilities()
                .endSecurityContext()
                .withNewResources()
                    .addToRequests("cpu", new Quantity("10m"))
                    .addToRequests("memory", new Quantity("16Mi"))
                    .addToLimits("cpu", new Quantity("100m"))
                    .addToLimits("memory", new Quantity("64Mi"))
                .endResources()
                .build());
        }

        var podBuilder = new PodBuilder()
            .withNewMetadata()
                .withName(info.podName())
                .withNamespace(userPodsNamespace)
                .withLabels(labels)
            .endMetadata()
            .withNewSpec()
                .withAutomountServiceAccountToken(false)
                .withNewSecurityContext()
                    .withRunAsNonRoot(plugin.runAsNonRoot())
                    .withRunAsUser(runAsUid)
                    .withRunAsGroup(runAsGid)
                    .withFsGroup(runAsGid)
                    .withSupplementalGroups(datasetSupplementalGroups(info))
                    .withNewSeccompProfile().withType(plugin.seccompProfileType()).endSeccompProfile()
                .endSecurityContext()
                .withAffinity(buildNodeAffinity())
                .addNewContainer()
                    .withName("tool")
                    .withImage(imageResolver.resolve(plugin))
                    .withCommand(plugin.containerCommand().isEmpty() ? null : plugin.containerCommand())
                    .addNewPort()
                        .withContainerPort(plugin.containerPort())
                        .withProtocol("TCP")
                    .endPort()
                    .withEnv(envVars)
                    .withNewResources()
                        .withRequests(requests)
                        .withLimits(limits)
                    .endResources()
                    .withVolumeMounts(mounts)
                    .withSecurityContext(buildContainerSecurityContext(plugin, plugin.readOnlyRootFilesystem()))
                    .withReadinessProbe(buildReadinessProbe(plugin, info.sessionId()))
                .endContainer()
                .withVolumes(volumes)
                .withRestartPolicy("Never")
            .endSpec();

        if (!initContainers.isEmpty()) {
            podBuilder.editSpec().withInitContainers(initContainers).endSpec();
        }

        return podBuilder.build();
    }

    private Affinity buildNodeAffinity() {
        if (allowedNodes == null || allowedNodes.isEmpty()) {
            return null;
        }
        return new AffinityBuilder()
            .withNewNodeAffinity()
                .withNewRequiredDuringSchedulingIgnoredDuringExecution()
                    .addNewNodeSelectorTerm()
                        .addNewMatchExpression()
                            .withKey("kubernetes.io/hostname")
                            .withOperator("In")
                            .withValues(allowedNodes)
                        .endMatchExpression()
                    .endNodeSelectorTerm()
                .endRequiredDuringSchedulingIgnoredDuringExecution()
            .endNodeAffinity()
            .build();
    }

    // -- Cluster resource summary --

    /**
     * Returns per-node cluster resource usage by combining Node capacity/allocatable
     * with real-time metrics from metrics-server.
     */
    public Map<String, Object> getClusterResourceSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();

        var nodes = client.nodes().list().getItems();
        int nodeCount = nodes.size();
        long totalCpuMillis = 0;
        long totalMemBytes = 0;

        for (var node : nodes) {
            var allocatable = node.getStatus().getAllocatable();
            totalCpuMillis += parseCpuToMillis(allocatable.get("cpu").toString());
            totalMemBytes += parseMemoryToBytes(allocatable.get("memory").toString());
        }

        // Actual usage from metrics-server
        long usedCpuMillis = 0;
        long usedMemBytes = 0;
        boolean metricsAvailable = false;
        try {
            NodeMetricsList metricsList = client.top().nodes().metrics();
            for (NodeMetrics nm : metricsList.getItems()) {
                usedCpuMillis += parseCpuToMillis(nm.getUsage().get("cpu").toString());
                usedMemBytes += parseMemoryToBytes(nm.getUsage().get("memory").toString());
            }
            metricsAvailable = true;
        } catch (Exception e) {
            LOG.warning("Failed to fetch node metrics: " + e.getMessage());
        }

        // Sum of requests and limits from all running pods
        long requestsCpuMillis = 0;
        long requestsMemBytes = 0;
        long limitsCpuMillis = 0;
        long limitsMemBytes = 0;
        var pods = client.pods().inAnyNamespace().list().getItems();
        for (var pod : pods) {
            if (pod.getStatus() == null || !"Running".equals(pod.getStatus().getPhase())) {
                continue;
            }
            for (var container : pod.getSpec().getContainers()) {
                var resources = container.getResources();
                if (resources == null) continue;
                var req = resources.getRequests();
                if (req != null) {
                    if (req.get("cpu") != null)
                        requestsCpuMillis += parseCpuToMillis(req.get("cpu").toString());
                    if (req.get("memory") != null)
                        requestsMemBytes += parseMemoryToBytes(req.get("memory").toString());
                }
                var lim = resources.getLimits();
                if (lim != null) {
                    if (lim.get("cpu") != null)
                        limitsCpuMillis += parseCpuToMillis(lim.get("cpu").toString());
                    if (lim.get("memory") != null)
                        limitsMemBytes += parseMemoryToBytes(lim.get("memory").toString());
                }
            }
        }

        summary.put("nodeCount", nodeCount);
        summary.put("cpuCores", totalCpuMillis / 1000);
        summary.put("memoryGi", totalMemBytes / (1024L * 1024 * 1024));
        // Actual usage
        summary.put("cpuUsedCores", usedCpuMillis / 1000);
        summary.put("memoryUsedGi", usedMemBytes / (1024L * 1024 * 1024));
        summary.put("cpuPercent", totalCpuMillis > 0 ? (int) (usedCpuMillis * 100 / totalCpuMillis) : 0);
        summary.put("memoryPercent", totalMemBytes > 0 ? (int) (usedMemBytes * 100 / totalMemBytes) : 0);
        // Requests (guaranteed)
        summary.put("cpuReqCores", requestsCpuMillis / 1000);
        summary.put("memoryReqGi", requestsMemBytes / (1024L * 1024 * 1024));
        summary.put("cpuReqPercent", totalCpuMillis > 0 ? (int) (requestsCpuMillis * 100 / totalCpuMillis) : 0);
        summary.put("memoryReqPercent", totalMemBytes > 0 ? (int) (requestsMemBytes * 100 / totalMemBytes) : 0);
        // Limits (burst max)
        summary.put("cpuLimCores", limitsCpuMillis / 1000);
        summary.put("memoryLimGi", limitsMemBytes / (1024L * 1024 * 1024));
        summary.put("cpuLimPercent", totalCpuMillis > 0 ? (int) (limitsCpuMillis * 100 / totalCpuMillis) : 0);
        summary.put("memoryLimPercent", totalMemBytes > 0 ? (int) (limitsMemBytes * 100 / totalMemBytes) : 0);
        summary.put("metricsAvailable", metricsAvailable);

        // PVC storage summary
        long pvcTotalBytes = 0;
        int pvcCount = 0;
        var pvcList = client.persistentVolumeClaims().inAnyNamespace().list().getItems();
        for (var pvc : pvcList) {
            var req = pvc.getSpec().getResources().getRequests();
            if (req != null && req.get("storage") != null) {
                pvcTotalBytes += parseMemoryToBytes(req.get("storage").toString());
                pvcCount++;
            }
        }
        summary.put("pvcCount", pvcCount);
        summary.put("pvcTotalGi", pvcTotalBytes / (1024L * 1024 * 1024));

        // ResourceQuota summary across all namespaces
        long quotaStorageBytes = 0;
        boolean hasStorageQuota = false;
        var quotaList = client.resourceQuotas().inAnyNamespace().list().getItems();
        for (var quota : quotaList) {
            var hard = quota.getSpec().getHard();
            if (hard != null && hard.get("requests.storage") != null) {
                quotaStorageBytes += parseMemoryToBytes(hard.get("requests.storage").toString());
                hasStorageQuota = true;
            }
        }
        summary.put("hasStorageQuota", hasStorageQuota);
        summary.put("storageQuotaGi", quotaStorageBytes / (1024L * 1024 * 1024));

        return summary;
    }

    /** Parses CPU quantity string (e.g. "64", "3623m", "500n") to millicores. */
    private long parseCpuToMillis(String cpu) {
        return Quantity.parse(cpu).getNumericalAmount().multiply(java.math.BigDecimal.valueOf(1000)).longValue();
    }

    /**
     * Parses a memory quantity (e.g. "131728508Ki", "19274Mi", "30M", "1024") to bytes. The decimal suffixes
     * (k, M, G) and the binary ones (Ki, Mi, Gi) both occur in pod specs, so the Kubernetes parser is used.
     */
    private long parseMemoryToBytes(String mem) {
        return Quantity.parse(mem).getNumericalAmount().longValue();
    }

    // === Tool Registry Management ===

    public com.scivicslab.k8spups.tool.ToolRegistry getToolRegistry(String namespace) {
        try {
            var cm = client.configMaps().inNamespace(namespace).withName("tool-registry").get();
            if (cm == null || cm.getData() == null) {
                return new com.scivicslab.k8spups.tool.ToolRegistry(List.of());
            }
            String yaml = cm.getData().getOrDefault("tools.yaml", "");
            if (yaml.isEmpty()) {
                return new com.scivicslab.k8spups.tool.ToolRegistry(List.of());
            }
            com.fasterxml.jackson.databind.ObjectMapper yamlMapper =
                new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
            return yamlMapper.readValue(yaml, com.scivicslab.k8spups.tool.ToolRegistry.class);
        } catch (Exception e) {
            LOG.warning("Failed to read tool registry: " + e.getMessage());
            return new com.scivicslab.k8spups.tool.ToolRegistry(List.of());
        }
    }

    public com.scivicslab.k8spups.tool.ToolDescriptor getToolDescriptor(String namespace, String toolName) {
        try {
            var cm = client.configMaps().inNamespace(namespace).withName("tool-catalog").get();
            if (cm == null || cm.getData() == null) {
                return null;
            }
            String yaml = cm.getData().get(toolName + ".yaml");
            if (yaml == null || yaml.isEmpty()) {
                return null;
            }
            com.fasterxml.jackson.databind.ObjectMapper yamlMapper =
                new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
            return yamlMapper.readValue(yaml, com.scivicslab.k8spups.tool.ToolDescriptor.class);
        } catch (Exception e) {
            LOG.warning("Failed to read tool descriptor for " + toolName + ": " + e.getMessage());
            return null;
        }
    }

    public boolean isToolEnabled(String namespace, String toolName) {
        try {
            var cm = client.configMaps().inNamespace(namespace).withName("tool-config").get();
            if (cm == null || cm.getData() == null) {
                return false;
            }
            return "true".equals(cm.getData().get(
                com.scivicslab.k8spups.tool.ToolConfigStore.enableKey(toolName)));
        } catch (Exception e) {
            return false;
        }
    }

    public void saveToolCatalog(String namespace, String toolName, com.scivicslab.k8spups.tool.ToolDescriptor descriptor) {
        try {
            // Serialize descriptor to YAML
            String yamlContent = com.fasterxml.jackson.dataformat.yaml.YAMLMapper.builder().build()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(descriptor);

            Map<String, String> data = new HashMap<>();
            data.put(toolName + ".yaml", yamlContent);

            var existingCm = client.configMaps().inNamespace(namespace).withName("tool-catalog").get();
            if (existingCm != null && existingCm.getData() != null) {
                existingCm.getData().putAll(data);
                client.configMaps().inNamespace(namespace).resource(existingCm).update();
            } else {
                var newCm = new io.fabric8.kubernetes.api.model.ConfigMapBuilder()
                    .withNewMetadata()
                    .withName("tool-catalog")
                    .withNamespace(namespace)
                    .addToLabels("app", "k8s-pups-tool-catalog")
                    .addToLabels("managed-by", "k8s-pups")
                    .endMetadata()
                    .withData(data)
                    .build();
                client.configMaps().inNamespace(namespace).resource(newCm).create();
            }
        } catch (Exception e) {
            LOG.severe("Failed to save tool catalog: " + e.getMessage());
        }
    }

    public void saveToolConfig(String namespace, String toolName, Map<String, String> config) {
        try {
            Map<String, String> cmData = new HashMap<>();
            Map<String, String> secretData = new HashMap<>();
            com.scivicslab.k8spups.tool.ToolDescriptor descriptor = getToolDescriptor(namespace, toolName);
            com.scivicslab.k8spups.tool.ToolConfigStore.partition(
                toolName, config, descriptor, cmData, secretData);

            if (!cmData.isEmpty()) {
                upsertConfigMap(namespace, "tool-config", cmData);
            }
            if (!secretData.isEmpty()) {
                upsertSecret(namespace, "tool-config", secretData);
            }
        } catch (Exception e) {
            LOG.severe("Failed to save tool config: " + e.getMessage());
        }
    }

    public void saveToolEnable(String namespace, String toolName, boolean enabled) {
        try {
            Map<String, String> data = new HashMap<>();
            data.put(com.scivicslab.k8spups.tool.ToolConfigStore.enableKey(toolName),
                String.valueOf(enabled));
            upsertConfigMap(namespace, "tool-config", data);
        } catch (Exception e) {
            LOG.severe("Failed to save tool enable flag: " + e.getMessage());
        }
    }

    public Map<String, String> getToolConfig(String namespace, String toolName) {
        Map<String, String> result = new HashMap<>();
        try {
            var cm = client.configMaps().inNamespace(namespace).withName("tool-config").get();
            if (cm != null && cm.getData() != null) {
                result.putAll(com.scivicslab.k8spups.tool.ToolConfigStore
                    .extractFromConfigMap(toolName, cm.getData()));
            }
            var secret = client.secrets().inNamespace(namespace).withName("tool-config").get();
            if (secret != null && secret.getData() != null) {
                result.putAll(com.scivicslab.k8spups.tool.ToolConfigStore
                    .extractFromSecret(toolName, secret.getData()));
            }
        } catch (Exception e) {
            LOG.warning("Failed to get tool config: " + e.getMessage());
        }
        return result;
    }

    /** Reads the data of a ConfigMap; empty when it does not exist or cannot be read. */
    public Map<String, String> readConfigMapData(String namespace, String name) {
        try {
            var cm = client.configMaps().inNamespace(namespace).withName(name).get();
            return cm != null && cm.getData() != null ? new HashMap<>(cm.getData()) : new HashMap<>();
        } catch (Exception e) {
            LOG.warning("Failed to read ConfigMap " + namespace + "/" + name + ": " + e.getMessage());
            return new HashMap<>();
        }
    }

    /** Creates the ConfigMap, or replaces its whole data, so that a key left out is removed. */
    public void replaceConfigMapData(String namespace, String name, Map<String, String> data) {
        var existing = client.configMaps().inNamespace(namespace).withName(name).get();
        if (existing != null) {
            existing.setData(new HashMap<>(data));
            client.configMaps().inNamespace(namespace).resource(existing).update();
        } else {
            var cm = new ConfigMapBuilder()
                .withNewMetadata().withName(name).withNamespace(namespace)
                .addToLabels("managed-by", "k8s-pups").endMetadata()
                .withData(new HashMap<>(data)).build();
            client.configMaps().inNamespace(namespace).resource(cm).create();
        }
    }

    private void upsertConfigMap(String namespace, String name, Map<String, String> data) {
        var existing = client.configMaps().inNamespace(namespace).withName(name).get();
        if (existing != null && existing.getData() != null) {
            existing.getData().putAll(data);
            client.configMaps().inNamespace(namespace).resource(existing).update();
        } else {
            var newCm = new ConfigMapBuilder()
                .withNewMetadata().withName(name).withNamespace(namespace)
                .addToLabels("managed-by", "k8s-pups").endMetadata()
                .withData(data).build();
            client.configMaps().inNamespace(namespace).resource(newCm).create();
        }
    }

    private void upsertSecret(String namespace, String name, Map<String, String> data) {
        var existing = client.secrets().inNamespace(namespace).withName(name).get();
        if (existing != null && existing.getData() != null) {
            existing.getData().putAll(data);
            client.secrets().inNamespace(namespace).resource(existing).update();
        } else {
            var newSecret = new SecretBuilder()
                .withNewMetadata().withName(name).withNamespace(namespace)
                .addToLabels("managed-by", "k8s-pups").endMetadata()
                .withType("Opaque").withData(data).build();
            client.secrets().inNamespace(namespace).resource(newSecret).create();
        }
    }
}
