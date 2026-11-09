package com.myraa.mobile;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The background whitelist is the promise that a lock-screen request is really carried out.
 * These tests fail if an action is described to the model but cannot actually run headless.
 */
public class ActionRegistryTest {

  @Test public void lockScreenEssentialsRunWithoutAnActivity() {
    String[] must = { "set_reminder", "list_reminders", "cancel_reminder", "save_note", "list_notes",
      "delete_note", "get_weather", "daily_briefing", "device_status", "set_flashlight",
      "set_volume", "media_control", "read_notifications", "clear_notifications", "read_calendar",
      "list_apps", "what_did_i_say", "missed_calls", "otp_summary", "screen_info" };
    for (String action : must) {
      assertTrue("background-safe action missing: " + action, ActionRegistry.backgroundSafe(action));
    }
  }

  @Test public void uiOnlyActionsAreNotPretendedToWork() {
    String[] needsWindow = { "call", "send_message", "open_app", "create_event", "delete_calendar_event",
      "ring_phone", "open_jarvis_settings", "navigate_to", "share_text", "set_alarm" };
    for (String action : needsWindow) {
      assertFalse("must need the window: " + action, ActionRegistry.backgroundSafe(action));
    }
  }

  @Test public void existingWindowActionsKeepTheirWindowHandler() {
    // These all have a UI implementation that asks for a permission, shows a chooser or confirms
    // with the user. They must never be answered by the headless registry while the app is open,
    // because that is what broke the flashlight, volume, reminders and SIM details.
    String[] haveWindowHandler = { "set_flashlight", "set_volume", "media_control", "device_status",
      "sim_info", "set_reminder", "list_reminders", "cancel_reminder", "save_note", "list_notes",
      "delete_note", "read_notifications", "clear_notifications", "read_calendar", "get_weather",
      "daily_briefing", "open_app", "call", "send_message", "create_event" };
    for (String action : haveWindowHandler) {
      assertFalse("must keep the window handler: " + action, ActionRegistry.newOnly(action));
    }
  }

  @Test public void newHeadlessToolsAreAnsweredByTheRegistry() {
    String[] headless = { "read_screen", "read_last_message", "fill_form", "screen_info",
      "what_did_i_say", "missed_calls", "otp_summary", "save_memory_summary" };
    for (String action : headless) assertTrue("registry must answer: " + action, ActionRegistry.newOnly(action));
  }

  @Test public void unknownOrEmptyActionsAreRejected() {
    assertFalse(ActionRegistry.backgroundSafe(""));
    assertFalse(ActionRegistry.backgroundSafe(null));
    assertFalse(ActionRegistry.backgroundSafe("not_a_real_action"));
  }
}
