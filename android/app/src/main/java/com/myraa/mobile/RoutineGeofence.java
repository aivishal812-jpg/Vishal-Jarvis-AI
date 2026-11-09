package com.myraa.mobile;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Looper;

/**
 * Optional location routine ("when I reach the office, do X").
 *
 * Android's Geofencing API only registers fences for location updates it can deliver, so this
 * uses the platform location provider directly with a coarse, low-power request while a place
 * is configured. ACCESS_COARSE_LOCATION is requested only when the user turns the routine on;
 * nothing is requested automatically and no location is stored or sent.
 */
final class RoutineGeofence {

  private static final float RADIUS_METERS = 250f;

  static void register(Context context, String placeName) {
    if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
    try {
      LocationManager locations = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
      if (locations == null) return;
      Location last = locations.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
      if (last != null) check(context, placeName, last);
    } catch (ignored RuntimeException) { /* tolerated: location may be unavailable */ }
  }

  static void clear(Context context) {
    android.content.SharedPreferences prefs = JarvisPrefs.get(context);
    prefs.edit().remove("proactive.routineLat").remove("proactive.routineLon").remove("proactive.routineInside").apply();
  }

  /** Remember the coordinates the user means the first time they are near the place name. */
  private static void check(Context context, String placeName, Location location) {
    android.content.SharedPreferences prefs = JarvisPrefs.get(context);
    boolean hasSaved = prefs.contains("proactive.routineLat");
    if (!hasSaved) {
      prefs.edit().putFloat("proactive.routineLat", (float) location.getLatitude())
        .putFloat("proactive.routineLon", (float) location.getLongitude()).apply();
      return;
    }
    float[] distance = new float[1];
    Location.distanceBetween(prefs.getFloat("proactive.routineLat", 0), prefs.getFloat("proactive.routineLon", 0),
      location.getLatitude(), location.getLongitude(), distance);
    boolean inside = distance[0] <= RADIUS_METERS;
    boolean wasInside = prefs.getBoolean("proactive.routineInside", false);
    if (inside && !wasInside) ProactiveScheduler.routineEntered(context, placeName);
    if (inside != wasInside) prefs.edit().putBoolean("proactive.routineInside", inside).apply();
  }

  private RoutineGeofence() { }
}
