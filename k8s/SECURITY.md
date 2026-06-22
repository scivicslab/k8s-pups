# k8s-pups セキュリティ設定ガイド

## 基本方針

`user-pods` namespace は **default-deny-all** で、必要な通信だけ NetworkPolicy で穴を開ける。
RBAC は ServiceAccount 単位で最小権限を付与する。

## 現在の構成

### NetworkPolicy（`networkpolicy.yaml`）

| ポリシー名 | 方向 | 対象 Pod | 宛先 | ポート | 用途 |
|-----------|------|---------|------|-------|------|
| `default-deny-all` | 全遮断 | 全 Pod | — | — | ベースライン |
| `allow-ingress-http` | Ingress | `tool != desktop` | envoy-gateway-system | 8090,8888,8080,3000 | Envoy → ツール |
| `allow-egress-dns` | Egress | 全 Pod | kube-system | 53 | DNS 解決 |
| `allow-egress-dgx` | Egress | `tool=chat-ui` | 192.168.5.13,15 | 8000 | DGX サーバ |
| `allow-egress-llm-api` | Egress | `tool=chat-ui-claude,codex` | any | 443 | 外部 LLM API |
| `allow-egress-minio` | Egress | 全 Pod | minio namespace | 9000 | S3 (MinIO) |
| `allow-egress-nfs` | Egress | 全 Pod | 192.168.5.20 | 2049,111 | NFS ワークスペース |

### RBAC（`rbac.yaml`）

| Role | Scope | リソース | 操作 |
|------|-------|---------|------|
| `k8s-pups-pod-manager` | user-pods namespace | pods, services, PVC, configmaps | CRUD |
| `k8s-pups-route-manager` | sc-account-bg namespace | httproutes, securitypolicies | CRUD |
| `k8s-pups-node-reader` | Cluster | nodes, pods, PV, PVC, resourcequotas, metrics | read |

※ すべて ServiceAccount `vault-auth`（k8s-pups namespace）に紐付け

---

## ユースケース別の必要設定

### 1. Docusaurus / Jupyter Lab（基本）

**現状で動作する。追加設定不要。**

- Ingress: Envoy Gateway 経由でアクセス
- Egress: DNS + NFS のみ必要（設定済み）

### 2. Coder Agent（LLM API 呼び出し）

**現状で動作する。追加設定不要。**

- `tool=chat-ui-claude` / `tool=chat-ui-codex` ラベルの Pod
- Egress: port 443 全開放（LLM API 向け）
- DGX: `tool=chat-ui` ラベルで port 8000

### 3. actor-IaC ワークフロー実行（SSH 経由）

**未対応 — NetworkPolicy 追加が必要**

Jupyter Lab 等から `jbang actor_iac.java run ...` で SSH 先のホストを操作するケース。

追加する NetworkPolicy:
```yaml
# Allow SSH egress to internal hosts
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-egress-ssh-internal
  namespace: user-pods
spec:
  podSelector:
    matchLabels:
      tool: jupyter-lab         # SSH が必要なツールだけに限定
  policyTypes:
    - Egress
  egress:
    - to:
        - ipBlock:
            cidr: 192.168.5.0/24
      ports:
        - protocol: TCP
          port: 22
```

注意点:
- SSH 鍵は NFS workspace 経由で Pod 内に渡す
- SSH 先のホストで操作権限を持つユーザーの鍵が必要
- RBAC 追加は不要（kubectl は使わず SSH 経由で操作）

### 4. Pod 内から `kubectl` 実行

**未対応 — NetworkPolicy + RBAC 追加が必要**

Pod 内から k8s API を直接叩くケース。

(a) NetworkPolicy — API Server への egress:
```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-egress-k8s-api
  namespace: user-pods
spec:
  podSelector:
    matchLabels:
      tool: chat-ui          # kubectl が必要なツールだけに限定
  policyTypes:
    - Egress
  egress:
    - to:
        - ipBlock:
            cidr: 10.152.183.1/32   # k8s API Server ClusterIP
      ports:
        - protocol: TCP
          port: 443
```

(b) RBAC — 専用 ServiceAccount + Role:
```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: pups-kubectl-user
  namespace: user-pods
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: pups-kubectl-readonly
rules:
  - apiGroups: [""]
    resources: ["pods", "services", "namespaces"]
    verbs: ["get", "list"]
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["get", "list"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: pups-kubectl-readonly-binding
subjects:
  - kind: ServiceAccount
    name: pups-kubectl-user
    namespace: user-pods
roleRef:
  kind: ClusterRole
  name: pups-kubectl-readonly
  apiGroup: rbac.authorization.k8s.io
```

注意点:
- **全ユーザーの Pod が同じ ServiceAccount を共有する** — ユーザーごとに権限を分けるなら Pod 生成時に別の ServiceAccount を割り当てる実装が必要
- readonly から始めて、必要に応じて権限を拡大する
- `kubectl` バイナリを Docker イメージに含める必要がある

### 5. Guacamole Desktop（リモートデスクトップ）

**現状で動作する。追加設定不要。**

- Ingress ルールで `tool != desktop` により HTTP ingress から除外されている
- Desktop は Guacamole 経由でアクセスするため別経路
- Egress: DNS + NFS のみ

---

## ラベル設計

Pod のツール種別は `tool` ラベルで識別する。NetworkPolicy の `podSelector` でこのラベルを使って通信を制御する。

| ラベル値 | ツール | 特別な egress |
|---------|--------|-------------|
| `docusaurus` | Docusaurus dev server | なし |
| `jupyter-lab` | Jupyter Lab | （SSH 追加予定） |
| `chat-ui` | Coder Agent | DGX port 8000 |
| `chat-ui-claude` | Claude Code | HTTPS port 443 |
| `chat-ui-codex` | Codex Agent | HTTPS port 443 |
| `desktop` | Guacamole Desktop | なし |

## 変更手順

1. `networkpolicy.yaml` にポリシーを追加
2. `kubectl apply -f k8s/networkpolicy.yaml`
3. 必要なら `rbac.yaml` も更新して apply
4. テスト: 対象 Pod から `nc -zv <host> <port>` で疎通確認
