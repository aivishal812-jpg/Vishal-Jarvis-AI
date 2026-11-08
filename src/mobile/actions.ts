export type MobileAction =
  | { action: 'open_app'; app: string }
  | { action: 'open_url'; url: string }
  | { action: 'play_media'; app?: string; query: string }
  | { action: 'search_youtube' | 'search_web'; query: string }
  | { action: 'call'; recipient: string; sim?: string }
  | { action: 'send_message'; app: 'whatsapp' | 'telegram' | 'instagram' | 'messenger' | 'sms' | string; recipient: string; text: string; sim?: string; confirm?: boolean }
  | { action: 'cancel_send' }
  | { action: 'sim_info' }
  | { action: 'set_flashlight'; enabled: boolean }
  | { action: 'open_wifi_settings' }
  | { action: 'open_settings_panel'; panel: string }
  | { action: 'set_alarm'; hour: number; minute: number; label?: string }
  | { action: 'set_timer'; seconds: number; label?: string }
  | { action: 'navigate_to'; destination: string }
  | { action: 'media_control'; command: 'play_pause' | 'next' | 'previous' }
  | { action: 'set_volume'; level?: number; direction?: 'up' | 'down' }
  | { action: 'device_status' }
  | { action: 'open_camera' }
  | { action: 'set_reminder'; text: string; atTime?: number; inMinutes?: number; repeat?: 'none' | 'daily' | 'weekly' }
  | { action: 'list_reminders' | 'list_notes' | 'clear_notifications' | 'ring_phone' | 'open_jarvis_settings' }
  | { action: 'cancel_reminder'; reminderId?: string; text?: string }
  | { action: 'save_note'; text: string }
  | { action: 'delete_note'; noteId: string }
  | { action: 'read_notifications'; limit?: number; app?: string }
  | { action: 'read_calendar'; days?: number }
  | { action: 'get_weather' | 'daily_briefing'; city?: string };

const ACTION_ENUM = ['open_app', 'open_url', 'play_media', 'search_youtube', 'search_web', 'call', 'send_message',
  'cancel_send', 'sim_info', 'set_flashlight', 'open_wifi_settings', 'open_settings_panel', 'set_alarm',
  'set_timer', 'create_event', 'navigate_to', 'share_text', 'copy_text', 'media_control', 'set_volume',
  'device_status', 'list_apps', 'open_camera', 'set_reminder', 'list_reminders', 'cancel_reminder', 'save_note',
  'list_notes', 'delete_note', 'read_notifications', 'clear_notifications', 'read_calendar', 'get_weather', 'daily_briefing',
  'ring_phone', 'open_jarvis_settings'];

/** Mirrors MobileTools.java so text chat and Live voice share one Android capability set. */
export const MOBILE_TOOL = {
  name: 'mobile_action',
  description: 'Work the user\'s Android phone through supported actions: open installed apps, open an https page, play a song, album, artist, video or channel directly in a music app (YouTube, YouTube Music, Spotify, Prime Music, Gaana, JioSaavn), search YouTube or the web, place a phone call directly (choosing the SIM when the phone has several), send a message directly in WhatsApp, Telegram, Instagram, Messenger or SMS, reveal the phone\'s SIMs, toggle the flashlight, open Wi-Fi and system panels, set alarms and timers, open a calendar event editor, open Maps navigation, share text, copy to the clipboard, send media keys, change media volume, read device status, list installed apps, open the camera, set/list/cancel reminders that pop up even when the app is closed, save/list/delete quick notes, read the user\'s recent notifications (and clear them), read upcoming calendar events, get the weather for a city, give a daily briefing, ring the phone loudly to find it, and open Jarvis Pro settings. Contacts are resolved locally on the device. Sending is always two steps: prepare, then confirm with confirm=true. Never invent success: report the returned status truthfully.',
  parameters: { type: 'OBJECT', properties: {
    action: { type: 'STRING', enum: ACTION_ENUM, description: 'Which Android action to perform.' },
    app: { type: 'STRING', description: 'For send_message: whatsapp, telegram, instagram, messenger or sms. For open_app: the app name as shown on the home screen, such as YouTube, Chrome, Calculator. For play_media: youtube, youtube_music, spotify, prime_music, gaana or jiosaavn when the user named a player; omit it otherwise so Android picks the best installed one.' },
    url: { type: 'STRING', description: 'A complete https:// page address explicitly given or implied by the user. Only for open_url.' },
    query: { type: 'STRING', description: 'For play_media: the song, album, artist, video or channel exactly as the user said it, for example “Millionaire” or “Triggered Insaan”. For search_youtube and search_web: the exact search text asked for.' },
    recipient: { type: 'STRING', description: 'Whom to call or message: the name exactly as spoken in Bengali script or Latin letters, including nicknames (for example লাবণ্য, Labanya, Labonno). Do not translate, respell or invent a number. The phone matches contacts phonetically and never auto-dials an ambiguous name. Or a full phone number with country code, or the exact @username for Telegram, Instagram or Messenger. Used by call and send_message.' },
    sim: { type: 'STRING', description: 'Which SIM to use, "1" or "2", taken from the user\'s answer or from sim_info. Only send it after the user chose. Used by call and by send_message with app sms.' },
    confirm: { type: 'BOOLEAN', description: 'false or omitted prepares the message and reports what would be sent; true actually presses Send. Only for send_message, and only after the user said send.' },
    enabled: { type: 'BOOLEAN', description: 'true turns the flashlight on, false turns it off. Only for set_flashlight.' },
    panel: { type: 'STRING', enum: ['wifi', 'bluetooth', 'sound', 'display', 'battery', 'location', 'apps', 'notifications', 'data', 'airplane', 'accessibility', 'nfc', 'home'], description: 'Which system settings panel to open. Only for open_settings_panel.' },
    hour: { type: 'INTEGER', description: 'Alarm hour on a 24-hour clock, 0-23. Only for set_alarm.' },
    minute: { type: 'INTEGER', description: 'Alarm minute, 0-59. Only for set_alarm.' },
    seconds: { type: 'INTEGER', description: 'Timer length in seconds, 1-86400. Only for set_timer.' },
    label: { type: 'STRING', description: 'Short label for an alarm or timer.' },
    title: { type: 'STRING', description: 'Calendar event title. Only for create_event.' },
    beginTime: { type: 'NUMBER', description: 'Event start as Unix epoch milliseconds in the device time zone. Only for create_event.' },
    endTime: { type: 'NUMBER', description: 'Event end as Unix epoch milliseconds. Defaults to one hour after the start. Only for create_event.' },
    location: { type: 'STRING', description: 'Optional place for a calendar event.' },
    description: { type: 'STRING', description: 'Optional notes for a calendar event.' },
    destination: { type: 'STRING', description: 'Place or address to show in Maps. Only for navigate_to.' },
    text: { type: 'STRING', description: 'The exact text: the message body for send_message (never shorten or rewrite it), the text to share or copy, what to remind about for set_reminder, the note body for save_note, or words from a reminder to cancel.' },
    atTime: { type: 'NUMBER', description: 'For set_reminder: when it should fire, as Unix epoch milliseconds computed from the local time. Use either atTime or inMinutes.' },
    inMinutes: { type: 'INTEGER', description: 'For set_reminder: fire this many minutes from now (1-525600).' },
    repeat: { type: 'STRING', enum: ['none', 'daily', 'weekly'], description: 'For set_reminder: repeat schedule. Default none.' },
    reminderId: { type: 'STRING', description: 'For cancel_reminder: the id from list_reminders.' },
    noteId: { type: 'STRING', description: 'For delete_note: the id from list_notes.' },
    days: { type: 'INTEGER', description: 'For read_calendar: how many days ahead to read, 1-31. Default 1 (today).' },
    city: { type: 'STRING', description: 'For get_weather and daily_briefing: the city name. Omit to use the user\'s saved home city.' },
    limit: { type: 'INTEGER', description: 'For read_notifications: how many to read, 1-20. Default 8.' },
    command: { type: 'STRING', enum: ['play_pause', 'next', 'previous'], description: 'Media key to send. Only for media_control.' },
    level: { type: 'INTEGER', description: 'Target media volume as a percentage, 0-100. Only for set_volume.' },
    direction: { type: 'STRING', enum: ['up', 'down'], description: 'Raise or lower media volume by one step. Only for set_volume when level is absent.' },
  }, required: ['action'] },
};

export const REMEMBER_TOOL = {
  name: 'remember_fact',
  description: 'Save one durable fact or preference about the user so future conversations can use it, for example their name, city, favourite language, or a standing routine. Only call this when the user asks you to remember something or states a lasting preference. Never store API keys, passwords, OTPs, card numbers or other secrets. Keep the fact under 240 characters, in the user\'s own words.',
  parameters: { type: 'OBJECT', properties: {
    fact: { type: 'STRING', description: 'Short, self-contained fact to remember. One sentence.' },
  }, required: ['fact'] },
};

export const LIVE_TOOLS = [MOBILE_TOOL, REMEMBER_TOOL];

/** Bengali-script app names the user can say; mapped to the name Android resolves. */
const APP_ALIASES: Record<string, string> = {
  'ইউটিউব': 'youtube', 'ইউটিউব অ্যাপ': 'youtube', 'ইউ টিউব': 'youtube', 'यूट्यूब': 'youtube',
  'হোয়াটসঅ্যাপ': 'whatsapp', 'হোয়াটস এপ': 'whatsapp', 'व्हाट्सएप': 'whatsapp', 'व्हाट्सऐप': 'whatsapp',
  'ইনস্টাগ্রাম': 'instagram', 'इंस्टाग्राम': 'instagram',
  'ফেসবুক': 'facebook', 'फेसबुक': 'facebook',
  'টেলিগ্রাম': 'telegram', 'টেলিগ্ৰাম': 'telegram', 'टेलीग्राम': 'telegram',
  'মেসেঞ্জার': 'messenger', 'मैसेंजर': 'messenger',
  'ক্যালকুলেটর': 'calculator', 'ক্যালকুলেটার': 'calculator', 'कैलकुलेटर': 'calculator',
  'ক্যামেরা': 'camera', 'कैमरा': 'camera',
  'গ্যালারি': 'gallery', 'গ্যালারি অ্যাপ': 'gallery', 'गैलरी': 'gallery',
  'সেটিংস': 'settings', 'सेटिंग्स': 'settings',
  'প্লে স্টোর': 'play store', 'গুগল প্লে স্টোর': 'play store', 'प्ले स्टोर': 'play store',
  'জিমেইল': 'gmail', 'ग्मेल': 'gmail',
  'ফোন': 'phone', 'फोन': 'phone',
  'কন্ট্যাক্টস': 'contacts', 'কনট্যাক্ট': 'contacts', 'संपर्क': 'contacts',
  'ম্যাপস': 'maps', 'ম্যাপ': 'maps', 'मैप्स': 'maps',
  'ক্লক': 'clock', 'ঘড়ি': 'clock', 'घड़ी': 'clock',
  'ফাইলস': 'files', 'ফাইল': 'files', 'फाइल': 'files',
  'স্পটিফাই': 'spotify', 'गाना': 'gaana', 'গানা': 'gaana', 'जियोसावन': 'jiosaavn',
  'ক্রোম': 'chrome', 'গুগল ক্রোম': 'chrome',
};
/** "the YouTube app" → "youtube" so Android can match the launcher label. */
function normalizeAppName(value: string): string {
  let name = value.trim().replace(/^["'“”]|["'“”]$/g, '').replace(/\s+/g, ' ').trim();
  const direct = APP_ALIASES[name.toLowerCase()];
  if (direct) return direct;
  // "the YouTube app, please" → "YouTube" before the alias lookup, so Android sees "youtube".
  name = name.replace(/\s+(?:please|now|for me)$/i, '')
    .replace(/^(?:the|a|an|my|মার|আমার)\s+/iu, '')
    .replace(/\s+(?:app|application|অ্যাপ|एप)$/iu, '')
    .replace(/\s+/g, ' ')
    .trim();
  const alias = APP_ALIASES[name.toLowerCase()];
  if (alias) return alias;
  // Plain English names are compared lower-case, exactly like Android's launcher labels.
  return /^[\x20-\x7e]+$/.test(name) ? name.toLowerCase() : name;
}

/** Which music app the user named, if any. */
const PLAY_APPS: Record<string, string> = {
  'youtube music': 'youtube_music', 'yt music': 'youtube_music',
  'youtube': 'youtube', 'you tube': 'youtube',
  'spotify': 'spotify', 'স্পটিফাই': 'spotify', 'स्पॉटिफ़ाई': 'spotify',
  'prime music': 'prime_music', 'amazon prime music': 'prime_music', 'amazon music': 'prime_music',
  'gaana': 'gaana', 'গানা': 'gaana', 'गाना': 'gaana',
  'jiosaavn': 'jiosaavn', 'jio saavn': 'jiosaavn', 'saavn': 'jiosaavn',
};
const APP_NAMES = Object.keys(PLAY_APPS).sort((a, b) => b.length - a.length).map(name => name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|');
const PLAY_VERBS_ONLY = /^(?:play|chalao|chala\s*do|bajao|play\s*koro|চালাও|বাজাও|শোনাও|দেখাও|চালিয়ে\s*দাও|চালু\s*করো)$/iu;
const NOT_MEDIA = /^(?:on|in|from|via|for|a|an|the|some|my|me|গান|ভিডিও|সঙ্গীত|মিউজিক|music|song|songs|video|something|anything|it|that|this|এটা|ওটা|চালাও|বাজাও|শোনাও|দেখাও|chalao|bajao|play\s*koro)$/iu;
const DEVICE_THING = /(flashlight|torch|ফ্ল্যাশ\s*লাইট|ফ্ল্যাশলাইট|টর্চ|wifi|wi-?fi|ওয়াই.?ফাই|bluetooth|ব্লুটুথ|camera|ক্যামেরা|settings|সেটিংস|alarm|অ্যালার্ম|আলার্ম|timer|টাইমার|volume|ভলিউম|airplane|ডাটা)/iu;
const MEDIA_NOUN = /(?:\s*(?:-?(?:এর|ের)|er))?\s*(?:গান|ভিডিও|সঙ্গীত|video|song|music|gaan)?\s*(?:টি|টা|ta|ti)?$/iu;
const PLAY_VERB = /^(.*?)\s*(?:টি|টা)?\s*(?:চালাও|চালিয়ে\s*দাও|চালু\s*করো|বাজাও|শোনাও|দেখাও|শুনতে\s*চাই|দেখতে\s*চাই|play\s*koro|play\s*kar(?:o|do)?|chalao|chala\s*do|bajao|baja\s*do)$/iu;

function usablePlayQuery(value: string): string {
  let query = value.trim();
  if (PLAY_VERBS_ONLY.test(query)) return '';
  for (let pass = 0; pass < 6; pass++) {
    const before = query;
    for (const pattern of [MEDIA_NOUN, /^(?:the\s+)?(?:song|track|video|music|গান|ভিডিও|সঙ্গীত)\s+/iu,
      /\s+(?:-?(?:এর|ের)|er|ke)\s+(?:new|latest|নতুন|old|best)?\s*$/iu, /\s+(?:new|latest|নতুন)$/iu]) {
      query = query.replace(pattern, '').trim();
    }
    if (query === before) break;
  }
  return NOT_MEDIA.test(query) || DEVICE_THING.test(query) ? '' : query;
}

/** “play Millionaire on Spotify”, “Triggered Insaan er video chalao”, “Spotify e Millionaire chalao”… */
export function parsePlayMedia(input: string): MobileAction | null {
  const text = input.trim().replace(/[.!?।]+$/u, '').trim();
  const english = /^(?:please\s+)?(?:play|stream)\s+(.+)$/i.exec(text);
  if (english) {
    let rest = english[1].trim();
    let app: string | undefined;
    const named = new RegExp(`^(?:the\\s+)?(?:song|track|video|music|channel|album|playlist)?\\s*["“']?(.+?)["”']?(?:\\s+(?:on|in|from|via)\\s+(${APP_NAMES}))?$`, 'iu').exec(rest);
    if (named) {
      rest = named[1].trim();
      if (named[2]) app = PLAY_APPS[named[2].trim().toLowerCase()];
    }
    const query = usablePlayQuery(rest);
    if (query && !/^(?:on|in|from|via)\s/iu.test(query)) return { action: 'play_media', query, ...(app ? { app } : {}) };
  }
  const wish = /^(?:আমি\s+)?(?:শুনতে|দেখতে)\s*চাই\s+(.+)$/u.exec(text);
  if (wish) {
    const query = usablePlayQuery(wish[1]);
    if (query) return { action: 'play_media', query };
  }
  const verb = PLAY_VERB.exec(text);
  if (verb) {
    let rest = verb[1].trim();
    let app: string | undefined;
    const tail = new RegExp(`^(.*?)\\s+(?:on|in|from)\\s+(${APP_NAMES})$`, 'iu').exec(rest);
    if (tail) { rest = tail[1].trim(); app = PLAY_APPS[tail[2].trim().toLowerCase()]; }
    const prefix = new RegExp(`^(${APP_NAMES})\\s*(?:-?এ|ে|তে|te|e|on|in)?\\s+(.+)$`, 'iu').exec(rest);
    if (prefix) { rest = prefix[2].trim(); app = PLAY_APPS[prefix[1].trim().toLowerCase()]; }
    const around = new RegExp(`^(.+?)\\s+(?:(${APP_NAMES})\\s*(?:-?এ|ে|তে|te|e|on|in))$`, 'iu').exec(rest);
    if (around) { rest = around[1].trim(); app = PLAY_APPS[around[2].trim().toLowerCase()]; }
    const query = usablePlayQuery(rest);
    if (query) return { action: 'play_media', query, ...(app ? { app } : {}) };
  }
  return null;
}

/** Exact local typed commands; Live voice and text chat use structured Gemini function calls. */
export function parseMobileAction(input: string): MobileAction | null {
  const digits = (value: string) => value.replace(/[০-৯]/g, d => String('০১২৩৪৫৬৭৮৯'.indexOf(d)));
  const text = digits(input.trim()).replace(/^(?:hey\s+)?(?:vishal\s+jarvis|vishal|roisal\s+jarvis|roisal|jarvis|myraa|myra|মায়া|মায়া)[,\s:]+/iu, '').replace(/^(?:please|can you|could you)\s+/i, '').trim();
  let call = /^(?:কল\s*(?:করো|কর|দাও)?|ফোন\s*করো|call(?:\s+to)?|dial)\s*[:,ঃ-]?\s*(?:এই\s+নম্বর(?:ে)?\s*)?([+0-9][0-9 ()-]{6,22})$/iu.exec(text) || /^([+0-9][0-9 ()-]{6,22})\s*(?:নম্বরে\s*)?(?:কল\s*করো|ফোন\s*করো)$/iu.exec(text);
  if (call) return { action: 'call', recipient: call[1].trim() };
  if (/^(?:(?:ওয়াই|ওয়াই).?ফাই|wifi|wi-fi|ইন্টারনেট)(?:\s*(?:অন|চালু|on|enable))?\s*(?:করো|চালু করো|খোলো|open)?$/iu.test(text) || /^(?:(?:ওয়াই|ওয়াই).?ফাই|wifi|wi-fi)\s*(?:সেটিংস|settings)\s*(?:খোলো|open)?$/iu.test(text) || /^(?:turn\s+on\s+)?wi-?fi\s+(?:on|enable)$/i.test(text)) return { action: 'open_wifi_settings' };
  if (/^(?:ফ্ল্যাশ\s*লাইট|ফ্ল্যাশলাইট|টর্চ|torch|flashlight)\s*(?:অন|চালু|on|ওপেন|খোলো|open)(?:\s*করো)?$/iu.test(text) || /^(?:অন|চালু|ওপেন|open)\s*(?:করো\s*)?(?:ফ্ল্যাশ\s*লাইট|ফ্ল্যাশলাইট|টর্চ|torch|flashlight)$/iu.test(text) || /^turn\s+on\s+(?:the\s+)?flashlight$/i.test(text)) return { action: 'set_flashlight', enabled: true };
  if (/^(?:ফ্ল্যাশ\s*লাইট|ফ্ল্যাশলাইট|টর্চ|torch|flashlight)\s*(?:অফ|বন্ধ|off)(?:\s*করো)?$/iu.test(text) || /^(?:বন্ধ|অফ)\s*(?:করো\s*)?(?:ফ্ল্যাশ\s*লাইট|ফ্ল্যাশলাইট|টর্চ|torch|flashlight)$/iu.test(text) || /^turn\s+off\s+(?:the\s+)?flashlight$/i.test(text)) return { action: 'set_flashlight', enabled: false };
  if (/^(?:read|show|check)\s+(?:my\s+)?notifications?$/i.test(text) || /^(?:নোটিফিকেশন|নোটিফিকেশনগুলো)\s*(?:পড়ো|পড়ে শোনাও|দেখাও)$/u.test(text)) return { action: 'read_notifications' };
  if (/^(?:show|list)\s+(?:my\s+)?reminders?$/i.test(text) || /^(?:রিমাইন্ডার|রিমাইন্ডারগুলো)\s*(?:দেখাও|বলো)$/u.test(text)) return { action: 'list_reminders' };
  if (/^(?:show|list)\s+(?:my\s+)?notes?$/i.test(text) || /^(?:নোট|নোটগুলো)\s*(?:দেখাও|বলো)$/u.test(text)) return { action: 'list_notes' };
  if (/^(?:find|ring)\s+my\s+phone$/i.test(text) || /^(?:আমার\s+)?ফোন\s*(?:খুঁজে দাও|বাজাও)$/u.test(text)) return { action: 'ring_phone' };
  if (/^(?:what'?s\s+)?(?:the\s+)?weather(?:\s+today)?$/i.test(text) || /^(?:আজকের\s+)?আবহাওয়া\s*(?:কেমন|বলো)?$/u.test(text)) return { action: 'get_weather' };
  const weatherIn = /^(?:what'?s\s+the\s+)?weather\s+(?:in|at)\s+([\p{L} .'-]{2,60})$/iu.exec(text);
  if (weatherIn) return { action: 'get_weather', city: weatherIn[1].trim() };
  if (/^(?:good\s+morning|daily\s+briefing|brief\s+me|what'?s\s+my\s+day)$/i.test(text) || /^(?:সুপ্রভাত|গুড মর্নিং|আজকের ব্রিফিং)$/u.test(text)) return { action: 'daily_briefing' };
  if (/^(?:open\s+)?jarvis\s+(?:pro\s+)?settings$/i.test(text)) return { action: 'open_jarvis_settings' };
  const remindIn = /^remind\s+me\s+in\s+(\d{1,4})\s*(m|min|mins|minute|minutes|h|hr|hour|hours)\s+(?:to\s+)?(.{1,300})$/i.exec(text);
  if (remindIn) {
    const amount = Number(remindIn[1]); const minutes = /^h/i.test(remindIn[2]) ? amount * 60 : amount;
    if (minutes >= 1 && minutes <= 525600) return { action: 'set_reminder', text: remindIn[3].trim(), inMinutes: minutes };
  }
  let contactCall = /^(?:এই নাম্বারে থাকা )?(.+?)\s*(?:কে|কে)?\s*(?:কল|ফোন)\s*(?:করো|কর|দাও)$/iu.exec(text)
    || /^(?:কল|ফোন)\s*(?:করো|কর|দাও)\s+(.+?)(?:\s*কে)?$/iu.exec(text)
    || /^([\p{L}.']+)(?:কে|কে|রে)\s*(?:কল|ফোন)\s*(?:করো|কর|দাও)?$/u.exec(text)
    || /^([\p{L}.']+(?:\s+[\p{L}.']+){0,2})\s+(?:ke|ko)\s+(?:call|phone|dial)(?:\s+(?:karo|koro|please))?$/iu.exec(text)
    || /^(?!(?:my|the|a|this|that|about)(?:\s|$))([\p{L}.']+(?:\s+[\p{L}.']+){0,2})\s+(?:call|phone)(?:\s+(?:karo|koro|please))?$/iu.exec(text)
    || /^call\s+(.+)$/i.exec(text);
  if (contactCall && contactCall[1].trim() && !/^[+0-9][0-9 ()+-]*$/.test(contactCall[1].trim())) return { action: 'call', recipient: contactCall[1].trim().replace(/\s+নামে$/, '').replace(/(?:কে|কে)$/u, '') };
  call = /^(?:কল\s*(?:করো|কর|দাও)|ফোন\s*করো|call|dial)$/iu.exec(text);
  if (call) return null;
  const alarm = /^(?:set\s+)?(?:an?\s+)?(?:alarm|অ্যালার্ম|আলার্ম|এলার্ম)\s*(?:for|at|টি)?\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm|এএম|পিএম)?$/iu.exec(text)
    || /^(?:wake me(?: up)? at)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$/i.exec(text)
    || /^(\d{1,2})(?::(\d{2}))?\s*টা(?:য়|র)?\s*(?:alarm|অ্যালার্ম|আলার্ম)\s*(?:দাও|দে|set|করো)?$/iu.exec(text);
  if (alarm) {
    let hour = Number(alarm[1]); const minute = alarm[2] ? Number(alarm[2]) : 0; const suffix = (alarm[3] || '').toLowerCase();
    if (suffix === 'pm' || suffix === 'পিএম') { if (hour < 12) hour += 12; }
    if (suffix === 'am' || suffix === 'এএম') { if (hour === 12) hour = 0; }
    if (hour <= 23 && minute <= 59) return { action: 'set_alarm', hour, minute };
  }
  const timer = /^(?:set\s+)?(?:a\s+)?timer\s+(?:for\s+)?(\d{1,4})\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hour|hours|সেকেন্ড|মিনিট|ঘণ্টা)$/iu.exec(text)
    || /^(\d{1,4})\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hour|hours|সেকেন্ড|মিনিট|ঘণ্টা)\s*(?:ের|এর)?\s*(?:টাইমার|timer)$/iu.exec(text);
  if (timer) {
    const amount = Number(timer[1]); const unit = timer[2].toLowerCase();
    const factor = /^(h|hr|hour|hours|ঘণ্টা)$/.test(unit) ? 3600 : /^(m|min|mins|minute|minutes|মিনিট)$/.test(unit) ? 60 : 1;
    const seconds = amount * factor;
    if (seconds >= 1 && seconds <= 86400) return { action: 'set_timer', seconds };
  }
  const volume = /^(?:set\s+)?(?:media\s+)?volume\s+(?:to\s+)?(\d{1,3})\s*%?$/i.exec(text);
  if (volume) { const level = Number(volume[1]); if (level <= 100) return { action: 'set_volume', level }; }
  if (/^(?:volume|sound)\s+(up|down|বাড়াও|কমাও)$/i.test(text)) return { action: 'set_volume', direction: /down|কমাও/i.test(text) ? 'down' : 'up' };
  if (/^(?:play|pause|next|previous)\s+(?:song|track|music)$/i.test(text)) return { action: 'media_control', command: /next/i.test(text) ? 'next' : /previous/i.test(text) ? 'previous' : 'play_pause' };
  if (/^(?:device|phone|battery)\s+(?:status|info)$/i.test(text) || /^(?:battery|ফোনের)\s*(?:কত|status|info)$/iu.test(text)) return { action: 'device_status' };
  if (/^(?:open|launch)\s+(?:the\s+)?camera$/i.test(text) || /^ক্যামেরা\s*(?:খোলো|চালু করো)$/u.test(text)) return { action: 'open_camera' };
  const link = /^(?:open|visit|go to)\s+(https?:\/\/\S{3,})$/i.exec(text);
  if (link) return { action: 'open_url', url: link[1] };
  const played = parsePlayMedia(text);
  if (played) return played;
  let match = /^(?:open\s+)?(?:youtube|you tube)(?:\s+and)?\s+search(?:\s+for)?\s+(.+)$/i.exec(text) || /^search(?:\s+for)?\s+(.+?)\s+on\s+youtube$/i.exec(text);
  if (match) return { action: 'search_youtube', query: match[1].trim() };
  match = /^(?:open\s+)?(?:chrome|google chrome|browser)(?:\s+and)?\s+search(?:\s+for)?\s+(.+)$/i.exec(text) || /^search(?:\s+for)?\s+(.+?)\s+(?:on|in)\s+(?:chrome|google|browser)$/i.exec(text);
  if (match) return { action: 'search_web', query: match[1].trim() };
  match = /^(?:send\s+)?(?:message|msg|text)\s+(.+?)\s+(?:on|using|to)\s+(?:whatsapp|wp|wa)\s*[:,]\s*(.+)$/is.exec(text) || /^(?:whatsapp|wp|wa)\s+(?:message|msg)(?:\s+to)?\s+(.+?)\s*:\s*(.+)$/is.exec(text);
  if (match) return { action: 'send_message', app: 'whatsapp', recipient: match[1].trim(), text: match[2].trim() };
  const openText = text.replace(/[.!?।]+$/u, '');
  // "open YouTube and play lofi" must open YouTube, not try to launch "YouTube and play lofi".
  const combined = /^(?:open|launch|start|খোলো|খুলে\s*দাও|ওপেন\s*করো)\s+(.+?)\s+(?:and|then|ar|আর|এবং|তারপর)\s+(.+)$/iu.exec(openText);
  if (combined && normalizeAppName(combined[1])) return { action: 'open_app', app: normalizeAppName(combined[1]) };
  match = /^(?:open|launch|start)\s+([\p{L}\p{M}\p{N}][\p{L}\p{M}\p{N} ._-]{0,79})$/iu.exec(openText)
    || /^([\p{L}\p{M}\p{N}][\p{L}\p{M}\p{N} ._-]{0,39})\s+(?:kholo|khol\s*do|khulo|open\s*karo|open\s*koro|chalu\s*koro|start\s*koro)$/iu.exec(openText)
    || /^([\p{L}\p{M}\p{N}][\p{L}\p{M}\p{N} ._-]{0,39})\s*(?:খোলো|খুলে\s*দাও|ওপেন\s*করো|চালু\s*করো)$/u.exec(openText);
  if (match) {
    const app = normalizeAppName(match[1]);
    if (app && !/^(?:it|that|this|এটা|ওটা|app|the\s+app)$/iu.test(app)) return { action: 'open_app', app };
  }
  return null;
}

/** Human-readable label for a tool call, used by the activity strip. */
export function describeAction(action: string, args: Record<string, unknown>): string {
  const first = (key: string) => String(args[key] ?? '').trim();
  switch (action) {
    case 'open_app': return `Opening ${first('app')}`;
    case 'open_url': return 'Opening link';
    case 'play_media': return `Playing “${first('query')}”${first('app') ? ` on ${first('app').replace('_', ' ')}` : ''}`;
    case 'search_youtube': return `Searching YouTube for “${first('query')}”`;
    case 'search_web': return `Searching the web for “${first('query')}”`;
    case 'call': return `Calling ${first('recipient')}${first('sim') ? ` on SIM ${first('sim')}` : ''}`;
    case 'send_message': return `${args.confirm ? 'Sending' : 'Preparing'} ${String(args.app || 'a message').toUpperCase()} to ${first('recipient')}`;
    case 'cancel_send': return 'Cancelling the prepared message';
    case 'sim_info': return 'Reading the phone SIMs';
    case 'set_flashlight': return args.enabled ? 'Turning the flashlight on' : 'Turning the flashlight off';
    case 'open_wifi_settings': return 'Opening Internet settings';
    case 'open_settings_panel': return `Opening ${first('panel')} settings`;
    case 'set_alarm': return `Setting an alarm for ${String(args.hour).padStart(2, '0')}:${String(args.minute).padStart(2, '0')}`;
    case 'set_timer': return `Setting a timer for ${first('seconds')} seconds`;
    case 'create_event': return `Opening calendar for “${first('title')}”`;
    case 'navigate_to': return `Opening Maps for ${first('destination')}`;
    case 'share_text': return 'Opening the share sheet';
    case 'copy_text': return 'Copying to the clipboard';
    case 'media_control': return `Sending media ${first('command')}`;
    case 'set_volume': return args.level !== undefined ? `Setting volume to ${first('level')}%` : `Turning volume ${first('direction')}`;
    case 'device_status': return 'Checking device status';
    case 'list_apps': return 'Reading installed apps';
    case 'open_camera': return 'Opening the camera';
    case 'set_reminder': return `Setting a reminder: “${first('text')}”`;
    case 'list_reminders': return 'Reading your reminders';
    case 'cancel_reminder': return 'Cancelling a reminder';
    case 'save_note': return 'Saving a note';
    case 'list_notes': return 'Reading your notes';
    case 'delete_note': return 'Deleting a note';
    case 'read_notifications': return 'Reading recent notifications';
    case 'clear_notifications': return 'Clearing notifications';
    case 'read_calendar': return 'Reading your calendar';
    case 'get_weather': return `Checking the weather${first('city') ? ` in ${first('city')}` : ''}`;
    case 'daily_briefing': return 'Preparing your daily briefing';
    case 'ring_phone': return 'Ringing the phone';
    case 'open_jarvis_settings': return 'Opening Jarvis Pro settings';
    default: return 'Working on your phone';
  }
}
