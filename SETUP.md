# APK Receiver

Send APK download URLs from your iPhone to an AOSP Android TV box. The box app runs a
tiny HTTP server, downloads the APK with Android's `DownloadManager`, and opens the
package installer (or installs silently if rooted).

```
iPhone camera -> QR -> http://<box-ip>:8080/?token=...
   paste APK URL -> POST /fetch -> DownloadManager -> /sdcard/Download/ -> installer
```

## Requirements

- Android 5.0+ (API 21) TV box (works best on Android 9-13; AOSP fine).
- JDK 17 + Android SDK (or just Android Studio).
- For first install: ADB over network, USB, or any sideload method.

## Build

### Android Studio (easiest)
1. `File > Open` this folder. Studio generates the Gradle wrapper jar on first sync.
2. Build the `app` module -> `Build > Build Bundle(s)/APK(s) > Build APK(s)`.

### Command line
The Gradle wrapper JAR is not committed. Generate it once:

```bash
# with a system Gradle:
gradle wrapper --gradle-version 8.7
# then:
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

If you build from the CLI you may need `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Install on the box (first time)

Enable **Developer options > ADB over network** on the box, then:

```bash
adb connect <box-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First run

1. Open **APK Receiver** on the TV.
2. Tap **Grant install permission** and enable *Allow from this source*.
3. A QR code appears. Scan it with the iPhone Camera app.
4. Safari opens the receiver page. Paste an APK URL and tap **Download & Install**.
5. The TV shows the install dialog - confirm with the remote.

Settings available in the app: port (default `8080`), an optional **auth token**
(recommended - it is embedded in the QR URL), **start on boot**, and
**silent install** (only enabled when root is detected; runs `su -c "pm install -r"`).

## iOS Shortcut (optional power-user path)

Add the receiver to the iOS **Share Sheet** so you can share any link straight to the TV:

1. Shortcuts app -> new shortcut named `Send to TV`.
2. Add **Receive** "URLs" from **Share Sheet** (turn off "If there's no input").
3. Add **Get Contents of URL**:
   - URL: `http://<box-ip>:8080/fetch`
   - Method: `POST`
   - Headers: `X-Auth-Token` = your token (if set)
   - Request Body: `JSON`, field `url` = `Shortcut Input`
4. Add **Show Notification** (optional).
5. In the shortcut's settings, enable *Show in Share Sheet* with types **URLs** and **Text**.

## API

```
GET  /                        web UI (index.html)
GET  /ping                    {"ok":true,...}
POST /fetch  {"url","name"?}  -> {"id","name","url","time"}
GET  /status?id=<id>          download status/progress
GET  /history                 recent requests
```

Auth: if a token is set, requests must include header `X-Auth-Token: <token>`,
query `?token=<token>`, or JSON field `token`. Only `http`/`https` URLs are accepted.

## Rooted silent install

Enable **silent install** in the app. On completion the box runs
`su -c "pm install -r /sdcard/Download/<file>.apk"` with no dialog. If the `su` call
fails it automatically falls back to the installer dialog.

## Troubleshooting

- **Server offline / QR opens nothing**: confirm the phone and box are on the same
  Wi-Fi/network, and that the box IP in the app matches. Reopen the app to refresh the QR.
- **Port already in use**: change the port in the app and tap *Save & restart server*.
- **No install dialog**: tap *Grant install permission*; also make sure the file ended
  with `.apk`.
- **Boot autostart does nothing on Android 15+**: recent Android restricts starting
  data-sync foreground services from `BOOT_COMPLETED`. Open the app once after boot, or
  use a rooted init/boot script to launch the service.
- **Token mismatch (401)**: re-scan the QR after changing the token - the QR embeds it.
