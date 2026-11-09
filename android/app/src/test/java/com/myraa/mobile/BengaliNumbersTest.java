package com.myraa.mobile;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** What the user says about times and amounts has to become the right clock value. */
public class BengaliNumbersTest {

  @Test public void bengaliDigitsBecomeAscii() {
    assertEquals("8:30", BengaliNumbers.digits("৮:৩০"));
    assertEquals("2026-11-09", BengaliNumbers.digits("২০২৬-১১-০৯"));
    assertEquals("hi 42 ok", BengaliNumbers.digits("hi ४२ ok"));
  }

  @Test public void dotClockReadsTheHourAndMinute() {
    int[] time = BengaliNumbers.time("৮টা ৩০");
    assertNotNull(time);
    assertEquals(8, time[0]);
    assertEquals(30, time[1]);
  }

  @Test public void englishClockWorks() {
    assertTime("8:30", 8, 30);
    assertTime("8.30 pm", 20, 30);
    assertTime("12:05 am", 0, 5);
  }

  @Test public void halfAndQuarterWordsAreHandled() {
    assertTime("সাড়ে সাতটা", 7, 30);
    assertTime("পৌনে আটটা", 7, 45);
    assertTime("সোয়া সাতটা", 7, 15);
    assertTime("দেড়টা", 1, 30);
  }

  @Test public void eveningWordsPushTheHourIntoTheAfternoon() {
    assertTime("সন্ধ্যা ৬টা", 18, 0);
    assertTime("রাত ৯টা", 21, 0);
    assertTime("সকাল ৯টা", 9, 0);
  }

  @Test public void spokenNumbersAddUp() {
    assertEquals(7, BengaliNumbers.value("সাত"));
    assertEquals(30, BengaliNumbers.value("ত্রিশ"));
    assertEquals(100, BengaliNumbers.value("একশো"));
    assertEquals(2500, BengaliNumbers.value("দুই হাজার পাঁচশো"));
    assertEquals(-1, BengaliNumbers.value("আজ আবহাওয়া"));
  }

  @Test public void durationsUnderstandHoursAndMinutes() {
    assertEquals(10, BengaliNumbers.durationMinutes("দশ মিনিট পর"));
    assertEquals(120, BengaliNumbers.durationMinutes("2 ঘণ্টা"));
    assertEquals(90, BengaliNumbers.durationMinutes("এক ঘণ্টা ত্রিশ মিনিট"));
    assertEquals(-1, BengaliNumbers.durationMinutes("kal dekha hobe"));
  }

  @Test public void proseGivesNoTime() {
    assertNull(BengaliNumbers.time("আজ আবহাওয়া কেমন?"));
    assertNull(BengaliNumbers.time("what is the weather"));
    assertNull(BengaliNumbers.time("আজকের আবহাওয়া কেমন"));
    assertNull(BengaliNumbers.time("১০ মিনিট পরে মনে করিয়ে দিও"));
    assertNull(BengaliNumbers.time("2 + 2 কত"));
    assertNull(BengaliNumbers.time("আগামীকাল ৫ তারিখে"));
    assertEquals("", BengaliNumbers.describe("kal dekha hobe"));
  }

  @Test public void durationsAreNotClockTimes() {
    assertNull(BengaliNumbers.time("বিশ মিনিট পর"));
    assertEquals(20, BengaliNumbers.durationMinutes("বিশ মিনিট পর"));
  }

  @Test public void describeProducesTheNormalisedLine() {
    assertEquals("clock=08:30", BengaliNumbers.describe("৮টা ৩০ এ remind korো"));
    assertEquals("clock=20:45", BengaliNumbers.describe("রাত ৮টা ৪৫"));
  }

  private static void assertTime(String phrase, int hour, int minute) {
    int[] time = BengaliNumbers.time(phrase);
    assertNotNull("no time parsed from " + phrase, time);
    assertEquals(phrase + " hour", hour, time[0]);
    assertEquals(phrase + " minute", minute, time[1]);
  }
}
