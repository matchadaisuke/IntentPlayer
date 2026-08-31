# IntentPlayer 開発者向けドキュメント

この文書は、IntentPlayerの内部構造、状態同期、権限、ストレージ、設定永続化、バックアップ、インテント互換性、CI方針を開発者向けにまとめたものです。利用者向けの操作説明はREADME、外部操作APIは `docs/INTENT_GUIDE.md` を参照してください。

## 設計上の優先順位

1. 再生が途切れないこと
2. UI・通知・Serviceの状態が食い違わないこと
3. 外部インテントAPIの後方互換性
4. 権限不足やSDカード制約でクラッシュせずSAFへ復旧できること
5. 設定値をどの経路から変更しても同じ値域・同じ挙動になること
6. バックアップの不正入力で既存設定を壊さないこと

## 主なコンポーネント

### `MainActivity`

- ランタイム権限要求
- SAFフォルダ選択
- バッテリー最適化設定への導線
- PlaybackServiceエラーのトースト表示
- 通知タップ時の再生タブ遷移
- 通常画面の「2回戻ると終了」

通知起動は `MainActivity.EXTRA_OPEN_PLAYER_TAB` を明示的に付けたIntentだけを扱います。`ACTION_MAIN` 以外をすべて通知起動扱いにしないでください。

### `MainViewModel`

UI向け状態の単一窓口です。

主なStateFlow:

- `tracks`
- `currentTrack`
- `isPlaying`
- `currentPositionMs`
- `durationMs`
- `playbackSpeed`
- 各設定値

独自再生モードではPlaybackServiceの内部Broadcast、標準MediaSessionモードではMediaControllerを主に使用します。

曲切替時のちらつきを防ぐため、UIが先に `currentTrack` を書き換えず、PlaybackService / MediaControllerから確定した状態を受けて更新する方針です。

### `PlaybackService`

ExoPlayerを保持する再生の中心です。

責務:

- プレイリスト読み込み
- 再生 / 一時停止 / 停止 / seek / next / previous
- 再生速度
- 音量とLoudnessEnhancer
- MediaSessionの有効/無効
- 通知
- Bluetooth / 有線機器連動
- 音量0監視
- 再生位置保存
- 外部イベント / エラーBroadcast

同じフォルダのキュー内で曲を移動するだけの場合は、フォルダ再スキャンや `setMediaItems()` を行わず、既存プレイリスト上で `seekTo(index, 0)` を使います。再スキャンはキュー全体の再生成を伴い、バッファ状態・速度・UI状態を揺らすためです。

## 再生状態の定義

「再生ボタンが押されている状態」と「実際にPCMが出ている瞬間」を分けます。

UIや通知の再生/一時停止表示は、バッファ中の一時的な `isPlaying=false` で反転しないよう、原則として次を基準にします。

- `playWhenReady == true`
- media itemが存在
- `STATE_IDLE` / `STATE_ENDED` ではない

`isPlaying` はオーディオフォーカスや実再生検知など、実際に再生が開始されたことを知りたい場面に限定します。

## 再生速度

正式な値域:

- 最小: `0.50x`
- 最大: `5.00x`
- 刻み: `0.25x`

制約は `PreferencesManager` を正本とします。

- `MIN_PLAYBACK_SPEED`
- `MAX_PLAYBACK_SPEED`
- `PLAYBACK_SPEED_STEP`
- `normalizePlaybackSpeed()`

UI、ViewModel、PlaybackService、ControlReceiver、バックアップ、ドキュメントで独自の値域をハードコードしないでください。

再生速度はSharedPreferencesへ永続化し、PlaybackService生成時に復元します。通知と再生画面の残り時間計算は保存済み/要求中の速度を用います。

## 残り時間

表示は次の2値です。

- 現在ファイルの実時間残り
- キュー全体の実時間残り

概念式:

```text
fileRemaining = mediaRemaining / playbackSpeed
queueRemaining = (mediaRemaining + followingTracksDuration) / playbackSpeed
```

ExoPlayerが切替中でdurationを一時的に返せない場合は、Trackモデルに保存済みのdurationをフォールバックに使います。

## ストレージ

### 直接アクセス

`StorageBrowser` で内部ストレージとremovable storage（SDカード等）を列挙します。

表示対象は原則として:

- ディレクトリ
- 再生対象の音声ファイル

ディレクトリを先に並べます。

### SAF

直接アクセスできない、権限がない、Android/ベンダー側制限で読み取れない場合の復旧経路です。

`content://` URIを継続利用する場合は `takePersistableUriPermission()` が必要です。ただし、バックアップJSONにURI文字列を書いてもAndroidの権限自体は移行できません。

## 権限

主な権限:

- `READ_MEDIA_AUDIO` (Android 13+)
- `READ_EXTERNAL_STORAGE` (旧Android)
- `MANAGE_EXTERNAL_STORAGE`
- `POST_NOTIFICATIONS`
- `BLUETOOTH_CONNECT` (Android 12+)
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`

Bluetooth権限を拒否された場合でもService生成でクラッシュしないよう、receiver登録は `SecurityException` を考慮します。

`MANAGE_EXTERNAL_STORAGE` がない場合も、フォルダ画面からSAFへ移動できる状態を維持してください。

## 独自再生システム

`PreferencesManager.isUseCustomMediaPlayback()` がtrueの場合はMediaSessionを公開しません。

目的:

- Wear OS
- Bluetooth AVRCP
- Android System UI

などへ曲情報や外部操作インターフェースを公開しないことです。

フォアグラウンド通知はMediaSessionとは別なので維持します。独自再生モードを「通知を隠す機能」と説明しないでください。

## 独自音量

保存値は `0.0f..5.0f`（0〜500%）。

- 0〜100%: ExoPlayer volume
- 100%超: ExoPlayerを100%にし、LoudnessEnhancerで追加ゲイン

100%超は端末・音源によって歪みや音割れが発生し得ます。

## Bluetooth自動再開

関連設定:

- Bluetooth接続に合わせた一時停止/再開
- 自動再開の有効時間: 1分〜24時間
- 再接続後待ち時間: 0〜5000ms

切断時に「実際に再生中だったか」を記録し、ユーザーが手動停止していたものを勝手に再開しないことが重要です。

## 音量0停止

「音量0で一時停止する」が有効な場合:

- 再生開始時に0なら再生要求を拒否
- 再生中に0になったら自動一時停止
- 同じ出力経路で音量が戻った場合のみ自動再開

ユーザーが理由を判断できるよう、ブロック/自動停止時は `com.intentplayer.ERROR` とアプリ表示中のトーストで理由を示します。

## 設定永続化

設定の正本は `PreferencesManager` です。

原則:

- Setterでもclampする
- Getterでもclampする
- NaN / Infinityを受け付けない
- UIだけの入力制限に依存しない
- ViewModelは保存後に保存層から読み直した値をStateFlowへ入れる

これにより、将来インテントやバックアップなど別経路から値が入っても同じ制約になります。

## 設定バックアップ

JSON形式です。

含める:

- ユーザー設定
- 再生速度
- 既定フォルダURI（復元可能な場合のみ）

含めない:

- 再生位置
- 現在のトラックindex
- 最近のエラー
- AndroidのURI権限そのもの

インポート時は:

1. ファイルサイズ制限
2. `format` 検証
3. version検証
4. 型検証
5. 値域clamp
6. すべて検証後に一括commit

の順にします。途中まで設定を書いてから失敗する実装にしないでください。

## Broadcast Intent API

公開Action:

- `com.intentplayer.CONTROL`
- `com.intentplayer.PLAYBACK_EVENT`
- `com.intentplayer.ERROR`

内部状態同期用Broadcastはpackage限定にします。一方、`PLAYBACK_EVENT` と `ERROR` はMacroDroid等が受信する公開APIなのでpackage限定にしてはいけません。

API詳細は `docs/INTENT_GUIDE.md` を正本とします。

## エラー処理

ユーザーに必要なエラー:

- 再生できない理由
- 権限不足
- フォルダ読み込み失敗
- 再生エラー
- 不正インテント

ログには技術詳細を残してよいですが、トーストはユーザーが次に何をすればよいか分かる日本語を優先します。

## CI

PRでは最低限次を実行します。

```sh
gradle testDebugUnitTest --stacktrace
gradle lintDebug --stacktrace
gradle assembleDebug --stacktrace
gradle assembleRelease --stacktrace
```

コンパイルが通るだけでなくLintとrelease variantも確認します。

## 変更時チェックリスト

再生関連の値域・仕様を変更した場合は、最低限以下を同時確認します。

- UI
- ViewModel
- PlaybackService
- ControlReceiver
- PreferencesManager
- 通知
- バックアップ
- README
- INTENT_GUIDE
- CI

特に再生速度、音量、時間設定は1箇所だけ変更すると状態不整合の原因になります。
