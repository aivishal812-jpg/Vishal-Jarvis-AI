#!/usr/bin/env node
/**
 * Automatic versionCode for release builds.
 *
 * Android only installs an APK over an existing app when versionCode is larger, so every
 * CI build must get a strictly increasing code. The run number is used when available
 * (1000 + run, leaving 1..999 for local/older builds); otherwise the current code is
 * bumped by one. run: node scripts/bump-version.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';

const manifestPath = 'android/app/src/main/AndroidManifest.xml';
const gradlePath = 'android/app/build.gradle';
const manifest = readFileSync(manifestPath, 'utf8');
const current = Number((/android:versionCode="(\d+)"/.exec(manifest) || [])[1] || 1);

const runNumber = Number(process.env.GITHUB_RUN_NUMBER || 0);
const next = runNumber > 0 ? 1000 + runNumber : current + 1;
if (next <= current) {
  console.log(`versionCode stays ${current} (run number ${runNumber} would not increase it).`);
  process.exit(0);
}

writeFileSync(manifestPath, manifest.replace(/android:versionCode="\d+"/, `android:versionCode="${next}"`));
try {
  const gradle = readFileSync(gradlePath, 'utf8');
  if (/versionCode\s+\d+/.test(gradle)) writeFileSync(gradlePath, gradle.replace(/versionCode\s+\d+/, `versionCode ${next}`));
} catch { /* Gradle file is optional for the script build */ }
console.log(`versionCode ${current} -> ${next}`);
