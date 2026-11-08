package com.myraa.mobile;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Home-screen widget: "Talk" starts voice, "Type" opens the chat. */
public final class JarvisWidget extends AppWidgetProvider {
  @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
    for (int id : ids) {
      RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.jarvis_widget);
      Intent talk = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(JarvisPrefs.EXTRA_AUTO_VOICE, true);
      Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      views.setOnClickPendingIntent(R.id.widget_talk, PendingIntent.getActivity(context, 31, talk, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
      views.setOnClickPendingIntent(R.id.widget_icon, PendingIntent.getActivity(context, 32, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
      manager.updateAppWidget(id, views);
    }
  }
}
