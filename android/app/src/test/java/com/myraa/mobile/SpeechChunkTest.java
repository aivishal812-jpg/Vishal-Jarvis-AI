package com.myraa.mobile;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The reply must never be cut off. The old code truncated at the engine limit (about 4,000
 * characters); these tests pin the behaviour that replaced it.
 */
public class SpeechChunkTest {

  @Test public void longReplyIsSplitAndNothingIsLost() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 400; i++) text.append("This is sentence number ").append(i).append(". ");
    String reply = text.toString().trim();
    List<String> chunks = SpeechEngine.split(reply);
    assertTrue("a long reply must be split", chunks.size() > 1);
    StringBuilder rejoined = new StringBuilder();
    for (String chunk : chunks) rejoined.append(chunk).append(' ');
    assertEqualsIgnoringSpace(reply, rejoined.toString());
  }

  @Test public void everyChunkStaysUnderTheEngineLimit() {
    StringBuilder text = new StringBuilder();
    while (text.length() < 20000) text.append("বাংলায় লেখা একটি লম্বা বাক্য যাতে থামা নেই ");
    for (String chunk : SpeechEngine.split(text.toString())) {
      assertTrue("chunk too long: " + chunk.length(), chunk.length() < 4000);
    }
  }

  @Test public void bengaliDandaEndsASentence() {
    List<String> chunks = SpeechEngine.split("প্রথম বাক্য। দ্বিতীয় বাক্য।");
    assertFalse(chunks.isEmpty());
    assertTrue(chunks.get(0).endsWith("।"));
  }

  @Test public void shortReplyIsOneChunk() {
    List<String> chunks = SpeechEngine.split("হ্যাঁ, করে দিলাম।");
    assertEquals(1, chunks.size());
  }

  @Test public void emptyTextGivesNoChunks() {
    assertTrue(SpeechEngine.split("   ").isEmpty());
    org.junit.Assert.assertTrue(SpeechEngine.split(null).isEmpty());
  }

  private static void assertEqualsIgnoringSpace(String a, String b) {
    assertEquals(a.replaceAll("\\s+", ""), b.replaceAll("\\s+", ""));
  }

  private static void assertEquals(String a, String b) {
    org.junit.Assert.assertEquals(a, b);
  }
}
