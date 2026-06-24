package com.scivicslab.k8spups.k8s;

import com.scivicslab.k8spups.plugin.ResourceProfile;
import com.scivicslab.k8spups.plugin.SidecarSpec;
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

    // NFS workspace config (home directory)
    private final String nfsServer;
    private final String nfsBasePath;

    // NFS k8s-dedicated config
    private final String nfsK8sServer;
    private final String nfsK8sBasePath;

    // Node allowlist for user pods (empty = no restriction)
    private final List<String> allowedNodes;

    private final String basePath;
    private final String controllerUrl;

    public K8sApiClient(String userPodsNamespace, String httpRouteNamespace, List<String> gatewayNames,
                        String oidcIssuer, String oidcAuthorizationEndpoint, String oidcTokenEndpoint,
                        String oidcClientId, String oidcSecretName, String oidcJwksUri,
                        String oidcRedirectBaseUrl,
                        String nfsServer, String nfsBasePath,
                        String nfsK8sServer, String nfsK8sBasePath,
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
        this.nfsServer = nfsServer;
        this.nfsBasePath = nfsBasePath;
        this.nfsK8sServer = nfsK8sServer;
        this.nfsK8sBasePath = nfsK8sBasePath;
        this.allowedNodes = allowedNodes;
        this.basePath = basePath;
        this.controllerUrl = controllerUrl;
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
                    "user", info.userId()
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
                            .withValue("/session/" + sessionId)
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
        String pathPrefix = "/session/" + sessionId + "-" + toolName + "-" + port;

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
        String redirectURL = oidcRedirectBaseUrl + "/session/" + sessionId + "/oauth2/callback";
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
        oidc.put("cookiePath", "/session/" + sessionId + "/");

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
     * Returns the sanitized userId for use in k8s resource names.
     */
    private String sanitizeUserId(String userId) {
        return userId.toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }

    /**
     * Returns the PVC name for a given userId and storage type.
     * Format: pups-data-{sanitizedUserId}-{storageType}
     */
    public String userPvcName(String userId, String storageType) {
        return "pups-data-" + sanitizeUserId(userId) + "-" + storageType;
    }

    /**
     * Returns the PVC name for a shared NFS volume.
     * Format: pups-shared-{ownerUserId}-from-{sourceUserId}
     */
    public String sharedPvcName(String ownerUserId, String sourceUserId) {
        return "pups-shared-" + sanitizeUserId(ownerUserId) + "-from-" + sanitizeUserId(sourceUserId);
    }

    /**
     * Returns the PV name for a shared NFS volume (same as PVC name).
     */
    private String sharedPvName(String ownerUserId, String sourceUserId) {
        return sharedPvcName(ownerUserId, sourceUserId);
    }

    /**
     * Creates a shared NFS PV/PVC pointing to another user's nfs-k8s directory.
     * The PV/PVC is owned by ownerUserId but references sourceUserId's NFS path.
     */
    public void createSharedNfsPvPvc(String ownerUserId, String sourceUserId, boolean readOnly) {
        String pvName = sharedPvName(ownerUserId, sourceUserId);
        String pvcName = sharedPvcName(ownerUserId, sourceUserId);
        String nfsPath = nfsK8sBasePath + "/" + sanitizeUserId(sourceUserId);

        // Create PV if absent
        if (client.persistentVolumes().withName(pvName).get() == null) {
            PersistentVolume pv = new PersistentVolumeBuilder()
                .withNewMetadata()
                    .withName(pvName)
                    .addToLabels("app", "k8s-pups-user")
                    .addToLabels("managed-by", "k8s-pups")
                    .addToLabels("user", ownerUserId)
                    .addToLabels("storage-type", "nfs-k8s-shared")
                    .addToLabels("shared-from", sanitizeUserId(sourceUserId))
                .endMetadata()
                .withNewSpec()
                    .withCapacity(Map.of("storage", new Quantity("1Ti")))
                    .withAccessModes("ReadWriteMany")
                    .withPersistentVolumeReclaimPolicy("Retain")
                    .withStorageClassName("")
                    .withNewCsi()
                        .withDriver("nfs.csi.k8s.io")
                        .withVolumeHandle(pvName)
                        .addToVolumeAttributes("server", nfsK8sServer)
                        .addToVolumeAttributes("share", nfsPath)
                    .endCsi()
                    .withMountOptions(List.of("nfsvers=4"))
                .endSpec()
                .build();
            client.persistentVolumes().resource(pv).create();
            LOG.info("Shared NFS PV created: " + pvName + " -> " + nfsK8sServer + ":" + nfsPath);
        }

        // Create PVC if absent
        if (client.persistentVolumeClaims().inNamespace(userPodsNamespace).withName(pvcName).get() == null) {
            PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                    .withName(pvcName)
                    .withNamespace(userPodsNamespace)
                    .addToLabels("app", "k8s-pups-user")
                    .addToLabels("managed-by", "k8s-pups")
                    .addToLabels("user", ownerUserId)
                    .addToLabels("storage-type", "nfs-k8s-shared")
                    .addToLabels("shared-from", sanitizeUserId(sourceUserId))
                .endMetadata()
                .withNewSpec()
                    .withAccessModes("ReadWriteMany")
                    .withStorageClassName("")
                    .withVolumeName(pvName)
                    .withNewResources()
                        .addToRequests("storage", new Quantity("1Ti"))
                    .endResources()
                .endSpec()
                .build();
            client.persistentVolumeClaims().inNamespace(userPodsNamespace).resource(pvc).create();
            LOG.info("Shared NFS PVC created: " + pvcName);
        }
    }

    /**
     * Deletes a shared NFS PV/PVC.
     */
    public void deleteSharedNfsPvPvc(String ownerUserId, String sourceUserId) {
        String pvName = sharedPvName(ownerUserId, sourceUserId);
        String pvcName = sharedPvcName(ownerUserId, sourceUserId);
        client.persistentVolumeClaims().inNamespace(userPodsNamespace).withName(pvcName).delete();
        client.persistentVolumes().withName(pvName).delete();
        LOG.info("Shared NFS PV/PVC deleted: " + pvcName);
    }

    /**
     * Lists shared PVCs owned by a user (PVCs with storage-type=nfs-k8s-shared and user=ownerUserId).
     */
    public List<Map<String, String>> listSharedPvcs(String ownerUserId) {
        List<Map<String, String>> result = new ArrayList<>();
        var pvcs = client.persistentVolumeClaims().inNamespace(userPodsNamespace)
            .withLabel("managed-by", "k8s-pups")
            .withLabel("user", ownerUserId)
            .withLabel("storage-type", "nfs-k8s-shared")
            .list().getItems();
        for (var pvc : pvcs) {
            String sharedFrom = pvc.getMetadata().getLabels().getOrDefault("shared-from", "");
            String phase = pvc.getStatus() != null && pvc.getStatus().getPhase() != null
                ? pvc.getStatus().getPhase() : "Unknown";
            boolean inUse = isSharedPvcInUse(ownerUserId, sharedFrom);
            result.add(Map.of(
                "sharedFrom", sharedFrom,
                "pvcName", pvc.getMetadata().getName(),
                "phase", phase,
                "inUse", String.valueOf(inUse)
            ));
        }
        return result;
    }

    /**
     * Checks if a shared PVC is mounted by any running/pending Pod.
     */
    public boolean isSharedPvcInUse(String ownerUserId, String sourceUserId) {
        String pvcName = sharedPvcName(ownerUserId, sourceUserId);
        var pods = client.pods().inNamespace(userPodsNamespace)
            .withLabel("managed-by", "k8s-pups").list().getItems();
        for (var pod : pods) {
            if (pod.getSpec() == null || pod.getSpec().getVolumes() == null) continue;
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "";
            if (!"Running".equals(phase) && !"Pending".equals(phase)) continue;
            for (var vol : pod.getSpec().getVolumes()) {
                if (vol.getPersistentVolumeClaim() != null
                        && pvcName.equals(vol.getPersistentVolumeClaim().getClaimName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if sourceUser allows targetUser to mount their nfs-k8s volume.
     */
    public boolean isShareAllowed(String sourceUserId, String targetUserId) {
        Map<String, String> prefs = getUserStorageInfo(sourceUserId);
        if (!"true".equals(prefs.get("share.enabled"))) return false;
        String allowList = prefs.getOrDefault("share.allow", "").trim();
        if ("*".equals(allowList)) return true;
        for (String u : allowList.split(",")) {
            if (u.trim().equals(targetUserId)) return true;
        }
        return false;
    }

    /**
     * Lists users who have sharing enabled and whose nfs-k8s PVC exists.
     * Returns list of {userId, shareMode} maps.
     */
    public List<Map<String, String>> listSharableVolumes(String requestingUserId) {
        List<Map<String, String>> result = new ArrayList<>();
        // Find all user prefs ConfigMaps
        var configMaps = client.configMaps().inNamespace(userPodsNamespace)
            .withLabel("managed-by", "k8s-pups")
            .list().getItems();
        for (var cm : configMaps) {
            if (cm.getData() == null) continue;
            if (!"true".equals(cm.getData().get("share.enabled"))) continue;
            // Extract userId from ConfigMap name (pups-prefs-{userId})
            String cmName = cm.getMetadata().getName();
            if (!cmName.startsWith("pups-prefs-")) continue;
            String sourceUserId = cmName.substring("pups-prefs-".length());
            if (sourceUserId.equals(requestingUserId)) continue; // skip self
            // Check if requester is allowed
            if (!isShareAllowed(sourceUserId, requestingUserId)) continue;
            // Check if source has nfs-k8s PVC
            Map<String, String> pvcInfo = getUserPvcInfo(sourceUserId, "nfs-k8s");
            if (!"true".equals(pvcInfo.get("exists"))) continue;
            String mode = cm.getData().getOrDefault("share.mode", "ro");
            result.add(Map.of("userId", sourceUserId, "shareMode", mode));
        }
        return result;
    }

    /**
     * Returns the PV name for NFS-based storage types (nfs-k8s, nfs-home).
     * PV is cluster-scoped; name matches PVC for easy pairing.
     */
    private String userPvName(String userId, String storageType) {
        return userPvcName(userId, storageType);
    }

    /**
     * Creates a Longhorn PVC for the user.
     * StorageClass: longhorn-single, AccessMode: ReadWriteOnce.
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
        PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
            .withNewMetadata()
                .withName(pvcName)
                .withNamespace(userPodsNamespace)
                .addToLabels("app", "k8s-pups-user")
                .addToLabels("managed-by", "k8s-pups")
                .addToLabels("user", userId)
                .addToLabels("storage-type", "longhorn")
            .endMetadata()
            .withNewSpec()
                .withStorageClassName("longhorn-single")
                .withAccessModes("ReadWriteOnce")
                .withNewResources()
                    .addToRequests("storage", new Quantity(storageSize))
                .endResources()
            .endSpec()
            .build();
        client.persistentVolumeClaims().inNamespace(userPodsNamespace).resource(pvc).create();
        LOG.info("Longhorn PVC created: " + pvcName + " (" + storageSize + ")");
    }

    /**
     * Creates a PV + PVC pair for the user's k8s-dedicated NFS storage.
     * First ensures the user directory exists on the NFS server (owned by UID 1000),
     * then creates the PV/PVC pointing to it.
     * Uses nfs.csi.k8s.io driver with the nfsK8sServer/nfsK8sBasePath config.
     * If they already exist, this is a no-op.
     * Note: NFS does not enforce the declared capacity; it is used only for display.
     *
     * @param userId      the user identifier
     * @param storageSize nominal size for display (e.g. "50Gi")
     */
    public void createNfsK8sPvPvc(String userId, String storageSize) {
        String pvName = userPvName(userId, "nfs-k8s");
        String pvcName = userPvcName(userId, "nfs-k8s");
        String sanitized = sanitizeUserId(userId);
        String nfsPath = nfsK8sBasePath + "/" + sanitized;

        // Ensure user directory exists on NFS server before creating PV
        PersistentVolume existingPv = client.persistentVolumes().withName(pvName).get();
        if (existingPv == null) {
            ensureNfsK8sDirectory(sanitized);

            PersistentVolume pv = new PersistentVolumeBuilder()
                .withNewMetadata()
                    .withName(pvName)
                    .addToLabels("app", "k8s-pups-user")
                    .addToLabels("managed-by", "k8s-pups")
                    .addToLabels("user", userId)
                    .addToLabels("storage-type", "nfs-k8s")
                .endMetadata()
                .withNewSpec()
                    .withCapacity(Map.of("storage", new Quantity(storageSize)))
                    .withAccessModes("ReadWriteMany")
                    .withPersistentVolumeReclaimPolicy("Retain")
                    .withStorageClassName("")
                    .withNewCsi()
                        .withDriver("nfs.csi.k8s.io")
                        .withVolumeHandle(pvName)
                        .addToVolumeAttributes("server", nfsK8sServer)
                        .addToVolumeAttributes("share", nfsPath)
                    .endCsi()
                    .withMountOptions(List.of("nfsvers=4"))
                .endSpec()
                .build();
            client.persistentVolumes().resource(pv).create();
            LOG.info("NFS-k8s PV created: " + pvName + " -> " + nfsK8sServer + ":" + nfsPath);
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
                    .addToLabels("user", userId)
                    .addToLabels("storage-type", "nfs-k8s")
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
            LOG.info("NFS-k8s PVC created: " + pvcName + " (" + storageSize + ")");
        }
    }

    /**
     * Ensures the user directory exists on the NFS server for nfs-k8s storage.
     * Runs a temporary Pod that mounts the NFS base path and creates the user subdirectory
     * with ownership set recursively to 1000:1000.
     *
     * The chown is recursive (-R) so that a pre-existing, stale, or legacy root-owned
     * directory (and any root-owned skeleton files left inside it by an earlier
     * root-based seeding) is corrected to the non-root pod UID. Without -R the top
     * directory or its contents could remain root-owned, leaving the non-root tool
     * container (UID 1000) unable to write and uploads failing with permission denied.
     */
    private void ensureNfsK8sDirectory(String sanitizedUserId) {
        String podName = "nfs-k8s-init-" + sanitizedUserId + "-" + System.currentTimeMillis() % 100000;
        String cmd = "mkdir -p /mnt/nfs-base/" + sanitizedUserId
            + " && chown -R 1000:1000 /mnt/nfs-base/" + sanitizedUserId
            + " && echo 'Directory ready'";

        Pod initPod = new PodBuilder()
            .withNewMetadata()
                .withName(podName)
                .withNamespace(userPodsNamespace)
                .addToLabels("app", "k8s-pups-nfs-init")
                .addToLabels("managed-by", "k8s-pups")
            .endMetadata()
            .withNewSpec()
                .withRestartPolicy("Never")
                .addNewVolume()
                    .withName("nfs-base")
                    .withNewNfs()
                        .withServer(nfsK8sServer)
                        .withPath(nfsK8sBasePath)
                    .endNfs()
                .endVolume()
                .addNewContainer()
                    .withName("init")
                    .withImage("busybox:1.36")
                    .withCommand("sh", "-c", cmd)
                    .addNewVolumeMount()
                        .withName("nfs-base")
                        .withMountPath("/mnt/nfs-base")
                    .endVolumeMount()
                .endContainer()
            .endSpec()
            .build();

        try {
            client.pods().inNamespace(userPodsNamespace).resource(initPod).create();
            LOG.info("NFS-k8s init pod created: " + podName);

            // Wait for the init pod to complete (up to 60 seconds)
            for (int i = 0; i < 60; i++) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                Pod pod = client.pods().inNamespace(userPodsNamespace).withName(podName).get();
                if (pod == null) break;
                String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
                if ("Succeeded".equals(phase)) {
                    LOG.info("NFS-k8s directory created for: " + sanitizedUserId);
                    break;
                } else if ("Failed".equals(phase)) {
                    LOG.warning("NFS-k8s init pod failed for: " + sanitizedUserId);
                    break;
                }
            }
        } finally {
            // Clean up init pod
            try {
                client.pods().inNamespace(userPodsNamespace).withName(podName).delete();
            } catch (Exception e) {
                LOG.warning("Failed to delete NFS-k8s init pod " + podName + ": " + e.getMessage());
            }
        }
    }

    /**
     * Creates a PV + PVC pair for the user's NFS home directory.
     * Requires LDAP POSIX account info (WorkspaceInfo).
     * If they already exist, this is a no-op.
     *
     * @param userId        the user identifier
     * @param workspaceInfo POSIX account info from LDAP
     */
    public void createNfsHomePvPvc(String userId, WorkspaceInfo workspaceInfo) {
        String pvName = userPvName(userId, "nfs-home");
        String pvcName = userPvcName(userId, "nfs-home");
        String nfsPath = nfsBasePath + "/" + workspaceInfo.username();

        // Create PV if absent
        PersistentVolume existingPv = client.persistentVolumes().withName(pvName).get();
        if (existingPv == null) {
            PersistentVolume pv = new PersistentVolumeBuilder()
                .withNewMetadata()
                    .withName(pvName)
                    .addToLabels("app", "k8s-pups-user")
                    .addToLabels("managed-by", "k8s-pups")
                    .addToLabels("user", userId)
                    .addToLabels("storage-type", "nfs-home")
                .endMetadata()
                .withNewSpec()
                    .withCapacity(Map.of("storage", new Quantity("1Ti")))
                    .withAccessModes("ReadWriteMany")
                    .withPersistentVolumeReclaimPolicy("Retain")
                    .withStorageClassName("")
                    .withNewCsi()
                        .withDriver("nfs.csi.k8s.io")
                        .withVolumeHandle(pvName)
                        .addToVolumeAttributes("server", nfsServer)
                        .addToVolumeAttributes("share", nfsPath)
                    .endCsi()
                    .withMountOptions(List.of("nfsvers=4"))
                .endSpec()
                .build();
            client.persistentVolumes().resource(pv).create();
            LOG.info("NFS-home PV created: " + pvName + " -> " + nfsServer + ":" + nfsPath);
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
                    .addToLabels("user", userId)
                    .addToLabels("storage-type", "nfs-home")
                .endMetadata()
                .withNewSpec()
                    .withAccessModes("ReadWriteMany")
                    .withStorageClassName("")
                    .withVolumeName(pvName)
                    .withNewResources()
                        .addToRequests("storage", new Quantity("1Ti"))
                    .endResources()
                .endSpec()
                .build();
            client.persistentVolumeClaims().inNamespace(userPodsNamespace).resource(pvc).create();
            LOG.info("NFS-home PVC created: " + pvcName);
        }
    }

    /**
     * Expands a Longhorn PVC to the given size. Only Longhorn supports expansion.
     * Fails silently if the PVC doesn't exist or the requested size is not larger.
     */
    public void expandLonghornPvc(String userId, String storageSize) {
        String pvcName = userPvcName(userId, "longhorn");
        PersistentVolumeClaim existing = client.persistentVolumeClaims()
            .inNamespace(userPodsNamespace).withName(pvcName).get();
        if (existing == null) {
            LOG.warning("Cannot expand: Longhorn PVC not found: " + pvcName);
            return;
        }
        Quantity currentSize = existing.getSpec().getResources().getRequests().get("storage");
        Quantity requestedSize = new Quantity(storageSize);
        if (currentSize != null && compareSizeGi(requestedSize, currentSize) > 0) {
            existing.getSpec().getResources().getRequests().put("storage", requestedSize);
            client.persistentVolumeClaims().inNamespace(userPodsNamespace)
                .resource(existing).update();
            LOG.info("Longhorn PVC expanded: " + pvcName + " from " + currentSize + " to " + storageSize);
        } else {
            LOG.info("No expansion needed for " + pvcName + " (current=" + currentSize + ", requested=" + storageSize + ")");
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

        // For NFS types, also delete the PV
        if ("nfs-k8s".equals(storageType) || "nfs-home".equals(storageType)) {
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
     * Returns PVC info for all storage types (longhorn, nfs-k8s, nfs-home).
     */
    public Map<String, Object> getAllUserPvcInfo(String userId) {
        Map<String, Object> result = new HashMap<>();
        for (String type : List.of("longhorn", "nfs-k8s", "nfs-home")) {
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
     * Saves the user's active storage type to the ConfigMap.
     */
    public void setActiveStorageType(String userId, String storageType) {
        updateUserPref(userId, "activeStorageType", storageType);
        // Legacy compat
        updateUserPref(userId, "storageType", storageType);
        LOG.info("Active storage type set for " + userId + ": " + storageType);
    }

    /**
     * Saves the user's NFS share settings to their ConfigMap.
     */
    public void saveShareSettings(String userId, boolean enabled, String allowList, String mode) {
        updateUserPref(userId, "share.enabled", String.valueOf(enabled));
        updateUserPref(userId, "share.allow", allowList != null ? allowList.trim() : "");
        updateUserPref(userId, "share.mode", mode != null ? mode : "ro");
        LOG.info("Share settings saved for " + userId + ": enabled=" + enabled
            + ", allow=" + allowList + ", mode=" + mode);
    }

    /**
     * Saves the user's storage preferences to a ConfigMap.
     * Creates the ConfigMap if it does not exist.
     */
    public void saveUserStoragePreference(String userId, String storageSize, String storageType) {
        String cmName = userPrefsConfigMapName(userId);
        ConfigMap existing = client.configMaps()
            .inNamespace(userPodsNamespace)
            .withName(cmName)
            .get();
        if (existing != null) {
            if (existing.getData() == null) {
                existing.setData(new HashMap<>());
            }
            existing.getData().put("storageSize", storageSize);
            existing.getData().put("storageType", storageType);
            existing.getData().put("activeStorageType", storageType);
            existing.getData().put("longhorn.size", storageSize);
            client.configMaps().inNamespace(userPodsNamespace)
                .resource(existing).update();
        } else {
            ConfigMap cm = new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(cmName)
                    .withNamespace(userPodsNamespace)
                    .addToLabels("app", "k8s-pups-user")
                    .addToLabels("managed-by", "k8s-pups")
                    .addToLabels("user", userId)
                .endMetadata()
                .addToData("storageSize", storageSize)
                .addToData("storageType", storageType)
                .addToData("activeStorageType", storageType)
                .addToData("longhorn.size", storageSize)
                .build();
            client.configMaps().inNamespace(userPodsNamespace).resource(cm).create();
        }
        LOG.info("Saved storage preference for " + userId + ": " + storageSize + " (" + storageType + ")");
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
                    .addToLabels("user", userId)
                .endMetadata()
                .addToData(key, value)
                .build();
            client.configMaps().inNamespace(userPodsNamespace).resource(cm).create();
        }
    }

    private String userPrefsConfigMapName(String userId) {
        return "pups-prefs-" + userId.toLowerCase().replaceAll("[^a-z0-9-]", "-");
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

    // -- Workspace PV/PVC operations (NFS home directory) --

    /**
     * Returns the workspace PVC name for a given userId.
     */
    private String workspacePvcName(String userId) {
        return "pups-workspace-" + userId.toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }

    /**
     * Creates a PV + PVC pair for mounting the user's NFS home directory.
     * The PV uses the nfs.csi.k8s.io CSI driver to mount the user's home directory
     * from the NFS server. The PVC binds to this specific PV via volumeName.
     *
     * <p>Both PV and PVC are created only if they don't already exist.
     * They persist across sessions (not deleted on session stop).</p>
     *
     * @param userId        the user identifier
     * @param workspaceInfo POSIX account info from LDAP
     */
    public void createWorkspacePvPvcIfAbsent(String userId, WorkspaceInfo workspaceInfo) {
        String pvName = workspacePvcName(userId);
        String pvcName = pvName;
        String nfsPath = nfsBasePath + "/" + workspaceInfo.username();

        // Create PV if absent (cluster-scoped)
        PersistentVolume existingPv = client.persistentVolumes().withName(pvName).get();
        if (existingPv == null) {
            PersistentVolume pv = new PersistentVolumeBuilder()
                .withNewMetadata()
                    .withName(pvName)
                    .addToLabels("app", "k8s-pups-user")
                    .addToLabels("managed-by", "k8s-pups")
                    .addToLabels("user", userId)
                .endMetadata()
                .withNewSpec()
                    .withCapacity(Map.of("storage", new Quantity("1Ti")))
                    .withAccessModes("ReadWriteMany")
                    .withPersistentVolumeReclaimPolicy("Retain")
                    .withStorageClassName("")
                    .withNewCsi()
                        .withDriver("nfs.csi.k8s.io")
                        .withVolumeHandle(pvName)
                        .addToVolumeAttributes("server", nfsServer)
                        .addToVolumeAttributes("share", nfsPath)
                    .endCsi()
                    .withMountOptions(List.of("nfsvers=4"))
                .endSpec()
                .build();
            client.persistentVolumes().resource(pv).create();
            LOG.info("Workspace PV created: " + pvName + " -> " + nfsServer + ":" + nfsPath);
        } else {
            LOG.info("Workspace PV already exists: " + pvName);
        }

        // Create PVC if absent (namespace-scoped)
        PersistentVolumeClaim existingPvc = client.persistentVolumeClaims()
            .inNamespace(userPodsNamespace).withName(pvcName).get();
        if (existingPvc == null) {
            PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                    .withName(pvcName)
                    .withNamespace(userPodsNamespace)
                    .addToLabels("app", "k8s-pups-user")
                    .addToLabels("managed-by", "k8s-pups")
                    .addToLabels("user", userId)
                .endMetadata()
                .withNewSpec()
                    .withAccessModes("ReadWriteMany")
                    .withStorageClassName("")
                    .withVolumeName(pvName)
                    .withNewResources()
                        .addToRequests("storage", new Quantity("1Ti"))
                    .endResources()
                .endSpec()
                .build();
            client.persistentVolumeClaims().inNamespace(userPodsNamespace).resource(pvc).create();
            LOG.info("Workspace PVC created: " + pvcName);
        } else {
            LOG.info("Workspace PVC already exists: " + pvcName);
        }
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

    private Probe buildReadinessProbe(ToolPlugin plugin) {
        if (plugin.readinessProbePath() == null) {
            return null;
        }
        return new ProbeBuilder()
            .withNewHttpGet()
                .withPath(plugin.readinessProbePath())
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
        //   nfs-home  -> workspace NFS PVC (LDAP home directory), run as LDAP UID
        //   longhorn  -> Longhorn PVC (RWO block storage)
        //   nfs-k8s   -> NFS k8s-dedicated PVC (RWX)
        String storageType = info.userStorageType() != null && !info.userStorageType().isBlank()
            ? info.userStorageType() : "longhorn";
        boolean useNfsHome = "nfs-home".equals(storageType)
            && plugin.workspaceEnabled() && info.workspaceInfo() != null;

        Map<String, String> labels = Map.of(
            "app", "k8s-pups-user",
            "managed-by", "k8s-pups",
            "tool", plugin.name(),
            "session", info.sessionId(),
            "user", info.userId(),
            "storage-type", storageType
        );

        // Sidecar mode: when nfs-home is selected and the plugin defines a sidecar spec,
        // the Pod splits into two containers with different UIDs.
        SidecarSpec sidecar = useNfsHome ? plugin.workspaceSidecar() : null;
        if (sidecar != null) {
            String workspaceMountTarget = plugin.workspaceMountPath() != null
                ? plugin.workspaceMountPath() : plugin.userDataMountPath();
            return buildSidecarPod(info, plugin, sidecar, envVars,
                profileRequests, profileLimits, workspaceMountTarget, labels);
        }

        return buildSingleContainerPod(info, plugin, envVars, profileRequests, profileLimits,
            useNfsHome, storageType, labels);
    }

    /**
     * Builds a single-container Pod (standard mode).
     * Storage type controls what volume is mounted at userDataMountPath:
     *   nfs-home (useNfsHome=true) -> workspace NFS PVC, run as LDAP UID
     *   longhorn / nfs-k8s         -> user PVC by type name
     */
    private Pod buildSingleContainerPod(SessionInfo info, ToolPlugin plugin,
            List<EnvVar> envVars, Map<String, Quantity> requests, Map<String, Quantity> limits,
            boolean useNfsHome, String storageType, Map<String, String> labels) {

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
            if (useNfsHome) {
                // nfs-home: mount workspace NFS PVC at userDataMountPath
                VolumeMountBuilder vmb = new VolumeMountBuilder()
                    .withName("user-data")
                    .withMountPath(plugin.userDataMountPath());
                if (plugin.workspaceSubPath() != null) {
                    vmb.withSubPath(plugin.workspaceSubPath());
                }
                mounts.add(vmb.build());
            } else {
                // longhorn / nfs-k8s: mount user PVC at userDataMountPath
                mounts.add(new VolumeMountBuilder()
                    .withName("user-data")
                    .withMountPath(plugin.userDataMountPath())
                    .build());
            }
        }

        List<Volume> volumes = new ArrayList<>();
        volumes.add(new VolumeBuilder().withName("tmp")
            .withNewEmptyDir().withSizeLimit(new Quantity("1Gi")).endEmptyDir().build());
        for (int i = 0; i < plugin.writablePaths().size(); i++) {
            volumes.add(new VolumeBuilder().withName("writable-" + i)
                .withNewEmptyDir().withSizeLimit(new Quantity("500Mi")).endEmptyDir().build());
        }
        if (plugin.userDataMountPath() != null) {
            if (useNfsHome) {
                // nfs-home: use workspace PVC (NFS home directory)
                volumes.add(new VolumeBuilder().withName("user-data")
                    .withNewPersistentVolumeClaim()
                        .withClaimName(workspacePvcName(info.userId()))
                        .withReadOnly(false)
                    .endPersistentVolumeClaim()
                    .build());
            } else {
                // longhorn / nfs-k8s: use type-specific user PVC
                volumes.add(new VolumeBuilder().withName("user-data")
                    .withNewPersistentVolumeClaim()
                        .withClaimName(userPvcName(info.userId(), storageType))
                        .withReadOnly(false)
                    .endPersistentVolumeClaim()
                    .build());
            }
        }

        // Additional mounts (secondary PVCs at user-specified paths)
        if (info.additionalMounts() != null) {
            int idx = 0;
            for (MountSpec extra : info.additionalMounts()) {
                String volName = "extra-data-" + idx;
                String pvcName = extra.sharedFrom() != null
                    ? sharedPvcName(info.userId(), extra.sharedFrom())
                    : userPvcName(info.userId(), extra.storageType());

                mounts.add(new VolumeMountBuilder()
                    .withName(volName)
                    .withMountPath(extra.mountPath())
                    .build());

                volumes.add(new VolumeBuilder().withName(volName)
                    .withNewPersistentVolumeClaim()
                        .withClaimName(pvcName)
                        .withReadOnly(false)
                    .endPersistentVolumeClaim()
                    .build());
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

        // When nfs-home is selected, run as LDAP UID so it can read/write NFS files.
        // Also disable readOnlyRootFilesystem so the entrypoint can add the UID
        // to /etc/passwd (required by Python's pwd module, dbus, etc.).
        Long runAsUid = plugin.runAsUser();
        Long runAsGid = plugin.runAsUser();
        boolean readOnlyRoot = plugin.readOnlyRootFilesystem();
        if (useNfsHome && info.workspaceInfo() != null) {
            runAsUid = info.workspaceInfo().uid();
            runAsGid = info.workspaceInfo().gid();
            readOnlyRoot = false;
            LOG.info("Pod " + info.podName() + " nfs-home mode: running as UID "
                + runAsUid + " (LDAP) instead of " + plugin.runAsUser() + " (image default)");
        }

        // Init container: seed PVC with /etc/skel if empty.
        // Runs as the pod-level UID (same as the main container).
        // NFS directory ownership is set to 1000:1000 at PVC creation time by
        // ensureNfsK8sDirectory(), so the pod-level UID can write here without root.
        List<io.fabric8.kubernetes.api.model.Container> initContainers = new ArrayList<>();
        if (plugin.userDataMountPath() != null && !useNfsHome) {
            String seedCmd =
                "if [ -z \"$(ls -A /mnt/pvc 2>/dev/null | sed '/^lost+found$/d')\" ]; then "
                + "echo 'PVC is empty, seeding from /etc/skel'; "
                + "cp -a /etc/skel/. /mnt/pvc/ 2>/dev/null || true; "
                + "echo 'Seed complete'; "
                + "else echo 'PVC already has data, skipping seed'; fi";
            initContainers.add(new io.fabric8.kubernetes.api.model.ContainerBuilder()
                .withName("seed-home")
                .withImage(plugin.containerImage())
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
                    .withNewSeccompProfile().withType(plugin.seccompProfileType()).endSeccompProfile()
                .endSecurityContext()
                .withAffinity(buildNodeAffinity())
                .addNewContainer()
                    .withName("tool")
                    .withImage(plugin.containerImage())
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
                    .withSecurityContext(buildContainerSecurityContext(plugin, readOnlyRoot))
                    .withReadinessProbe(buildReadinessProbe(plugin))
                .endContainer()
                .withVolumes(volumes)
                .withRestartPolicy("Never")
            .endSpec();

        if (!initContainers.isEmpty()) {
            podBuilder.editSpec().withInitContainers(initContainers).endSpec();
        }

        return podBuilder.build();
    }

    /**
     * Builds a two-container Pod (sidecar mode for workspace).
     * Container "tool": runs the service (containerPort) as the plugin's default UID.
     * Container "desktop": runs the workspace environment as the LDAP UID with NFS mounted.
     * Both containers share the Pod network namespace (localhost).
     */
    private Pod buildSidecarPod(SessionInfo info, ToolPlugin plugin, SidecarSpec sidecar,
            List<EnvVar> envVars, Map<String, Quantity> profileRequests,
            Map<String, Quantity> profileLimits, String workspaceMountTarget,
            Map<String, String> labels) {

        WorkspaceInfo ws = info.workspaceInfo();

        // -- Volumes (Pod-level, shared by both containers) --
        List<Volume> volumes = new ArrayList<>();
        volumes.add(new VolumeBuilder().withName("tmp")
            .withNewEmptyDir().withSizeLimit(new Quantity("1Gi")).endEmptyDir().build());
        if (workspaceMountTarget != null) {
            volumes.add(new VolumeBuilder().withName("workspace")
                .withNewPersistentVolumeClaim()
                    .withClaimName(workspacePvcName(info.userId()))
                    .withReadOnly(false)
                .endPersistentVolumeClaim()
                .build());
        }

        // -- "tool" container: service gateway (e.g. guacd + Tomcat) --
        // Runs as plugin's default UID. Lightweight resources. No NFS mount needed.
        List<VolumeMount> toolMounts = List.of(
            new VolumeMountBuilder().withName("tmp").withMountPath("/tmp").build());

        Map<String, Quantity> toolRequests = new HashMap<>();
        sidecar.toolResourceRequests().forEach((k, v) -> toolRequests.put(k, new Quantity(v)));
        Map<String, Quantity> toolLimits = new HashMap<>();
        sidecar.toolResourceLimits().forEach((k, v) -> toolLimits.put(k, new Quantity(v)));

        // -- "desktop" container: workspace environment (e.g. VNC + MATE) --
        // Runs as LDAP UID for correct NFS file ownership. Gets user-selected profile resources.
        List<VolumeMount> desktopMounts = new ArrayList<>();
        desktopMounts.add(new VolumeMountBuilder().withName("tmp").withMountPath("/tmp").build());
        if (workspaceMountTarget != null) {
            VolumeMountBuilder vmb = new VolumeMountBuilder()
                .withName("workspace")
                .withMountPath(workspaceMountTarget);
            if (plugin.workspaceSubPath() != null) {
                vmb.withSubPath(plugin.workspaceSubPath());
            }
            desktopMounts.add(vmb.build());
        }

        List<EnvVar> desktopEnvVars = new ArrayList<>(sidecar.desktopEnv().entrySet().stream()
            .map(e -> new EnvVarBuilder().withName(e.getKey()).withValue(e.getValue()).build())
            .toList());
        if (info.userParams() != null) {
            for (var entry : info.userParams().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    desktopEnvVars.add(new EnvVarBuilder()
                        .withName(entry.getKey())
                        .withValue(entry.getValue())
                        .build());
                }
            }
        }

        LOG.info("Pod " + info.podName() + " using sidecar mode: tool (UID "
            + plugin.runAsUser() + ") + desktop (UID " + ws.uid() + ")");

        return new PodBuilder()
            .withNewMetadata()
                .withName(info.podName())
                .withNamespace(userPodsNamespace)
                .withLabels(labels)
            .endMetadata()
            .withNewSpec()
                .withAutomountServiceAccountToken(false)
                .withAffinity(buildNodeAffinity())
                .withNewSecurityContext()
                    .withRunAsNonRoot(true)
                    .withNewSeccompProfile().withType(plugin.seccompProfileType()).endSeccompProfile()
                .endSecurityContext()
                // Container 1: service gateway (e.g. Guacamole)
                .addNewContainer()
                    .withName("tool")
                    .withImage(plugin.containerImage())
                    .withCommand(sidecar.toolCommand())
                    .addNewPort()
                        .withContainerPort(plugin.containerPort())
                        .withProtocol("TCP")
                    .endPort()
                    .withEnv(envVars)
                    .withNewResources()
                        .withRequests(toolRequests)
                        .withLimits(toolLimits)
                    .endResources()
                    .withVolumeMounts(toolMounts)
                    .withNewSecurityContext()
                        .withRunAsUser(plugin.runAsUser())
                        .withRunAsGroup(plugin.runAsUser())
                        .withAllowPrivilegeEscalation(false)
                        .withReadOnlyRootFilesystem(plugin.readOnlyRootFilesystem())
                        .withNewCapabilities().addToDrop("ALL").endCapabilities()
                    .endSecurityContext()
                    .withReadinessProbe(buildReadinessProbe(plugin))
                .endContainer()
                // Container 2: workspace desktop (e.g. VNC + MATE)
                .addNewContainer()
                    .withName("desktop")
                    .withImage(plugin.containerImage())
                    .withCommand(sidecar.desktopCommand())
                    .withEnv(desktopEnvVars)
                    .withNewResources()
                        .withRequests(profileRequests)
                        .withLimits(profileLimits)
                    .endResources()
                    .withVolumeMounts(desktopMounts)
                    .withNewSecurityContext()
                        .withRunAsUser(ws.uid())
                        .withRunAsGroup(ws.gid())
                        .withAllowPrivilegeEscalation(false)
                        .withReadOnlyRootFilesystem(plugin.readOnlyRootFilesystem())
                        .withNewCapabilities().addToDrop("ALL").endCapabilities()
                    .endSecurityContext()
                .endContainer()
                .withVolumes(volumes)
                .withRestartPolicy("Never")
            .endSpec()
            .build();
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
        if (cpu.endsWith("n")) {
            return Long.parseLong(cpu.substring(0, cpu.length() - 1)) / 1_000_000;
        } else if (cpu.endsWith("u")) {
            return Long.parseLong(cpu.substring(0, cpu.length() - 1)) / 1_000;
        } else if (cpu.endsWith("m")) {
            return Long.parseLong(cpu.substring(0, cpu.length() - 1));
        } else {
            return Long.parseLong(cpu) * 1000;
        }
    }

    /** Parses memory quantity string (e.g. "131728508Ki", "19274Mi", "1024") to bytes. */
    private long parseMemoryToBytes(String mem) {
        if (mem.endsWith("Ki")) {
            return Long.parseLong(mem.substring(0, mem.length() - 2)) * 1024;
        } else if (mem.endsWith("Mi")) {
            return Long.parseLong(mem.substring(0, mem.length() - 2)) * 1024 * 1024;
        } else if (mem.endsWith("Gi")) {
            return Long.parseLong(mem.substring(0, mem.length() - 2)) * 1024L * 1024 * 1024;
        } else if (mem.endsWith("Ti")) {
            return Long.parseLong(mem.substring(0, mem.length() - 2)) * 1024L * 1024 * 1024 * 1024;
        } else {
            return Long.parseLong(mem);
        }
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
