import { readFileSync } from 'node:fs';
/**
 * Regression tests for the deterministic command parser and the tool schema that
 * text chat and Live voice share. Run with `npm test`.
 */
import assert from 'node:assert/strict';
import { ACTION_ENUM, LIVE_TOOLS, MOBILE_TOOL, REMEMBER_TOOL, describeAction, parseMobileAction } from '../src/mobile/actions';

type Case = [input: string, expected: ReturnType<typeof parseMobileAction>];
const cases: Case[] = [
  ['torch on', { action: 'set_flashlight', enabled: true }],
  ['টর্চ বন্ধ করো', { action: 'set_flashlight', enabled: false }],
  ['turn off the flashlight', { action: 'set_flashlight', enabled: false }],
  ['wifi', { action: 'open_wifi_settings' }],
  ['আলার্ম 7:30', { action: 'set_alarm', hour: 7, minute: 30 }],
  ['৭টায় অ্যালার্ম দাও', { action: 'set_alarm', hour: 7, minute: 0 }],
  ['set an alarm for 9 pm', { action: 'set_alarm', hour: 21, minute: 0 }],
  ['wake me up at 6:15 am', { action: 'set_alarm', hour: 6, minute: 15 }],
  ['timer 5 minutes', { action: 'set_timer', seconds: 300 }],
  ['2 hours timer', { action: 'set_timer', seconds: 7200 }],
  ['২ ঘণ্টা টাইমার', { action: 'set_timer', seconds: 7200 }],
  ['৫ মিনিটের টাইমার', { action: 'set_timer', seconds: 300 }],
  ['volume 40', { action: 'set_volume', level: 40 }],
  ['volume down', { action: 'set_volume', direction: 'down' }],
  ['next song', { action: 'media_control', command: 'next' }],
  ['play Millionaire', { action: 'play_media', query: 'Millionaire' }],
  ['play Millionaire on Spotify', { action: 'play_media', query: 'Millionaire', app: 'spotify' }],
  ['play Triggered Insaan on YouTube', { action: 'play_media', query: 'Triggered Insaan', app: 'youtube' }],
  ['play the song Kesariya in YouTube Music', { action: 'play_media', query: 'Kesariya', app: 'youtube_music' }],
  ['Spotify e Millionaire chalao', { action: 'play_media', query: 'Millionaire', app: 'spotify' }],
  ['Triggered Insaan er video chalao', { action: 'play_media', query: 'Triggered Insaan' }],
  ['Arijit Singh er gaan chalao', { action: 'play_media', query: 'Arijit Singh' }],
  ['মিলিওনেয়ার গানটা চালাও', { action: 'play_media', query: 'মিলিওনেয়ার' }],
  ['Triggered Insaan এর ভিডিও চালাও', { action: 'play_media', query: 'Triggered Insaan' }],
  ['play music', { action: 'media_control', command: 'play_pause' }],
  ['play Triggered Insaan', { action: 'play_media', query: 'Triggered Insaan' }],
  ['Triggered Insaan er new video chalao', { action: 'play_media', query: 'Triggered Insaan' }],
  ['play Despacito in Gaana', { action: 'play_media', query: 'Despacito', app: 'gaana' }],
  ['Spotify e Millionaire chalao', { action: 'play_media', query: 'Millionaire', app: 'spotify' }],
  ['শুনতে চাই Arijit Singh', { action: 'play_media', query: 'Arijit Singh' }],
  ['play on spotify', null],
  ['play চালাও', null],
  ['play', null],
  ['torch chalao', null],
  ['open https://example.com/a?b=1', { action: 'open_url', url: 'https://example.com/a?b=1' }],
  ['call +919876543210', { action: 'call', recipient: '+919876543210' }],
  ['রাহুল কে কল করো', { action: 'call', recipient: 'রাহুল' }],
  ['লাবণ্যকে কল করো', { action: 'call', recipient: 'লাবণ্য' }],
  ['লাবন্যকে ফোন করো', { action: 'call', recipient: 'লাবন্য' }],
  ['Labanya ke call karo', { action: 'call', recipient: 'Labanya' }],
  ['Labonno call', { action: 'call', recipient: 'Labonno' }],
  ['open whatsapp', { action: 'open_app', app: 'whatsapp' }],
  ['open the youtube app', { action: 'open_app', app: 'youtube' }],
  ['open YouTube app', { action: 'open_app', app: 'youtube' }],
  ['open my calculator', { action: 'open_app', app: 'calculator' }],
  ['ইউটিউব খোলো', { action: 'open_app', app: 'youtube' }],
  ['ইনস্টাগ্রাম খোলো', { action: 'open_app', app: 'instagram' }],
  ['হোয়াটসঅ্যাপ খোলো', { action: 'open_app', app: 'whatsapp' }],
  ['টেলিগ্রাম খোলো', { action: 'open_app', app: 'telegram' }],
  ['ফেসবুক খোলো', { action: 'open_app', app: 'facebook' }],
  ['গ্যালারি খোলো', { action: 'open_app', app: 'gallery' }],
  ['সেটিংস খোলো', { action: 'open_app', app: 'settings' }],
  ['ক্যালকুলেটর ওপেন করো', { action: 'open_app', app: 'calculator' }],
  ['youtube kholo', { action: 'open_app', app: 'youtube' }],
  ['youtube open koro', { action: 'open_app', app: 'youtube' }],
  ['open youtube and play lofi', { action: 'open_app', app: 'youtube' }],
  ['open the app', null],
  ['open the app please', null],
  ['youtube search cricket highlights', { action: 'search_youtube', query: 'cricket highlights' }],
  ['message Rahul on whatsapp: কেমন আছো?', { action: 'send_message', app: 'whatsapp', recipient: 'Rahul', text: 'কেমন আছো?' }],
  ['send message Priya on whatsapp: kal dekha hobe', { action: 'send_message', app: 'whatsapp', recipient: 'Priya', text: 'kal dekha hobe' }],
  ['device status', { action: 'device_status' }],
  ['open camera', { action: 'open_camera' }],
  ['hello there', null],
  ['call', null],
  ['search the web for ', null],
  ['open', null],
  ['set a reminder', null],
];

let failed = 0;
for (const [input, expected] of cases) {
  const actual = parseMobileAction(input);
  try {
    assert.deepEqual(actual, expected);
  } catch {
    failed++;
    console.error(`FAIL parseMobileAction(${JSON.stringify(input)})\n  expected ${JSON.stringify(expected)}\n  actual   ${JSON.stringify(actual)}`);
  }
}

// The parser must never invent a device action from ordinary prose.
for (const [input, expected] of [
  ['read my notifications', 'read_notifications'], ['নোটিফিকেশন পড়ো', 'read_notifications'], ['show my reminders', 'list_reminders'],
  ['find my phone', 'ring_phone'], ['weather in Kolkata', 'get_weather'], ['good morning', 'daily_briefing'],
  ['remind me in 10 minutes to drink water', 'set_reminder'], ['open jarvis settings', 'open_jarvis_settings'],
] as const) {
  const parsed = parseMobileAction(input) as { action?: string } | null;
  if (parsed?.action !== expected) { failed++; console.error(`FAIL ${input} -> ${parsed?.action}`); }
}
for (const prose of ['আজ আবহাওয়া কেমন?', 'how are you', 'tell me a joke about my phone', 'what is 2 + 2']) {
  if (parseMobileAction(prose) !== null) {
    failed++;
    console.error(`FAIL prose became an action: ${prose}`);
  }
}

const declared = ACTION_ENUM;
// Read the Java declaration directly so the two lists can never silently drift apart.
const javaSource = readFileSync(new URL('../android/app/src/main/java/com/myraa/mobile/MobileTools.java', import.meta.url), 'utf8');
const enumBlock = javaSource.slice(javaSource.indexOf('"enum", new JSONArray()'), javaSource.indexOf('"description", "Which Android action'));
const javaMirror = [...enumBlock.matchAll(/\.put\("([a-z_]+)"\)/g)].map(m => m[1]);
if (JSON.stringify(declared) !== JSON.stringify(javaMirror)) {
  failed++;
  console.error('FAIL mobile_action enum drifted from MobileTools.java');
}
assert.equal(LIVE_TOOLS.length, 2);
assert.equal(REMEMBER_TOOL.parameters.required?.[0], 'fact');
assert.match(describeAction('set_alarm', { hour: 7, minute: 5 }), /07:05/);
assert.equal(describeAction('unknown_action', {}), 'Working on your phone');
assert.match(describeAction('delete_calendar_event', { title: 'Dentist' }), /Dentist/);
assert.match(describeAction('delete_calendar_event', { all: true }), /every upcoming event/);

console.log(`${cases.length + 4} parser cases checked, ${declared.length} actions declared, ${failed} failure(s)`);
process.exit(failed === 0 ? 0 : 1);
