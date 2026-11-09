package com.myraa.mobile;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The call assistant talks into a live phone call, so its rules are tested like a safety feature:
 * it must never answer itself, never repeat itself, never speak a secret, and never invent an
 * answer out of an unfinished sentence.
 */
public class CallCoachTest {

  @Test public void modesAreExplicitAndOffByDefaultString() {
    assertEquals(CallCoach.Mode.SPEAK, CallCoach.Mode.of("speak"));
    assertEquals(CallCoach.Mode.SUGGEST, CallCoach.Mode.of("suggest"));
    assertEquals(CallCoach.Mode.OFF, CallCoach.Mode.of("off"));
    // An unknown or missing value must not become "speak": silence is the safe default.
    assertEquals(CallCoach.Mode.SUGGEST, CallCoach.Mode.of(null));
    assertEquals(CallCoach.Mode.SUGGEST, CallCoach.Mode.of("loud"));
  }

  @Test public void ownWordsAreRemovedFromTheTranscript() {
    String transcript = "Caller: দাম কত?\nVishal: ২৫০ টাকা\nCaller: ঠিক আছে\n";
    String filtered = CallCoach.withoutOwnWords(transcript, "Vishal");
    assertFalse("Jarvis must not answer its own line", filtered.contains("২৫০"));
    assertTrue(filtered.contains("দাম কত"));
    assertTrue(filtered.contains("ঠিক আছে"));
  }

  @Test public void onlyWholeSentencesAreAnswered() {
    assertEquals("", CallCoach.lastSentence("আমি আজ"));
    assertEquals("দাম কত?", CallCoach.lastSentence("আমি আজ বাজারে গিয়েছিলাম। দাম কত?"));
    assertEquals("How much is it?", CallCoach.lastSentence("I went to the market. How much is it?"));
    // A danda ends a Bengali sentence just like a full stop.
    assertEquals("ঠিক আছে।", CallCoach.lastSentence("পরে কথা হবে। ঠিক আছে।"));
  }

  @Test public void theSameSentenceIsNeverAnsweredTwice() {
    String[] answered = { "দাম কত?" };
    assertTrue(CallCoach.alreadyAnswered(answered, "দাম  কত ?"));
    assertFalse(CallCoach.alreadyAnswered(answered, "কোথায় যাব?"));
  }

  @Test public void secretsAreNeverSpoken() {
    for (String secret : new String[]{ "আপনার OTP টা বলুন", "what is your PIN", "পাসওয়ার্ড বলো",
        "card number দাও", "cvv বলো" }) {
      assertTrue("must be refused: " + secret, CallCoach.forbidden(secret));
    }
    assertFalse(CallCoach.forbidden("আপনি কেমন আছেন?"));
  }

  @Test public void questionsAreAnsweredAndSmallTalkIsNot() {
    assertTrue(CallCoach.needsHelp("আপনার দাম কত?"));
    assertTrue(CallCoach.needsHelp("where are you now?"));
    assertTrue(CallCoach.needsHelp("can you send it today"));
    assertFalse(CallCoach.needsHelp("হ্যাঁ ঠিক আছে"));
    assertFalse(CallCoach.needsHelp("ok"));
  }

  @Test public void thePromptCarriesBothSidesAndOneInstruction() throws Exception {
    String conversation = CallCoach.conversation("Caller: দাম কত?\n", false);
    assertTrue(conversation.contains("দাম কত"));
    assertTrue(conversation.contains("Reply with only the words to say"));
    assertTrue(CallCoach.INSTRUCTION.contains("never read out"));
    // The instruction must forbid tools: a live call must never press buttons on the phone.
    assertFalse(CallCoach.INSTRUCTION.toLowerCase(java.util.Locale.ROOT).contains("call the tool"));
  }

  @Test public void theRequestBodyHasNoToolsAndALimit() throws Exception {
    JSONObject body = QuickGemini.body("rule", "Caller: hello?");
    assertFalse("a live call must not be able to run phone tools", body.has("tools"));
    assertFalse(body.has("toolConfig"));
    assertEquals(400, body.getJSONObject("generationConfig").getInt("maxOutputTokens"));
    assertTrue(body.toString().contains("Caller: hello?"));
  }

  @Test public void answersAreReadFromRealPayloadsOnly() {
    String payload = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"**২৫০ টাকা**\"},"
      + "{\"thought\":true,\"text\":\"thinking\"}]}}]}";
    assertEquals("২৫০ টাকা", QuickGemini.answer(payload));
    assertEquals("", QuickGemini.answer("{}"));
    assertEquals("", QuickGemini.answer("not json"));
    // Provider sentence limits never reach the call: the answer is capped.
    StringBuilder longText = new StringBuilder();
    for (int i = 0; i < 200; i++) longText.append("শব্দ ");
    String huge = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + longText + "\"}]}}]}";
    assertTrue(QuickGemini.answer(huge).length() <= 600);
  }

  @Test public void statusLinesDescribeTheRealMode() {
    assertTrue(CallCoach.statusLine(CallCoach.Mode.OFF).contains("off"));
    assertTrue(CallCoach.statusLine(CallCoach.Mode.SPEAK).contains("out loud"));
    assertTrue(CallCoach.statusLine(CallCoach.Mode.SUGGEST).contains("speaker"));
  }
}
