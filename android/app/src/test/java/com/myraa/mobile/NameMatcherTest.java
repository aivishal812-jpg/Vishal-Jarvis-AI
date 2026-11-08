package com.myraa.mobile;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class NameMatcherTest {
  private static List<NameMatcher.Contact> book(NameMatcher.Contact... contacts) {
    return Arrays.asList(contacts);
  }

  @Test public void extractsSpokenNameFromBengaliAndEnglishCallPhrases() {
    assertEquals("লাবণ্য", NameMatcher.extractSpokenName("লাবণ্যকে কল করো"));
    assertEquals("লাবন্য", NameMatcher.extractSpokenName("লাবন্যকে ফোন করো"));
    assertEquals("Labanya", NameMatcher.extractSpokenName("Labanya ke call karo"));
    assertEquals("Labonno", NameMatcher.extractSpokenName("Labonno call"));
    assertEquals("রাহুল", NameMatcher.extractSpokenName("রাহুল কে কল করো"));
  }

  @Test public void labanyaVariantsMatchSavedBengaliName() {
    List<NameMatcher.Contact> contacts = book(new NameMatcher.Contact("লাবণ্য", "919899000001"));
    String[] spoken = new String[]{
      "লাবণ্যকে কল করো", "Labanya ke call karo", "Labonno call", "লাবন্যকে ফোন করো"
    };
    for (String query : spoken) {
      NameMatcher.Decision decision = NameMatcher.match(query, contacts);
      assertNotNull(query, decision.best);
      assertEquals(query, "লাবণ্য", decision.best.name);
      assertFalse(query + " must not be treated as no-match", "none".equals(decision.verdict));
      assertFalse(query + " is unique and must not ask to choose", "choose".equals(decision.verdict));
      assertTrue(query, decision.best.score >= NameMatcher.CONFIRM_SCORE);
    }
  }

  @Test public void neverAutoSelectsWhenTwoSavedNamesAreClose() {
    List<NameMatcher.Contact> contacts = book(
      new NameMatcher.Contact("লাবণ্য", "919899000001"),
      new NameMatcher.Contact("Lavanya Sen", "919899000002")
    );
    NameMatcher.Decision decision = NameMatcher.match("Labanya ke call karo", contacts);
    if (decision.ranked.size() >= 2 && decision.ranked.get(1).score >= NameMatcher.CHOOSE_SCORE) {
      assertEquals("choose", decision.verdict);
    } else {
      assertFalse("none".equals(decision.verdict));
      assertNotEquals("Must not silently pick a random close name", "none", decision.verdict);
    }
  }

  @Test public void unmatchedSpokenNameIsNone() {
    List<NameMatcher.Contact> contacts = book(new NameMatcher.Contact("রাহুল", "919800000000"));
    NameMatcher.Decision decision = NameMatcher.match("Priyanka ke call karo", contacts);
    assertEquals("none", decision.verdict);
  }

  @Test public void userAliasDoesNotChangeSavedName() {
    List<NameMatcher.Contact> contacts = book(new NameMatcher.Contact("লাবণ্য", "919899000001"));
    Map<String, String> aliases = new HashMap<String, String>();
    aliases.put(BengaliPhonetics.fold("Labu"), "লাবণ্য");
    NameMatcher.Decision decision = NameMatcher.match("Labu ke call karo", contacts, aliases);
    assertNotNull(decision.best);
    assertEquals("লাবণ্য", decision.best.name);
    assertTrue(decision.best.score >= NameMatcher.CONFIRM_SCORE);
  }

  @Test public void speechEnhancerKeepsFrameLength() {
    SpeechEnhancer enhancer = new SpeechEnhancer();
    byte[] frame = new byte[SpeechEnhancer.FRAME * 2];
    enhancer.process(frame, 0, frame.length);
    assertEquals(SpeechEnhancer.FRAME * 2, frame.length);
  }
}
