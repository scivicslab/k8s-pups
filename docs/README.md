# k8s-pups ドキュメント

## 概要

k8s-pupsはKubernetes上でPodライフサイクルを管理するQuarkusアプリケーション。
`k8s-pups` namespaceにデプロイされ、`sc-account-bg`のGateway APIを経由して外部に公開される。

## アーキテクチャ上の位置づけ

### Blue/Greenとの関係

k8s-pupsはsc-accountのBlue/Greenデプロイ（set1/set2）に**相乗り**する形で外部公開される。

```
ブラウザ → HAProxy（VIP 192.168.5.25）
           ↓ ソースIPでset1/set2を選択
           → Gateway API（sc-account-bg namespace）
             ↓ HTTPRouteで /pups パスを転送
             → k8s-pups Service（k8s-pups namespace）
               ↓ ClusterIP
               → k8s-pups Pod
```

HAProxyはソースIPでBlue（set1: NodePort 30444）かGreen（set2: NodePort 32617）かを選択する。
どちらのGatewayを経由しても`/pups`パスはk8s-pupsの同一Podに転送される（k8s-pups自体はBlue/Greenなし）。

### k8s マニフェスト

| ファイル | 内容 |
|---------|------|
| `k8s/namespace.yaml` | `k8s-pups` namespace（PSS: restricted）、`user-pods` namespace |
| `k8s/rbac.yaml` | ServiceAccount `vault-auth`、Role、RoleBinding |
| `k8s/deployment.yaml` | Deployment（Vault CSI経由でOIDCシークレット取得） |
| `k8s/service.yaml` | ClusterIP Service（ポート8080） |
| `k8s/gateway-route.yaml` | ReferenceGrant + HTTPRoute（set1/set2 両方に追加） |
| `k8s/secret-provider-class.yaml` | VaultからOIDCシークレットを取得するSPC |
| `k8s/networkpolicy.yaml` | NetworkPolicy |

### 外部アクセスURL

- 開発者端末（192.168.5.14）からは Green（set2）経由: `https://192.168.5.25/pups/`
- その他からは Blue（set1）経由: `https://192.168.5.25/pups/`

## E2Eテスト方式: test pod（kubectl exec）

### Port-Forwardを使わない理由

従来のE2EテストではDB/LDAP検証のために`kubectl port-forward`を使う方法が一般的だが、以下の問題がある:

1. **不安定**: port-forwardはタイムアウトや接続切断が頻繁に発生する
2. **設定複雑**: テスト実行前に手動でport-forwardを立てる必要がある
3. **環境依存**: 開発者ごとにポート番号が衝突する可能性がある

代わりに**test pod（kubectl exec）方式**を採用する。

### test pod方式の仕組み

クラスタ内にすでに起動している`test-pod`に`kubectl exec`でコマンドを送り込み、
cluster-internal DNSを使って各サービス（PostgreSQL、LDAP等）に直接アクセスする。

```
開発者マシン
    │
    │ kubectl exec -n sc-account-bg test-pod -- psql -h postgres-set2 ...
    ↓
test-pod（sc-account-bg namespace）
    │
    │ psql -h postgres-set2.sc-account-bg.svc.cluster.local ...
    │ ldapsearch -H ldap://ldap-set2:389 ...
    ↓
PostgreSQL / LDAP（cluster-internal）
```

### sc-accountでの実装例

`quarkus-sc-account`プロジェクトの以下のクラスを参照:

#### `KubectlExec.java`

```
src/main/java/com/github/oogasawa/quarkus/scaccount/e2e/helper/KubectlExec.java
```

kubectl execのラッパー。`ProcessBuilder`でローカルの`kubectl`コマンドを呼び出す。

```java
// コマンド実行
String output = KubectlExec.exec("psql", "-h", "postgres-set2", "-U", "postgres", ...);

// bash経由（パイプ等が使える）
String output = KubectlExec.execBash("psql -t -A -c \"SELECT ...\" | head -1");

// test pod到達確認
boolean ok = KubectlExec.isTestPodReachable();
```

#### `E2EConfig.java`

```
src/main/java/com/github/oogasawa/quarkus/scaccount/e2e/base/E2EConfig.java
```

環境変数で設定を切り替える。デフォルト値はGreen環境（set2）を向いている。

| 環境変数 | デフォルト | 説明 |
|---------|-----------|------|
| `E2E_TEST_POD` | `test-pod` | test podのPod名 |
| `E2E_TEST_POD_NAMESPACE` | `sc-account-bg` | test podのnamespace |
| `E2E_POSTGRES_SERVICE` | `postgres-set2` | PostgreSQLのService名 |
| `E2E_LDAP_SERVICE` | `ldap-set2` | LDAPのService名 |
| `E2E_POSTGRES_PASSWORD` | （必須） | PostgreSQL adminパスワード |
| `E2E_LDAP_ADMIN_PASSWORD` | （必須） | LDAP adminパスワード |

#### `PostgresHelper.java`

```
src/main/java/com/github/oogasawa/quarkus/scaccount/e2e/helper/PostgresHelper.java
```

`kubectl exec`経由で`psql`コマンドをtest pod内で実行する。

```java
// DB疎通確認
boolean available = PostgresHelper.isAvailable();

// 暗号化パスワード取得
String cipher = PostgresHelper.getEncryptedPassword("testadmin");
```

#### `LdapHelper.java` / `LdapEntry.java`

```
src/main/java/com/github/oogasawa/quarkus/scaccount/e2e/helper/LdapHelper.java
src/main/java/com/github/oogasawa/quarkus/scaccount/e2e/helper/LdapEntry.java
```

`kubectl exec`経由で`ldapsearch`コマンドをtest pod内で実行する。
Apache LDAP APIは不要。LDIFを`LdapEntry`クラスでパースする。

```java
// ユーザー検索
LdapEntry entry = LdapHelper.findUser("testadmin");
String dn        = entry.getDn();
String uid       = entry.get("uid");
List<String> ocs = entry.getAll("objectClass");

// objectClass確認
boolean ok = LdapHelper.hasObjectClass(entry, "posixAccount");

// 属性取得
String uidNumber = LdapHelper.getAttribute(entry, "uidNumber");

// 全posixAccountユーザー取得
List<LdapEntry> accounts = LdapHelper.findAllPosixAccounts();
```

### test podの準備

test podはクラスタ内で常時起動している`psql`と`ldapsearch`が使えるPodである。
sc-account-bgのE2Eテストで使用するtest podが流用できる。

```bash
# test podが存在するか確認
kubectl get pod test-pod -n sc-account-bg

# test podからpostgresに疎通できるか確認
kubectl exec -n sc-account-bg test-pod -- \
  psql -h postgres-set2 -U postgres -d scaccountdb -c "SELECT 1"

# test podからLDAPに疎通できるか確認
kubectl exec -n sc-account-bg test-pod -- \
  ldapsearch -H ldap://ldap-set2:389 -x -b "dc=nig,dc=ac,dc=jp" "(uid=testadmin)" uid
```

### テスト実行時の環境変数設定

```bash
export E2E_POSTGRES_PASSWORD=<postgres adminパスワード>
export E2E_LDAP_ADMIN_PASSWORD=<LDAP adminパスワード>

# 必要に応じてtest podを指定
export E2E_TEST_POD=test-pod
export E2E_TEST_POD_NAMESPACE=sc-account-bg

# E2Eテスト実行
mvn exec:java -Pe2e -Dexec.args="AdminPasswordE2ETest"
```

## デプロイ手順

### 前提条件

- `k8s-pups` namespaceとRBACが適用済み
- Vault CSI Driverが動作中
- `k8s-pups-spc`（SecretProviderClass）が適用済み
- sc-account-bgのGateway API（set1/set2）が稼働中

### ビルドとデプロイ

```bash
cd /home/devteam/works/k8s-pups

# ビルド
rm -rf target && mvn install

# Dockerイメージのビルド・プッシュ
TAG="0.1.0-$(date +%y%m%d%H%M)"
sudo docker build -t k8s-pups:$TAG .
sudo docker tag k8s-pups:$TAG 192.168.5.23:32000/k8s-pups:$TAG
sudo docker push 192.168.5.23:32000/k8s-pups:$TAG

# Deploymentのimageタグを更新して適用
kubectl set image deployment/k8s-pups-controller -n k8s-pups k8s-pups=192.168.5.23:32000/k8s-pups:$TAG
kubectl rollout status deployment/k8s-pups-controller -n k8s-pups --timeout=90s
```

### Gateway HTTPRouteの適用

初回のみ実施（sc-account-bgのGatewayに/pupsルートを追加）:

```bash
kubectl apply -f k8s/gateway-route.yaml
```

### 動作確認

```bash
# Pod起動確認
kubectl get pods -n k8s-pups

# ヘルスチェック（VIP経由）
curl -sk https://192.168.5.25/pups/q/health | jq .status

# ダッシュボード（ブラウザでアクセスするとKeycloakリダイレクト）
curl -sk https://192.168.5.25/pups/dashboard -o /dev/null -w "%{http_code}\n"
# → 302（Keycloakへリダイレクト）
```

## トラブルシューティング

### ファイルアップロードが失敗する（パスが `/home/devteam/works/uploads/...`）

**症状**

```
Upload failed (ファイル名): /home/devteam/works/uploads/xxxx.tmp
```

**原因**

`quarkus-coder-agent` の `application.properties` に `coder-agent.llm.working-dir=/home/devteam/works` が設定されており、コンテナ内でもそのまま適用されるため、ホストマシンのパスに書き込もうとして失敗する。

**修正方法**

`CoderAgentPlugin.java` の `environmentVariables()` に `CODER_AGENT_LLM_WORKING_DIR` を追加し、コンテナ内のPVCマウントパスで上書きする。

```java
// CoderAgentPlugin.java
@Override
public Map<String, String> environmentVariables() {
    return Map.of(
        "CODER_AGENT_LLM_SERVERS", "...",
        "HOME", "/home/user",
        "CODER_AGENT_LLM_WORKING_DIR", "/home/user"  // ← これを追加
    );
}
```

アップロードファイルは `/home/user/uploads/` に保存され、PVC（`pups-data-{userId}`）に永続化される。

---

### コントローラー再起動後に古いPodが残る

**症状**

`kubectl get pods -n user-pods` で以前のセッションのPodが残っている。

**原因**

コントローラーはインメモリでセッション状態を管理しているため、再起動するとセッション情報を失う。
起動時のorphan reconciliationがPodをラベル（`session={sessionId}`）で検索せず、誤った名前（`pups-{sessionId}`）で削除しようとしていたため、実際のPod（`pups-{tool}-{sessionId}`）が削除されなかった。（v0.1.0-2602260202で修正済み）

**手動クリーンアップ**

```bash
kubectl delete pods --all -n user-pods
```

---

### セッション起動時に SecurityPolicy が作成されずに 404

**症状**

coder-agentを起動してKeycloak認証後、`/session/{id}/oauth2/callback` で404になる。

**原因**

`k8s-pups-pod-manager` RoleにPVC作成権限がなく、`SessionActor.start()` がPVC作成でForbiddenエラーになり、以後のSecurityPolicy/HTTPRoute作成に到達できない。

**修正方法**

```bash
kubectl patch role k8s-pups-pod-manager -n user-pods --type=json \
  -p='[{"op":"replace","path":"/rules/0/resources","value":["pods","services","persistentvolumeclaims"]}]'
```

（v0.1.0-2602260202以降、`k8s/rbac.yaml` に `persistentvolumeclaims` が含まれているため、マニフェストを再適用すれば解消される）
