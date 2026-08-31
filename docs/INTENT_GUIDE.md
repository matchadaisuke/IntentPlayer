# IntentPlayer インテント操作ガイド

IntentPlayer は、ブロードキャスト・インテントを使って MacroDroid、Tasker、Automate、ADB などから操作できます。

## 基本設定

- Action: `com.intentplayer.CONTROL`
- 種類: Broadcast / ブロードキャスト
- 対象パッケージ: `com.intentplayer` 推奨
- Extra `command` (String): 実行する操作

`play` / `force_play` で `folderUri` を省略した場合は、IntentPlayer の **設定 → ファイルとフォルダ → 既定のフォルダ** を使用します。

## command 一覧

| command | 動作 | 追加のExtra |
|---|---|---|
| `play` | 現在位置から再生 / 再開 | `folderUri` (String、省略可) |
| `force_play` | 対象フォルダの先頭から再生 | `folderUri` (String、省略可) |
| `pause` | 一時停止 | なし |
| `stop` | 停止して再生位置をクリア | なし |
| `next` | 次のファイルへ移動 | なし |
| `previous` | 3秒超再生済みなら先頭、それ以外は前のファイル | なし |
| `seek` | 再生位置を移動 | `seekTo` (Long、ミリ秒、0以上) |
| `speed` | 再生速度を変更 | `speed` (Float、0.50〜5.00、0.25刻み) |

## 再生速度

`speed` は `0.50` から `5.00` まで `0.25`刻みで指定します。

有効例:

- `0.5`
- `0.75`
- `1.0`
- `1.25`
- `2.0`
- `3.5`
- `5.0`

`1.1` や `5.25` などはエラーになります。設定した速度は保存され、Serviceが再生成された後も維持されます。通知と再生画面の残り時間もこの速度を反映します。

## folderUri

`folderUri` は `String` です。

- SAFで選択したフォルダ: `content://...`
- 直接アクセス可能なストレージ: `file://...`

`content://` URI は、IntentPlayerがAndroidから読み取り権限を保持している必要があります。別アプリが独自に取得したURIを文字列だけ渡しても、IntentPlayer側に権限がなければ読めない場合があります。

## MacroDroid

1. **アクション → インテントを送信** を追加します。
2. ターゲットを **ブロードキャスト** にします。
3. Action に `com.intentplayer.CONTROL` を指定します。
4. Package を指定できる場合は `com.intentplayer` を指定します。
5. Extra `command` を String で追加します。
6. 必要に応じて `folderUri`、`seekTo`、`speed` を追加します。

### 例: 既定フォルダを再生

- `command` (String): `play`

### 例: 2.5倍速

- `command` (String): `speed`
- `speed` (Float): `2.5`

### 例: 1分30秒へ移動

- `command` (String): `seek`
- `seekTo` (Long): `90000`

## Tasker

1. Task に **Send Intent** を追加します。
2. Action に `com.intentplayer.CONTROL` を指定します。
3. Target を **Broadcast Receiver** にします。
4. Package に `com.intentplayer` を指定します。
5. Extra に `command:play` などを設定します。

Taskerのバージョンによって型指定UIが異なります。`seekTo` は Long 相当、`speed` は Float 相当で送信してください。

## Automate

1. **Broadcast send** ブロックを追加します。
2. Package に `com.intentplayer` を指定します。
3. Action に `com.intentplayer.CONTROL` を指定します。
4. Extras に `command` と必要な値を追加します。

## ADB 例

```sh
adb shell am broadcast -a com.intentplayer.CONTROL -p com.intentplayer --es command play
adb shell am broadcast -a com.intentplayer.CONTROL -p com.intentplayer --es command pause
adb shell am broadcast -a com.intentplayer.CONTROL -p com.intentplayer --es command next
adb shell am broadcast -a com.intentplayer.CONTROL -p com.intentplayer --es command seek --el seekTo 60000
adb shell am broadcast -a com.intentplayer.CONTROL -p com.intentplayer --es command speed --ef speed 2.5
adb shell am broadcast -a com.intentplayer.CONTROL -p com.intentplayer --es command speed --ef speed 5.0
```

## 再生完了イベント

IntentPlayer は次のBroadcastを外部へ送信します。

- Action: `com.intentplayer.PLAYBACK_EVENT`

Extras:

- `event` (String)
  - `track_completed`: 1ファイル完了
  - `playlist_completed`: キュー完了
- `trackName` (String): 対象ファイル名
- `folderUri` (String): 現在のフォルダURI

## エラーイベント

- Action: `com.intentplayer.ERROR`

Extras:

- `reason` (String): エラー識別子
- `message` (String): 人が読める説明

代表例:

| reason | 意味 |
|---|---|
| `no_command` | `command` がない |
| `unknown_command` | 未対応command |
| `invalid_seek` | `seekTo` が不正 |
| `invalid_speed` | 再生速度が範囲外または0.25刻みではない |
| `invalid_uri` | `folderUri` が不正 |
| `no_folder_uri` | folderUriも既定フォルダもない |
| `no_files` | 音声ファイルがない |
| `permission_error` | フォルダ権限不足 |
| `scan_error` | フォルダ読み込み失敗 |
| `blocked_media_mute` | メディア音量0のため再生を拒否 |
| `paused_media_mute` | 再生中に音量0になり自動停止 |
| `playback_failed` | ExoPlayer再生エラー |

アプリが表示中の場合、主要な再生エラーはトーストでも表示します。

## アプリ設定について

現時点の公開インテントAPIは、上記の再生操作と再生速度変更です。Bluetooth連動、オーディオフォーカス、独自音量ON/OFF、自動再開時間などの設定値そのものを外部から書き換える汎用 `set_setting` API はまだ公開していません。

設定の一括移行には、アプリ内の **設定 → バックアップ → エクスポート / インポート** を使用してください。実装されていない設定用Extraを送っても反映されません。

## Androidのバックグラウンド制限

PlaybackServiceが完全停止している状態では、Androidのバックグラウンド起動制限により外部アプリからServiceを開始できない場合があります。その場合はIntentPlayerを一度開いてから再度実行してください。

また、次の権限やOS設定が動作に影響します。

- 通知権限
- 音声ファイル読み取り権限
- すべてのファイルへのアクセス
- SAFの永続URI権限
- Android 12以降のBluetooth接続権限
- バッテリー最適化

## 互換性方針

既存の `command` 名とExtra名は可能な限り維持します。値域を変更する場合はREADME・本ドキュメント・入力検証・UI・Serviceを同時に更新します。
