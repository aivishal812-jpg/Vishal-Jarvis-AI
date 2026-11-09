package com.myraa.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Jarvis used to answer "I cannot do that" about things the app has supported for a while
 * (screenshots, the call assistant, opening apps). That is a prompt bug, not a model whim, so the
 * prompt itself is tested.
 */
public class PromptHonestyTest {

  private static String prompt() {
    return MobileTools.systemPrompt("You are Vishal Jarvis.", new JSONArray(), "", "");
  }

  @Test public void thePromptNeverListsARealAbilityAsImpossible() {
    String text = prompt().toLowerCase(java.util.Locale.ROOT);
    // The exact sentence that used to make Jarvis refuse screenshots and files.
    assertFalse("the old false limit came back: 'cannot take screenshots'", text.contains("cannot take screenshots"));
    assertFalse(text.contains("cannot read files"));
  }

  @Test public void theCallAssistantIsDescribed() {
    String text = prompt();
    assertTrue("the call assistant must be described", text.contains("## Phone calls: answering and taking part"));
    assertTrue(text.contains("pick up a ringing call"));
    assertTrue(text.contains("call's own audio line"));
    assertTrue("the honest limit must be stated, not hidden", text.contains("speakerphone"));
  }

  @Test public void capabilityListCoversTheSwitchesThatActuallyExist() {
    String text = prompt();
    for (String ability : new String[]{ "Opening apps", "screen reading", "PDF reading", "long-term memory",
        "wake word", "call assistant", "WhatsApp" }) {
      assertTrue("capability missing from the prompt: " + ability, text.contains(ability));
    }
    assertTrue("denying a supported ability must be called out as a bug", text.contains("Denying one of them is a bug"));
  }

  @Test public void everyAnnouncedActionIsDispatchable() throws Exception {
    JSONObject action = null;
    JSONArray declarations = MobileTools.declarations();
    for (int i = 0; i < declarations.length(); i++) {
      JSONObject item = declarations.optJSONObject(i);
      if (item != null && MobileTools.ACTION.equals(item.optString("name"))) action = item;
    }
    assertTrue("mobile_action must be declared", action != null);
    JSONObject properties = action.getJSONObject("parameters").getJSONObject("properties");
    JSONArray values = properties.getJSONObject("action").getJSONArray("enum");
    Set<String> names = new HashSet<>();
    for (int i = 0; i < values.length(); i++) names.add(values.optString(i));

    // Anything the prompt promises in the call section must be a real action.
    assertTrue("call_assistant must be a declared action", names.contains("call_assistant"));
    // The actions the prompt names in its capability list must all exist.
    for (String named : new String[]{ "open_app", "read_screen", "what_did_i_say", "set_reminder", "send_message", "call" }) {
      assertTrue("prompt names an action that is not declared: " + named, names.contains(named));
    }
    // A live call must never be reachable from the background path with its own tool.
    assertFalse("call_assistant must stay a window action", ActionRegistry.newOnly("call_assistant"));
    assertFalse("call_assistant must not be announced to the headless job", BackgroundActions.supported("call_assistant"));
  }

  @Test public void theToolDeclaresWhatTheCommandFieldMeans() throws Exception {
    JSONArray declarations = MobileTools.declarations();
    for (int i = 0; i < declarations.length(); i++) {
      JSONObject item = declarations.optJSONObject(i);
      if (item == null || !MobileTools.ACTION.equals(item.optString("name"))) continue;
      JSONObject command = item.getJSONObject("parameters").getJSONObject("properties").getJSONObject("command");
      String description = command.optString("description");
      assertTrue("command must explain call_assistant", description.contains("call_assistant"));
      JSONArray values = command.optJSONArray("enum");
      assertTrue(values != null && values.length() > 3);
    }
  }
}
