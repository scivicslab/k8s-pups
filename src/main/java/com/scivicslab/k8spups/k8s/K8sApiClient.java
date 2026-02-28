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

    // NFS workspace config
    private final String nfsServer;
    private final String nfsBasePath;

    public K8sApiClient(String userPodsNamespace, String httpRouteNamespace, List<String> gatewayNames,
                        String oidcIssuer, String oidcAuthorizationEndpoint, String oidcTokenEndpoint,
                        String oidcClientId, String oidcSecretName, String oidcJwksUri,
                        String nfsServer, String nfsBasePath) {
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
        this.nfsServer = nfsServer;
        this.nfsBasePath = nfsBasePath;
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

    public void deleteService(String serviceName) {
        client.services().inNamespace(userPodsNamespace).withName(serviceName).delete();
        LOG.info("Service deleted: " + serviceName);
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
        String redirectURL = "https://192.168.5.25/session/" + sessionId + "/oauth2/callback";
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
        oidc.put("scopes", List.of("openid", "profile"));
        // Fix the idToken cookie name so the jwt section below can extract it by name.
        oidc.put("cookieNames", Map.of("idToken", idTokenCookieName));

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
        jwtProvider.put("name", "keycloak");
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
        jwtPrincipal.put("provider", "keycloak");
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
     * Returns the PVC name for a given userId.
     * Sanitizes the userId to be a valid k8s resource name.
     */
    private String userPvcName(String userId) {
        return "pups-data-" + userId.toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }

    /**
     * Creates a per-user PVC in the user-pods namespace if it does not already exist.
     * The PVC persists across sessions (not deleted on session stop).
     *
     * If the PVC already exists with a smaller size, it will be expanded to the
     * requested size (PVC expansion must be supported by the storage class).
     *
     * @param userId      the user identifier
     * @param storageSize the requested storage size (e.g. "100Gi", "1Ti")
     */
    public void createUserPvcIfAbsent(String userId, String storageSize) {
        String pvcName = userPvcName(userId);
        PersistentVolumeClaim existing = client.persistentVolumeClaims()
            .inNamespace(userPodsNamespace)
            .withName(pvcName)
            .get();
        if (existing != null) {
            // Check if expansion is needed
            Quantity currentSize = existing.getSpec().getResources().getRequests().get("storage");
            Quantity requestedSize = new Quantity(storageSize);
            if (currentSize != null && compareSizeGi(requestedSize, currentSize) > 0) {
                try {
                    LOG.info("Expanding PVC " + pvcName + " from " + currentSize + " to " + storageSize);
                    existing.getSpec().getResources().getRequests().put("storage", requestedSize);
                    client.persistentVolumeClaims().inNamespace(userPodsNamespace)
                        .resource(existing).update();
                } catch (Exception e) {
                    LOG.warning("PVC expansion failed for " + pvcName
                        + " (StorageClass may not support resize): " + e.getMessage());
                }
            } else {
                LOG.info("User PVC already exists: " + pvcName + " (" + currentSize + ")");
            }
            return;
        }
        PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
            .withNewMetadata()
                .withName(pvcName)
                .withNamespace(userPodsNamespace)
                .addToLabels("app", "k8s-pups-user")
                .addToLabels("user", userId)
            .endMetadata()
            .withNewSpec()
                .withAccessModes("ReadWriteOnce")
                .withNewResources()
                    .addToRequests("storage", new Quantity(storageSize))
                .endResources()
            .endSpec()
            .build();
        client.persistentVolumeClaims().inNamespace(userPodsNamespace).resource(pvc).create();
        LOG.info("User PVC created: " + pvcName + " (" + storageSize + ")");
    }

    /**
     * Returns information about a user's PVC.
     *
     * @param userId the user identifier
     * @return map with "exists", "size", "phase" keys
     */
    public Map<String, String> getUserPvcInfo(String userId) {
        String pvcName = userPvcName(userId);
        PersistentVolumeClaim pvc = client.persistentVolumeClaims()
            .inNamespace(userPodsNamespace)
            .withName(pvcName)
            .get();
        if (pvc == null) {
            return Map.of("exists", "false");
        }
        Quantity size = pvc.getSpec().getResources().getRequests().get("storage");
        String phase = pvc.getStatus() != null && pvc.getStatus().getPhase() != null
            ? pvc.getStatus().getPhase() : "Unknown";
        return Map.of("exists", "true",
            "size", size != null ? size.toString() : "0",
            "phase", phase);
    }

    /**
     * Reads the user's storage size preference from their ConfigMap.
     *
     * @param userId the user identifier
     * @return the preferred storage size (e.g. "100Gi"), or null if not set
     */
    public String getUserStoragePreference(String userId) {
        String cmName = userPrefsConfigMapName(userId);
        ConfigMap cm = client.configMaps()
            .inNamespace(userPodsNamespace)
            .withName(cmName)
            .get();
        if (cm == null || cm.getData() == null) {
            return null;
        }
        return cm.getData().get("storageSize");
    }

    /**
     * Saves the user's storage size preference to a ConfigMap.
     * Creates the ConfigMap if it does not exist.
     *
     * @param userId      the user identifier
     * @param storageSize the storage size (e.g. "100Gi", "500Gi")
     */
    public void saveUserStoragePreference(String userId, String storageSize) {
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
                .build();
            client.configMaps().inNamespace(userPodsNamespace).resource(cm).create();
        }
        LOG.info("Saved storage preference for " + userId + ": " + storageSize);
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
                    .withMountOptions(List.of("nfsvers=4.1"))
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

    private Pod buildPodSpec(SessionInfo info) {
        ToolPlugin plugin = info.toolPlugin();

        // Build env vars: plugin-defined + PUPS_SESSION_PATH always injected.
        // PUPS_SESSION_PATH lets tools (e.g. JupyterLab) know their own base URL.
        List<EnvVar> envVars = new ArrayList<>(plugin.environmentVariables().entrySet().stream()
            .map(e -> new EnvVarBuilder().withName(e.getKey()).withValue(e.getValue()).build())
            .toList());
        envVars.add(new EnvVarBuilder()
            .withName("PUPS_SESSION_PATH")
            .withValue("/session/" + info.sessionId() + "/")
            .build());

        // Inject user-provided parameters as env vars (e.g. API keys)
        if (info.userParams() != null) {
            for (var entry : info.userParams().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    envVars.add(new EnvVarBuilder()
                        .withName(entry.getKey())
                        .withValue(entry.getValue())
                        .build());
                }
            }
        }

        // Build resource requirements from selected profile
        ResourceProfile profile = resolveProfile(plugin, info.resourceProfile());
        Map<String, Quantity> requests = new HashMap<>();
        profile.requests().forEach((k, v) -> requests.put(k, new Quantity(v)));
        Map<String, Quantity> limits = new HashMap<>();
        profile.limits().forEach((k, v) -> limits.put(k, new Quantity(v)));
        LOG.info("Pod " + info.podName() + " using resource profile: " + profile.name()
            + " (" + profile.displayName() + ")");

        // Determine workspace state
        boolean workspaceActive = plugin.workspaceEnabled() && info.workspaceInfo() != null;
        String workspaceMountTarget = null;
        if (workspaceActive) {
            workspaceMountTarget = plugin.workspaceMountPath() != null
                ? plugin.workspaceMountPath()
                : plugin.userDataMountPath();
        }
        boolean workspaceReplacesUserData = workspaceActive
            && workspaceMountTarget != null
            && workspaceMountTarget.equals(plugin.userDataMountPath());

        // Build volume mounts: /tmp always + plugin-specific writable paths + optional user PVC + workspace
        List<VolumeMount> mounts = new ArrayList<>();
        mounts.add(new VolumeMountBuilder().withName("tmp").withMountPath("/tmp").build());
        for (int i = 0; i < plugin.writablePaths().size(); i++) {
            mounts.add(new VolumeMountBuilder()
                .withName("writable-" + i)
                .withMountPath(plugin.writablePaths().get(i))
                .build());
        }
        // Per-user PVC (skipped when workspace replaces it at the same mount path)
        if (plugin.userDataMountPath() != null && !workspaceReplacesUserData) {
            mounts.add(new VolumeMountBuilder()
                .withName("user-data")
                .withMountPath(plugin.userDataMountPath())
                .build());
        }
        // Workspace NFS mount
        if (workspaceActive && workspaceMountTarget != null) {
            VolumeMountBuilder vmb = new VolumeMountBuilder()
                .withName("workspace")
                .withMountPath(workspaceMountTarget);
            if (plugin.workspaceSubPath() != null) {
                vmb.withSubPath(plugin.workspaceSubPath());
            }
            mounts.add(vmb.build());
        }

        List<Volume> volumes = new ArrayList<>();
        volumes.add(new VolumeBuilder().withName("tmp")
            .withNewEmptyDir().withSizeLimit(new Quantity("1Gi")).endEmptyDir().build());
        for (int i = 0; i < plugin.writablePaths().size(); i++) {
            volumes.add(new VolumeBuilder().withName("writable-" + i)
                .withNewEmptyDir().withSizeLimit(new Quantity("500Mi")).endEmptyDir().build());
        }
        if (plugin.userDataMountPath() != null && !workspaceReplacesUserData) {
            volumes.add(new VolumeBuilder().withName("user-data")
                .withNewPersistentVolumeClaim()
                    .withClaimName(userPvcName(info.userId()))
                    .withReadOnly(false)
                .endPersistentVolumeClaim()
                .build());
        }
        if (workspaceActive && workspaceMountTarget != null) {
            volumes.add(new VolumeBuilder().withName("workspace")
                .withNewPersistentVolumeClaim()
                    .withClaimName(workspacePvcName(info.userId()))
                    .withReadOnly(false)
                .endPersistentVolumeClaim()
                .build());
        }

        // Determine UID/GID: workspace overrides plugin default with LDAP values
        Long runAsUid = workspaceActive ? info.workspaceInfo().uid() : plugin.runAsUser();
        Long runAsGid = workspaceActive ? info.workspaceInfo().gid() : plugin.runAsUser();

        Map<String, String> labels = Map.of(
            "app", "k8s-pups-user",
            "managed-by", "k8s-pups",
            "tool", plugin.name(),
            "session", info.sessionId(),
            "user", info.userId()
        );

        return new PodBuilder()
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
                    .withNewSeccompProfile().withType(plugin.seccompProfileType()).endSeccompProfile()
                .endSecurityContext()
                .addNewContainer()
                    .withName("tool")
                    .withImage(plugin.containerImage())
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
                    .withNewSecurityContext()
                        .withAllowPrivilegeEscalation(false)
                        .withReadOnlyRootFilesystem(plugin.readOnlyRootFilesystem())
                        .withNewCapabilities().addToDrop("ALL").endCapabilities()
                    .endSecurityContext()
                    .withReadinessProbe(buildReadinessProbe(plugin))
                .endContainer()
                .withVolumes(volumes)
                .withRestartPolicy("Never")
            .endSpec()
            .build();
    }
}
