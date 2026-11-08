package com.myraa.mobile;

import org.json.JSONObject;

/** In-process events only: task data is never broadcast to another application. */
final class AiTaskEvents {
  interface Listener { void receive(JSONObject event); }
  private static volatile Listener listener;
  static volatile boolean visible;
  static void attach(Listener value) { listener = value; }
  static void detach(Listener value) { if (listener == value) listener = null; }
  static void emit(JSONObject event) { Listener current = listener; if (current != null) current.receive(event); }
  private AiTaskEvents() {}
}
