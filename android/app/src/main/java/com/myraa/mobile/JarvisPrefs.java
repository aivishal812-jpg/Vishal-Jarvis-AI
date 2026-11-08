package com.myraa.mobile;

import android.content.Context;
import android.content.SharedPreferences;

/** Keys for the Jarvis Pro features. Everything lives in the app's private preferences. */
final class JarvisPrefs {
  static final String FILE = "myraa.mobile.v1";
  static final String WAKE_WORD = "pro.wakeWord";
  static final String SECURE_ACTIONS = "pro.secureActions";
  static final String FALLBACK_MODEL = "pro.fallbackModel";
  static final String HOME_CITY = "pro.homeCity";
  static final String EXTRA_AUTO_VOICE = "com.myraa.mobile.AUTO_VOICE";
  static final String DEFAULT_FALLBACK = "gemini-2.5-flash";
  static SharedPreferences get(Context context) { return context.getSharedPreferences(FILE, Context.MODE_PRIVATE); }
  static boolean wakeWord(Context c) { return get(c).getBoolean(WAKE_WORD, false); }
  static boolean secureActions(Context c) { return get(c).getBoolean(SECURE_ACTIONS, false); }
  static String fallbackModel(Context c) {
    String value = get(c).getString(FALLBACK_MODEL, DEFAULT_FALLBACK);
    return value == null || !value.matches("[A-Za-z0-9._-]{0,100}") ? DEFAULT_FALLBACK : value.trim();
  }
  static String homeCity(Context c) { String v = get(c).getString(HOME_CITY, ""); return v == null ? "" : v.trim(); }
  private JarvisPrefs() {}
}
