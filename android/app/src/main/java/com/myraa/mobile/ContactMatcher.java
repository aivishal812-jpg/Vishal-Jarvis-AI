package com.myraa.mobile;

import android.app.Activity;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.content.SharedPreferences;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import java.util.*;

/** Loads the permitted Android address book and ranks it with {@link NameMatcher}. */
public final class ContactMatcher {
  private final Activity activity;
  private final ContactAliasStore aliases;
  private final ContactMemory memory;
  private final SharedPreferences prefs;
  private List<NameMatcher.Person> cached;
  private long cachedAt;
  private Map<String, Integer> usageCache;
  private long usageAt;

  public ContactMatcher(Activity activity, ContactAliasStore aliases) {
    this(activity, aliases, null);
  }

  public ContactMatcher(Activity activity, ContactAliasStore aliases, SharedPreferences prefs) {
    this.activity = activity;
    this.aliases = aliases;
    this.prefs = prefs;
    this.memory = new ContactMemory(prefs == null ? "" : prefs.getString(ContactMemory.KEY, ""));
  }

  /**
   * Decides who the user meant, without ever asking.
   *
   * Order: a name the user already settled → the phone's own call/message history (father is
   * called every week, "Baban" never) → exact name → any other match. The history is what makes
   * "baba" resolve to the right person on the very first try, with no list and no question.
   */
  public NameMatcher.Decision match(String spoken) {
    return NameMatcher.resolve(spoken, people(), memory, aliases.snapshot(), usage());
  }

  ContactMemory.Pick learned(String spoken) {
    return memory.lookup(spoken);
  }

  /** How often each number appears in the call log; empty when the user has not allowed it. */
  Map<String, Integer> usage() {
    long now = System.currentTimeMillis();
    if (usageCache != null && now - usageAt < 60000) return usageCache;
    usageCache = loadUsage();
    usageAt = now;
    return usageCache;
  }

  private Map<String, Integer> loadUsage() {
    Map<String, Integer> counts = new HashMap<>();
    if (activity.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      return counts;
    }
    String[] projection = new String[]{android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DATE};
    long since = System.currentTimeMillis() - 120L * 24 * 60 * 60 * 1000;   // last four months
    try (Cursor c = activity.getContentResolver().query(android.provider.CallLog.Calls.CONTENT_URI, projection,
        android.provider.CallLog.Calls.DATE + ">?", new String[]{String.valueOf(since)},
        android.provider.CallLog.Calls.DATE + " DESC")) {
      if (c == null) return counts;
      int rows = 0;
      while (c.moveToNext() && rows++ < 800) {
        String phone = international(c.getString(0));
        if (phone == null) continue;
        Integer previous = counts.get(phone);
        counts.put(phone, previous == null ? 1 : previous + 1);
      }
    } catch (SecurityException | RuntimeException denied) {
      // Call-log access is optional: without it the address book is still used, just less exact.
      return counts;
    }
    return counts;
  }

  /** Remembers the person the user actually used, for this spoken word. */
  public void rememberPick(String spoken, NameMatcher.Candidate chosen) {
    if (chosen == null) return;
    memory.remember(spoken, chosen.name, chosen.phone, chosen.contactId);
    if (prefs != null) prefs.edit().putString(ContactMemory.KEY, memory.toJson()).apply();
  }

  public void rememberAlias(String spoken, String contactName) {
    aliases.remember(spoken, contactName);
  }

  List<NameMatcher.Person> people() {
    long now = System.currentTimeMillis();
    if (cached != null && now - cachedAt < 30000) return cached;
    cached = loadPeople();
    cachedAt = now;
    return cached;
  }

  /** Drops the 30-second cache after the user edits the address book or a learned pick. */
  public void invalidate() { cached = null; cachedAt = 0; usageCache = null; usageAt = 0; }

  ContactMemory memory() { return memory; }

  List<NameMatcher.Person> loadPeople() {
    Map<String, List<String>> nicknames = loadNicknames();
    Map<String, String> phonetic = loadPhoneticNames();
    List<NameMatcher.Person> found = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    String[] projection = new String[]{Phone.DISPLAY_NAME, Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.CONTACT_ID};
    try (Cursor c = activity.getContentResolver().query(Phone.CONTENT_URI, projection, null, null, Phone.DISPLAY_NAME + " ASC")) {
      if (c == null) return found;
      while (c.moveToNext() && found.size() < 2500) {
        String name = c.getString(0);
        String raw = c.getString(2);
        if (raw == null || raw.isEmpty()) raw = c.getString(1);
        String phone = international(raw);
        String contactId = c.getString(3);
        if (phone == null || name == null || name.trim().isEmpty()) continue;
        // One entry per person per number: a second number for the same person is a real choice,
        // a duplicate row for the same number is not.
        if (!seen.add(phone + "\0" + name)) continue;
        List<String> extras = new ArrayList<>();
        List<String> nicks = nicknames.get(contactId);
        if (nicks != null) extras.addAll(nicks);
        String ph = phonetic.get(contactId);
        if (ph != null && !ph.trim().isEmpty()) extras.add(ph);
        found.add(new NameMatcher.Person(name, phone, contactId == null ? "" : contactId, extras));
      }
    } catch (SecurityException ignored) { /* the permission was revoked between screens */ }
    return found;
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
    } catch (SecurityException ignored) { /* nothing to do on release of a temporary object */ }
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
    } catch (SecurityException ignored) { /* nothing to do on release of a temporary object */ }
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
    } catch (Exception ignored) { /* nothing to do on release of a temporary object */ }
    return map;
  }

  private String international(String raw) {
    if (raw == null) return null;
    String clean = raw.replaceAll("[ ()-]", "");
    if (clean.startsWith("00")) clean = "+" + clean.substring(2);
    if (clean.matches("\\+[1-9][0-9]{7,14}")) return clean.substring(1);
    String country = "";
    try { country = activity.getSystemService(TelephonyManager.class).getNetworkCountryIso(); }
    catch (Exception ignored) { /* nothing to do on release of a temporary object */ }
    if (country == null || country.length() != 2) {
      String language = activity.getSharedPreferences("myraa.mobile.v1", 0).getString("language", "bn-IN");
      country = language.endsWith("IN") ? "IN" : "US";
    }
    String e164 = PhoneNumberUtils.formatNumberToE164(raw, country.toUpperCase(Locale.ROOT));
    return e164 == null ? null : e164.substring(1);
  }
}
