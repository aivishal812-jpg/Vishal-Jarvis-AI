package com.myraa.mobile;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-stage contact name matcher: extract the spoken name, generate
 * phonetic/spelling variants, rank Android contacts, and decide whether
 * to call, confirm, or ask the user to choose.
 */
public final class NameMatcher {
  public static final double AUTO_SCORE = 0.86;
  public static final double CONFIRM_SCORE = 0.72;
  public static final double CHOOSE_SCORE = 0.68;
  public static final double AUTO_GAP = 0.10;

  public static final class Contact {
    public final String name;
    public final String phone;
    public final List<String> aliases;
    public Contact(String name, String phone) { this(name, phone, Collections.<String>emptyList()); }
    public Contact(String name, String phone, List<String> aliases) {
      this.name = name == null ? "" : name;
      this.phone = phone == null ? "" : phone;
      this.aliases = aliases == null ? Collections.<String>emptyList() : aliases;
    }
  }

  public static final class Candidate {
    public final String name;
    public final String phone;
    public final double score;
    public final String reason;
    public Candidate(String name, String phone, double score, String reason) {
      this.name = name; this.phone = phone; this.score = score; this.reason = reason;
    }
  }

  public static final class Decision {
    public final String verdict; // exact | confident | confirm | choose | none
    public final Candidate best;
    public final List<Candidate> ranked;
    public final String query;
    public Decision(String verdict, Candidate best, List<Candidate> ranked, String query) {
      this.verdict = verdict; this.best = best; this.ranked = ranked; this.query = query;
    }
  }

  private static final Pattern WAKE = Pattern.compile(
    "^(?:hey\\s+)?(?:vishal\\s+jarvis|vishal|roisal\\s+jarvis|roisal|jarvis|myraa|myra|মায়া|মায়া)\\s*[,: ]+",
    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern CALL_AFTER = Pattern.compile(
    "^(?:এই\\s+(?:নাম্বারে|নামে)\\s+থাকা\\s+)?(.+?)\\s*(?:কে|কে|ke|ko)?\\s*(?:কল|ফোন|call|phone|dial)\\s*(?:করো|কর|দাও|karo|koro|kardo|please)?$",
    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern CALL_BEFORE = Pattern.compile(
    "^(?:কল|ফোন|call|phone|dial)(?:\\s+(?:করো|কর|দাও|karo|koro|to))?\\s+(.+?)(?:\\s*(?:কে|কে|ke))?$",
    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern NAME_THEN_CALL = Pattern.compile(
    "^(.+?)\\s+(?:ke|ko|কে|কে)\\s+(?:call|phone|fon|কল|ফোন)\\s*(?:karo|koro|করো|কর|দাও)?$",
    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern TRAILING_CALL = Pattern.compile(
    "^(.+?)\\s+(?:call|phone|কল|ফোন)$",
    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern OBJECT_SUFFIX = Pattern.compile("(?:কে|কে|কে|re)$");

  public static String extractSpokenName(String raw) {
    if (raw == null) return "";
    String text = raw.trim().replaceAll("\\s+", " ");
    text = WAKE.matcher(text).replaceFirst("");
    text = text.replaceFirst("^(?:please|can you|could you|doya kore|দয়া করে)\\s+", "");
    if (text.matches("^[+0-9 ()-]{6,22}$")) return text.trim();
    Matcher m = NAME_THEN_CALL.matcher(text);
    if (m.matches()) return cleanName(m.group(1));
    m = CALL_AFTER.matcher(text);
    if (m.matches()) return cleanName(m.group(1));
    m = CALL_BEFORE.matcher(text);
    if (m.matches()) return cleanName(m.group(1));
    m = TRAILING_CALL.matcher(text);
    if (m.matches()) return cleanName(m.group(1));
    return cleanName(text);
  }

  private static String cleanName(String name) {
    if (name == null) return "";
    String value = name.trim().replaceAll("^[\"']+|[\"']+$", "");
    value = value.replaceFirst("^(?:এই\\s+)?(?:নামে\\s+)?", "");
    value = value.replaceFirst("\\s+নামে$", "");
    value = OBJECT_SUFFIX.matcher(value).replaceFirst("");
    value = value.replaceFirst("(?:কে)$", "");
    return value.trim().replaceAll("\\s+", " ");
  }

  public static Decision match(String spoken, List<Contact> contacts) {
    return match(spoken, contacts, Collections.<String, String>emptyMap());
  }

  /**
   * @param aliases user-approved spoken-form → saved contact name (never overwrites the address book)
   */
  public static Decision match(String spoken, List<Contact> contacts, Map<String, String> aliases) {
    String query = extractSpokenName(spoken);
    List<Candidate> ranked = new ArrayList<>();
    if (query.isEmpty() || contacts == null || contacts.isEmpty()) {
      return new Decision("none", null, ranked, query);
    }
    String queryFold = BengaliPhonetics.fold(query);
    String queryKey = BengaliPhonetics.phoneticKey(query);
    String queryCons = BengaliPhonetics.consonantKey(queryFold);
    Set<String> queryVars = BengaliPhonetics.latinVariants(query);
    String aliasTarget = null;
    if (aliases != null) {
      aliasTarget = aliases.get(queryFold);
      if (aliasTarget == null) aliasTarget = aliases.get(queryKey);
    }

    for (Contact contact : contacts) {
      if (contact == null || contact.name.trim().isEmpty()) continue;
      Score best = scoreAgainst(query, queryFold, queryKey, queryCons, queryVars, contact.name);
      if (aliasTarget != null && aliasTarget.equalsIgnoreCase(contact.name)) {
        best = best.max(new Score(0.98, "user-alias"));
      }
      if (aliases != null) {
        String byName = aliases.get(BengaliPhonetics.fold(contact.name));
        if (byName != null && BengaliPhonetics.fold(byName).equals(queryFold)) {
          best = best.max(new Score(0.98, "user-alias"));
        }
      }
      for (String extra : contact.aliases) {
        best = best.max(scoreAgainst(query, queryFold, queryKey, queryCons, queryVars, extra));
        if (aliasTarget != null && extra != null && aliasTarget.equalsIgnoreCase(extra)) {
          best = best.max(new Score(0.97, "nickname-alias"));
        }
      }
      if (best.value >= 0.45) {
        ranked.add(new Candidate(contact.name, contact.phone, clamp(best.value), best.reason));
      }
    }
    Collections.sort(ranked, new Comparator<Candidate>() {
      public int compare(Candidate a, Candidate b) {
        int byScore = Double.compare(b.score, a.score);
        if (byScore != 0) return byScore;
        return a.name.compareToIgnoreCase(b.name);
      }
    });
    if (ranked.size() > 12) ranked = new ArrayList<>(ranked.subList(0, 12));
    if (ranked.isEmpty()) return new Decision("none", null, ranked, query);

    Candidate first = ranked.get(0);
    Candidate second = ranked.size() > 1 ? ranked.get(1) : null;
    double gap = second == null ? 1.0 : first.score - second.score;
    boolean samePerson = second != null && first.phone.equals(second.phone);
    boolean ambiguous = second != null && !samePerson && gap < AUTO_GAP && second.score >= CHOOSE_SCORE;

    if (ambiguous) {
      List<Candidate> close = new ArrayList<>();
      for (Candidate c : ranked) {
        if (c.score >= CHOOSE_SCORE && first.score - c.score <= 0.18) close.add(c);
      }
      return new Decision("choose", first, close.isEmpty() ? ranked : close, query);
    }
    if (first.score >= 0.97 && (second == null || gap >= 0.05 || samePerson)) {
      return new Decision("exact", first, Collections.singletonList(first), query);
    }
    if (first.score >= AUTO_SCORE && (second == null || gap >= AUTO_GAP || samePerson)) {
      return new Decision("confident", first, Collections.singletonList(first), query);
    }
    if (first.score >= CONFIRM_SCORE) {
      return new Decision("confirm", first, Collections.singletonList(first), query);
    }
    if (second != null && first.score >= CHOOSE_SCORE) {
      return new Decision("choose", first, ranked.subList(0, Math.min(5, ranked.size())), query);
    }
    return new Decision("none", first, ranked, query);
  }

  private static final class Score {
    final double value; final String reason;
    Score(double value, String reason) { this.value = value; this.reason = reason; }
    Score max(Score other) { return other.value > value ? other : this; }
  }

  private static Score scoreAgainst(String query, String queryFold, String queryKey, String queryCons,
                                    Set<String> queryVars, String candidateName) {
    if (candidateName == null || candidateName.trim().isEmpty()) return new Score(0, "empty");
    String nameFold = BengaliPhonetics.fold(candidateName);
    if (nameFold.isEmpty()) return new Score(0, "empty");
    if (nameFold.equals(queryFold)) return new Score(1.0, "exact");
    if (stripSpaces(nameFold).equals(stripSpaces(queryFold))) return new Score(0.99, "exact-spacing");

    Set<String> nameVars = BengaliPhonetics.latinVariants(candidateName);
    String nameKey = BengaliPhonetics.phoneticKey(candidateName);
    String nameCons = BengaliPhonetics.consonantKey(nameFold);
    double best = 0;
    String reason = "weak";

    if (!queryKey.isEmpty() && queryKey.equals(nameKey)) {
      best = 0.93; reason = "phonetic-key";
    } else if (!queryCons.isEmpty() && queryCons.equals(nameCons) && queryCons.length() >= 2) {
      best = 0.88; reason = "consonant-key";
    }

    for (String q : queryVars) {
      for (String n : nameVars) {
        if (q.equals(n)) {
          if (best < 0.96) { best = 0.96; reason = "variant-exact"; }
          continue;
        }
        if (stripSpaces(q).equals(stripSpaces(n)) && best < 0.94) {
          best = 0.94; reason = "variant-spacing";
        }
        double jaro = jaroWinkler(q, n);
        if (jaro > best) { best = jaro * 0.97; reason = "jaro"; }
        double lev = levenshteinSimilarity(q, n);
        if (lev > best) { best = lev * 0.93; reason = "levenshtein"; }
      }
    }

    if (nameFold.startsWith(queryFold) || queryFold.startsWith(nameFold)) {
      int min = Math.min(nameFold.length(), queryFold.length());
      int max = Math.max(nameFold.length(), queryFold.length());
      if (min >= 3) {
        double prefix = 0.70 + 0.22 * ((double) min / max);
        if (prefix > best) { best = prefix; reason = "prefix"; }
      }
    }
    String[] queryTokens = queryFold.split(" ");
    String[] nameTokens = nameFold.split(" ");
    if (queryTokens.length > 0 && nameTokens.length > 0) {
      int hits = 0;
      for (String qt : queryTokens) {
        for (String nt : nameTokens) {
          if (qt.equals(nt) || (qt.length() >= 3 && (nt.startsWith(qt) || qt.startsWith(nt)))) hits++;
        }
      }
      if (hits > 0) {
        double token = 0.62 + 0.3 * hits / Math.max(queryTokens.length, nameTokens.length);
        if (token > best) { best = token; reason = "token"; }
      }
    }
    return new Score(clamp(best), reason);
  }

  private static String stripSpaces(String value) { return value.replace(" ", ""); }

  static double jaroWinkler(String a, String b) {
    if (a.equals(b)) return 1.0;
    if (a.isEmpty() || b.isEmpty()) return 0.0;
    int matchDist = Math.max(a.length(), b.length()) / 2 - 1;
    if (matchDist < 0) matchDist = 0;
    boolean[] aMatch = new boolean[a.length()];
    boolean[] bMatch = new boolean[b.length()];
    int matches = 0;
    for (int i = 0; i < a.length(); i++) {
      int start = Math.max(0, i - matchDist);
      int end = Math.min(i + matchDist + 1, b.length());
      for (int j = start; j < end; j++) {
        if (bMatch[j] || a.charAt(i) != b.charAt(j)) continue;
        aMatch[i] = true; bMatch[j] = true; matches++; break;
      }
    }
    if (matches == 0) return 0.0;
    int t = 0, k = 0;
    for (int i = 0; i < a.length(); i++) {
      if (!aMatch[i]) continue;
      while (!bMatch[k]) k++;
      if (a.charAt(i) != b.charAt(k)) t++;
      k++;
    }
    double m = matches;
    double jaro = (m / a.length() + m / b.length() + (m - t / 2.0) / m) / 3.0;
    int prefix = 0;
    int limit = Math.min(4, Math.min(a.length(), b.length()));
    for (int i = 0; i < limit && a.charAt(i) == b.charAt(i); i++) prefix++;
    return jaro + prefix * 0.1 * (1 - jaro);
  }

  static double levenshteinSimilarity(String a, String b) {
    int max = Math.max(a.length(), b.length());
    if (max == 0) return 1.0;
    return 1.0 - ((double) levenshtein(a, b) / max);
  }

  static int levenshtein(String a, String b) {
    int n = a.length(), m = b.length();
    int[] prev = new int[m + 1];
    int[] cur = new int[m + 1];
    for (int j = 0; j <= m; j++) prev[j] = j;
    for (int i = 1; i <= n; i++) {
      cur[0] = i;
      char ca = a.charAt(i - 1);
      for (int j = 1; j <= m; j++) {
        int cost = ca == b.charAt(j - 1) ? 0 : 1;
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      int[] tmp = prev; prev = cur; cur = tmp;
    }
    return prev[m];
  }

  private static double clamp(double value) {
    if (value < 0) return 0;
    if (value > 1) return 1;
    return value;
  }
}
