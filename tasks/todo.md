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

## 2026-11-06: S3 データセットを FUSE でマウントする（サイドカー方式）
背景: S3 のデータセットは今、Pod の環境変数に一時認証情報（MinIO STS、900 秒）を入れるだけで、ファイルとしては見えない。認証情報は更新されないので 15 分で読めなくなる。FUSE の権限（`SYS_ADMIN`、`/dev/fuse`）は研究者のコードが動くツールのコンテナには渡さず、マウントだけをする別コンテナ（サイドカー）に渡す。

### 仕様
- （2026-11-06 ユーザー決定）"+ Attach storage" のデータセットの選択肢は `Local-datasets`（NFS）と `S3-datasets` の 2 つ。それぞれ 1 回の attach で、利用者が読んでよいその種類のデータセットが全部付く。マウントポイントは別で、ツールの中では `datasets-local/<名前>` と `datasets-S3/<名前>`。データセットを 1 件ずつ選ぶ選択肢はやめる。先に S3 を実装し、Local はその後
- S3 のデータセットを attach したセッションの Pod に、コンテナ `s3-mount` を足す。中身は `rclone mount` だけ（イメージ `s3-mount`、ubuntu:24.04 + rclone + fuse3）
- `s3-mount` は `privileged: true`（Bidirectional の mount propagation に必須）。ポートを開かない。ツールのコンテナとは `shareProcessNamespace` しない
- 両コンテナが同じ emptyDir（`s3-datasets`）を持つ。`s3-mount` は Bidirectional、ツールは HostToContainer かつ `readOnly`。ツールから見える場所は `datasets-S3/<名前>`（共有ディレクトリ全体を読み取り専用で付ける）
- マウントは読み取り専用（`--read-only`）。キャッシュは `--vfs-cache-mode off`（巨大ファイルを Pod のディスクに溜めない）
- 認証情報: コントローラがセッションごとの Secret（`<pod>-s3`）に `credential_process` 用の JSON（AccessKeyId, SecretAccessKey, SessionToken, Expiration）を書く。両コンテナは `AWS_CONFIG_FILE` で `credential_process = cat <Secret のファイル>` を読む。SDK は Expiration が近づくと読み直す
  - ツールのコンテナの `aws`・`rclone`・`boto3` も同じ仕組みに移す。今の `AWS_ACCESS_KEY_ID` などの環境変数はやめる（環境変数があると SDK はそちらを優先し、更新されない）
- 更新: コントローラの定期チェック（データセットへのアクセスが無くなったセッションを止める処理、`SessionManager` 692 行付近）で、まだアクセスが有効な S3 セッションの認証情報を作り直して Secret を書き換える。Secret のファイルが Pod に届くまで最大 1〜2 分かかるので、作り直しは残り 5 分を切ったら行う
- Secret はセッションの Pod を owner にして、Pod と一緒に消える
- Visa が切れたら今どおりセッションを止める（更新はしない）

### 手順
- [x] 1. 実機で確かめる（使い捨て Pod、W206）: rclone 1.75 の `env_auth` が `credential_process` を読み、Expiration 後に読み直すこと。aws CLI と boto3 も同様。privileged サイドカーの rclone mount が HostToContainer でツールのコンテナに見えること
  - 結果（2026-11-06、k8s-pups namespace の使い捨て Pod）: UID 30420・capability 無しのコンテナから `datasets/JGAS000002` が読めた。書き込みは Read-only file system で拒否。Expiration 90 秒の認証情報で、rclone mount は 02:17:42 と 02:19:13 に `credential_process` を呼び、期限後も読めた。aws CLI は `credential_process = cat <file>` で読めた（boto3 は同じ botocore の仕組み、terminal イメージには入っていない）
  - 結果（2026-11-06 午後、同じく使い捨て Pod）:
    - 読み取り専用で `datasets/` 全体に S3 の見え方を付けると、その下に NFS のデータセットを入れ子でマウントできない（runc が `read-only file system` で mountpoint を作れない）。S3 のデータセットは 1 件ずつ `datasets/<名前>` に付ける形にする（subPath と propagation の組み合わせは未検証）
    - サイドカーが FUSE をアンマウントせずに終わると（SIGKILL、終了コード 137）、ノードに `fuse.rclone` のマウントが残り、Pod の削除が終わらない。今日の試験 Pod 2 個が 4 時間残った。ノードで `nsenter -t 1 -m` してから `umount -l` して片付けた
    - したがってサイドカーは SIGTERM で `fusermount3 -u` してから終わる。`terminationGracePeriodSeconds` はその時間を見込む
- [x] 2. `docker/s3-mount/`（Dockerfile、entrypoint: データセットごとに `rclone mount datasets:<bucket>/<name> /mnt/s3/<name>`、終了時に `fusermount3 -u`）。Kaniko でビルド
- [x] 3. `S3Access`: 環境変数の代わりに「Secret の中身（credential_process の JSON と AWS config）」と「データセットの一覧」を返す。ユニットテスト
- [x] 4. `K8sApiClient`: S3 データセットがあるとき Secret を作り、サイドカーと emptyDir・mount propagation を Pod に足す。Pod の spec を組む部分のユニットテスト
- [x] 5. `SessionManager`: 定期チェックで認証情報を更新。ユニットテスト（期限の判定は純粋関数に切り出す）
- [x] 6. `mvn test` → コントローラのイメージ → W206 に出す → E2E passport-own-s3 に「`ls datasets/JGAS000002` と `cat` で読める」「20 分後も読める」を足して通す
- [x] 6a. 名前空間 `user-pods-with-audit` に `ValidatingAdmissionPolicy` を置く（overlay）。privileged はイメージ `s3-mount` のコンテナだけ、`hostPath` は禁止、それ以外のコンテナは `allowPrivilegeEscalation: false`。コントローラが乗っ取られても、計算機を乗っ取れる Pod を作らせない
  - 結果（2026-11-06）: ユニットテスト 373 件が通過。W206 にコントローラ `0.1.0-2611061820`、`s3-mount:0.1.0-2611061813` を出した。E2E passport-own-s3（`datasets-S3` を attach、`ls`・`cat` で読める、書けない、環境変数に認証情報が無い、aws と rclone も読める）と passport-own-nfs（`datasets-local`）が通過。admission policy は server-side dry run で、hostPath・privileged・偽の s3-mount を拒否し、普通の Pod は通すことを確かめた。15 分を超えて読めること（認証情報の更新）は確認中
- [ ] 7. a007 に出す（イメージをコピー、tool-images・controller、admission policy）

### 安全面
- privileged なのは `s3-mount` だけ。利用者はこのコンテナに入れない（入口は proxy 経由のツールの HTTP だけ。`kubectl exec` の権限は利用者に無い）
- ネットワーク名前空間はツールと共有する（Pod 内のコンテナはそうなる）。`s3-mount` は待ち受けないので、ツール側から叩ける口は増えない
- マウントは読み取り専用、MinIO 側のポリシーも読み取り専用のまま
- 名前空間 `user-pods-with-audit` に Pod Security のラベルは無い（privileged を拒まない）。他の Pod に privileged を許すことにはならないが、ラベルで縛っていないこと自体は今も同じ
