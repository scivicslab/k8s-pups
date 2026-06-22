# user-pods NetworkPolicy 設計

## 基本方針

`user-pods` namespace にはデフォルト拒否ポリシー（`default-deny-all`）を適用し、
必要な通信のみ個別の NetworkPolicy で明示的に許可する **ホワイトリスト方式** を採用する。

## ポリシー一覧

### `default-deny-all` — デフォルト拒否

| 項目 | 値 |
|------|-----|
| 対象Pod | 全Pod |
| Ingress | 全拒否 |
| Egress | 全拒否 |

全ての通信を遮断するベースライン。以下のポリシーで必要な通信だけ穴を開ける。

---

### Egress（出方向）

#### `allow-egress-dns` — DNS 解決

| 項目 | 値 |
|------|-----|
| 対象Pod | 全Pod |
| 宛先 | `kube-system` namespace（CoreDNS） |
| ポート | 53/UDP, 53/TCP |

全 Pod が名前解決できなければ何も動かないため、無条件で許可。

#### `allow-egress-nfs` — NFS ストレージ

| 項目 | 値 |
|------|-----|
| 対象Pod | 全Pod |
| 宛先 | `192.168.5.20/32`（NFS サーバ） |
| ポート | 2049/TCP (NFS), 111/TCP (portmapper) |

ユーザのホームディレクトリ（NFS マウント）へのアクセス。

#### `allow-egress-minio` — S3 オブジェクトストレージ

| 項目 | 値 |
|------|-----|
| 対象Pod | 全Pod |
| 宛先 | `minio` namespace |
| ポート | 9000/TCP |

MinIO（S3 互換ストレージ）へのアクセス。

#### `allow-egress-dgx` — GPU サーバ（LLM 推論）

| 項目 | 値 |
|------|-----|
| 対象Pod | `tool=chat-ui` |
| 宛先 | `192.168.5.15/32`, `192.168.5.13/32` |
| ポート | 8000/TCP |

Coder Agent から DGX/GPU サーバ上の LLM 推論エンドポイントへのアクセス。

#### `allow-egress-llm-api` — 外部 LLM API

| 項目 | 値 |
|------|-----|
| 対象Pod | `tool in (chat-ui-claude, chat-ui-codex)` |
| 宛先 | 全外部（ポート制限あり） |
| ポート | 443/TCP |

Claude / Codex エージェントから外部 API（Anthropic, OpenAI 等）への HTTPS 通信。

#### `allow-egress-internet` — 外部インターネット

| 項目 | 値 |
|------|-----|
| 対象Pod | `tool in (jupyter-lab, guacamole)` |
| 宛先 | 外部 IP のみ（`0.0.0.0/0` から `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16` を除外） |
| ポート | 80/TCP, 443/TCP |

JupyterLab およびリモートデスクトップから外部リソース（パッケージリポジトリ、NCBI、
PyPI 等）へのアクセス。プライベート IP レンジは除外し、クラスタ内部への意図しない
アクセスを防止する。

---

### Ingress（入方向）

#### `allow-ingress-http` — Gateway からの HTTP アクセス

| 項目 | 値 |
|------|-----|
| 対象Pod | `tool not in (desktop)` |
| 送信元 | `envoy-gateway-system` namespace |
| ポート | 8090, 8888, 8080, 3000/TCP |

Envoy Gateway（HTTPRoute 経由）からツール Pod への HTTP 転送。
デスクトップ Pod は Guacamole サイドカー経由でアクセスするため除外。

---

## 設計判断

### なぜデフォルト拒否か

- ユーザが任意のコードを実行できるマルチテナント環境である
- 万一コンテナが侵害された場合の横移動（lateral movement）を制限する
- 必要な通信を明示的に許可することで、監査しやすくなる

### なぜ JupyterLab / Guacamole に外部アクセスを許可するか

- 研究用途で外部データベース（NCBI SRA、UniProt 等）からのデータ取得が必須
- `pip install`, `conda install`, `apt-get` でパッケージ追加が必要
- HTTP/HTTPS（80, 443）のみに制限し、任意ポートへの接続は許可しない
- プライベート IP レンジを除外し、クラスタ内サービスへの不正アクセスを防止

### 外部アクセスを許可しないツール

- `chat-ui`: DGX と外部 LLM API のみ許可（専用ポリシーで制限）
- `sra-submission`: 外部通信不要（submission-portal 経由でデータを受け渡し）
- `file-browser`: 外部通信不要（ローカルストレージのブラウズのみ）
- `docusaurus`: 外部通信不要（ビルド済みイメージで静的サイトを配信）
