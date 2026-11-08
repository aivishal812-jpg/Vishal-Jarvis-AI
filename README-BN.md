# Vishal Jarvis — Android App Source (v1.4.0)

## v1.4.0 আপডেট

আগের UI, 3D রোবট, settings, voice ও ফোন-অ্যাকশন রাখা হয়েছে। পাঠানো AI প্রশ্ন ফোনে
সেভ হয়; অ্যাপ মিনিমাইজ বা স্ক্রিন বন্ধ থাকলেও Android-এর foreground service ও
persistent JobScheduler কাজ চালাতে বা নেটওয়ার্ক ফিরে এলে আবার চেষ্টা করতে পারে।
ফলাফল history-তে থাকে, notification অনুমতি থাকলে খবর দেয়। ফোন পুরোপুরি বন্ধ থাকলে
অ্যাপ কাজ করতে পারে না; কোনো server-side worker নেই। Android battery restriction,
Doze, force-stop এবং reboot-এর পরে প্রথম unlock কাজ দেরি করাতে পারে।

SIM access-এর automatic permission prompt সরানো হয়েছে। আগে থেকে phone-state access
না থাকলে নির্দিষ্ট SIM-এর তালিকা/নির্বাচন নাও পাওয়া যেতে পারে; ফোনের default account
বা system chooser ব্যবহার করা যায়। Phone ও SMS-এর আলাদা অনুমতি আগের মতোই আছে।
Settings-এ text size এবং `.txt` conversation export/share যোগ হয়েছে। বর্তমান build ও
নিরাপত্তার নির্দেশনা `android/README.md`-এ রয়েছে।

---

এই ZIP-এ Android-এ চালানোর জন্য Vishal Jarvis-এর সম্পূর্ণ source code রয়েছে। আগের v1.3.0-এ এজেন্ট এখন কাজটা **নিজেই শেষ করে**: নিজে ফোন করে (একাধিক SIM থাকলে জিজ্ঞেস করে), আর WhatsApp/Telegram/Instagram/Messenger/SMS-এ মেসেজ নিজে পাঠিয়ে দেয়।

## v1.3.0-এ নতুন কী

### এজেন্ট নিজে কল করে
- "রাহুল কে কল করো" বা "call +91…" বললেই এজেন্ট **নিজে কল শুরু করে** — শুধু dialer খোলে না।
- **ডুয়াল SIM:** ফোনে একাধিক SIM থাকলে এজেন্ট জিজ্ঞেস করে — "SIM 1 Airtel, SIM 2 Jio — কোনটা?" — তারপর সেই SIM দিয়ে কল করে। অনুমান করে না।
- দরকার: **Phone** permission (SIM তালিকার জন্য phone state access)।
- ফলাফলে `call_started` মানে Android ডায়াল শুরু করেছে; ওপাশে ফোন ধরেছে এমন দাবি করে না।

### এজেন্ট নিজে মেসেজ পাঠায়
- WhatsApp, **Telegram, Instagram, Messenger এবং SMS** — এজেন্ট নির্দিষ্ট chat খোলে, লেখাটা বসায়, এবং **Send চেপে দেয়**।
- দুই ধাপে: আগে এজেন্ট প্রাপক ও হুবহু লেখাটা বলে, আপনি "send" বললে তবেই পাঠায়; "cancel" বললে বাতিল।
- chat অ্যাপে সরাসরি পাঠাতে একটি **Accessibility service** ("Vishal Jarvis message sender") লাগে, যা আপনি নিজে Android Settings → Accessibility থেকে চালু করবেন। এটা শুধু WhatsApp, WhatsApp Business, Telegram, Instagram ও Messenger-এ সীমিত — বাকি কিছু পড়তে পারে না।
- ফলাফল সৎভাবে জানায়: `sent` (Send চাপার পর composer খালি হয়েছে), `sent_unverified` (Send চাপা হয়েছে কিন্তু নিশ্চিত করা যায়নি), `sms_delivered_to_network` (Android SMS নিয়েছে), `failed`, `permission_required`।

## v1.2.0-এ যা এসেছিল

### এজেন্ট এখন লেখা চ্যাটেও কাজ করে
- আগে শুধু Live voice-এ tool call ছিল; এখন **text chat-ও** Gemini function calling দিয়ে ফোনের কাজ করে (open app, search, message draft, alarm, timer, volume, ইত্যাদি)।
- উত্তর **stream** হয়ে আসে — প্রথম শব্দ থেকেই দেখা যায়, পুরো উত্তরের জন্য অপেক্ষা করতে হয় না।
- প্রতিটি ধাপ UI-তে দেখায়: "Opening WhatsApp", "Setting an alarm for 07:30" ইত্যাদি, সাথে সফল/ব্যর্থের সত্যি ফলাফল।
- একসাথে কয়েকটি কাজ চেইন করা যায় (সর্বোচ্চ ৪ ধাপ), এবং প্রতি ধাপে আসল ফলাফল না পেলে এজেন্ট সফলতার দাবি করে না।

### নতুন Android অ্যাকশন
| অ্যাকশন | কী করে | নিরাপত্তা |
| --- | --- | --- |
| `open_app` | ইনস্টল করা যেকোনো অ্যাপের নাম ধরে খোলে | শুধু লঞ্চার activity |
| `open_url` | https লিংক ব্রাউজারে খোলে | শুধু http(s), credential/localhost ব্লক |
| `search_youtube` / `search_web` | YouTube বা ব্রাউজার search | query encode করা |
| `call` | **নিজে কল করে** (নাম local contacts থেকে, একাধিক SIM থাকলে জিজ্ঞেস করে) | Phone permission; `call_started` ছাড়া কিছু দাবি করে না |
| `send_message` | WhatsApp/Telegram/Instagram/Messenger/SMS-এ **নিজে পাঠায়** | আগে প্রস্তুত → "send" বললে তবেই; accessibility শুধু ওই ৫টি প্যাকেজে |
| `cancel_send` / `sim_info` | প্রস্তুত মেসেজ বাতিল / ফোনের SIM তালিকা | কিছুই পাঠায় না |
| `set_flashlight` | টর্চ on/off | Camera permission |
| `open_wifi_settings` / `open_settings_panel` | Wi-Fi, Bluetooth, sound, display, battery, location, apps, notifications, data, airplane, accessibility, NFC, home | Android-এর নিয়মে সুইচ ইউজার নিজে চাপে |
| `set_alarm` / `set_timer` | clock app-এর editor খোলে | ইউজার confirm করে |
| `create_event` | calendar editor খোলে | কিছুই নিজে সেভ করে না |
| `navigate_to` | Maps-এ গন্তব্য খোলে | navigation ইউজার শুরু করে |
| `share_text` / `copy_text` | share sheet বা clipboard | ইউজার অ্যাপ বেছে নেয় |
| `media_control` / `set_volume` | play/pause/next, media ভলিউম | শুধু media stream |
| `device_status` | ব্যাটারি, নেটওয়ার্ক, storage, ভলিউম, মডেল | শুধু পড়ে |
| `list_apps` | ইনস্টল করা অ্যাপের নাম | শুধু নিজের ডিভাইসে |
| `open_camera` | ক্যামেরা খোলে | ছবি নিজে তোলে না |

### দীর্ঘমেয়াদি memory
- "মনে রাখো আমি বাংলায় উত্তর চাই" বললে এজেন্ট `remember_fact` tool দিয়ে ছোট fact সেভ করে (সর্বোচ্চ ৩০টি, ২৪০ অক্ষর)।
- Settings → Memory-তে প্রতিটি fact দেখা, মুছে ফেলা বা হাতে যোগ করা যায়।
- পাসওয়ার্ড, OTP, API key, কার্ড নম্বর ইত্যাদি সেভ করতে নেটিভ স্তরেই refusal হয়।

### UX উন্নতি
- Quick-action chips: Torch, Wi-Fi, WhatsApp, Battery, Alarm, Camera, Search
- প্রতিটি উত্তরে **Copy / Read / Retry** বাটন; মেসেজে সময়
- কথোপকথনে **search**, সম্পূর্ণ history toggle, transcript **copy/share**
- **Permission manager**: কোন permission আছে, এক ট্যাপে চাওয়া
- **Theme** (Dark/Light/System) এবং **Reduce motion**
- Offline banner, haptic feedback, বড় composer, ছোট স্ক্রিনের জন্য আলাদা layout
- Key না থাকলে onboardিং কার্ড, সম্ভাব্য কাজের suggestion

### ভেতরের উন্নতি
- Native `MobileTools.java` — text chat ও Live voice একই tool schema ব্যবহার করে
- Streaming SSE parser, tool loop, cancellation, timeout এবং duplicate-action protection
- History ৪০ turn context-এ সীমিত, reply এসে history সেভ না হলে স্পষ্ট error
- `npm run typecheck` যোগ করা হয়েছে; Vite build আগের মতোই

## আগের ফিচার যা অপরিবর্তিত আছে
Gemini text chat, Gemini Live voice, Android TTS, encrypted API key (Android Keystore), conversation history, settings, floating voice controls, notification controls, background Live call, contact lookup, torch, Wi-Fi panel, ১৩টি system panel, alarm/timer/calendar/Maps, share, clipboard, media keys, volume, device status, app list, camera।

## APK বানানো

দরকার: Node.js 22, JDK 21, Android SDK Platform 35, Build Tools 35.0.0, PowerShell 7 (Windows)।

```powershell
npm install
npm run check          # TypeScript typecheck + Vite asset build
pwsh -NoProfile -File scripts/build-android.ps1 -SdkRoot 'C:\path\to\android-sdk' -JavaRoot 'C:\path\to\jdk-21'
```

APK: `artifacts/android/Vishal-Jarvis-1.4.0-debug.apk`

ইনস্টল:

```powershell
adb install -r artifacts/android/Vishal-Jarvis-1.4.0-debug.apk
```

## ফোনে চালানোর শর্ত
- Android 8.0 / API 26 বা নতুন, Android System WebView 100+, OpenGL ES 3
- internet + নিজের Gemini API key (Settings-এ দিতে হবে; কোডে নেই)
- প্রয়োজন অনুযায়ী microphone, contacts, camera, notification এবং floating-control permission

## সীমাবদ্ধতা (সৎভাবে)
এই ZIP source package, APK নয়। GitHub Actions-এ APK build ও Android emulator পরীক্ষা করা হয়; বাস্তব ফোনে startup, 3D character, voice, permission, network এবং battery আচরণ আলাদা করে যাচাই করা জরুরি।

এজেন্ট এখনো এগুলো পারে না (ইচ্ছাকৃতভাবে): notification পড়া, screenshot/screen capture, অন্য অ্যাপের UI চালানো, ফাইল পড়া/লেখা, নীরবে message পাঠানো বা কল করা, Wi-Fi/Bluetooth নীরবে বন্ধ-চালু করা। Android package ID `com.myraa.mobile` আগের compatibility-র জন্য অপরিবর্তিত।
