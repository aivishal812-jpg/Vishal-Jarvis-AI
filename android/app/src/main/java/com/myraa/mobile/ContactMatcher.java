package com.myraa.mobile;

import android.app.Activity;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import java.util.*;

/** Loads the permitted Android address book and ranks it with {@link NameMatcher}. */
public final class ContactMatcher {
  private final Activity activity;
  private final ContactAliasStore aliases;

  public ContactMatcher(Activity activity, ContactAliasStore aliases) {
    this.activity = activity;
    this.aliases = aliases;
  }

  public NameMatcher.Decision match(String spoken) {
    return NameMatcher.match(spoken, loadContacts(), aliases.snapshot());
  }

  public void rememberAlias(String spoken, String contactName) {
    aliases.remember(spoken, contactName);
  }

  public List<NameMatcher.Contact> loadContacts() {
    Map<String, List<String>> nicknames = loadNicknames();
    Map<String, String> phonetic = loadPhoneticNames();
    List<NameMatcher.Contact> found = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    String[] projection = new String[]{Phone.DISPLAY_NAME, Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.CONTACT_ID};
    try (Cursor c = activity.getContentResolver().query(Phone.CONTENT_URI, projection, null, null, Phone.DISPLAY_NAME + " ASC")) {
      if (c == null) return found;
      while (c.moveToNext() && found.size() < 2500) {
        String name = c.getString(0);
        String raw = c.getString(2);
        if (raw == null || raw.isEmpty()) raw = c.getString(1);
        String phone = international(raw);
        if (phone == null || name == null || name.trim().isEmpty() || !seen.add(phone + "\0" + name)) continue;
        String contactId = c.getString(3);
        List<String> extras = new ArrayList<>();
        List<String> nicks = nicknames.get(contactId);
        if (nicks != null) extras.addAll(nicks);
        String ph = phonetic.get(contactId);
        if (ph != null && !ph.trim().isEmpty()) extras.add(ph);
        found.add(new NameMatcher.Contact(name, phone, extras));
      }
    } catch (SecurityException ignored) {}
    return found;
  }

  private Map<String, List<String>> loadNicknames() {
    Map<String, List<String>> map = new HashMap<>();
    try (Cursor c = activity.getContentResolver().query(
      ContactsContract.Data.CONTENT_URI,
      new String[]{ContactsContract.Data.CONTACT_ID, Nickname.NAME},
      ContactsContract.Data.MIMETYPE + "=?",
      new String[]{Nickname.CONTENT_ITEM_TYPE},
      null)) {
      if (c == null) return map;
      while (c.moveToNext()) {
        String id = c.getString(0);
        String nick = c.getString(1);
        if (id == null || nick == null || nick.trim().isEmpty()) continue;
        List<String> list = map.get(id);
        if (list == null) { list = new ArrayList<>(); map.put(id, list); }
        list.add(nick);
      }
    } catch (SecurityException ignored) {}
    return map;
  }

  private Map<String, String> loadPhoneticNames() {
    Map<String, String> map = new HashMap<>();
    try (Cursor c = activity.getContentResolver().query(
      ContactsContract.Contacts.CONTENT_URI,
      new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.PHONETIC_NAME},
      ContactsContract.Contacts.PHONETIC_NAME + " IS NOT NULL",
      null, null)) {
      if (c == null) return map;
      while (c.moveToNext()) {
        String id = c.getString(0);
        String phonetic = c.getString(1);
        if (id != null && phonetic != null && !phonetic.trim().isEmpty()) map.put(id, phonetic);
      }
    } catch (Exception ignored) {}
    return map;
  }

  private String international(String raw) {
    if (raw == null) return null;
    String clean = raw.replaceAll("[ ()-]", "");
    if (clean.startsWith("00")) clean = "+" + clean.substring(2);
    if (clean.matches("\\+[1-9][0-9]{7,14}")) return clean.substring(1);
    String country = "";
    try { country = activity.getSystemService(TelephonyManager.class).getNetworkCountryIso(); }
    catch (Exception ignored) {}
    if (country == null || country.length() != 2) {
      String language = activity.getSharedPreferences("myraa.mobile.v1", 0).getString("language", "bn-IN");
      country = language.endsWith("IN") ? "IN" : "US";
    }
    String e164 = PhoneNumberUtils.formatNumberToE164(raw, country.toUpperCase(Locale.ROOT));
    return e164 == null ? null : e164.substring(1);
  }
}
