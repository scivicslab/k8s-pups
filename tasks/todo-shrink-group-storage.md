# グループストレージの縮小（設計 A：二段階、Lustre のコマンドは管理者が a007 で打つ）

依頼: chat-01（project10/chat-01:s148:t58:r1）

## 確かめた事実（a007 は読み取りのみ）
- この節の識別子（`projectId`、`exaFS` の NID とファイルシステム名、PV 名、グループ名）は置き換えた例の値である。a007 の実際の値は、そのグループの PV の volumeAttributes（`kubectl get pv <name> -o yaml`）で読む。
- PV の `volumeAttributes` に `projectId`（例 `12345`）と `exaFS`（例 `mds0@o2ib0:...:/lustre0`）がある。`mountPoint` は `/lustre0_k8s`（ノード側の CSI のマウント先）で、a007 では Lustre `/lustre0` が `/lustre0` にマウントされている。コマンドのファイルシステムは `exaFS` の最後の `:` の後ろ（`/lustre0`）から作る。
- `lfs quota -p 12345 /lustre0` の blimit は KB 単位（100Gi = 104857600）。`lfs setquota -B` の数値は単位なしなら KB。表示するコマンドは KB の数値で書き、`lfs quota` の表示と同じ単位にそろえる。
- 課金: `storage_group` の更新トリガ `storage_group_record` が `storage_reservation_change` を書く。縮小の準備（active→revoked）で `revoke` 0 が 1 行、縮小の続行（revoked→active を新しい size と同じ UPDATE で）で `restore` 新サイズが 1 行。別に resize の UPDATE をしないので二重にならない。
- RBAC: a007 で動いている `k8s-pups-with-audit-node-reader` と `k8s-pups-private-overlays/overlays/{a007-with-audit,pups-with-audit}` に PV の `update` がある（restore で claimRef を外すため）。PV の容量の書き換えも `update` で行い、`patch` は使わない。新しい権限は要らない。リポジトリ内の `k8s/overlays/nig/rbac.yaml`・`local-llm` は `update` を持たないが、これらは nfs/既存の別配備で、変更しない。

## 決めたこと
- 永続化: `V10__storage_group_shrink.sql` で `storage_group.shrink_to TEXT NULL` を足す。state は `active`/`revoked` のまま。`shrink_to` は state が `revoked` のときだけ値を持てる（CHECK）。
- 縮小待ちのときに普通の Give back が押されたら **拒否**する（理由: 管理者がもう quota を下げていたら、古いサイズで戻すと Kubernetes の数字と quota が食い違う）。続行か取り消しを選ばせる。
- 取り消しは「取り上げた状態」に戻すだけ（`shrink_to` を消す）。quota をもう下げていた場合に戻すコマンド（`-B <旧サイズ>`）も画面に出す。
- nfs バックエンド、または PV に `projectId`/`exaFS` が無い場合は縮小を拒否する（Lustre の project quota が無い）。

## 手順
- [x] 0. 既存コードと a007 の事実を読む
- [x] 1. `GroupShrink`（純粋な POJO）: サイズ比較・状態ごとの拒否文・Lustre コマンド文の組み立て
- [x] 2. `GroupShrinkTest`（ユニットテスト）
- [x] 3. Flyway `V10__storage_group_shrink.sql`
- [x] 4. `StorageAllocationRepository`: `Group.shrinkTo`、`prepareGroupShrink`、`finishGroupShrink`、`cancelGroupShrink`
- [x] 5. `K8sApiClient.restoreGroupPvc` に容量を指定できる形を足す（PV の claimRef を外す更新と同じ update で capacity も書く）
- [x] 6. `StorageAllocationService`: `prepareGroupShrink` / `continueGroupShrink` / `cancelGroupShrink`、`restoreGroup` の拒否、監査イベント `group.shrink.prepare` / `group.shrink` / `group.shrink.cancel`
- [x] 7. `StorageAdminResource`: `/g/{name}/shrink`、`/shrink/continue`、`/shrink/cancel`、画面にコマンドを出す
- [x] 8. テンプレート `storageAdminGroup.html`
- [x] 9. `rm -rf target` → `nice -n 19 mvn -T 1 test`
- [x] 10. レビュー節を書く

## レビュー

### 変わったもの
- 新規: `storage/GroupShrink.java`（純粋な POJO）、`GroupShrinkTest.java`（7 件）、`db/migration/V10__storage_group_shrink.sql`
- 変更: `StorageAllocationRepository`（`Group.shrinkTo`、`prepareGroupShrink`・`finishGroupShrink`・`cancelGroupShrink`）、`StorageAllocationService`（`prepareGroupShrink`・`shrinkCommands`・`continueGroupShrink`・`cancelGroupShrink`、`restoreGroup` の拒否）、`K8sApiClient`（`restoreGroupPvc(group, rec, size)`、`restorePvc` が PV の容量も同じ update で書く）、`StorageAdminResource`（`/g/{name}/shrink`、`/shrink/continue`、`/shrink/cancel`）、`storageAdminGroup.html`
- `DashboardResource.java` ほか既存の未コミット変更には触れていない。

### ふるまい
- 準備: 拒否の順は「縮小待ちがある」→「取り上げ済み」→「サイズの形」→「同じ／大きい」→ nfs バックエンド → Pod が使用中（revoke と同じ文）→ PVC が PV に結び付いていない → PV に `projectId`/`exaFS` が無い。通れば PVC を消し、1 回の UPDATE で `state='revoked'`・PV の記録・`shrink_to` を書く。監査 `group.shrink.prepare`。
- 画面: `exaFS` の最後の `:` の後ろをファイルシステムとし、`lfs quota -p <id> <fs>`、`lfs setquota -p <id> -B <KiB> <fs>`、取り消し用の `-B <旧KiB>` を出す。値は数字・パスの形を検査してから文字列にする。
- 続行: PV の claimRef を外し容量を新サイズにする update を 1 回、PVC を新サイズで同じ PV に作る。DB は size・state・shrink_to を 1 回の UPDATE で変える。監査 `group.shrink`。
- 取り消し: `shrink_to` を消すだけ。取り上げた状態（旧サイズ）に戻り、普通の Give back で戻せる。監査 `group.shrink.cancel`。
- 縮小待ちの Give back は拒否（画面ではボタンを出さず、直接 POST しても service が拒否）。

### 課金
- 準備で `revoke`（0）が 1 行、続行で `restore`（新サイズ）が 1 行。取り消しは行を書かない。`event` の値に `shrink` は足していない（CHECK と課金 API を変えないため）。
- `storage_reservation_daily` は日ごとの最大を取るので、縮小した日は旧サイズで数えられる。これは resize・restore と同じ既存の決まり。

### 確かめていないこと
- 実クラスタでの動作（PV の `spec.capacity` を下げる update が通るか、exa CSI ドライバが容量の変わった PV に PVC を結び付けるか、外部 resizer が手を出さないか）。デプロイは別の判断。
- Flyway V10 を実 DB に当てること（ユニットテストに DB は無い）。
- `lfs setquota -B` に単位なしの数値を渡すと KiB と読む点は lfs の仕様どおりだが、a007 では実行していない（読み取りの `lfs quota` だけ実行し、`kbytes`・`blimit` の列名と 100Gi = 104857600 を確かめた）。
