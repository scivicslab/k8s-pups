# tasks/todo.md — k8s-pups

## 2026-09-23: 共有サービスのカード（GPU broker）
背景: broker は `gpu-broker` namespace の Deployment（replicas 1、NodePort 30805）として k0s 内に立てた。k8s-pups のツールは全て利用者ごとのセッション Pod なので、クラスタ共有の常駐サービスを表す型が無い。
- [x] `plugin/SharedService`（name, displayName, description, namespace, deployment, service, port）。設定 `k8spups.shared-services`（有効にする名前の列）＋ `k8spups.shared-service.<name>.*`。既定で gpu-broker の定義を同梱、有効化は `K8SPUPS_SHARED_SERVICES=gpu-broker`
- [x] `tool/SharedServiceAccess.isAdmin(userId, roles, adminUsers, adminRoles)`（純粋）。設定 `k8spups.admin-users`（env `K8SPUPS_ADMIN_USERS`）と `k8spups.admin-roles`（既定 `admin`）。ユニットテスト
- [x] `K8sApiClient.sharedServiceStatus(ns, deployment)` → RUNNING / STARTING / DOWN / UNKNOWN（readyReplicas と replicas から）、`scaleDeployment(ns, name, replicas)`
- [x] `DashboardResource`: カード用データ（状態、admin フラグ）、`GET|POST /service/{name}/{path}` 中継（ログイン必須、`http://<service>.<ns>.svc:<port>/`）、`POST /service/{name}/launch|stop`（admin のみ、それ以外 403）。既存 `proxySession` の転送部を `forward()` に括り出して共用
- [x] `dashboard.html`: Launch a Tool の格子に共有サービスのカード。全員: 状態バッジ＋Open（Down なら無効＋「contact an administrator」）。admin: Launch／Stop（Stop は confirm）
- [x] RBAC（gpu-broker overlay に Role + RoleBinding、SA `vault-auth`@k8s-pups-local-llm と @k8s-pups）、NetworkPolicy（local-llm overlay に controller→gpu-broker の egress）、overlay env `K8SPUPS_SHARED_SERVICES`, `K8SPUPS_ADMIN_USERS`
- [x] ビルド（BASE_PATH=/local-llm、テスト込み）→ 5.14 でイメージ → apply → testadmin E2E（カード表示、Open で broker の状態ページ、Launch/Stop の可視、一般利用者相当の 403）
- 結果: k8s-pups 7e36b72（カード・中継・launch/stop）、9276ba9（`K8SPUPS_ENGLISH_TOOLKIT_CHAT_MODEL` → Pod の `ENGLISH_CHAT_MODEL`）。コントローラ 0.1.0-2609231829 を local-llm へ。overlays 1644a56・0826e61（RBAC、egress、`K8SPUPS_SHARED_SERVICES=gpu-broker`、`K8SPUPS_ADMIN_USERS=testadmin,oogasawa`、English Toolkit の broker を `gpu-broker.gpu-broker.svc:28005` に、既定モデルを gemma-4 に固定）。w206-e2e-test に `shared-service` シナリオ（カード RUNNING、Open の href、admin ボタン、`/service/gpu-broker/` と `/queues` の中継）PASS。english-toolkit シナリオも in-cluster broker 経由で PASS（broker の 5.14 endpoint に replies が立った）
- 気付き: broker の `/v1/models` の順序は発見順で、インスタンスごとに違う（MiniPC は gemma が先、クラスタ内は Qwen2.5-14B が先）。English Toolkit は「空なら先頭」なので既定が変わった → 配備側で固定した。E2E の `#chat-model` 読みは chat/config 適用前に読む競合があったので待ちを足した
- 未実施: 一般利用者アカウントでの 403 の実機確認（テスト用の非 admin アカウントが無い。判定はユニットテストと端点のコードで担保）。generic /pups への展開（RBAC は済、controller の env と netpol は未）。MiniPC 側消費者（AI-workspace 28000 は `-Dgpu.broker.url=localhost:28005` で起動中、env `GPU_BROKER_URL=localhost:28005`、exdb2/html-saurus/english-toolkit 本番）の `192.168.5.22:30805` への切替は利用者判断
