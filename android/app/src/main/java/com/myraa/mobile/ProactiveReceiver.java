package com.myraa.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives the proactive wake-ups (briefing, digest, meeting reminder) without any Activity.
 * A broadcast receiver has about 10 seconds, so the work is short, local and never networked
 * except for the weather line inside the existing briefing, which is skipped when offline.
 */
public final class ProactiveReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context context, Intent intent) {
    if (intent == null) return;
    String action = intent.getAction();
    if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
      ProactiveScheduler.reschedule(context);
      ProactiveScheduler.scheduleMeetingCheck(context);
      return;
    }
    if (ProactiveScheduler.ACTION_TICK.equals(action)) {
      ProactiveScheduler.tick(context);
      return;
    }
    if (ProactiveScheduler.ACTION_MEETING.equals(action)) {
      String title = intent.getStringExtra("title");
      long begin = intent.getLongExtra("begin", 0);
      int lead = ProactiveScheduler.meetingLead(context);
      if (title != null) {
        ProactiveScheduler.notify(context, 4206, "Starting in " + lead + " minutes",
          title + (begin > 0 ? " at " + android.text.format.DateFormat.getTimeFormat(context).format(new java.util.Date(begin)) : ""));
      }
      // Arm the next meeting reminder so a recurring schedule keeps working.
      ProactiveScheduler.scheduleMeetingCheck(context);
    }
  }
}
