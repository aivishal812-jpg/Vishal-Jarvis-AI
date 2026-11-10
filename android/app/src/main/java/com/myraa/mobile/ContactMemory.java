package com.myraa.mobile;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What the user actually meant the last few times they said a name.
 *
 * A phone book is full of "Baba", "Baban", "Baban-2", "Bablu", "Babu" — a fuzzy matcher can rank
 * those, but only the user knows which one is their father. This store learns that once, silently,
 * from a successful call or message, and uses it next time:
 *
 *  - "Baba" is pinned to one contact id, with how many calls/messages it has had
 *  - a different spoken word that is very close to a pinned word counts as the same intent, but a
 *    pinned word always wins over a broad prefix match, so "Baba" can never drift to "Baban"
 *  - the store is a plain JSON string, so it can be exported, backed up and tested without Android
 */
final class ContactMemory {

  static final String KEY = "contactMemoryV1";
  /** A learned pick is trusted when it was used at least this many times. */
  static final int TRUSTED_USES = 1;
  /** How many times "Baba" must be chosen before a near-miss spelling reuses it. */
  static final int NEAR_MISS_USES = 2;

  /** One learned spoken word → one saved person. */
  static final class Pick {
    final String spoken;
    final String name;
    final String phone;
    final String contactId;
    int uses;
    long lastUsed;

    Pick(String spoken, String name, String phone, String contactId, int uses, long lastUsed) {
      this.spoken = spoken == null ? "" : spoken;
      this.name = name == null ? "" : name;
      this.phone = phone == null ? "" : phone;
      this.contactId = contactId == null ? "" : contactId;
      this.uses = Math.max(0, uses);
      this.lastUsed = lastUsed;
    }

    JSONObject json() throws JSONException {
      return new JSONObject().put("spoken", spoken).put("name", name).put("phone", phone)
        .put("id", contactId).put("uses", uses).put("at", lastUsed);
    }

    boolean samePerson(String otherName, String otherPhone, String otherId) {
      if (!phone.isEmpty() && !otherPhone.isEmpty() && phone.equals(otherPhone)) return true;
      if (!contactId.isEmpty() && !otherId.isEmpty() && contactId.equals(otherId)) return true;
      return !name.isEmpty() && name.equalsIgnoreCase(otherName == null ? "" : otherName);
    }
  }

  private final List<Pick> picks = new ArrayList<>();

  ContactMemory() {}
  ContactMemory(String json) { load(json); }

  List<Pick> all() { return Collections.unmodifiableList(picks); }

  void load(String json) {
    picks.clear();
    if (json == null || json.trim().isEmpty()) return;
    try {
      JSONObject root = new JSONObject(json);
      java.util.Iterator<String> keys = root.keys();
      while (keys.hasNext()) {
        JSONObject item = root.optJSONObject(keys.next());
        if (item == null) continue;
        Pick pick = new Pick(item.optString("spoken"), item.optString("name"), item.optString("phone"),
          item.optString("id"), item.optInt("uses"), item.optLong("at", 0));
        if (pick.spoken.isEmpty() || pick.name.isEmpty()) continue;
        picks.add(pick);
      }
    } catch (JSONException malformed) {
      picks.clear();
    }
    sort();
  }

  String toJson() {
    JSONObject root = new JSONObject();
    try {
      for (Pick pick : picks) {
        root.put(key(pick.spoken) + "|" + (pick.contactId.isEmpty() ? pick.phone : pick.contactId), pick.json());
      }
    } catch (JSONException ignored) { /* a value that cannot be stored is skipped, never fatal */ }
    return root.toString();
  }

  /**
   * The person this spoken word was resolved to before, or null.
   *
   * A previously pinned word wins outright. A close spelling is only reused once the pinned word
   * has been confirmed at least twice, so a single mis-tap cannot hijack a name.
   */
  Pick lookup(String spoken) {
    String wanted = normalise(spoken);
    if (wanted.isEmpty()) return null;
    for (Pick pick : picks) {
      if (pick.uses >= TRUSTED_USES && normalise(pick.spoken).equals(wanted)) return pick;
    }
    Pick closest = null;
    double best = 0;
    for (Pick pick : picks) {
      if (pick.uses < NEAR_MISS_USES) continue;
      double score = similarity(wanted, normalise(pick.spoken));
      if (score >= 0.86 && score > best) { best = score; closest = pick; }
    }
    return closest;
  }

  /** Remembers this choice for the spoken word; a better phone number updates the same pick. */
  void remember(String spoken, String name, String phone, String contactId) {
    String key = normalise(spoken);
    if (key.isEmpty() || name == null || name.trim().isEmpty()) return;
    for (Pick pick : picks) {
      if (!normalise(pick.spoken).equals(key)) continue;
      if (pick.samePerson(name, phone, contactId)) {
        pick.uses = pick.uses + 1;
        pick.lastUsed = System.currentTimeMillis();
        if (pick.phone.isEmpty()) pick.phone = phone == null ? "" : phone;
        persistHint();
        return;
      }
    }
    // A different person for the same word replaces the old pick, so the correction is what sticks.
    for (int i = picks.size() - 1; i >= 0; i--) if (normalise(picks.get(i).spoken).equals(key)) picks.remove(i);
    picks.add(new Pick(spoken.trim(), name.trim(), phone == null ? "" : phone, contactId == null ? "" : contactId,
      1, System.currentTimeMillis()));
    sort();
    persistHint();
  }

  /** Number of learned words, for the settings screen. */
  int size() { return picks.size(); }

  /** The most-used picks first, for a short readable list in settings. */
  List<Pick> top(int limit) {
    List<Pick> copy = new ArrayList<>(picks);
    Collections.sort(copy, new Comparator<Pick>() {
      public int compare(Pick a, Pick b) {
        if (a.uses != b.uses) return b.uses - a.uses;
        return Long.compare(b.lastUsed, a.lastUsed);
      }
    });
    return copy.size() > limit ? new ArrayList<>(copy.subList(0, limit)) : copy;
  }

  /** Forgets one spoken word (settings), never the address book itself. */
  void forget(String spoken) {
    String key = normalise(spoken);
    for (int i = picks.size() - 1; i >= 0; i--) if (normalise(picks.get(i).spoken).equals(key)) picks.remove(i);
    persistHint();
  }

  boolean isEmpty() { return picks.isEmpty(); }

  // ------------------------------------------------------------------ helpers

  private void persistHint() { sort(); }

  private void sort() {
    Collections.sort(picks, new Comparator<Pick>() {
      public int compare(Pick a, Pick b) {
        if (a.uses != b.uses) return b.uses - a.uses;
        return Long.compare(b.lastUsed, a.lastUsed);
      }
    });
  }

  private static String key(String spoken) {
    return normalise(spoken).replaceAll("[^\\p{L}\\p{N}]+", "-");
  }

  static String normalise(String value) {
    if (value == null) return "";
    return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }

  /** Jaro-Winkler, matching the scale the name matcher already uses. */
  static double similarity(String a, String b) {
    return NameMatcher.jaroWinkler(a, b);
  }
}
