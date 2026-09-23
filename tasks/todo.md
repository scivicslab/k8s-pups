# tasks/todo.md — k8s-pups

## 2026-09-23: 共有サービスのカード（GPU broker）
背景: broker は `gpu-broker` namespace の Deployment（replicas 1、NodePort 30805）として k0s 内に立てた。k8s-pups のツールは全て利用者ごとのセッション Pod なので、クラスタ共有の常駐サービスを表す型が無い。
- [ ] `plugin/SharedService`（name, displayName, description, namespace, deployment, service, port）。設定 `k8spups.shared-services`（有効にする名前の列）＋ `k8spups.shared-service.<name>.*`。既定で gpu-broker の定義を同梱、有効化は `K8SPUPS_SHARED_SERVICES=gpu-broker`
- [ ] `tool/SharedServiceAccess.isAdmin(userId, roles, adminUsers, adminRoles)`（純粋）。設定 `k8spups.admin-users`（env `K8SPUPS_ADMIN_USERS`）と `k8spups.admin-roles`（既定 `admin`）。ユニットテスト
- [ ] `K8sApiClient.sharedServiceStatus(ns, deployment)` → RUNNING / STARTING / DOWN / UNKNOWN（readyReplicas と replicas から）、`scaleDeployment(ns, name, replicas)`
- [ ] `DashboardResource`: カード用データ（状態、admin フラグ）、`GET|POST /service/{name}/{path}` 中継（ログイン必須、`http://<service>.<ns>.svc:<port>/`）、`POST /service/{name}/launch|stop`（admin のみ、それ以外 403）。既存 `proxySession` の転送部を `forward()` に括り出して共用
- [ ] `dashboard.html`: Launch a Tool の格子に共有サービスのカード。全員: 状態バッジ＋Open（Down なら無効＋「contact an administrator」）。admin: Launch／Stop（Stop は confirm）
- [ ] RBAC（gpu-broker overlay に Role + RoleBinding、SA `vault-auth`@k8s-pups-local-llm と @k8s-pups）、NetworkPolicy（local-llm overlay に controller→gpu-broker の egress）、overlay env `K8SPUPS_SHARED_SERVICES`, `K8SPUPS_ADMIN_USERS`
- [ ] ビルド（BASE_PATH=/local-llm、テスト込み）→ 5.14 でイメージ → apply → testadmin E2E（カード表示、Open で broker の状態ページ、Launch/Stop の可視、一般利用者相当の 403）
