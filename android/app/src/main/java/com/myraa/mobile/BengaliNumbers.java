package com.myraa.mobile;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bengali / Banglish numbers, clock times and durations, so what the user says is what the
 * phone does.
 *
 * The speech recogniser returns Bengali digits ("৮টা ৩০"), Bengali words ("সাড়ে সাতটা",
 * "দশ মিনিট পর") and Banglish ("sat ta baje") mixed with English. Neither the model nor the
 * clock apps handle that consistently, so this class normalises it deterministically and is
 * covered by JVM unit tests.
 */
final class BengaliNumbers {

  private static final char[] BENGALI_DIGITS = {'০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯'};
  private static final char[] DEVANAGARI_DIGITS = {'०', '१', '२', '३', '४', '५', '६', '७', '८', '९'};

  private static final Map<String, Integer> WORDS = new LinkedHashMap<>();
  static {
    WORDS.put("শূন্য", 0); WORDS.put("শুন্য", 0); WORDS.put("এক", 1); WORDS.put("দুই", 2); WORDS.put("দুটো", 2); WORDS.put("দুইটা", 2);
    WORDS.put("তিন", 3); WORDS.put("চার", 4); WORDS.put("পাঁচ", 5); WORDS.put("পাচ", 5); WORDS.put("ছয়", 6); WORDS.put("ছয়টা", 6);
    WORDS.put("সাত", 7); WORDS.put("আট", 8); WORDS.put("নয়", 9); WORDS.put("নৌ", 9); WORDS.put("দশ", 10);
    WORDS.put("এগারো", 11); WORDS.put("বারো", 12); WORDS.put("তেরো", 13); WORDS.put("চৌদ্দ", 14); WORDS.put("পনেরো", 15);
    WORDS.put("ষোল", 16); WORDS.put("সতেরো", 17); WORDS.put("আঠারো", 18); WORDS.put("উনিশ", 19); WORDS.put("বিশ", 20);
    WORDS.put("একুশ", 21); WORDS.put("বাইশ", 22); WORDS.put("তেইশ", 23); WORDS.put("চব্বিশ", 24);
    WORDS.put("পঁচিশ", 25); WORDS.put("ছাব্বিশ", 26); WORDS.put("সাতাশ", 27); WORDS.put("আটাশ", 28);
    WORDS.put("উনত্রিশ", 29); WORDS.put("ত্রিশ", 30); WORDS.put("চল্লিশ", 40); WORDS.put("পঞ্চাশ", 50);
    WORDS.put("ষাট", 60); WORDS.put("সত্তর", 70); WORDS.put("আশি", 80); WORDS.put("নব্বই", 90);
    WORDS.put("শো", 100); WORDS.put("একশো", 100); WORDS.put("একশ", 100); WORDS.put("হাজার", 1000);
    WORDS.put("आठ", 8); WORDS.put("सात", 7); WORDS.put("दस", 10); WORDS.put("दो", 2); WORDS.put("एक", 1);
  }

  private static final Map<String, Integer> BANGLISH = new LinkedHashMap<>();
  static {
    BANGLISH.put("ek", 1); BANGLISH.put("dui", 2); BANGLISH.put("tin", 3); BANGLISH.put("char", 4);
    BANGLISH.put("panch", 5); BANGLISH.put("poch", 5); BANGLISH.put("choy", 6); BANGLISH.put("sat", 7);
    BANGLISH.put("aat", 8); BANGLISH.put("ath", 8); BANGLISH.put("noy", 9); BANGLISH.put("dosh", 10);
  }

  /** Words that make an hour PM. */
  private static final Pattern AFTERNOON = Pattern.compile(
      "(?i)\\b(pm|p\\.m|রাত|রাতে|সন্ধ্যা|সন্ধ্যায়|বিকেল|বিকাল|বিকেলে|দুপুর|দুপুরে|raat|rate|bikel|bikale|shondha|shondhe|dupur)\\b");
  /** Words that make an hour AM. */
  private static final Pattern MORNING = Pattern.compile(
      "(?i)\\b(am|a\\.m|সকাল|সকালে|ভোর|ভোরে|sokal|sokale|bhor|bhore)\\b");

  private BengaliNumbers() { }

  /** Converts Bengali and Devanagari digits to ASCII digits, leaving everything else alone. */
  static String digits(String input) {
    if (input == null) return "";
    StringBuilder out = new StringBuilder(input.length());
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      int bengali = indexOf(BENGALI_DIGITS, c);
      int devanagari = indexOf(DEVANAGARI_DIGITS, c);
      if (bengali >= 0) out.append((char) ('0' + bengali));
      else if (devanagari >= 0) out.append((char) ('0' + devanagari));
      else out.append(c);
    }
    return out.toString();
  }

  private static int indexOf(char[] set, char c) {
    for (int i = 0; i < set.length; i++) if (set[i] == c) return i;
    return -1;
  }

  /** The value of a spoken number, or -1 when the words are not a number. */
  static int value(String phrase) {
    if (phrase == null) return -1;
    String text = digits(phrase.toLowerCase(Locale.ROOT)).trim().replaceAll("[^\\p{L}\\p{N}\\s]", "");
    if (text.isEmpty()) return -1;
    if (text.matches("\\d{1,7}")) return Integer.parseInt(text);
    int total = 0, current = 0;
    boolean any = false;
    for (String part : text.split("\\s+")) {
      if (part.isEmpty()) continue;
      Integer word = WORDS.get(part);
      if (word == null) word = BANGLISH.get(part);
      if (word == null) return -1;
      any = true;
      if (word == 100 || word == 1000) {
        if (current == 0) current = 1;
        total += current * word;
        current = 0;
      } else current += word;
    }
    return any ? total + current : -1;
  }

  /**
   * A spoken clock time as {hour, minute} on a 24-hour clock, or null when no time was said.
   * ৮টা ৩০ / 8:30 / 8.30 pm / সাড়ে সাতটা / সোয়া সাতটা / পৌনে আটটা / দেড়টা / আড়াইটা.
   */
  static int[] time(String raw) {
    if (raw == null) return null;
    String text = digits(raw).toLowerCase(Locale.ROOT).trim();
    if (text.isEmpty()) return null;
    boolean afternoon = AFTERNOON.matcher(text).find();
    boolean morning = MORNING.matcher(text).find();

    // 1. Explicit h:mm or h.mm
    Matcher colon = Pattern.compile("(\\d{1,2})\\s*[:.]\\s*(\\d{2})").matcher(text);
    if (colon.find()) {
      int hour = Integer.parseInt(colon.group(1));
      int minute = Integer.parseInt(colon.group(2));
      if (minute > 59) return null;
      if (afternoon && hour < 12) hour += 12;
      if (morning && hour == 12) hour = 0;
      return new int[]{hour % 24, minute};
    }

    // 2. "Xটা Y" — hour, then minutes
    Matcher hm = Pattern.compile("(\\d{1,2}|[\\p{L}]+)\\s*(?:টা|টা|tar|টায়)\\s*(\\d{1,2})").matcher(text);
    if (hm.find()) {
      int hour = toInt(hm.group(1));
      int minute = Integer.parseInt(hm.group(2));
      if (hour >= 0 && hour <= 24 && minute < 60) return adjust(hour, minute, afternoon, morning);
    }

    // 3. Modifier + hour + টা
    Matcher mod = Pattern.compile(
        "(সাড়ে|সারে|সাড়ে|সোয়া|শোয়া|পৌনে|পনে|দেড়|দে\\s*ড়|আড়াই|আর|quarter|half|sare|sare|shoya|poune|pone)\\s*([\\p{L}\\d]{1,12})\\s*(?:টা|টায়|টার|বাজে|বাজায়|baje|ta)?").matcher(text);
    while (mod.find()) {
      String modifier = mod.group(1).toLowerCase(Locale.ROOT);
      int hour = toInt(mod.group(2));
      if (hour < 0 || hour > 24) continue;
      int minute = 0;
      if (modifier.startsWith("সাড়ে") || modifier.startsWith("সারে") || modifier.equals("sare") || modifier.equals("half")) minute = 30;
      else if (modifier.startsWith("সোয়া") || modifier.startsWith("শোয়া") || modifier.startsWith("শো") || modifier.equals("shoya") || modifier.equals("quarter")) minute = 15;
      else if (modifier.startsWith("পৌনে") || modifier.startsWith("পনে") || modifier.equals("poune") || modifier.equals("pone")) { hour -= 1; minute = 45; }
      else if ("আড়াই".equals(modifier)) { hour = 2; minute = 30; }
      else if (modifier.startsWith("দেড়") || modifier.startsWith("দে")) { if (hour == 1) { minute = 30; } }
      return adjust(hour, minute, afternoon, morning);
    }

    // 4. A bare hour with a clock word: "সাতটায়", "8 baje"
    Matcher bare = Pattern.compile("(\\d{1,2}|[\\p{L}]+)\\s*(?:টা|টায়|টার|টাতে|baje|bajay|o.?clock)\\b").matcher(text);
    if (bare.find()) {
      int hour = toInt(bare.group(1));
      if (hour >= 0 && hour <= 24) return adjust(hour, 0, afternoon, morning);
    }
    return null;
  }

  private static int[] adjust(int hour, int minute, boolean afternoon, boolean morning) {
    int h = hour;
    if (h < 0) h = 0;
    if (afternoon && h < 12) h += 12;
    if (morning && h == 12) h = 0;
    if (h == 24) h = 0;
    if (h > 23) h = h % 24;
    if (minute < 0 || minute > 59) minute = 0;
    return new int[]{h, minute};
  }

  private static int toInt(String token) {
    if (token == null) return -1;
    String value = token.trim();
    if (value.matches("\\d{1,4}")) return Integer.parseInt(value);
    return value(value);
  }

  /** Minutes from a spoken duration ("দশ মিনিট", "2 ঘণ্টা", "এক ঘণ্টা ত্রিশ মিনিট"), or -1. */
  static int durationMinutes(String raw) {
    if (raw == null) return -1;
    String text = digits(raw).toLowerCase(Locale.ROOT);
    Matcher matcher = Pattern.compile(
        "(\\d{1,4}|[\\p{L}]+)\\s*(ঘন্টা|ঘণ্টা|ghonta|ghanta|hours?|hrs?|hr|মিনিট|মিন\\b|minute|minutes|mins?|min)").matcher(text);
    int minutes = -1;
    while (matcher.find()) {
      int amount = toInt(matcher.group(1));
      if (amount < 0) continue;
      String unit = matcher.group(2);
      boolean hour = unit.startsWith("ঘণ") || unit.startsWith("ঘন") || unit.equals("ghonta") || unit.equals("ghanta")
        || unit.startsWith("hour") || unit.startsWith("hr");
      minutes = (minutes < 0 ? 0 : minutes) + (hour ? amount * 60 : amount);
    }
    return minutes;
  }

  /** One normalised line the model can rely on, or "" when nothing numeric was said. */
  static String describe(String raw) {
    if (raw == null || raw.trim().isEmpty()) return "";
    StringBuilder out = new StringBuilder();
    int[] clock = time(raw);
    if (clock != null) out.append("clock=").append(String.format(Locale.US, "%02d:%02d", clock[0], clock[1]));
    int minutes = durationMinutes(raw);
    if (minutes > 0) {
      if (out.length() > 0) out.append(", ");
      out.append("durationMinutes=").append(minutes);
    }
    String plain = digits(raw);
    if (out.length() == 0 && plain.matches("(?s).*\\d.*")) {
      String only = plain.replaceAll("[^0-9+]", " ").trim().replaceAll("\\s+", " ");
      if (!only.isEmpty()) out.append("digits=").append(only);
    }
    return out.toString();
  }
}
