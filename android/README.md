# Vishal Jarvis Android 1.5.1

The existing Android application keeps package ID `com.myraa.mobile`, Android 8 / API 26
minimum, API 35 target, the bundled WebView/Three.js robot and the existing settings,
live voice, memory and phone actions. Android System WebView 100+ and OpenGL ES 3 are
required. Gemini requires the user's own API key and access to the configured model.

## Reliability fixes (1.5.1)

Opening apps and other everyday actions were the weak point, so 1.5.1 fixes the whole path:

- **App names:** Bengali, Hindi and Banglish names are mapped before lookup
  (`ইউটিউব → youtube`, `হোয়াটসঅ্যাপ → whatsapp`), `the / my / … app` is stripped, and the
  launcher matcher no longer guesses from one or two letters. A miss now reports up to three
  close installed labels (`Did you mean …?`) instead of a bare failure.
- **Fallbacks:** YouTube opens in the browser when the app is missing, the camera action
  retries with the capture intent, Maps falls back to the web map, and popular services
  (Facebook, Instagram, Spotify, Netflix, …) open their site when no app is installed.
- **No wedged actions:** a finished action now clears its chooser/permission waiter, a timed-out
  action is cancelled instead of leaving a dialog that made every later action report "busy",
  and the in-app timeout dropped from 120 s to 60 s.
- **Live voice:** the bundled WebView called `/api/permissions`, which only exists on the
  desktop server; on Android that made every live call fail before audio started. It is now
  skipped inside the app. Typed text during a call is sent as `clientContent`, a tool handler
  exception no longer kills the session, and a second tap while connecting is ignored.
- **Text chat:** the `busy` lock no longer blocks phone commands, and long answers are no
  longer cut at 2048 tokens. Rate limiting (429/408/409) is described as temporary instead of
  invalidating a working key.
- **Activity/WebView:** the stop-live receiver is registered once, a WebView microphone denial
  now explains itself, and the page is kept when the Activity is recreated so a typed message
  or live call is not silently lost.

## Music and video playback (1.5)

`play_media` in `MobileActions` sends Android's standard
`android.intent.action.MEDIA_PLAY_FROM_SEARCH` browse intent with
`EXTRA_MEDIA_TITLE` and `SearchManager.QUERY`. The intent is pinned to one package
(`com.google.android.youtube`, `com.google.android.apps.youtube.music`, `com.spotify.music`,
`com.amazon.mp3`, `com.gaana`, `com.jio.media.ondemand`) when the user named a player, and is
only sent when that app is installed and resolves the intent. When no player is named, the
installed players are tried in that order. If a player does not accept the browse intent, its
own search URL for the exact query opens instead. The result status is `play_started` with
`data.mode` set to `direct` or `search`, or `not_installed` / `invalid`; the app never claims a
specific track began. Playback happens inside the user's own player and account, and the app
adds no media permission, storage access or media download.

## Background architecture

`MainActivity` persists each submitted AI text task in `AiTaskStore` before starting it.
An eligible `dataSync` foreground service holds a notification while the visible-user
request is active. A persistent `JobScheduler` job (the platform equivalent of a durable
WorkManager job, without an added AndroidX dependency) requires network, survives reboot,
and retries transient IO, quota and 5xx errors with exponential backoff. Permanent
configuration errors are reported instead of endlessly retried. The existing Android
Keystore key is read natively; no key is included in job extras, WebView, notifications,
source code or logs. User and model turns are recorded once per task ID. A job is cancelled
when the user explicitly stops it or clears history.

An Activity that is destroyed hands the request to its saved job. Device actions that need
foreground UI/confirmation are not performed from an unattended job. A durable
`tool_started` marker prevents a partially completed call/message/other phone action from
being replayed automatically after interruption; the user is asked to check it. Simple
foreground actions and Gemini Live keep their existing paths. AI tasks cannot execute
while the phone is powered off because there is no server-side worker. Android can defer
jobs in Doze, under battery restrictions or following force-stop; persisted jobs may wait
until the first device unlock after reboot. A user must allow notifications on Android 13+
for visible completion alerts.

Time-sensitive/research prompts are sent with Gemini Google Search grounding. Sources are
stored with the answer when returned. Grounding availability depends on the chosen model,
project, region, quota and network; an unsupported model reports an actionable error and a
response without grounding metadata is explicitly labelled as lacking sources.

Conversation search and dark/light/system themes are retained. Advanced → Text size changes
WebView text zoom without moving or replacing the character. Conversation & device can
copy, save via Android's document picker, or share a UTF-8 text file through a narrow,
read-only temporary content grant; there is no broad filesystem permission.

The SIM/phone-state permission is never requested automatically. Existing Phone and SMS
permissions and actions remain. If Android has not already granted phone-state access,
the app cannot enumerate/select SIM accounts and reports that honestly, without showing a
SIM access prompt. Android's default account/system chooser can still handle calls.

## CI and signing

`.github/workflows/android-build.yml` installs JDK 21, Gradle 8.11.1, SDK platform 35 and
build-tools 35.0.0. It runs `npm run check`, Gradle unit tests, builds and verifies the real
APK, and runs instrumentation against an Android emulator. A manual `workflow_dispatch`
can optionally publish the verified artifact to Releases. All four `ANDROID_SIGNING_*`
GitHub Secrets described in the repository README must be present for release signing;
otherwise a labelled debug APK is built. A changed signing certificate prevents an in-place
Android update and uninstalling the old app removes its local data.

## Local Windows build

1. Install Node 22, JDK 21, Android SDK platform 35/build-tools 35.0.0 and Gradle 8.11.1.
2. From `app-source/`, run `npm ci`, `npm run check`, `node --import tsx scripts/export-mobile-personality.ts`, and `pwsh -File scripts/ensure-build-icons.ps1`.
3. Copy `build/icon.png` to `android/app/src/main/res/drawable/vishal_jarvis_icon.png`.
4. Create `android/local-debug.keystore` with alias `androiddebugkey` and password `android`
   (the CI script demonstrates this), then run `gradle -p android :app:testDebugUnitTest :app:assembleDebug`.

The old Windows `scripts/build-android.ps1` is retained as a standalone debug build path;
it outputs `artifacts/android/Vishal-Jarvis-1.5.0-debug.apk`. The primary CI path uses Gradle.

The emulator suite uses fixture HTTPS responses and no real Google key; it covers screen-off
completion, Activity destruction and scheduler handoff, retry, grounding, text export and
side-effect replay protection. Emulator tests are not a substitute for testing on the user's
actual device, battery configuration, network and Gemini account.
