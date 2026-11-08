package com.myraa.mobile;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: one tap from the notification shade starts a voice conversation. */
public final class JarvisTileService extends TileService {
  @Override public void onStartListening() {
    Tile tile = getQsTile(); if (tile == null) return;
    tile.setLabel("Jarvis"); tile.setState(Tile.STATE_ACTIVE); tile.updateTile();
  }
  @Override public void onClick() {
    Intent intent = new Intent(this, VoiceCommandActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
      .putExtra(JarvisPrefs.EXTRA_AUTO_VOICE, true);
    if (Build.VERSION.SDK_INT >= 34) {
      startActivityAndCollapse(PendingIntent.getActivity(this, 21, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    } else startActivityAndCollapseLegacy(intent);
  }
  @SuppressWarnings("deprecation") private void startActivityAndCollapseLegacy(Intent intent) { startActivityAndCollapse(intent); }
}
