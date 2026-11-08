package com.myraa.mobile;

import java.util.*;

/**
 * Bengali ↔ Latin phonetic helpers for contact names.
 * Generates spelling variants so spoken English, Banglish and Bengali
 * can match a saved contact without requiring exact orthography.
 */
public final class BengaliPhonetics {
  private BengaliPhonetics() {}

  public static boolean hasBengali(String text) {
    if (text == null) return false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c >= 0x0980 && c <= 0x09FF) return true;
    }
    return false;
  }

  public static String fold(String input) {
    if (input == null) return "";
    StringBuilder out = new StringBuilder(input.length());
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (Character.isLetterOrDigit(c) || (c >= 0x0980 && c <= 0x09FF)) {
        out.append(Character.toLowerCase(c));
      } else if (Character.isWhitespace(c) || c == '-' || c == '\'' || c == '.' || c == '।') {
        if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
      }
    }
    return out.toString().trim().replaceAll(" +", " ");
  }

  /** Primary Latin rendering plus common West Bengal pronunciations. */
  public static Set<String> latinVariants(String input) {
    LinkedHashSet<String> variants = new LinkedHashSet<>();
    String folded = fold(input);
    if (folded.isEmpty()) return variants;
    if (!hasBengali(folded)) {
      String latin = folded;
      variants.add(latin);
      addLatinSpellingVariants(latin, variants);
      return variants;
    }
    List<String> rendered = transliterate(folded);
    for (String item : rendered) {
      if (item.isEmpty()) continue;
      variants.add(item);
      addLatinSpellingVariants(item, variants);
    }
    return variants;
  }

  public static String phoneticKey(String input) {
    String latin = latinVariants(input).isEmpty() ? fold(input) : latinVariants(input).iterator().next();
    return compactKey(latin);
  }

  public static String compactKey(String latin) {
    if (latin == null) return "";
    String s = latin.toLowerCase(Locale.ROOT);
    s = s.replace("kh", "k").replace("gh", "g").replace("ph", "f").replace("bh", "b")
      .replace("dh", "d").replace("th", "t").replace("ch", "c").replace("sh", "s")
      .replace("zh", "j").replace("aa", "a").replace("ee", "i").replace("oo", "u")
      .replace("ou", "o").replace("au", "o").replace("oi", "o").replace("ai", "a");
    s = s.replace("nya", "n").replace("nno", "n").replace("nna", "n").replace("nyo", "n")
      .replace("nia", "n").replace("nn", "n").replace("ny", "n");
    s = s.replace('v', 'b').replace('w', 'b').replace('z', 'j').replace('q', 'k')
      .replace('c', 'k').replace('y', 'i').replace('x', 'k');
    s = s.replace('o', 'a').replace('e', 'a');
    s = s.replaceAll("(.)\\1+", "$1");
    s = s.replaceAll("[^a-z0-9]+", "");
    return s;
  }

  public static String consonantKey(String latin) {
    String key = compactKey(latin);
    return key.replaceAll("[aeiou]+", "");
  }

  private static void addLatinSpellingVariants(String latin, Set<String> variants) {
    String s = latin.toLowerCase(Locale.ROOT);
    variants.add(s.replace('v', 'b'));
    variants.add(s.replace('b', 'v'));
    variants.add(s.replace("ph", "f"));
    variants.add(s.replace('y', 'i'));
    variants.add(s.replace("ya", "ia"));
    variants.add(s.replace("nya", "nna"));
    variants.add(s.replace("nya", "nno"));
    variants.add(s.replace("nno", "nya"));
    variants.add(s.replace("nna", "nya"));
    variants.add(s.replace('o', 'a'));
    variants.add(s.replace('a', 'o'));
    if (s.endsWith("ya")) variants.add(s.substring(0, s.length() - 2) + "o");
    if (s.endsWith("ya")) variants.add(s.substring(0, s.length() - 2) + "no");
    if (s.endsWith("iya")) variants.add(s.substring(0, s.length() - 3) + "i");
    if (s.endsWith("a") && s.length() > 2) variants.add(s.substring(0, s.length() - 1) + "o");
    if (s.endsWith("o") && s.length() > 2) variants.add(s.substring(0, s.length() - 1) + "a");
    if (s.endsWith("i") && s.length() > 2) variants.add(s + "a");
    variants.add(s.replace("ee", "i").replace("oo", "u"));
    // Bound growth so ranking stays real-time on large address books.
    if (variants.size() > 48) {
      Iterator<String> it = variants.iterator();
      int keep = 0;
      while (it.hasNext()) {
        it.next();
        keep++;
        if (keep > 48) it.remove();
      }
    }
  }

  private static List<String> transliterate(String bengali) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    StringBuilder primary = new StringBuilder();
    StringBuilder alt = new StringBuilder();
    int i = 0;
    while (i < bengali.length()) {
      char c = bengali.charAt(i);
      if (c == ' ' || c == '-' || c == '.') {
        primary.append(' '); alt.append(' '); i++; continue;
      }
      int[] skip = {1};
      String[] pair = mapCluster(bengali, i, skip);
      primary.append(pair[0]);
      alt.append(pair[1]);
      i += skip[0];
    }
    out.add(fold(primary.toString()));
    out.add(fold(alt.toString()));
    out.add(fold(primary.toString().replace("nya", "nno")));
    out.add(fold(primary.toString().replace("nya", "nna")));
    out.add(fold(alt.toString().replace("bo", "ba").replace("no", "na")));
    return new ArrayList<>(out);
  }

  /** Returns {a-inherent, o-inherent} Latin for the grapheme at i. */
  private static String[] mapCluster(String text, int i, int[] consumed) {
    char c = text.charAt(i);
    String[] independent = independentVowel(c);
    if (independent != null) { consumed[0] = 1; return independent; }
    String cons = consonant(c);
    if (cons == null) {
      consumed[0] = 1;
      return new String[]{"", ""};
    }
    int j = i + 1;
    String extra = "";
    while (j < text.length() && text.charAt(j) == 0x09CD && j + 1 < text.length()) {
      char next = text.charAt(j + 1);
      String joined = consonant(next);
      if (joined == null) break;
      if (next == 0x09AF) extra += "y";          // য-ফলা
      else if (next == 0x09B0) extra += "r";     // র-ফলা
      else if (next == 0x09AC) extra += "w";     // ব-ফলা
      else extra += joined;
      j += 2;
    }
    String vowelA = "a";
    String vowelO = "o";
    if (j < text.length()) {
      String[] dep = dependentVowel(text.charAt(j));
      if (dep != null) {
        vowelA = dep[0];
        vowelO = dep[1];
        j++;
      } else if (text.charAt(j) == 0x09CD) {
        vowelA = "";
        vowelO = "";
        j++;
      }
    }
    consumed[0] = j - i;
    if ("y".equals(extra) && vowelA.isEmpty()) {
      return new String[]{cons + "ya", cons + "yo"};
    }
    if ("y".equals(extra)) {
      // ণ্য / ন্য often spoken as "nno" in West Bengal names such as লাবণ্য.
      if ("n".equals(cons) || "n".equals(cons)) {
        return new String[]{cons + extra + vowelA, cons + "n" + (vowelO.isEmpty() ? "o" : vowelO)};
      }
      return new String[]{cons + extra + vowelA, cons + extra + vowelO};
    }
    return new String[]{cons + extra + vowelA, cons + extra + vowelO};
  }

  private static String[] independentVowel(char c) {
    switch (c) {
      case 0x0985: return new String[]{"a", "o"};
      case 0x0986: return new String[]{"a", "aa"};
      case 0x0987: return new String[]{"i", "i"};
      case 0x0988: return new String[]{"i", "ee"};
      case 0x0989: return new String[]{"u", "u"};
      case 0x098A: return new String[]{"u", "oo"};
      case 0x098B: return new String[]{"ri", "ri"};
      case 0x098F: return new String[]{"e", "e"};
      case 0x0990: return new String[]{"oi", "ai"};
      case 0x0993: return new String[]{"o", "o"};
      case 0x0994: return new String[]{"ou", "au"};
      default: return null;
    }
  }

  private static String[] dependentVowel(char c) {
    switch (c) {
      case 0x09BE: return new String[]{"a", "aa"};
      case 0x09BF: return new String[]{"i", "i"};
      case 0x09C0: return new String[]{"i", "ee"};
      case 0x09C1: return new String[]{"u", "u"};
      case 0x09C2: return new String[]{"u", "oo"};
      case 0x09C3: return new String[]{"ri", "ri"};
      case 0x09C7: return new String[]{"e", "e"};
      case 0x09C8: return new String[]{"oi", "ai"};
      case 0x09CB: return new String[]{"o", "o"};
      case 0x09CC: return new String[]{"ou", "au"};
      case 0x09D7: return new String[]{"ou", "au"};
      default: return null;
    }
  }

  private static String consonant(char c) {
    switch (c) {
      case 0x0995: return "k";
      case 0x0996: return "kh";
      case 0x0997: return "g";
      case 0x0998: return "gh";
      case 0x0999: return "ng";
      case 0x099A: return "ch";
      case 0x099B: return "chh";
      case 0x099C: return "j";
      case 0x099D: return "jh";
      case 0x099E: return "n";
      case 0x099F: return "t";
      case 0x09A0: return "th";
      case 0x09A1: return "d";
      case 0x09A2: return "dh";
      case 0x09A3: return "n";
      case 0x09A4: return "t";
      case 0x09A5: return "th";
      case 0x09A6: return "d";
      case 0x09A7: return "dh";
      case 0x09A8: return "n";
      case 0x09AA: return "p";
      case 0x09AB: return "ph";
      case 0x09AC: return "b";
      case 0x09AD: return "bh";
      case 0x09AE: return "m";
      case 0x09AF: return "j";
      case 0x09B0: return "r";
      case 0x09B2: return "l";
      case 0x09B6: return "sh";
      case 0x09B7: return "sh";
      case 0x09B8: return "s";
      case 0x09B9: return "h";
      case 0x09DC: return "r";
      case 0x09DD: return "rh";
      case 0x09DF: return "y";
      case 0x09CE: return "t";
      default: return null;
    }
  }
}
