#!/usr/bin/env bash
# Linux/macOS/CI build of the Vishal Jarvis APK without Gradle.
# Needs: ANDROID_HOME (platform 35 + build-tools 35.0.0), JAVA_HOME (JDK 17+), Node 20+.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK with platform 35 and build-tools 35.0.0}"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
BT="$ANDROID_HOME/build-tools/35.0.0"
JAR="$ANDROID_HOME/platforms/android-35/android.jar"
for f in "$JAR" "$BT/aapt2" "$BT/d8" "$BT/apksigner" "$BT/zipalign"; do [ -e "$f" ] || { echo "Missing $f" >&2; exit 1; }; done

MANIFEST="android/app/src/main/AndroidManifest.xml"
VERSION="$(sed -n 's/.*android:versionName="\([0-9.]*\)".*/\1/p' "$MANIFEST" | head -1)"
VERSION="${VERSION:-0.0.0}"
VERSION_CODE="$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$MANIFEST" | head -1)"
VERSION_CODE="${VERSION_CODE:-1}"

if [ "${SKIP_WEB:-0}" != "1" ]; then
  [ -d node_modules ] || npm ci --no-audit --no-fund
  npx vite build --config vite.mobile.config.ts
  npx tsx scripts/export-mobile-personality.ts
fi
ASSETS="android/app/src/main/assets"
[ -f "$ASSETS/mobile.html" ] || { echo "Web assets missing in $ASSETS" >&2; exit 1; }

OUT="android/app/build/local"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

# 1. Resources first, so R.java exists for the widget and other resource users.
"$BT/aapt2" compile --dir android/app/src/main/res -o "$OUT/res.zip"
sed 's/<manifest /<manifest package="com.myraa.mobile" /' "$MANIFEST" > "$OUT/AndroidManifest.xml"
"$BT/aapt2" link --manifest "$OUT/AndroidManifest.xml" -I "$JAR" -A "$ASSETS" --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 35 --version-name "$VERSION" --version-code "$VERSION_CODE" \
  -o "$OUT/unsigned.apk" "$OUT/res.zip"

# 2. Java -> dex.
{ find android/app/src/main/java -name '*.java'; find "$OUT/gen" -name '*.java'; } > "$OUT/sources.txt"
"${JAVA_BIN}javac" -encoding UTF-8 --release 8 -nowarn -Xlint:-options -classpath "$JAR" -d "$OUT/classes" @"$OUT/sources.txt"
(cd "$OUT/classes" && "${JAVA_BIN}jar" --create --file ../classes.jar .)
"$BT/d8" --lib "$JAR" --min-api 26 --release --output "$OUT/dex" "$OUT/classes.jar"
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes*.dex)

# 3. Align and sign.
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KS="${ANDROID_KEYSTORE:-android/local-debug.keystore}"
KS_PASS="${ANDROID_KEYSTORE_PASSWORD:-android}"
KS_ALIAS="${ANDROID_KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${ANDROID_KEY_PASSWORD:-$KS_PASS}"
if [ ! -f "$KS" ]; then
  "${JAVA_BIN}keytool" -genkeypair -keystore "$KS" -storepass "$KS_PASS" -alias "$KS_ALIAS" -keypass "$KEY_PASS" \
    -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Vishal Jarvis,O=Vishal,C=IN' >/dev/null 2>&1
fi
mkdir -p artifacts/android
APK="artifacts/android/Vishal-Jarvis-$VERSION.apk"
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify "$APK"
"$BT/aapt2" dump badging "$APK" | head -3
sha256sum "$APK"
echo "APK ready: $APK"
