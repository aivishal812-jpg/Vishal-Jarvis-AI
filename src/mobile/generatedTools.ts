/**
 * GENERATED FILE — do not edit.
 *
 * Produced by scripts/sync-tools.mjs from android/app/src/main/java/com/myraa/mobile/MobileTools.java
 * so text chat and Gemini Live can never drift apart. Edit MobileTools.java instead, then run:
 *   node scripts/sync-tools.mjs
 * 'npm run check' fails when this file is stale.
 */
export type ToolDeclaration = {
  name: string;
  description: string;
  parameters: { type: 'OBJECT'; properties: Record<string, unknown>; required: string[] };
};

export const GENERATED_TOOLS: ToolDeclaration[] = [
  {
    "name": "mobile_action",
    "description": "Work the user's Android phone through supported actions: open installed apps, open an https page, play a song, album, artist, video or channel directly in a music app (YouTube, YouTube Music, Spotify, Prime Music, Gaana or JioSaavn), search YouTube or the web, place a phone call directly (choosing the SIM when the phone has several), send a message directly in WhatsApp, Telegram, Instagram, Messenger or SMS, reveal the phone's SIMs, toggle the flashlight, open Wi-Fi and system panels, set alarms and timers, open a calendar event editor, open Maps navigation, share text, copy to the clipboard, send media keys, change media volume, read device status, list installed apps, open the camera, set/list/cancel reminders that pop up even when the app is closed, save/list/delete quick notes, read the user's recent notifications (and clear them), read upcoming calendar events, delete one upcoming calendar event or all of them after the user confirms in the app dialog, work with no window at all for safe actions (reminders, notes, weather, device status, flashlight, volume, media keys, notifications, calendar), read the text of the current screen through the accessibility tree, read the message the user is looking at, fill a form with values the user gave (never submitting it), summarise important notifications, missed calls and one-time codes with the code itself hidden, and search earlier conversations, get the weather for a city, give a daily briefing, ring the phone loudly to find it, and open Jarvis Pro settings. Contacts are resolved locally on the device. Sending is always two steps: prepare, then confirm with confirm=true. Never invent success: report the returned status truthfully.",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "action": {
          "type": "STRING",
          "enum": [
            "open_app",
            "open_url",
            "play_media",
            "search_youtube",
            "search_web",
            "call",
            "send_message",
            "cancel_send",
            "sim_info",
            "set_flashlight",
            "open_wifi_settings",
            "open_settings_panel",
            "set_alarm",
            "set_timer",
            "create_event",
            "navigate_to",
            "share_text",
            "copy_text",
            "media_control",
            "set_volume",
            "device_status",
            "list_apps",
            "open_camera",
            "set_reminder",
            "list_reminders",
            "cancel_reminder",
            "save_note",
            "list_notes",
            "delete_note",
            "read_notifications",
            "clear_notifications",
            "read_calendar",
            "delete_calendar_event",
            "get_weather",
            "daily_briefing",
            "ring_phone",
            "open_jarvis_settings",
            "read_screen",
            "read_last_message",
            "fill_form",
            "screen_info",
            "missed_calls",
            "otp_summary",
            "what_did_i_say",
            "call_assistant"
          ],
          "description": "Which Android action to perform."
        },
        "app": {
          "type": "STRING",
          "description": "For send_message: whatsapp, telegram, instagram, messenger or sms. For open_app: the app name as shown on the home screen, such as YouTube, Chrome, Calculator. For play_media: youtube, youtube_music, spotify, prime_music, gaana or jiosaavn only when the user named a player; omit it otherwise."
        },
        "url": {
          "type": "STRING",
          "description": "A complete https:// page address explicitly given or implied by the user. Only for open_url."
        },
        "recipient": {
          "type": "STRING",
          "description": "Whom to call or message: a saved contact name exactly as the user said it, a full phone number with country code, or the exact @username for Telegram, Instagram or Messenger. Contacts are resolved locally on the device. Used by call and send_message."
        },
        "sim": {
          "type": "STRING",
          "description": "Which SIM to use, \"1\" or \"2\", taken from the user's answer or from sim_info. Only send it after the user chose. Used by call and by send_message with app sms."
        },
        "confirm": {
          "type": "BOOLEAN",
          "description": "false or omitted prepares the message and reports what would be sent; true actually presses Send. Only for send_message, and only after the user said send."
        },
        "enabled": {
          "type": "BOOLEAN",
          "description": "true turns the flashlight on, false turns it off. Only for set_flashlight."
        },
        "panel": {
          "type": "STRING",
          "enum": [
            "wifi",
            "bluetooth",
            "sound",
            "display",
            "battery",
            "location",
            "apps",
            "notifications",
            "data",
            "airplane",
            "accessibility",
            "nfc",
            "home"
          ],
          "description": "Which system settings panel to open. Only for open_settings_panel."
        },
        "hour": {
          "type": "INTEGER",
          "description": "Alarm hour on a 24-hour clock, 0-23. Only for set_alarm."
        },
        "minute": {
          "type": "INTEGER",
          "description": "Alarm minute, 0-59. Only for set_alarm."
        },
        "seconds": {
          "type": "INTEGER",
          "description": "Timer length in seconds, 1-86400. Only for set_timer."
        },
        "label": {
          "type": "STRING",
          "description": "Short label for an alarm or timer."
        },
        "title": {
          "type": "STRING",
          "description": "Calendar event title. Only for create_event."
        },
        "beginTime": {
          "type": "NUMBER",
          "description": "Event start as Unix epoch milliseconds in the device's time zone. Only for create_event."
        },
        "endTime": {
          "type": "NUMBER",
          "description": "Event end as Unix epoch milliseconds. Defaults to one hour after the start. Only for create_event."
        },
        "location": {
          "type": "STRING",
          "description": "Optional place for a calendar event."
        },
        "description": {
          "type": "STRING",
          "description": "Optional notes for a calendar event."
        },
        "destination": {
          "type": "STRING",
          "description": "Place or address to show in Maps. Only for navigate_to."
        },
        "text": {
          "type": "STRING",
          "description": "The exact text: the message body for send_message (never shorten or rewrite it), the text to share or copy, what to remind about for set_reminder, the note body for save_note, or words from a reminder to cancel."
        },
        "atTime": {
          "type": "NUMBER",
          "description": "For set_reminder: when it should fire, as Unix epoch milliseconds computed from the local time above. Use either atTime or inMinutes."
        },
        "inMinutes": {
          "type": "INTEGER",
          "description": "For set_reminder: fire this many minutes from now (1-525600)."
        },
        "repeat": {
          "type": "STRING",
          "enum": [
            "none",
            "daily",
            "weekly"
          ],
          "description": "For set_reminder: repeat schedule. Default none."
        },
        "reminderId": {
          "type": "STRING",
          "description": "For cancel_reminder: the id from list_reminders."
        },
        "noteId": {
          "type": "STRING",
          "description": "For delete_note: the id from list_notes."
        },
        "days": {
          "type": "INTEGER",
          "description": "For read_calendar and delete_calendar_event: how many days ahead to look, 1-31. Default 1 for reading, 7 for deleting."
        },
        "eventId": {
          "type": "NUMBER",
          "description": "For delete_calendar_event: the exact event id from read_calendar or from an earlier needs_choice result. Prefer this over a title after the user picked an event."
        },
        "all": {
          "type": "BOOLEAN",
          "description": "For delete_calendar_event: true only when the user asked to delete every upcoming event. Defaults to false, which deletes a single event."
        },
        "city": {
          "type": "STRING",
          "description": "For get_weather and daily_briefing: the city name. Omit to use the user's saved home city."
        },
        "limit": {
          "type": "INTEGER",
          "description": "For read_notifications: how many to read, 1-20. Default 8."
        },
        "command": {
          "type": "STRING",
          "enum": [
            "play_pause",
            "next",
            "previous",
            "on",
            "off",
            "status",
            "speak"
          ],
          "description": "Media key for media_control (play_pause, next, previous); on / off / status / speak for call_assistant."
        },
        "level": {
          "type": "INTEGER",
          "description": "Target media volume as a percentage, 0-100. Only for set_volume."
        },
        "maxChars": {
          "type": "INTEGER",
          "description": "For read_screen: how much screen text to return, 500-20000. Default 6000."
        },
        "values": {
          "type": "ARRAY",
          "items": {
            "type": "STRING"
          },
          "description": "For fill_form: the exact values to type, in the order the empty fields appear on the screen. Only values the user gave in this request; never guess or invent a password, OTP or card number."
        },
        "importantOnly": {
          "type": "BOOLEAN",
          "description": "For read_notifications: true returns only notifications that look important (high priority, missed calls, messages, alarms)."
        },
        "query": {
          "type": "STRING",
          "description": "For what_did_i_say: words to look for in earlier conversations and saved facts. For play_media: the song, album, artist, video or channel. For search_youtube and search_web: the search text."
        },
        "direction": {
          "type": "STRING",
          "enum": [
            "up",
            "down"
          ],
          "description": "Raise or lower media volume by one step. Only for set_volume when level is absent."
        }
      },
      "required": [
        "action"
      ]
    }
  },
  {
    "name": "remember_fact",
    "description": "Save one durable fact or preference about the user so future conversations can use it, for example their name, city, favourite language, or a standing routine. Only call this when the user asks you to remember something or states a lasting preference. Never store API keys, passwords, OTPs, card numbers or other secrets. Keep the fact under 240 characters, in the user's own words.",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "fact": {
          "type": "STRING",
          "description": "Short, self-contained fact to remember. One sentence."
        },
        "kind": {
          "type": "STRING",
          "enum": [
            "fact",
            "contact",
            "place",
            "routine",
            "shop",
            "other"
          ],
          "description": "What kind of thing this is. Use contact for a person with a saved number, place for an address or area, routine for a repeating habit. Default fact."
        },
        "name": {
          "type": "STRING",
          "description": "For a structured entry: the short name to look it up by later, for example \"shop\" or \"dentist\". Omit for a plain fact."
        },
        "value": {
          "type": "STRING",
          "description": "For a structured entry: the number or detail to store with name, for example +919876543210."
        }
      },
      "required": [
        "fact"
      ]
    }
  }
];
