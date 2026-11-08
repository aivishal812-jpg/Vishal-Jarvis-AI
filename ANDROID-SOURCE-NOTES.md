# Android source notes — Vishal Jarvis 1.3.0

The Android app keeps its original native package ID and bridge/prefs/event names for
compatibility, while its visible app name and assistant identity are Vishal Jarvis.
The mobile 3D character is `src/mobile/RobotCharacter.ts`, a self-contained procedural
Three.js male android. It replaces the previously referenced external PMX model.

## What changed in 1.3.0
- `call` places the call itself (`ACTION_CALL`) and, when `TelecomManager` reports more than
  one call-capable account, returns `needs_sim` with the labels so the agent can ask which
  SIM; the retry passes `sim` and `EXTRA_PHONE_ACCOUNT_HANDLE`.
- `send_message` (WhatsApp, Telegram, Instagram, Messenger, SMS) is always prepare → confirm:
  the first call returns `needs_confirmation` with the exact text, `confirm=true` executes.
  SMS goes through `SmsManager` (per-SIM subscription when chosen) and reports the sent
  receipt; chat apps go through `MessageAutomationService`, an opt-in AccessibilityService
  restricted to those packages, which fills the composer and clicks Send, then verifies the
  composer is empty before reporting `sent`.
- `cancel_send` and `sim_info` were added; `whatsapp_message`, `send_sms`, `call_number` and
  `call_contact` were replaced by `call` and `send_message`.

## What changed in 1.2.0

**The agent can act from typed chat.** Previously only Gemini Live voice exposed a
function call. `MainActivity.runAgent` now streams `streamGenerateContent` over SSE,
parses `functionCall` parts, executes them through `MobileActions`, returns
`functionResponse` parts and loops (maximum four rounds, four calls per round). Text
deltas reach the UI while the answer is still generating.

**One tool schema.** `MobileTools.java` builds the `mobile_action` and `remember_fact`
declarations plus the system prompt (personality, local time, epoch milliseconds,
capability and honesty rules, remembered facts). `src/mobile/actions.ts` mirrors the
same declarations for Live voice, so the two paths cannot drift silently. The Live
instruction now reuses the same prompt builder instead of a second hand-written copy.

**Graceful degradation.** If the saved model rejects the tool schema with HTTP 400 on
the first round, the request is retried once without tools and the UI explains that
device actions need another model, so chat keeps working.

**Long-term memory.** Up to 30 facts of at most 240 characters are stored in the same
private SharedPreferences file. Natively enforced rules: dedupe, oldest-first eviction,
and refusal of text that looks like a password, OTP, API key or card number. Facts are
injected as user preferences, never as instructions, and the UI can add, delete or
clear them.

**More device actions.** `MobileActions` grew from 8 to 23 actions: `open_url`,
`send_sms`, `open_settings_panel`, `set_alarm`, `set_timer`, `create_event`,
`navigate_to`, `share_text`, `copy_text`, `media_control`, `set_volume`,
`device_status`, `list_apps`, `open_camera`. `open_app` also falls back to system app
categories (camera, calendar, messaging, maps, music, gallery, email, market, files)
when no labelled launcher entry matches.

**UI.** Streaming bubble with caret, tool activity strip, per-message copy/read/retry,
conversation search, transcript copy/share, quick-action chips, memory manager,
permission manager, theme and reduced-motion settings, offline banner, onboarding card.

## Security posture (unchanged principles)

- One opt-in accessibility service, scoped to WhatsApp/WhatsApp Business/Telegram/Instagram/Messenger; no MediaProjection, notification listener, shell or file APIs.
- No arbitrary intent from model output: URLs must be public http(s) with a host and no
  credentials; app names resolve through launcher activities; phone numbers and contact
  names are validated and resolved locally.
- Sending is always prepare → user says send → execute; system toggles still open their own panel.
- The API key stays in Keystore and is never exposed to the WebView.
- Provider error bodies never reach the UI or logs.

## Not verified in this environment

This source package has not been rebuilt into an APK or retested on a physical Android
device after these changes. `npm run typecheck` and the Vite production build were run
here; javac/D8/AAPT2 and the instrumentation suite require the Android SDK and a device.

## Building

The repository's GitHub workflow builds from this `app-source/` directory (not from the
distributed ZIP) so a source change is always what gets compiled.
