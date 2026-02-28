# k8s-pups

**Kubernetes Per-User Pod Service** — A Quarkus application that provisions isolated Kubernetes Pods for authenticated users on demand.

## Overview

k8s-pups gives each authenticated user their own short-lived Pod running a selected tool (IDE, notebook, desktop, doc site preview, etc.). Sessions are managed in-memory by a [POJO-actor](https://github.com/scivicslab/POJO-actor) based actor system, and Kubernetes resources (Pod, Service, HTTPRoute, SecurityPolicy) are created and destroyed through the fabric8 Kubernetes client.

### Key Features

- **Per-user isolation** — each session runs in its own Pod with dedicated storage
- **Plugin-based tools** — `ToolPlugin` SPI for adding new tool types via `ServiceLoader`
- **Gateway API routing** — automatic `HTTPRoute` + Envoy Gateway `SecurityPolicy` per session
- **NFS workspace mounting** — users with POSIX accounts (LDAP) get their home directory mounted via NFS
- **Resource profiles** — selectable tiers (Light / Standard) with different CPU, memory, and storage limits
- **Idle timeout** — automatic cleanup of sessions after configurable inactivity period
- **Orphan reconciliation** — detects and cleans up leaked resources on controller restart
- **Dashboard UI** — Qute-rendered HTML dashboard with real-time session status polling

## Architecture

```
Browser → HAProxy (VIP) → Envoy Gateway (Gateway API)
                              ├── /pups/*           → k8s-pups controller  (k8s-pups namespace)
                              └── /session/{id}/*   → user Pod             (user-pods namespace)
```

### Actor Hierarchy

```
K8sPupsActorSystem (CDI singleton, @ApplicationScoped)
  └─ SessionManagerActor (singleton)
       ├─ SessionActor (session abc-123)
       │    └─ manages: Pod, Service, HTTPRoute, SecurityPolicy
       ├─ SessionActor (session def-456)
       │    └─ manages: Pod, Service, HTTPRoute, SecurityPolicy
       └─ ...
```

### Session Lifecycle

```
CREATING → STARTING → READY → STOPPING → STOPPED
                  ↘
                  FAILED
```

1. User selects a tool and clicks **Launch** on the dashboard
2. `SessionManagerActor` enforces global/per-user session limits, creates a `SessionActor`
3. `SessionActor.start()` creates Kubernetes resources:
   - PVC (per-user persistent storage) or NFS workspace PV/PVC
   - Pod (tool container with security context, volumes, probes)
   - Service (ClusterIP in `user-pods` namespace)
   - HTTPRoute (routes `/session/{id}/*` through Envoy Gateway)
4. Pod watch detects container readiness → state transitions to `READY`
5. User accesses the tool via browser; idle timer resets on each access
6. After idle timeout (default 30 min), session is automatically stopped and all resources deleted

### NFS Workspace Mounting

When a tool plugin has `workspaceEnabled() = true` and the user has a POSIX account in LDAP:

1. Controller queries LDAP for `uidNumber`, `gidNumber`, and `homeDirectory`
2. An NFS PV/PVC pair (`pups-workspace-{userId}`) is created pointing to the user's home directory on the NFS server
3. The Pod's `securityContext` is set to the user's LDAP UID/GID
4. The NFS share is mounted into the container (mount path depends on the plugin)

Users without a POSIX account silently fall back to a per-user PVC — no error is shown.

## Built-in Tool Plugins

| Plugin | Display Name | Port | Description |
|--------|-------------|------|-------------|
| `coder-agent` | Coder Agent | 8090 | LLM-powered coding agent with local LLM backend (VS Code Server + AI) |
| `coder-agent-claude` | Coder Agent (Claude) | 8090 | Coding agent powered by Anthropic Claude API |
| `coder-agent-codex` | Coder Agent (Codex) | 8090 | Coding agent powered by OpenAI Codex API |
| `guacamole` | Remote Desktop | 8080 | Browser-based Linux desktop via Apache Guacamole (Xfce + VNC) |
| `jupyter-lab` | Jupyter Lab | 8888 | Interactive notebooks with Python scientific stack (NumPy, Pandas, Matplotlib, SciPy) |
| `docusaurus` | Docusaurus | 3000 | Live preview of Docusaurus documentation sites from the user's workspace |

### Plugin Capabilities

Each plugin can configure:

- **Container image and port** — what to run and where to connect
- **Resource profiles** — named tiers with different CPU/memory/storage limits
- **User parameters** — form fields on the dashboard (e.g., API key, project path)
- **Security context** — `runAsUser`, `readOnlyRootFilesystem`, `seccompProfile`
- **Workspace** — NFS home directory mounting with optional subpath
- **Readiness probe** — HTTP health check for startup detection
- **Path passthrough** — whether to rewrite or preserve the URL path prefix

### Writing a Custom Plugin

Implement the `ToolPlugin` interface and register it via `META-INF/services`:

```java
public class MyPlugin implements ToolPlugin {
    @Override public String name() { return "my-tool"; }
    @Override public String displayName() { return "My Tool"; }
    @Override public String containerImage() { return "registry/my-tool:1.0.0"; }
    @Override public int containerPort() { return 8080; }
    @Override public ConnectionType connectionType() { return ConnectionType.HTTP; }
}
```

Add the fully qualified class name to:

```
src/main/resources/META-INF/services/com.scivicslab.k8spups.plugin.ToolPlugin
```

## REST API

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/` | — | Redirect to `/dashboard` |
| `GET` | `/dashboard` | OIDC | Main dashboard (HTML) |
| `POST` | `/session/start` | OIDC | Launch a new session |
| `POST` | `/session/stop` | OIDC | Stop a session |
| `POST` | `/session/memo` | OIDC | Update session memo text |
| `GET` | `/session/status` | OIDC | Current user's sessions (JSON) |
| `POST` | `/storage/save` | OIDC | Save storage size preference |

Health endpoints are available at `/q/health/live` and `/q/health/ready`.

## Tech Stack

- **Java 21** — Virtual Threads for non-blocking I/O on Kubernetes API calls
- **Quarkus 3.28** — REST, OIDC, Qute templates, Kubernetes Client, SmallRye Health
- **POJO-actor 3.0** — Lightweight actor model for session management
- **fabric8 Kubernetes Client** — Pod, Service, HTTPRoute, SecurityPolicy, PVC, PV CRUD
- **Keycloak** — OIDC authentication for dashboard and per-session JWT validation
- **Envoy Gateway** — Gateway API implementation for request routing and security policies
- **NFS CSI Driver** (`nfs.csi.k8s.io`) — Dynamic NFS volume provisioning for workspace mounting
- **MicroK8s** — Target Kubernetes cluster

## Project Structure

```
k8s-pups/
├── src/main/java/com/scivicslab/k8spups/
│   ├── actor/             # Actor system, session management
│   │   ├── K8sPupsActorSystem.java    # CDI singleton, plugin discovery, scheduler
│   │   ├── SessionManagerActor.java   # Global session management, limits
│   │   ├── SessionActor.java          # Per-session Pod lifecycle
│   │   ├── SessionState.java          # State enum
│   │   ├── SessionStatus.java         # Immutable status snapshot
│   │   └── SessionSummary.java        # Aggregated counts
│   ├── k8s/               # Kubernetes API client
│   │   ├── K8sApiClient.java          # fabric8 wrapper (Pod/Svc/Route/PVC)
│   │   ├── SessionInfo.java           # Session creation parameters
│   │   ├── WorkspaceInfo.java         # LDAP POSIX account info
│   │   └── LdapUserInfoClient.java    # LDAP query client
│   ├── plugin/            # ToolPlugin SPI and built-in plugins
│   │   ├── ToolPlugin.java            # Plugin interface (20+ methods)
│   │   ├── ConnectionType.java        # HTTP / VNC enum
│   │   ├── ResourceProfile.java       # Named resource tier
│   │   ├── UserParameter.java         # Dashboard form field
│   │   ├── CoderAgentPlugin.java
│   │   ├── CoderAgentClaudePlugin.java
│   │   ├── CoderAgentCodexPlugin.java
│   │   ├── GuacamolePlugin.java
│   │   ├── JupyterLabPlugin.java
│   │   └── DocusaurusPlugin.java
│   └── resource/          # REST endpoints
│       └── DashboardResource.java     # Dashboard + session API
├── src/main/resources/
│   ├── templates/
│   │   └── dashboard.html             # Qute HTML template
│   └── application.properties         # Quarkus + k8s-pups configuration
├── k8s/                   # Kubernetes manifests
│   ├── namespace.yaml                 # k8s-pups + user-pods namespaces
│   ├── deployment.yaml                # Controller deployment
│   ├── service.yaml                   # Controller ClusterIP service
│   ├── rbac.yaml                      # ServiceAccount + Roles
│   ├── gateway-route.yaml             # HTTPRoute + SecurityPolicy for dashboard
│   ├── networkpolicy.yaml             # Default-deny + allow rules for user-pods
│   └── minio.yaml                     # Optional MinIO S3 storage
├── e2e/                   # Playwright E2E tests
├── Dockerfile             # UBI8 + OpenJDK 21
└── pom.xml
```

## Configuration

Key properties in `application.properties` (all overridable via environment variables):

| Property | Default | Description |
|----------|---------|-------------|
| `k8spups.user-pods-namespace` | `user-pods` | Namespace for user Pods |
| `k8spups.controller-namespace` | `k8s-pups` | Controller's own namespace |
| `k8spups.httproute-namespace` | `sc-account-bg` | Namespace for HTTPRoutes |
| `k8spups.gateway-names` | `sc-account-set1-gateway,sc-account-set2-gateway` | Gateway parentRefs |
| `k8spups.idle-timeout-minutes` | `30` | Session idle timeout |
| `k8spups.max-sessions` | `100` | Global session limit |
| `k8spups.max-sessions-per-user` | `2` | Per-user session limit |
| `k8spups.storage-size-options` | `20Gi,50Gi,100Gi,500Gi,1Ti` | PVC size choices |
| `k8spups.default-storage-size` | `100Gi` | Default PVC size |
| `k8spups.workspace.ldap.url` | `ldap://ldap-set2.sc-account-bg.svc:389` | LDAP server for POSIX lookup |
| `k8spups.workspace.nfs.server` | `192.168.5.20` | NFS server for home directories |
| `k8spups.workspace.nfs.base-path` | `/Public/Users` | NFS base path |

## Build & Deploy

```bash
# Build
rm -rf target && mvn install

# Docker image
TAG="0.1.0-$(date +%y%m%d%H%M)"
sudo docker build -t 192.168.5.23:32000/k8s-pups:$TAG .
sudo docker push 192.168.5.23:32000/k8s-pups:$TAG

# Deploy (full manifest — includes env vars and volume mounts)
kubectl apply -f k8s/deployment.yaml

# Or update image only (when no manifest changes)
kubectl set image deployment/k8s-pups-controller -n k8s-pups \
  k8s-pups=192.168.5.23:32000/k8s-pups:$TAG
kubectl rollout status deployment/k8s-pups-controller -n k8s-pups
```

### Prerequisites

- MicroK8s cluster with Envoy Gateway and NFS CSI driver
- Keycloak realm with `k8s-pups` OIDC client
- LDAP server with POSIX account attributes (for workspace mounting)
- NFS server exporting user home directories
- Container registry at `192.168.5.23:32000`

## License

Proprietary — SCIVICS Laboratory
