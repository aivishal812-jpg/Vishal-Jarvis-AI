package com.myraa.mobile;

import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.*;

/**
 * User-approved spoken aliases. Never writes back into the Android
 * Contacts database; aliases live only in app preferences.
 */
public final class ContactAliasStore {
  private static final String KEY = "contactAliasesV1";
  private final SharedPreferences prefs;
  private final Map<String, String> spokenToName = new LinkedHashMap<>();

  public ContactAliasStore(SharedPreferences prefs) {
    this.prefs = prefs;
    load();
  }

  public synchronized Map<String, String> snapshot() {
    return new LinkedHashMap<>(spokenToName);
  }

  public synchronized void remember(String spoken, String contactName) {
    if (spoken == null || contactName == null) return;
    String key = BengaliPhonetics.fold(NameMatcher.extractSpokenName(spoken));
    String phonetic = BengaliPhonetics.phoneticKey(spoken);
    if (key.isEmpty() || key.length() > 80) return;
    spokenToName.put(key, contactName);
    if (!phonetic.isEmpty()) spokenToName.put(phonetic, contactName);
    persist();
  }

  private void load() {
    spokenToName.clear();
    try {
      JSONObject json = new JSONObject(prefs.getString(KEY, "{}"));
      Iterator<String> keys = json.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        String name = json.optString(key, "");
        if (!key.isEmpty() && !name.isEmpty()) spokenToName.put(key, name);
      }
    } catch (JSONException ignored) { /* nothing to do on release of a temporary object */ }
  }

  private void persist() {
    JSONObject json = new JSONObject();
    try {
      for (Map.Entry<String, String> e : spokenToName.entrySet()) json.put(e.getKey(), e.getValue());
    } catch (JSONException ignored) { /* nothing to do on release of a temporary object */ }
    prefs.edit().putString(KEY, json.toString()).apply();
  }
}
