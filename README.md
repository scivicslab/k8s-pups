# k8s-pups

**K8s Per-User Pod Service** — A Quarkus application that provisions isolated Kubernetes Pods for authenticated users on demand.

## Overview

k8s-pups gives each authenticated user their own short-lived Pod running a selected tool (IDE, notebook, desktop, etc.).
Sessions are managed in-memory by a [POJO-actor](https://github.com/scivicslab/POJO-actor) based actor system, and Kubernetes resources (Pod, Service, HTTPRoute, SecurityPolicy) are created / destroyed through the fabric8 Kubernetes client.

### Key features

- **Per-user isolation** — one Pod per user with dedicated PVC storage
- **Plugin-based tools** — `ToolPlugin` SPI for adding new tool types via `ServiceLoader`
- **Gateway API routing** — automatic `HTTPRoute` + Envoy `SecurityPolicy` per session
- **Orphan reconciliation** — detects and cleans up leaked resources on controller restart
- **Dashboard UI** — Qute-rendered HTML dashboard with real-time session status

## Architecture

```
Browser → HAProxy (VIP) → Envoy Gateway (Gateway API)
                              ├── /pups/*  → k8s-pups controller (k8s-pups namespace)
                              └── /session/{id}/* → user Pod (user-pods namespace)
```

### Actor hierarchy

```
K8sPupsActorSystem (CDI singleton)
  └─ SessionManagerActor (1 instance)
       └─ SessionActor (1 per user)
            └─ manages: Pod, Service, HTTPRoute, SecurityPolicy
```

### Session lifecycle

```
CREATING → STARTING → READY → STOPPING → STOPPED
                        ↓
                      FAILED
```

## Built-in tool plugins

| Plugin | Description |
|--------|-------------|
| `coder-agent` | LLM-powered coding agent (VS Code Server + AI backend) |
| `guacamole` | Apache Guacamole remote desktop |
| `jupyter-lab` | Jupyter Lab notebook environment |

## Tech stack

- **Java 21** (Virtual Threads)
- **Quarkus 3.28** (REST, OIDC, Qute, Kubernetes Client, SmallRye Health)
- **POJO-actor 3.0** (lightweight actor model)
- **fabric8 Kubernetes Client** (Pod/Service/HTTPRoute/SecurityPolicy CRUD)
- **Keycloak** (OIDC authentication)
- **MicroK8s** (target cluster)

## Project structure

```
k8s-pups/
├── src/main/java/com/scivicslab/k8spups/
│   ├── actor/          # Actor system, session management
│   ├── k8s/            # Kubernetes API client, session info
│   ├── plugin/         # ToolPlugin SPI and built-in plugins
│   └── resource/       # REST endpoints (dashboard, session API)
├── src/main/resources/
│   ├── templates/      # Qute HTML templates
│   └── application.properties
├── k8s/                # Kubernetes manifests
├── e2e/                # Playwright E2E tests
├── docs/               # Internal documentation
├── Dockerfile
└── pom.xml
```

## Build & deploy

```bash
# Build
rm -rf target && mvn install

# Docker image
TAG="0.1.0-$(date +%y%m%d%H%M)"
sudo docker build -t 192.168.5.23:32000/k8s-pups:$TAG .
sudo docker push 192.168.5.23:32000/k8s-pups:$TAG

# Deploy
kubectl set image deployment/k8s-pups-controller -n k8s-pups \
  k8s-pups=192.168.5.23:32000/k8s-pups:$TAG
kubectl rollout status deployment/k8s-pups-controller -n k8s-pups
```

## License

Proprietary — SCIVICS Laboratory
