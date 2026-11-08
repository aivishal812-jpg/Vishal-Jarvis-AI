package com.myraa.mobile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Android's connectivity state is a hint; the authenticated Gemini check is authoritative. */
final class NetworkDiagnostics {
  static String status(Context context) {
    try {
      ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      Network network = cm == null ? null : cm.getActiveNetwork();
      NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
      if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "offline";
      if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) return "captive_portal";
      if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return "unverified";
      return "online";
    } catch (RuntimeException e) { return "unknown"; }
  }
  private NetworkDiagnostics() {}
}
