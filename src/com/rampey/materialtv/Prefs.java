package com.rampey.materialtv;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The launcher's own choices, kept on the TV: pinned dock apps, calmer motion, apps per row, the app to open at boot. */
final class Prefs {
    static final int MIN_COLUMNS = 4;
    static final int MAX_COLUMNS = 7;
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    /** Pinned dock packages, in the order they sit in the dock (leftmost first). */
    List<String> pinned() {
        String s = sp.getString("pinned", "");
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split(",")));
    }

    void setPinned(List<String> packages) {
        sp.edit().putString("pinned", TextUtils.join(",", packages)).apply();
    }

    /** Apps hidden from the dock and the Apps page (they stay installed). */
    Set<String> hidden() {
        String s = sp.getString("hidden", "");
        return s.isEmpty() ? new HashSet<>() : new HashSet<>(Arrays.asList(s.split(",")));
    }

    void setHidden(Set<String> packages) {
        sp.edit().putString("hidden", TextUtils.join(",", packages)).apply();
    }

    /** 0 follows the TV's own 12/24-hour setting, 1 is 12-hour, 2 is 24-hour. */
    int clockMode() {
        return sp.getInt("clock_mode", 0);
    }

    void setClockMode(int mode) {
        sp.edit().putInt("clock_mode", mode).apply();
    }

    String clockPattern(Context c) {
        int mode = clockMode();
        boolean h24 = mode == 2 || (mode == 0 && android.text.format.DateFormat.is24HourFormat(c));
        return h24 ? "HH:mm" : "h:mm";
    }

    /** Screensaver shows random online photos instead of the generated shapes. */
    /** Screensaver photos: 0 off (generated shapes), 1 random online photos, 2 the bundled wallpapers (offline). */
    int photosMode() {
        return sp.contains("photos_mode") ? sp.getInt("photos_mode", 0) : (sp.getBoolean("photos", false) ? 1 : 0);
    }

    void setPhotosMode(int mode) {
        sp.edit().putInt("photos_mode", mode).apply();
    }

    boolean photosOn() {
        return photosMode() != 0;
    }

    /** The bundled photo behind Home (a file name under assets/wallpapers), or null for colors only. */
    String wallpaper() {
        return sp.getString("wallpaper", null);
    }

    void setWallpaper(String file) {
        sp.edit().putString("wallpaper", file).remove("wallpaper_now").remove("wallpaper_at").apply();
    }

    /** The wallpaper setting that shuffles the bundled photos (see wallpaperEvery). */
    static final String RANDOM_WALLPAPER = "random";
    /** How often a random wallpaper changes, in minutes. */
    static final int[] WALLPAPER_EVERY = {15, 30, 60, 180, 720, 1440};

    boolean randomWallpaper() {
        return RANDOM_WALLPAPER.equals(wallpaper());
    }

    int wallpaperEvery() {
        return sp.getInt("wallpaper_every", 60);
    }

    void setWallpaperEvery(int minutes) {
        sp.edit().putInt("wallpaper_every", minutes).apply();
    }

    /** The photo a random wallpaper shows right now, and since when. */
    String wallpaperNow() {
        return sp.getString("wallpaper_now", null);
    }

    long wallpaperAt() {
        return sp.getLong("wallpaper_at", 0);
    }

    void setWallpaperNow(String file) {
        sp.edit().putString("wallpaper_now", file).putLong("wallpaper_at", System.currentTimeMillis()).apply();
    }

    // ---- folders (groups of apps on the Apps page) ----

    /** Folder name -> package names, in the order the folders were made. */
    java.util.Map<String, List<String>> folders() {
        java.util.Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        try {
            org.json.JSONObject all = new org.json.JSONObject(foldersRaw());
            java.util.Iterator<String> names = all.keys();
            while (names.hasNext()) {
                String name = names.next();
                org.json.JSONArray arr = all.getJSONArray(name);
                List<String> pkgs = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) pkgs.add(arr.getString(i));
                out.put(name, pkgs);
            }
        } catch (org.json.JSONException ignored) {
        }
        return out;
    }

    String foldersRaw() {
        return sp.getString("folders", "{}");
    }

    /** Empty folders are dropped. */
    void setFolders(java.util.Map<String, List<String>> folders) {
        org.json.JSONObject all = new org.json.JSONObject();
        try {
            for (java.util.Map.Entry<String, List<String>> e : folders.entrySet()) {
                if (!e.getValue().isEmpty()) all.put(e.getKey(), new org.json.JSONArray(e.getValue()));
            }
        } catch (org.json.JSONException ignored) {
        }
        sp.edit().putString("folders", all.toString()).apply();
    }

    /** Fixed color themes next to "follow the focused app" (index 0); seeds are the same kind the banners give. */
    static final String[] COLOR_NAMES = {"Theo ứng dụng", "Đại dương", "Rừng xanh", "Hoàng hôn", "Hồng", "Tím", "Than chì"};
    private static final int[] COLOR_SEEDS = {0, 0xFF1976D2, 0xFF2E7D32, 0xFFEF6C00, 0xFFD81B60, 0xFF6750A4, 0xFF607D8B};

    int colorTheme() {
        return Math.max(0, Math.min(COLOR_NAMES.length - 1, sp.getInt("color_theme", 0)));
    }

    void setColorTheme(int index) {
        sp.edit().putInt("color_theme", index).apply();
    }

    /** The fixed seed color, or 0 when colors follow the focused app. */
    int colorSeed() {
        return COLOR_SEEDS[colorTheme()];
    }

    /** The Continue tab: what apps offer under Watch Next. */
    boolean continueOn() {
        return sp.getBoolean("continue", false);
    }

    void setContinueOn(boolean on) {
        sp.edit().putBoolean("continue", on).apply();
    }

    // ---- TV channels (the row under the dock, fed by the TV's own channel apps) ----

    /** The TV channels row: on until it is turned off (its logos come from the addresses the apps publish). */
    boolean tvChannelsOn() {
        return sp.getBoolean("tv_channels", true);
    }

    void setTvChannelsOn(boolean on) {
        sp.edit().putBoolean("tv_channels", on).apply();
    }

    /** The channels as last read from the TV, kept so the row shows before the table is read again. */
    String tvChannelsRaw() {
        return sp.getString("tv_channels_cache", "");
    }

    void setTvChannelsRaw(String json) {
        sp.edit().putString("tv_channels_cache", json).apply();
    }

    // ---- recently played (kept on the TV, only while the Continue tab is on) ----

    private static final int MAX_RECENT = 12;

    List<WatchNext.Item> recent() {
        List<WatchNext.Item> out = new ArrayList<>();
        try {
            org.json.JSONArray all = new org.json.JSONArray(sp.getString("recent", "[]"));
            for (int i = 0; i < all.length(); i++) {
                org.json.JSONObject o = all.getJSONObject(i);
                WatchNext.Item it = new WatchNext.Item();
                it.pkg = o.getString("pkg");
                it.title = o.getString("title");
                it.subtitle = o.optString("subtitle", "");
                it.mediaId = o.optString("mediaId", null);
                it.mediaUri = o.optString("mediaUri", null);
                it.poster = o.optString("poster", null);
                it.position = o.optLong("position");
                it.duration = o.optLong("duration");
                it.engaged = o.optLong("engaged");
                it.recent = true;
                out.add(it);
            }
        } catch (org.json.JSONException ignored) {
        }
        return out;
    }

    /** Newest first; the same app and title replaces its older entry. */
    void addRecent(WatchNext.Item item) {
        List<WatchNext.Item> list = recent();
        for (int i = list.size() - 1; i >= 0; i--) {
            WatchNext.Item previous = list.get(i);
            if (previous.pkg.equals(item.pkg) && previous.title.equals(item.title)) {
                if (item.poster == null) item.poster = previous.poster;
                if (item.duration <= 0) item.duration = previous.duration;
                if (item.position < 0) item.position = previous.position;
                if (item.mediaId == null) item.mediaId = previous.mediaId;
                if (item.mediaUri == null) item.mediaUri = previous.mediaUri;
                list.remove(i);
            }
        }
        list.add(0, item);
        while (list.size() > MAX_RECENT) list.remove(list.size() - 1);
        saveRecent(list);
    }

    /** Attach late artwork without moving an item forward or overwriting its newer playback position. */
    boolean setRecentPoster(String pkg, String title, String poster) {
        List<WatchNext.Item> list = recent();
        for (WatchNext.Item item : list) {
            if (item.pkg.equals(pkg) && item.title.equals(title)) {
                item.poster = poster;
                saveRecent(list);
                return true;
            }
        }
        return false;
    }

    private void saveRecent(List<WatchNext.Item> list) {
        try {
            org.json.JSONArray all = new org.json.JSONArray();
            for (WatchNext.Item it : list) {
                all.put(new org.json.JSONObject().put("pkg", it.pkg).put("title", it.title).put("subtitle", it.subtitle == null ? "" : it.subtitle)
                        .put("mediaId", it.mediaId).put("mediaUri", it.mediaUri).put("poster", it.poster).put("position", it.position)
                        .put("duration", it.duration).put("engaged", it.engaged));
            }
            sp.edit().putString("recent", all.toString()).apply();
        } catch (org.json.JSONException ignored) {
        }
    }

    void clearRecent() {
        sp.edit().remove("recent").remove("continue_dismissed").apply();
    }

    void removeRecentPackage(String pkg) {
        List<WatchNext.Item> list = recent();
        if (list.removeIf(item -> item.pkg.equals(pkg))) saveRecent(list);
    }

    /** Hide a program locally until it is watched again; no privileged TV-provider access required. */
    void dismissContinue(WatchNext.Item item) {
        org.json.JSONObject hidden;
        try { hidden = new org.json.JSONObject(sp.getString("continue_dismissed", "{}")); }
        catch (org.json.JSONException e) { hidden = new org.json.JSONObject(); }
        try {
            hidden.put(WatchNext.key(item), item.engaged);
            while (hidden.length() > 100) hidden.remove(hidden.keys().next());
        } catch (org.json.JSONException ignored) { }
        List<WatchNext.Item> list = recent();
        list.removeIf(previous -> WatchNext.key(previous).equals(WatchNext.key(item)));
        saveRecent(list);
        sp.edit().putString("continue_dismissed", hidden.toString()).apply();
    }

    List<WatchNext.Item> visibleContinue(List<WatchNext.Item> items) {
        List<WatchNext.Item> out = new ArrayList<>();
        org.json.JSONObject hidden;
        try { hidden = new org.json.JSONObject(sp.getString("continue_dismissed", "{}")); }
        catch (org.json.JSONException e) { hidden = new org.json.JSONObject(); }
        for (WatchNext.Item item : items) {
            if (!hidden.has(WatchNext.key(item)) || item.engaged > hidden.optLong(WatchNext.key(item))) out.add(item);
        }
        return out;
    }

    // ---- weather (on by default: a city the user typed, else the TV's own location, else a guess from its IP) ----

    boolean weatherOn() {
        return !sp.getBoolean("weather_off", false);
    }

    void setWeatherOn(boolean on) {
        SharedPreferences.Editor e = sp.edit().putBoolean("weather_off", !on);
        if (!on) e.remove("weather_city").remove("weather_lat").remove("weather_lon").remove("weather_text").remove("weather_at")
                .remove("auto_city").remove("auto_lat").remove("auto_lon").remove("auto_at");
        e.apply();
    }

    /** A city typed in Weather settings; otherwise the place is found automatically. */
    boolean weatherManual() {
        return sp.contains("weather_lat");
    }

    /** Where the weather is for: the typed city, else the last automatic place; false when neither is known yet. */
    boolean weatherKnown() {
        return weatherManual() || sp.contains("auto_lat");
    }

    String weatherCity() {
        return weatherManual() ? sp.getString("weather_city", "") : sp.getString("auto_city", "");
    }

    double weatherLat() {
        return Double.longBitsToDouble(sp.getLong(weatherManual() ? "weather_lat" : "auto_lat", 0));
    }

    double weatherLon() {
        return Double.longBitsToDouble(sp.getLong(weatherManual() ? "weather_lon" : "auto_lon", 0));
    }

    void setWeatherPlace(String city, double lat, double lon) {
        sp.edit().putBoolean("weather_off", false).putString("weather_city", city).putLong("weather_lat", Double.doubleToLongBits(lat))
                .putLong("weather_lon", Double.doubleToLongBits(lon)).remove("weather_text").remove("weather_at").apply();
    }

    /** Back to finding the place automatically (the typed city is forgotten). */
    void useAutoPlace() {
        sp.edit().remove("weather_city").remove("weather_lat").remove("weather_lon").remove("weather_text").remove("weather_at")
                .remove("auto_at").apply();
    }

    long autoPlaceAt() {
        return sp.getLong("auto_at", 0);
    }

    void setAutoPlace(String city, double lat, double lon) {
        boolean moved = Math.abs(lat - weatherLat()) > 0.05 || Math.abs(lon - weatherLon()) > 0.05;
        SharedPreferences.Editor e = sp.edit().putString("auto_city", city == null ? "" : city)
                .putLong("auto_lat", Double.doubleToLongBits(lat)).putLong("auto_lon", Double.doubleToLongBits(lon))
                .putLong("auto_at", System.currentTimeMillis());
        if (moved && !weatherManual()) e.remove("weather_text").remove("weather_at");
        e.apply();
    }

    boolean fahrenheit() {
        return sp.getBoolean("fahrenheit", false);
    }

    void setFahrenheit(boolean on) {
        sp.edit().putBoolean("fahrenheit", on).remove("weather_text").remove("weather_at").apply();
    }

    /** The last weather line ("21°C · Clear"), kept so the screensaver can show it without going online. */
    String weatherText() {
        return sp.getString("weather_text", "");
    }

    long weatherAt() {
        return sp.getLong("weather_at", 0);
    }

    void setWeatherText(String text) {
        sp.edit().putString("weather_text", text).putLong("weather_at", System.currentTimeMillis()).apply();
    }

    /** Every choice back to how it came; pinned and hidden apps stay. */
    void resetSettings() {
        SharedPreferences.Editor e = sp.edit();
        for (String key : new String[]{"reduced_motion", "columns", "clock_mode", "start_app", "fahrenheit", "photos", "photos_mode", "wallpaper", "wallpaper_every", "wallpaper_now", "wallpaper_at","continue", "continue_dismissed", "tv_channels", "tv_channels_cache", "recent", "color_theme", "weather_city", "weather_lat",
                "weather_lon", "weather_text", "weather_at", "weather_off", "auto_city", "auto_lat", "auto_lon", "auto_at"}) e.remove(key);
        e.apply();
    }

    boolean reducedMotion() {
        return sp.getBoolean("reduced_motion", false);
    }

    void setReducedMotion(boolean on) {
        sp.edit().putBoolean("reduced_motion", on).apply();
    }

    int columns() {
        return Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, sp.getInt("columns", 5)));
    }

    void setColumns(int n) {
        sp.edit().putInt("columns", n).apply();
    }

    /** The app that opens instead of Home the first time Home comes up after the TV starts; null means none. */
    String startApp() {
        return sp.getString("start_app", null);
    }

    void setStartApp(String pkg) {
        sp.edit().putString("start_app", pkg).apply();
    }

    /** True once per boot: the first call after each start of the TV returns true, later calls false. */
    boolean firstHomeSinceBoot(int bootCount) {
        if (sp.getInt("boot_seen", -1) == bootCount) return false;
        sp.edit().putInt("boot_seen", bootCount).apply();
        return true;
    }
}
