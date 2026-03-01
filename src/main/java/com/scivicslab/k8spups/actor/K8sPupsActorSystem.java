package com.scivicslab.k8spups.actor;

import com.scivicslab.k8spups.k8s.K8sApiClient;
import com.scivicslab.k8spups.k8s.LdapUserInfoClient;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.scivicslab.pojoactor.core.scheduler.Scheduler;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * CDI singleton that initializes and owns the POJO-actor ActorSystem.
 * Creates SessionManagerActor and schedules idle timeout checks.
 */
@ApplicationScoped
public class K8sPupsActorSystem {

    private static final Logger LOG = Logger.getLogger(K8sPupsActorSystem.class.getName());

    @ConfigProperty(name = "k8spups.user-pods-namespace", defaultValue = "user-pods")
    String userPodsNamespace;

    @ConfigProperty(name = "k8spups.httproute-namespace", defaultValue = "sc-account-bg")
    String httpRouteNamespace;

    @ConfigProperty(name = "k8spups.gateway-names", defaultValue = "sc-account-set1-gateway,sc-account-set2-gateway")
    List<String> gatewayNames;

    @ConfigProperty(name = "k8spups.idle-timeout-minutes", defaultValue = "1440")
    long idleTimeoutMinutes;

    @ConfigProperty(name = "k8spups.max-lifetime-minutes", defaultValue = "10080")
    long maxLifetimeMinutes;

    @ConfigProperty(name = "k8spups.max-sessions", defaultValue = "100")
    int maxSessions;

    @ConfigProperty(name = "k8spups.max-sessions-per-user", defaultValue = "2")
    int maxSessionsPerUser;

    @ConfigProperty(name = "k8spups.unlimited-users", defaultValue = "")
    String unlimitedUsersStr;

    @ConfigProperty(name = "k8spups.storage-size-options", defaultValue = "20Gi,50Gi,100Gi,500Gi,1Ti")
    List<String> storageSizeOptions;

    @ConfigProperty(name = "k8spups.default-storage-size", defaultValue = "100Gi")
    String defaultStorageSize;

    @ConfigProperty(name = "k8spups.session-oidc.issuer")
    String sessionOidcIssuer;

    @ConfigProperty(name = "k8spups.session-oidc.authorization-endpoint")
    String sessionOidcAuthorizationEndpoint;

    @ConfigProperty(name = "k8spups.session-oidc.token-endpoint")
    String sessionOidcTokenEndpoint;

    @ConfigProperty(name = "k8spups.session-oidc.client-id")
    String sessionOidcClientId;

    @ConfigProperty(name = "k8spups.session-oidc.secret-name")
    String sessionOidcSecretName;

    @ConfigProperty(name = "k8spups.session-oidc.jwks-uri")
    String sessionOidcJwksUri;

    // Workspace (LDAP + NFS) config
    @ConfigProperty(name = "k8spups.workspace.ldap.url", defaultValue = "")
    String workspaceLdapUrl;

    @ConfigProperty(name = "k8spups.workspace.ldap.base-dn", defaultValue = "")
    String workspaceLdapBaseDn;

    @ConfigProperty(name = "k8spups.workspace.ldap.bind-dn", defaultValue = "")
    String workspaceLdapBindDn;

    @ConfigProperty(name = "k8spups.workspace.ldap.bind-password", defaultValue = "")
    String workspaceLdapBindPassword;

    @ConfigProperty(name = "k8spups.workspace.nfs.server", defaultValue = "")
    String workspaceNfsServer;

    @ConfigProperty(name = "k8spups.workspace.nfs.base-path", defaultValue = "/Public/Users")
    String workspaceNfsBasePath;

    private ActorSystem actorSystem;
    private ActorRef<SessionManagerActor> sessionManager;
    private Scheduler scheduler;
    private K8sApiClient k8sClient;

    @PostConstruct
    void init() {
        LOG.info("Initializing k8s-pups ActorSystem");

        // Discover tool plugins via ServiceLoader
        Map<String, ToolPlugin> plugins = new LinkedHashMap<>();
        for (ToolPlugin plugin : ServiceLoader.load(ToolPlugin.class)) {
            plugins.put(plugin.name(), plugin);
            LOG.info("Registered tool plugin: " + plugin.name() + " (" + plugin.displayName() + ")");
        }

        // Create ActorSystem
        actorSystem = new ActorSystem("k8s-pups");

        // Create K8sApiClient
        k8sClient = new K8sApiClient(userPodsNamespace, httpRouteNamespace, gatewayNames,
            sessionOidcIssuer, sessionOidcAuthorizationEndpoint, sessionOidcTokenEndpoint,
            sessionOidcClientId, sessionOidcSecretName, sessionOidcJwksUri,
            workspaceNfsServer, workspaceNfsBasePath);

        // Create LdapUserInfoClient for workspace mounting (null if not configured)
        LdapUserInfoClient ldapClient = null;
        if (!workspaceLdapUrl.isBlank() && !workspaceLdapBindPassword.isBlank()) {
            ldapClient = new LdapUserInfoClient(
                workspaceLdapUrl, workspaceLdapBaseDn, workspaceLdapBindDn, workspaceLdapBindPassword);
            LOG.info("Workspace LDAP client configured: " + workspaceLdapUrl);
        } else {
            LOG.info("Workspace LDAP not configured; workspace mounting disabled");
        }

        // Parse unlimited users list
        Set<String> unlimitedUsers = new HashSet<>();
        for (String u : unlimitedUsersStr.split(",")) {
            String trimmed = u.trim();
            if (!trimmed.isEmpty()) unlimitedUsers.add(trimmed);
        }

        // Create SessionManagerActor
        SessionManagerActor manager = new SessionManagerActor(
            k8sClient, plugins, maxSessions, maxSessionsPerUser, idleTimeoutMinutes,
            maxLifetimeMinutes, unlimitedUsers, ldapClient);
        sessionManager = actorSystem.actorOf("session-manager", manager);

        // Schedule idle timeout checks
        scheduler = new Scheduler();
        scheduler.scheduleAtFixedRate("idle-check", sessionManager,
            SessionManagerActor::checkIdleSessions, 60, 60, TimeUnit.SECONDS);

        // Clean up any orphaned resources left from a previous controller instance
        reconcileOrphanedResources();

        LOG.info("k8s-pups ActorSystem initialized with " + plugins.size() + " tool plugin(s)");
    }

    @PreDestroy
    void shutdown() {
        LOG.info("Shutting down k8s-pups ActorSystem");
        if (scheduler != null) {
            scheduler.close();
        }
        // Destroy all active sessions to clean up k8s resources before exit
        if (sessionManager != null) {
            try {
                sessionManager.tell(SessionManagerActor::destroyAllSessions).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.warning("Error during graceful session cleanup: " + e.getMessage());
            }
        }
        if (actorSystem != null) {
            actorSystem.terminate();
        }
    }

    private void reconcileOrphanedResources() {
        LOG.info("Starting orphan resource reconciliation...");
        try {
            // Known sessions at startup (empty since actor system just started)
            Set<String> knownSessions = sessionManager.ask(SessionManagerActor::getSessionIds)
                .get(10, TimeUnit.SECONDS);

            // Collect all session IDs present in k8s across all resource types (in parallel)
            CompletableFuture<List<String>> podsFuture = CompletableFuture.supplyAsync(
                k8sClient::listManagedPodSessionIds);
            CompletableFuture<List<String>> servicesFuture = CompletableFuture.supplyAsync(
                k8sClient::listManagedServiceSessionIds);
            CompletableFuture<List<String>> routesFuture = CompletableFuture.supplyAsync(
                k8sClient::listManagedHTTPRouteSessionIds);
            CompletableFuture<List<String>> policiesFuture = CompletableFuture.supplyAsync(
                k8sClient::listManagedSecurityPolicySessionIds);

            Set<String> k8sSessions = new HashSet<>();
            k8sSessions.addAll(podsFuture.get(10, TimeUnit.SECONDS));
            k8sSessions.addAll(servicesFuture.get(10, TimeUnit.SECONDS));
            k8sSessions.addAll(routesFuture.get(10, TimeUnit.SECONDS));
            k8sSessions.addAll(policiesFuture.get(10, TimeUnit.SECONDS));

            // Orphans = in k8s but not known to this controller
            k8sSessions.removeAll(knownSessions);

            if (k8sSessions.isEmpty()) {
                LOG.info("No orphaned resources found.");
                return;
            }

            LOG.info("Found " + k8sSessions.size() + " orphaned session(s): " + k8sSessions);
            for (String sessionId : k8sSessions) {
                LOG.info("Deleting orphaned resources for session: " + sessionId);
                // TODO: Re-enable after SSO issue is resolved
                // try { k8sClient.deleteSecurityPolicy(sessionId); } catch (Exception e) {
                //     LOG.warning("Failed to delete orphaned SecurityPolicy for " + sessionId + ": " + e.getMessage());
                // }
                try { k8sClient.deleteHTTPRoute(sessionId); } catch (Exception e) {
                    LOG.warning("Failed to delete orphaned HTTPRoute for " + sessionId + ": " + e.getMessage());
                }
                try { k8sClient.deleteService("pups-svc-" + sessionId); } catch (Exception e) {
                    LOG.warning("Failed to delete orphaned Service for " + sessionId + ": " + e.getMessage());
                }
                try { k8sClient.deleteOrphanedPodBySession(sessionId); } catch (Exception e) {
                    LOG.warning("Failed to delete orphaned Pod for " + sessionId + ": " + e.getMessage());
                }
            }
            LOG.info("Orphan reconciliation complete.");
        } catch (Exception e) {
            LOG.warning("Orphan reconciliation failed: " + e.getMessage());
        }
    }

    public ActorRef<SessionManagerActor> getSessionManager() {
        return sessionManager;
    }

    public List<String> getStorageSizeOptions() {
        return storageSizeOptions;
    }

    public String getDefaultStorageSize() {
        return defaultStorageSize;
    }

    public K8sApiClient getK8sClient() {
        return k8sClient;
    }
}
