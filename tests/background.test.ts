/** Static safeguards supplement on-device instrumentation of the durable job. */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const read = (path: string) => readFileSync(path, 'utf8');
const activity = read('android/app/src/main/java/com/myraa/mobile/MainActivity.java');
const actions = read('android/app/src/main/java/com/myraa/mobile/MobileActions.java');
const manifest = read('android/app/src/main/AndroidManifest.xml');
const coordinator = read('android/app/src/main/java/com/myraa/mobile/AiTaskCoordinator.java');
const ui = read('src/mobile/main.ts');
for (const source of [activity, actions]) {
  for (const match of source.matchAll(/requestPermissions\s*\(([^;]+);/gs)) {
    assert.doesNotMatch(match[1], /READ_PHONE_STATE|READ_PHONE_NUMBERS/, 'SIM access must not be requested automatically');
  }
}
assert.match(actions, /simInfo[\s\S]*?No SIM access request was made/);
assert.doesNotMatch(manifest, /android\.permission\.READ_PHONE_NUMBERS/, 'unused SIM-number permission is not declared');
for (const permission of ['CALL_PHONE', 'SEND_SMS', 'READ_CONTACTS', 'CAMERA', 'RECORD_AUDIO'])
  assert.ok(manifest.includes(`android.permission.${permission}`), `keep unrelated ${permission} permission`);
assert.match(manifest, /AiTaskJobService[\s\S]*?BIND_JOB_SERVICE/);
assert.match(manifest, /AiTaskForegroundService[\s\S]*?dataSync/);
assert.match(coordinator, /setRequiredNetworkType\(JobInfo.NETWORK_TYPE_ANY\)/);
assert.match(coordinator, /setPersisted\(true\)/);
assert.match(coordinator, /setBackoffCriteria/);
const pagehide = ui.match(/window\.addEventListener\('pagehide'[^\n]*/)?.[0] || '';
assert.doesNotMatch(pagehide, /cancel\(/, 'closing the Activity must not cancel saved tasks');
for (const feature of ['search-toggle', 'share-history', 'save-history-file', 'text-size', 'theme', 'avatar']) assert.ok(ui.includes(feature));

// Regressions fixed in 1.5.1 must not come back.
const audio = read('src/lib/audio.ts');
assert.ok(audio.includes('isAndroidApp'), 'the desktop-only permission endpoint must stay skipped inside the app');
assert.match(actions, /APP_ALIASES/, 'Bengali app names must stay mapped');
assert.match(actions, /closestApps/, 'an unknown app must still offer suggestions');
assert.match(actions, /ACTION_IMAGE_CAPTURE/, 'the camera action must keep its fallback');
assert.match(ui, /runMobileWithFollowUp/, '"open X and play Y" must keep the two-step path');
assert.ok(!ui.includes('if (!text || busy) return;'), 'phone commands must not be blocked by the busy lock');
// Calendar deletion must stay confirm-first, permission-complete and honest.
const pro = read('android/app/src/main/java/com/myraa/mobile/ProActions.java');
const calendar = read('android/app/src/main/java/com/myraa/mobile/CalendarReader.java');
assert.ok(manifest.includes('android.permission.WRITE_CALENDAR'), 'calendar write permission must be declared');
assert.match(pro, /delete_calendar_event/, 'the delete action must be exposed to the model');
assert.match(calendar, /static int deleteEvents/, 'the delete implementation must exist');
assert.match(pro, /setPositiveButton\("Delete"/, 'only the user tap may delete an event');
assert.match(pro, /Nothing was deleted/, 'a refused delete must be reported honestly');
assert.match(pro, /CALENDAR_WRITE_REQUEST/, 'the missing write grant must have its own permission result path');
console.log('Background scheduling, SIM prompt, calendar delete and existing UI/export contracts passed.');
