# Storage Settings（ユーザーストレージ設定）

## 概要

k8s-pupsダッシュボードの「Storage Settings」カードにより、ユーザーが自分のPVC（PersistentVolumeClaim）のディスクサイズを選択できる。選択したサイズは次回のツール起動時にPVCに反映される。

## 仕組み

### データフロー

```
ダッシュボード（💾 Storage Settings）
    │ POST /storage/save  storageSize=500Gi
    ↓
DashboardResource
    │ バリデーション（選択肢に含まれるか確認）
    ↓
SessionManagerActor → K8sApiClient
    │ ConfigMap "pups-prefs-{userId}" に保存
    ↓
user-pods namespace の ConfigMap
    data:
      storageSize: "500Gi"
```

### ツール起動時のPVC作成

```
ツール Launch ボタン
    ↓
SessionManagerActor.createSession()
    │ ConfigMap から storageSize を読み取り
    │ SessionInfo.userStoragePreference にセット
    ↓
SessionActor.start()
    │ resolveStorageSize():
    │   1. userStoragePreference があればそれを使用
    │   2. なければ ResourceProfile のデフォルト値にフォールバック
    ↓
K8sApiClient.createUserPvcIfAbsent(userId, storageSize)
    │ PVC未作成 → 指定サイズで新規作成
    │ PVC既存 & 小さい → 拡張を試みる
    │ PVC既存 & 同じor大きい → 何もしない
    ↓
Pod に PVC マウント（/home/user 等）
```

### 重要: PVCの制約

- **PVCは拡張のみ可能**。縮小はKubernetesの制約で不可。
- StorageClassに `allowVolumeExpansion: true` が必要。microk8s-hostpathの場合は手動で有効化が必要:
  ```bash
  kubectl patch sc microk8s-hostpath -p '{"allowVolumeExpansion": true}'
  ```
- hostpathプロビジョナーは物理的にサイズを制限しない（ノードのディスク容量がそのまま使える）。PVCのサイズはKubernetes上の管理値として機能する。

## 設定

### `application.properties`

| プロパティ | 環境変数 | デフォルト | 説明 |
|-----------|---------|-----------|------|
| `k8spups.storage-size-options` | `K8SPUPS_STORAGE_SIZE_OPTIONS` | `20Gi,50Gi,100Gi,500Gi,1Ti` | ドロップダウンに表示する選択肢 |
| `k8spups.default-storage-size` | `K8SPUPS_DEFAULT_STORAGE_SIZE` | `100Gi` | ユーザーが未設定時のデフォルト値 |

### RBAC

`k8s-pups-pod-manager` Roleに `configmaps` リソースの権限が必要:

```yaml
rules:
  - apiGroups: [""]
    resources: ["pods", "services", "persistentvolumeclaims", "configmaps"]
    verbs: ["create", "delete", "get", "list", "watch", "update"]
```

## Kubernetesリソース

### ConfigMap（ユーザー設定保存先）

| 項目 | 値 |
|------|---|
| 名前 | `pups-prefs-{userId}` |
| Namespace | `user-pods` |
| ラベル | `app=k8s-pups-user`, `managed-by=k8s-pups`, `user={userId}` |
| データキー | `storageSize` |

例:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: pups-prefs-testadmin
  namespace: user-pods
  labels:
    app: k8s-pups-user
    managed-by: k8s-pups
    user: testadmin
data:
  storageSize: "500Gi"
```

### PVC（ユーザーデータ）

| 項目 | 値 |
|------|---|
| 名前 | `pups-data-{userId}` |
| Namespace | `user-pods` |
| AccessMode | `ReadWriteOnce` |
| サイズ | ユーザー設定値 or プラグインデフォルト |

## 変更対象ファイル一覧

| ファイル | 変更内容 |
|---------|---------|
| `K8sApiClient.java` | `getUserPvcInfo()`, `getUserStoragePreference()`, `saveUserStoragePreference()` 追加 |
| `SessionInfo.java` | `userStoragePreference` フィールド追加 |
| `SessionManagerActor.java` | ConfigMap委譲メソッド追加、`createSession()`でストレージ設定読み取り |
| `SessionActor.java` | `resolveStorageSize()`でユーザー設定優先 |
| `K8sPupsActorSystem.java` | `storageSizeOptions`, `defaultStorageSize` 設定プロパティ追加 |
| `DashboardResource.java` | テンプレートデータ追加、`POST /storage/save` エンドポイント追加 |
| `application.properties` | `k8spups.storage-size-options`, `k8spups.default-storage-size` 追加、auth permissionに `/storage/*` 追加 |
| `dashboard.html` | Storage Settingsカード追加（ツールグリッド先頭） |
| `rbac.yaml` | `configmaps` リソース権限追加 |

## トラブルシューティング

### Saveしてもダッシュボードのサイズが変わらない

**症状**: Storage SettingsでサイズをSaveしたが、「Current PVC: 20Gi (Bound)」のまま。

**原因**: Saveはユーザーの**設定（ConfigMap）**を保存するだけ。実際のPVCサイズは次回ツール起動時に拡張される。既存PVCが既に存在する場合、新しい設定値が現在のPVCサイズより大きければ拡張を試みる。

**確認方法**:
```bash
# ConfigMapに設定が保存されているか確認
kubectl get configmap pups-prefs-testadmin -n user-pods -o yaml

# PVCの現在のサイズ確認
kubectl get pvc pups-data-testadmin -n user-pods
```

### PVC拡張がエラーになる

**症状**: ツール起動時にログに "PVC expansion failed (StorageClass may not support resize)" と表示される。

**原因**: StorageClassに `allowVolumeExpansion: true` が設定されていない。

**修正方法**:
```bash
kubectl patch sc microk8s-hostpath -p '{"allowVolumeExpansion": true}'
```

### ConfigMap作成がForbiddenエラーになる

**症状**: ストレージ設定のSave時に500エラー。ログに "configmaps is forbidden" と表示。

**原因**: `k8s-pups-pod-manager` Roleに `configmaps` 権限がない。

**修正方法**:
```bash
kubectl apply -f k8s/rbac.yaml
```
