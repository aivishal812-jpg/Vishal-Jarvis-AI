package com.myraa.mobile;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Interrupting Jarvis mid-answer is only useful if it never interrupts itself. These rules are the
 * difference between "I can cut in any time" and a voice that stops after every second word.
 */
public class BargeInTest {

  @Test public void aRealSentenceInterrupts() {
    assertTrue(BargeIn.interrupts("আমার নাম কি", ""));
    assertTrue(BargeIn.interrupts("wait, that is wrong", ""));
    assertTrue(BargeIn.interrupts("বাবাকে কল করো", ""));
  }

  @Test public void acknowledgementsAndNoiseDoNotInterrupt() {
    for (String heard : new String[]{ "ok", "okay", "হুম", "আচ্ছা", "ঠিক আছে", "thanks", "ধন্যবাদ",
        "হ্যাঁ", "yes", "", "  ", "...", "a" }) {
      assertFalse("must not interrupt: " + heard, BargeIn.interrupts(heard, ""));
    }
  }

  @Test public void jarvissOwnVoiceNeverInterrupts() {
    String speaking = "আজকের আবহাওয়া ভালো আছে, তুমি বাইরে যেতে পারো।";
    // The engine's echo of its own words must be ignored.
    assertFalse(BargeIn.interrupts("আজকের আবহাওয়া", speaking));
    assertFalse(BargeIn.interrupts("তুমি বাইরে যেতে পারো", speaking));
    // A genuinely new request in the same language still works.
    assertTrue(BargeIn.interrupts("না, কালকের আবহাওয়া বলো", speaking));
  }

  @Test public void theMicrophoneIsNotOpenedOnTheFirstSyllable() {
    assertTrue(BargeIn.SETTLE_MS >= 500);
  }
}
