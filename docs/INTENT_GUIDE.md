# IntentPlayer インテント操作ガイド

IntentPlayerは、ブロードキャスト・インテントを使ってMacroDroid、Tasker、Automateなどの自動化アプリから操作できます。

## 基本設定

- アクション: `com.intentplayer.CONTROL`
- 種類: Broadcast / ブロードキャスト
- 対象パッケージ: `com.intentplayer`
- Extra `command` (String): 実行する操作

`play` / `force_play` で `folderUri` を省略した場合は、IntentPlayerの **設定 → ファイルとフォルダ → 既定のフォルダ** を使用します。

## command一覧

| command | 動作 | 追加のExtra |
|---|---|---|
| `play` | 再生 / 再開 | `folderUri` (String、省略可) |
| `force_play` | 指定フォルダを読み込んで再生 | `folderUri` (String、省略可) |
| `pause` | 一時停止 | なし |
| `stop` | 停止 | なし |
| `next` | 次のファイル | なし |
| `previous` | 前のファイル | なし |
| `seek` | 再生位置を移動 | `seekTo` (Long、ミリ秒) |
| `speed` | 再生速度を変更 | `speed` (Float、0.5〜2.0) |

## MacroDroid

1. アクションから **インテントを送信** を追加します。
2. ターゲットを **ブロードキャスト** にします。
3. アクションに `com.intentplayer.CONTROL` を入力します。
4. パッケージを指定できる場合は `com.intentplayer` を入力します。
5. Extraに `command` をStringとして追加し、`play`、`pause`、`next` などを指定します。
6. `seek` の場合はLongの `seekTo`、`speed` の場合はFloatの `speed` を追加します。

例: 既定のフォルダを再生

- Action: `com.intentplayer.CONTROL`
- Extra: `command` = `play` (String)

## Tasker

1. Taskに **Send Intent** を追加します。
2. Actionに `com.intentplayer.CONTROL` を入力します。
3. Targetを **Broadcast Receiver** にします。
4. Packageに `com.intentplayer` を指定します。
5. Extraに `command:play` のように操作を設定します。

TaskerのバージョンによってExtraの型指定UIが異なります。`seekTo` は整数(Long)、`speed` は小数(Float)として送信してください。

## Automate

1. フローに **Broadcast send** ブロックを追加します。
2. Action package / Packageには `com.intentplayer` を指定します。
3. Actionに `com.intentplayer.CONTROL` を指定します。
4. Extrasに `command` と値を追加します。

例: 次のファイル

- `command`: `next`

## ADBで動作確認

```sh
adb shell am broadcast -a com.intentplayer.CONTROL -p com.intentplayer --es command play
adb shell am broadcast -a com.intentplayer.CONTROL -p com.intentplayer --es command pause
adb shell am broadcast -a com.intentplayer.CONTROL -p com.intentplayer --es command next
adb shell am broadcast -a com.intentplayer.CONTROL -p com.intentplayer --es command seek --el seekTo 60000
```

## 再生完了イベント

IntentPlayerは再生状況をブロードキャストで通知します。

- Action: `com.intentplayer.PLAYBACK_EVENT`
- `event`: `track_completed` または `playlist_completed`
- `trackName`: 対象ファイル名

## エラーイベント

- Action: `com.intentplayer.ERROR`
- `reason`: エラーの種類
- `message`: エラー内容

## Androidのバックグラウンド制限

IntentPlayerの再生サービスが完全に停止している状態では、Androidのバックグラウンド起動制限によって外部アプリから再生サービスを開始できない場合があります。その場合はIntentPlayerを一度開いてから再度実行してください。
