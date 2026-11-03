package com.rampey.materialtv;

import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Weather from Open-Meteo (no account, no key). What leaves the TV: a city name the user typed and that city's
 * coordinates; or, with no city typed, the coordinates of the TV's own location, and when the TV has no location
 * (most TVs and projectors: no GPS, no network location) one request to ipwho.is, which sees the home's public IP and
 * answers with a nearby city. On by default; turned off in Weather settings.
 */
final class Weather {
    private Weather() {
    }

    interface Callback<T> {
        /** Called on the UI thread; null when the request failed. */
        void done(T result);
    }

    static final class Place {
        final String name;
        final double lat, lon;

        Place(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "weather");
        t.setDaemon(true);
        return t;
    });

    static void findCity(Handler ui, String query, Callback<Place> cb) {
        IO.execute(() -> {
            Place place = null;
            try {
                String body = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="
                        + URLEncoder.encode(query.trim(), "UTF-8"));
                JSONArray results = new JSONObject(body).optJSONArray("results");
                if (results != null && results.length() > 0) {
                    JSONObject r = results.getJSONObject(0);
                    String country = r.optString("country", "");
                    place = new Place(r.getString("name") + (country.isEmpty() ? "" : ", " + country),
                            r.getDouble("latitude"), r.getDouble("longitude"));
                }
            } catch (Exception ignored) {
            }
            Place found = place;
            ui.post(() -> cb.done(found));
        });
    }

    /**
     * Where the TV is: Android's last known location when the app may read it (any provider, newest first), named
     * with the Geocoder when the TV has one; otherwise a guess from the public IP (ipwho.is). Null when neither works.
     */
    static void locate(android.content.Context ctx, Handler ui, Callback<Place> cb) {
        android.location.Location best = null;
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                android.location.LocationManager lm = ctx.getSystemService(android.location.LocationManager.class);
                for (String p : lm.getAllProviders()) {
                    android.location.Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
            } catch (RuntimeException ignored) {
            }
        }
        android.location.Location here = best;
        android.content.Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            Place place = null;
            if (here != null) {
                String name = "";
                try {
                    if (android.location.Geocoder.isPresent()) {
                        java.util.List<android.location.Address> a = new android.location.Geocoder(app)
                                .getFromLocation(here.getLatitude(), here.getLongitude(), 1);
                        if (a != null && !a.isEmpty() && a.get(0).getLocality() != null) name = a.get(0).getLocality();
                    }
                } catch (Exception ignored) {
                }
                place = new Place(name, here.getLatitude(), here.getLongitude());
            } else {
                try {
                    JSONObject r = new JSONObject(get("https://ipwho.is/?fields=success,city,country,latitude,longitude"));
                    if (r.optBoolean("success")) {
                        String country = r.optString("country", "");
                        place = new Place(r.optString("city", "") + (country.isEmpty() ? "" : ", " + country),
                                r.getDouble("latitude"), r.getDouble("longitude"));
                    }
                } catch (Exception ignored) {
                }
            }
            Place found = place;
            ui.post(() -> cb.done(found));
        });
    }

    /** "21°C · Partly cloudy" for the place, or null when offline. */
    static void current(Handler ui, double lat, double lon, boolean fahrenheit, Callback<String> cb) {
        IO.execute(() -> {
            String line = null;
            try {
                String body = get("https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code&latitude="
                        + lat + "&longitude=" + lon + "&temperature_unit=" + (fahrenheit ? "fahrenheit" : "celsius"));
                JSONObject cur = new JSONObject(body).getJSONObject("current");
                line = Math.round(cur.getDouble("temperature_2m")) + (fahrenheit ? "°F" : "°C") + " · " + describe(cur.getInt("weather_code"));
            } catch (Exception ignored) {
            }
            String result = line;
            ui.post(() -> cb.done(result));
        });
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    static String describe(int code) {
        if (code == 0) return "Trời quang";
        if (code == 1) return "Ít mây";
        if (code == 2) return "Mây rải rác";
        if (code == 3) return "Nhiều mây";
        if (code == 45 || code == 48) return "Sương mù";
        if (code >= 51 && code <= 57) return "Mưa phùn";
        if (code >= 61 && code <= 67) return "Mưa";
        if (code >= 71 && code <= 77) return "Tuyết";
        if (code >= 80 && code <= 82) return "Mưa rào";
        if (code == 85 || code == 86) return "Mưa tuyết";
        if (code >= 95) return "Dông";
        return "";
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 65536) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
