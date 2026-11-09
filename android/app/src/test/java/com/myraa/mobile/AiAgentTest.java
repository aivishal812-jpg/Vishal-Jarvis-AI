package com.myraa.mobile;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class AiAgentTest {
  @Test public void detectsResearchInEnglishBengaliAndHindi() {
    assertTrue(AiAgent.needsResearch("Research the latest train news today"));
    assertTrue(AiAgent.needsResearch("আজকের আবহাওয়া কেমন?"));
    assertTrue(AiAgent.needsResearch("आज का मौसम बताओ"));
    assertFalse(AiAgent.needsResearch("Explain the Pythagorean theorem"));
    assertFalse(AiAgent.needsResearch(null));
  }
  @Test public void deviceTasksKeepTheirToolsEvenWhenTheyMentionToday() {
    // "আজকের আবহাওয়া" used to be treated as pure research, so the weather action was never called.
    assertTrue(AiAgent.mentionsDeviceAction("আজকের আবহাওয়া বলো"));
    assertTrue(AiAgent.mentionsDeviceAction("what is the weather? use the weather action"));
    assertTrue(AiAgent.mentionsDeviceAction("remind me in 10 minutes"));
    assertTrue(AiAgent.mentionsDeviceAction("torch jalao"));
    assertTrue(AiAgent.mentionsDeviceAction("টর্চ জ্বালাও"));
    assertFalse(AiAgent.mentionsDeviceAction("আজকের খবর কী?"));
    assertFalse(AiAgent.mentionsDeviceAction("Explain the Pythagorean theorem"));
    assertFalse(AiAgent.mentionsDeviceAction(null));
  }

  @Test public void retriesOnlyTransientNetworkAndProviderFailures() {
    assertTrue(AiAgent.retryable(new IOException("offline")));
    assertTrue(AiAgent.retryable(new GeminiAccess.Failure("QUOTA", 429, "quota")));
    assertTrue(AiAgent.retryable(new GeminiAccess.Failure("UNAVAILABLE", 503, "unavailable")));
    assertFalse(AiAgent.retryable(new GeminiAccess.Failure("KEY", 403, "invalid key")));
    assertFalse(AiAgent.retryable(new IllegalArgumentException("bad configuration")));
  }
  @Test public void safeErrorsDoNotExposeProviderPayloadOrCredentials() {
    assertFalse(AiAgent.safeMessage(new IOException("secret key in URL")).contains("secret"));
    assertFalse(AiAgent.safeMessage(new IllegalArgumentException("password")).contains("password"));
  }
}
