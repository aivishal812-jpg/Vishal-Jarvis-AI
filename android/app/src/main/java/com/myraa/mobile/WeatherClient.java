package com.myraa.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Free, key-less weather from Open-Meteo (https://open-meteo.com). Runs on a background thread. */
final class WeatherClient {
  static JSONObject forecast(String city) throws Exception {
    String place = city == null ? "" : city.trim();
    if (place.isEmpty() || place.length() > 80) throw new IllegalArgumentException("Which city? Tell me the city name, or save your home city in Jarvis Pro settings.");
    JSONObject geo = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=" + URLEncoder.encode(place, "UTF-8"));
    JSONArray results = geo.optJSONArray("results");
    if (results == null || results.length() == 0) throw new IllegalArgumentException("I could not find a place called " + place + ".");
    JSONObject hit = results.getJSONObject(0);
    double lat = hit.getDouble("latitude"), lon = hit.getDouble("longitude");
    JSONObject data = get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
      + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,precipitation"
      + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=3&timezone=auto");
    JSONObject current = data.optJSONObject("current"), daily = data.optJSONObject("daily");
    JSONObject out = new JSONObject();
    String name = hit.optString("name") + (hit.optString("admin1").isEmpty() ? "" : ", " + hit.optString("admin1"));
    out.put("place", name);
    if (current != null) {
      out.put("temperatureC", current.optDouble("temperature_2m")).put("feelsLikeC", current.optDouble("apparent_temperature"))
        .put("humidity", current.optInt("relative_humidity_2m")).put("windKmh", current.optDouble("wind_speed_10m"))
        .put("condition", describe(current.optInt("weather_code")));
    }
    if (daily != null) {
      JSONArray days = new JSONArray();
      JSONArray dates = daily.optJSONArray("time"), max = daily.optJSONArray("temperature_2m_max"), min = daily.optJSONArray("temperature_2m_min"),
        rain = daily.optJSONArray("precipitation_probability_max"), codes = daily.optJSONArray("weather_code");
      for (int i = 0; dates != null && i < dates.length(); i++) {
        days.put(new JSONObject().put("date", dates.optString(i)).put("maxC", max == null ? null : max.optDouble(i))
          .put("minC", min == null ? null : min.optDouble(i)).put("rainChance", rain == null ? null : rain.optInt(i))
          .put("condition", codes == null ? "" : describe(codes.optInt(i))));
      }
      out.put("days", days);
    }
    StringBuilder summary = new StringBuilder(name).append(": ");
    if (current != null) summary.append(Math.round(current.optDouble("temperature_2m"))).append("°C, ").append(describe(current.optInt("weather_code")))
      .append(", feels like ").append(Math.round(current.optDouble("apparent_temperature"))).append("°C, humidity ").append(current.optInt("relative_humidity_2m")).append("%.");
    JSONArray days = out.optJSONArray("days");
    if (days != null && days.length() > 0) {
      JSONObject today = days.getJSONObject(0);
      summary.append(" Today ").append(Math.round(today.optDouble("minC"))).append("–").append(Math.round(today.optDouble("maxC"))).append("°C, rain chance ").append(today.optInt("rainChance")).append("%.");
    }
    out.put("summary", summary.toString());
    return out;
  }
  static String describe(int code) {
    if (code == 0) return "clear sky";
    if (code <= 2) return "partly cloudy";
    if (code == 3) return "overcast";
    if (code == 45 || code == 48) return "fog";
    if (code >= 51 && code <= 57) return "drizzle";
    if (code >= 61 && code <= 67) return "rain";
    if (code >= 71 && code <= 77) return "snow";
    if (code >= 80 && code <= 82) return "rain showers";
    if (code >= 95) return "thunderstorm";
    return "mixed weather";
  }
  private static JSONObject get(String address) throws Exception {
    HttpsURLConnection c = (HttpsURLConnection) new URL(address).openConnection();
    try {
      c.setConnectTimeout(10000); c.setReadTimeout(15000); c.setInstanceFollowRedirects(false);
      int status = c.getResponseCode();
      if (status != 200) throw new IOException("Weather service returned " + status);
      try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); if (out.size() > 512 * 1024) throw new IOException("Too large"); }
        return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
      }
    } finally { c.disconnect(); }
  }
  private WeatherClient() {}
}
