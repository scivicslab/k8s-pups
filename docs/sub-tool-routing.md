# サブツール動的ルーティング

## 概要

`quarkus-service-portal` のようなマルチプロセスツールは、Pod 内で複数のサブツール
（chat-ui、MCP Gateway、Workflow Editor 等）を子プロセスとして起動する。
各サブツールはポッド内部の `localhost:{port}` で待ち受けるが、ブラウザからは
直接アクセスできない。

k8s-pups は、ポータルから「サブツールが READY になった」という通知を受け、
動的に Service と HTTPRoute を作成して外部 URL を払い出す。

## アーキテクチャ

```
ブラウザ
  │ https://{host}/session/{parentSessionId}-{toolName}-{port}/
  ▼
Envoy Gateway
  │ HTTPRoute: pups-subtool-{parentSessionId}-{toolName}-{port}
  │   match:   PathPrefix /session/{parentSessionId}-{toolName}-{port}
  │   rewrite: ReplacePrefixMatch /
  ▼
Service: pups-subtool-{parentSessionId}-{toolName}-{port}
  │ namespace: user-pods
  │ selector:  session={parentSessionId}
  │ port:      {port} → targetPort: {port}
  ▼
user Pod（quarkus-service-portal）
  └─ localhost:{port}（chat-ui、workflow-editor 等）
```

## Pod への環境変数注入

k8s-pups はすべてのツール Pod に以下の環境変数を注入する。

| 環境変数 | 値の例 | 用途 |
|---------|-------|------|
| `PUPS_SESSION_ID` | `abc123` | サブツール登録 API のパスパラメータ |
| `PUPS_CONTROLLER_URL` | `http://k8s-pups-controller.k8s-pups-local-llm.svc:8080/local-llm` | 登録 API のベース URL |
| `PUPS_SESSION_PATH` | `/session/abc123/` | 既存。ツール自身のパスプレフィックス設定用 |
| `PUPS_API_BASE` | `/local-llm` | 既存。コントローラーのベースパス |

`PUPS_CONTROLLER_URL` の値は `k8spups.external-base-url` 設定値から構築する。

## サブツール登録 API

### POST `/api/sub-tool/{sessionId}`

サブツールが READY になった時点でポータルが呼び出す。
Service と HTTPRoute を作成し、外部アクセス URL を返す。

**リクエストボディ (JSON):**

```json
{
  "toolName": "quarkus-chat-ui",
  "port": 8082
}
```

**レスポンスボディ (JSON):**

```json
{
  "accessUrl": "/session/abc123-quarkus-chat-ui-8082/"
}
```

### DELETE `/api/sub-tool/{sessionId}/{toolName}/{port}`

サブツール停止時にポータルが呼び出す。Service と HTTPRoute を削除する。

レスポンスは `204 No Content`。

## 作成されるリソース

### Service（namespace: user-pods）

```yaml
apiVersion: v1
kind: Service
metadata:
  name: pups-subtool-{sessionId}-{toolName}-{port}
  namespace: user-pods
  labels:
    managed-by: k8s-pups
    parent-session: {sessionId}
    sub-tool: {toolName}
spec:
  selector:
    session: {sessionId}
  ports:
    - port: {port}
      targetPort: {port}
      protocol: TCP
```

### HTTPRoute（namespace: {httpRouteNamespace}）

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: pups-subtool-{sessionId}-{toolName}-{port}
  namespace: {httpRouteNamespace}
  labels:
    managed-by: k8s-pups
    parent-session: {sessionId}
    sub-tool: {toolName}
spec:
  parentRefs: [{gateways}]
  rules:
    - matches:
        - path:
            type: PathPrefix
            value: /session/{sessionId}-{toolName}-{port}
      filters:
        - type: URLRewrite
          urlRewrite:
            path:
              type: ReplacePrefixMatch
              replacePrefixMatch: /
        - type: RequestHeaderModifier
          requestHeaderModifier:
            set:
              - name: X-Forwarded-Proto
                value: https
      backendRefs:
        - name: pups-subtool-{sessionId}-{toolName}-{port}
          namespace: user-pods
          port: {port}
      timeouts:
        request: 3600s
```

## ライフサイクル

```
1. k8s-pups が親ツールの Pod を作成
   → PUPS_SESSION_ID, PUPS_CONTROLLER_URL を env vars として注入

2. Pod 内でポータルが起動し、子プロセスを管理

3. 子プロセスが READY になる
   → ポータルが POST /api/sub-tool/{sessionId} を呼ぶ
   → k8s-pups が Service + HTTPRoute を作成
   → accessUrl をレスポンスとして返す

4. ポータルが accessUrl をキャッシュし、UI のリンクとして使用

5. 子プロセスが停止する
   → ポータルが DELETE /api/sub-tool/{sessionId}/{toolName}/{port} を呼ぶ
   → k8s-pups が Service + HTTPRoute を削除

6. 親ツールのセッションが終了する（Pod 削除）
   → ラベル parent-session={sessionId} のリソースが残っていれば一括削除
```

## NetworkPolicy

user-pods 内の Pod が k8s-pups コントローラーを呼べるように、
`user-pods` namespace に以下の Egress ポリシーが必要。

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-egress-k8s-pups-controller
  namespace: user-pods
spec:
  podSelector: {}
  policyTypes:
    - Egress
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: k8s-pups-local-llm
      ports:
        - protocol: TCP
          port: 8080
```

`user-pods` namespace の NetworkPolicy は本リポジトリとは別 overlay で管理するため、
このポリシーは当該 overlay のネットワークポリシー設定ファイルに追加すること。

## 設計上の判断

### なぜポータルから API を呼ぶのか（k8s-pups が自律管理しない理由）

サブツールの起動タイミングはポータル内のビジネスロジックで決まる。
k8s-pups は子プロセスが何個起動したかを Pod 外から知る手段がない。
ポータルが READY 通知を送る pull 型の設計が最もシンプル。

### なぜ親セッション ID をリソース名に含めるのか

同一ユーザーが複数の ai-toolkit セッションを持てる可能性がある。
また別ユーザーの同名ツールとの衝突を防ぐために、
`{sessionId}-{toolName}-{port}` の 3 要素でユニーク性を確保する。
