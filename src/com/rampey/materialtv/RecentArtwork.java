package com.rampey.materialtv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Private, bounded artwork storage. Never fetches anything from the network. */
final class RecentArtwork {
    static final String PREFIX = "recent-art:";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "recent-art"));
    private static final Set<String> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private RecentArtwork() { }

    static String address(String pkg, String title) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((pkg + "|" + title).getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (byte b : digest) key.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return PREFIX + key + ".jpg";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static File file(Context context, String address) {
        if (address == null || !address.matches("recent-art:[0-9a-f]{64}\\.jpg")) return null;
        return new File(new File(context.getFilesDir(), "recent-art"), address.substring(PREFIX.length()));
    }

    static void save(Context context, Handler ui, String address, Bitmap source, Weather.Callback<Boolean> callback) {
        Context app = context.getApplicationContext();
        pending.add(address);
        IO.execute(() -> {
            boolean saved = false;
            Bitmap scaled = null;
            File target = file(app, address);
            try {
                if (target != null && !source.isRecycled() && (target.getParentFile().isDirectory() || target.getParentFile().mkdirs())) {
                    float ratio = Math.min(1f, 640f / Math.max(source.getWidth(), source.getHeight()));
                    scaled = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * ratio)),
                            Math.max(1, Math.round(source.getHeight() * ratio)), true);
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        saved = scaled.compress(Bitmap.CompressFormat.JPEG, 85, out);
                    }
                }
            } catch (java.io.IOException | RuntimeException ignored) {
            } finally {
                if (scaled != null && scaled != source) scaled.recycle();
                if (!saved && target != null) target.delete();
            }
            boolean result = saved;
            ui.post(() -> {
                try {
                    callback.done(result);
                } finally {
                    pending.remove(address);
                    prune(app);
                }
            });
        });
    }

    static void load(Context context, Handler ui, String address, Weather.Callback<Bitmap> callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            File target = file(app, address);
            Bitmap bitmap = target != null ? BitmapFactory.decodeFile(target.getAbsolutePath()) : null;
            ui.post(() -> callback.done(bitmap));
        });
    }

    /** Only artwork referenced by the (at most 12) remembered items survives. */
    static void prune(Context context) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            Set<String> keep = new HashSet<>();
            for (WatchNext.Item item : new Prefs(app).recent()) {
                File f = file(app, item.poster);
                if (f != null) keep.add(f.getName());
            }
            for (String address : pending) {
                File f = file(app, address);
                if (f != null) keep.add(f.getName());
            }
            File[] files = new File(app.getFilesDir(), "recent-art").listFiles();
            if (files != null) for (File f : files) if (!keep.contains(f.getName())) f.delete();
        });
    }
}
