# Klestra v1.1 Embedded Quorum

Kotlin Multiplatform で組み込み利用する、固定メンバー構成の簡易 Raft エンジンです。
JVM ターゲットを設定し、選挙・ログ複製のロジックを `commonMain` に配置しています。

## ビルドと実行

- JDK 17（`JAVA_HOME` を設定）
- Gradle 8.14（同梱 Wrapper で固定）
- Kotlin 2.2.20（要件の Kotlin 1.9+ を満たします）

Kotlin と Gradle の組み合わせは [Kotlin 公式の互換性表](https://kotlinlang.org/docs/gradle-configure-project.html) に基づきます。
初回は Gradle と Maven Central の依存関係を取得するため、ネットワーク接続が必要です。

```bash
./gradlew build
./gradlew jvmTest
./gradlew runDemo
```

Windows では `gradlew.bat` を使用します。`runDemo` は 3 ノード間で RPC をメモリ内配送し、
自動選出後に `put greeting=hello` が全ノードでコミットされたことを検証します。

## 構成

```text
src/commonMain/kotlin/dev/kigawa/klestra/
  KlestraConfig.kt       CRD 形式の設定・メンバー定義
  AgentMode.kt           4 つの動作モード
  ElectionPolicy.kt      Automatic 選挙ポリシー
  ConsensusMessage.kt    JSON 化可能な Raft RPC とログ
  ConsensusEngine.kt     選挙・ハートビート・複製・コミット
src/jvmMain/kotlin/dev/kigawa/klestra/
  Main.kt                3 ノードの実行例
src/commonTest/kotlin/dev/kigawa/klestra/
  ConsensusEngineTest.kt 選挙、分断、ログ修復、設定などのテスト
```

## 設定データ

`KlestraConfig` は Kubernetes カスタムリソース形式のシリアライズ可能なデータ型です。
このプロジェクトは CRD の OpenAPI スキーマや Kubernetes コントローラーをインストールしません。
リソースの API バージョン `v1alpha1` とライブラリのバージョン `1.1.0` は別です。

```json
{
  "apiVersion": "klestra.kigawa.dev/v1alpha1",
  "kind": "KlestraConfig",
  "metadata": { "name": "example", "namespace": "default" },
  "spec": {
    "nodeId": "node-1",
    "members": [
      { "id": "node-1", "endpoint": "memory://node-1", "voting": true },
      { "id": "node-2", "endpoint": "memory://node-2", "voting": true },
      { "id": "node-3", "endpoint": "memory://node-3", "voting": true }
    ],
    "electionPolicy": {
      "type": "Automatic",
      "minTimeoutMillis": 300,
      "maxTimeoutMillis": 600,
      "heartbeatIntervalMillis": 100
    }
  }
}
```

`Json.decodeFromString<KlestraConfig>(text)` で読み込めます。全ノードで同じメンバー一覧を使い、
`spec.nodeId` を自分の ID に変更します。ID の重複、ローカルノードの欠落、投票者が 0 の構成、
無効なタイムアウトは生成・デコード時に拒否します。`endpoint` はホスト側の通信実装用で、
エンジンは接続を開始しません。

## 4 モードと自動選挙

| AgentMode | 動作 |
| --- | --- |
| `FOLLOWER` | 投票し、リーダーからのログを受信する |
| `CANDIDATE` | 任期を増やして自己投票し、他の投票者に投票を要求する |
| `LEADER` | ハートビートを送り、コマンドを複製する |
| `OBSERVER` | `voting=false` のメンバー。ログを受信するが、投票・立候補しない |

`ElectionPolicy.Automatic` は選挙ごとに範囲内の待機時間をランダムに選びます。
有効なハートビートの受信または投票の許可で待機時間をリセットし、期限切れなら自動的に選挙を開始します。
ログの新しさと任期ごとに 1 候補への投票を検証し、重複した票は数えません。
過半数は **投票者数 / 2 + 1** で、Observer は含みません。

## アプリケーションへの組み込み

```kotlin
val config = KlestraConfig(
    metadata = ObjectMeta("example"),
    spec = KlestraSpec(
        nodeId = "node-1",
        members = listOf(QuorumMember("node-1", "memory://node-1")),
    ),
)
val engine = ConsensusEngine(config)
engine.tick(600) // この単一投票者構成ではリーダーになる
engine.propose("put greeting=hello")
check(engine.committedCommands == listOf("put greeting=hello"))
```

複数ノードでは、アプリケーションが以下を単一のイベントループ上で実行します。

1. 経過時間（ミリ秒）を `tick(elapsedMillis)` に渡す。
2. 戻り値の `OutboundMessage` をそれぞれ `to` に配送する。
3. 受信側の `receive(senderId, message)` に渡し、その戻り値も配送する。
4. リーダーに対して `propose(command)` を呼び、戻り値の RPC を配送する。

`propose` はローカルログへの追加を行います。戻った時点で複数ノードへのコミットを保証しません。
投票者の過半数への複製を確認すると `commitIndex` が進みます。
アプリケーションは `log` と `commitIndex` を読み、未適用のコミット済みエントリーを順番に適用します。
インデックスは 1 始まりで、0 は空ログを示します。各リーダー任期の開始時には
`command=null` の no-op を追加し、過去の任期のログも安全にコミットできるようにします。
`committedCommands` は no-op を除いたコマンド一覧のスナップショットです。

## 実装範囲

- 任期、投票、ログはメモリ内のみです。再起動を含む Raft の安全性には永続ストレージが必要です。
- メンバー構成は固定です。動的な参加・離脱、スナップショット、ログ圧縮は未実装です。
- 通信、認証、再送のスケジューリング、状態機械への適用は利用側が担当します。
  RPC は信頼されたメンバーから届く前提で、エンジンはスレッドセーフではありません。
- 分断された旧リーダーは新しい任期を知るまでリーダー状態に留まりますが、過半数なしでは新しいログをコミットできません。
- 線形化可能な読み取りや、クライアント要求の重複排除は提供しません。

テストでは自動選挙、単一投票者、重複投票、選挙再試行、ログ鮮度、ハートビート、
古い任期・遅延応答、ネットワーク分断後の再選出とログ修復、Observer、JSON 往復を検証します。
