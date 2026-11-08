package com.rampey.materialtv;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Material 3 Expressive home screen for TV: the background and accents take their color from the app in focus,
 * one dock of your most-used apps, everything else on an Apps page. No network, no tracking.
 */
public class HomeActivity extends Activity {

    private static final int SIDE = 48, DOCK_PAD = 8, SCROLL_PAD = 18, GAP = 14, RING = 6, APP_MARGIN = 9;

    /** Material's "emphasized decelerate" curve for things arriving on screen. */
    private static final PathInterpolator EMPHASIZED = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<GradientDrawable, ValueAnimator> cornerAnimations = new HashMap<>();
    private final Map<GradientDrawable, ValueAnimator> ringAnimations = new HashMap<>();
    private final java.util.Set<ValueAnimator> uiAnimations = new java.util.HashSet<>();

    private void startUiAnimation(ValueAnimator animation) {
        uiAnimations.add(animation);
        animation.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                uiAnimations.remove(animation);
            }
        });
        animation.start();
    }

    private void animateCorners(GradientDrawable card, GradientDrawable ring, float target) {
        ValueAnimator previous = cornerAnimations.remove(card);
        if (previous != null) previous.cancel();
        if (Motion.reduced()) {
            card.setCornerRadius(target);
            ring.setCornerRadius(target + dp(RING));
            return;
        }
        ValueAnimator animation = ValueAnimator.ofFloat(card.getCornerRadius(), target);
        animation.setDuration(Motion.FAST_SPATIAL_MS);
        animation.setInterpolator(Motion.FAST_SPATIAL);
        animation.addUpdateListener(a -> {
            float radius = (float) a.getAnimatedValue();
            card.setCornerRadius(radius);
            ring.setCornerRadius(radius + dp(RING));
        });
        animation.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                cornerAnimations.remove(card);
            }
        });
        cornerAnimations.put(card, animation);
        startUiAnimation(animation);
    }
    private final Map<String, Integer> seedCache = new ConcurrentHashMap<>();
    private final Map<Integer, Palette> paletteCache = new ConcurrentHashMap<>();
    private final Map<Integer, Typeface> titleFonts = new HashMap<>();
    private final Map<Integer, Typeface> fonts = new HashMap<>();
    private PackageManager pm;
    private Palette palette = Palette.of(Palette.DEFAULT_SEED);

    private GradientDrawable backdrop;
    private CookieDrawable cookie;
    private ImageView shape;
    /**
     * The background shape drifts at 3 degrees a second, on every display frame (60 fps on a 60 Hz TV). It only runs
     * while Home is on screen; the shape is a hardware layer, so each frame just rotates a texture.
     */
    private long driftLast;
    private static final long DRIFT_FRAME_MS = 0;   // every display frame (60 fps on a 60 Hz TV)
    private boolean drifting;
    private final Runnable drift = new Runnable() {
        @Override public void run() {
            if (!drifting || !shape.isAttachedToWindow()) return;   // the shape is not on screen: nothing to turn
            long now = android.os.SystemClock.uptimeMillis();
            // Every display frame: 60 fps on a 60 Hz TV (owner choice; ~50% of one core, only while Home is up).
            if (driftLast == 0 || now - driftLast >= DRIFT_FRAME_MS) {
                if (driftLast > 0) shape.setRotation((shape.getRotation() + 0.003f * (now - driftLast)) % 360f);
                driftLast = now;
            }
            shape.postOnAnimation(this);
        }
    };
    private final Runnable prebuildApps = new Runnable() {
        @Override public void run() {
            if (!HomeActivity.this.showingApps) fillApps();
        }
    };
    private GlassDrawable dockShape;
    private GradientDrawable channelShape;
    private View homePage;
    private ScrollView appsPage;
    private GridLayout appsGrid;
    private LinearLayout dock;
    /** The TV channels row under the dock, and what it currently shows. */
    private FrameLayout channelDock;
    private LinearLayout channelRow;
    private String channelKey = "";
    private final List<TvChannels.Channel> channelList = new ArrayList<>();
    private final Map<String, Bitmap> channelLogos = new java.util.LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 40;   // small, mostly transparent logos: about 1 MB in all
        }
    };
    private final Set<String> logoLoading = new java.util.HashSet<>();
    private boolean channelsLoading;
    private TextView clock;
    private TextView date;
    private TextView weather;
    private TextView focusTitle;
    private ButtonGroup nav;

    // Focus settles before the expensive part runs, so holding a direction stays smooth.
    private String pendingPkg;
    private CharSequence pendingLabel;
    private Drawable pendingArt;
    private final Runnable settle = new Runnable() {
        @Override public void run() {
            applyFocus(pendingPkg, pendingLabel, pendingArt);
        }
    };

    // Now Playing.
    private MediaSessionManager sessions;
    private ComponentName mediaListener;
    private MediaController playing;
    private String livePkg;   // the app of the session shown: playing's, or the dump's when there is no controller
    private View nowCard;
    private ImageView nowArt;
    private TextView nowTitle;
    private TextView nowArtist;
    private WavyProgress nowProgress;
    private final Map<String, EqualizerBadge> dockBadges = new HashMap<>();
    private final Map<String, View> deviceDots = new HashMap<>();   // input id -> "on" dot
    private final android.media.tv.TvInputManager.TvInputCallback inputCallback = new android.media.tv.TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) {
            showDeviceState(inputId, state);
        }

        // An HDMI device names itself over CEC a little after it appears ("Playback 1" -> "PlayStation 5").
        @Override public void onInputAdded(String inputId) {
            refreshDockSoon();
        }

        @Override public void onInputRemoved(String inputId) {
            refreshDockSoon();
        }

        @Override public void onInputUpdated(String inputId) {
            refreshDockSoon();
        }

        @Override public void onTvInputInfoUpdated(android.media.tv.TvInputInfo info) {
            refreshDockSoon();
        }
    };
    private final Runnable refreshDock = () -> {
        if (drifting) fillDock();
    };

    private void refreshDockSoon() {
        handler.removeCallbacks(refreshDock);
        handler.postDelayed(refreshDock, 500);
    }
    private ImageView nowButton;
    private GradientDrawable nowShape;
    private GradientDrawable nowButtonShape;
    // The callbacks hand over the session's metadata and state; they are kept rather than fetched again from the
    // controller, which would re-parcel the whole bundle (album art included) several times a second.
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) {
            liveMeta = metadata;
            showNowPlaying();
        }

        @Override public void onPlaybackStateChanged(PlaybackState state) {
            liveState = state;
            showNowPlaying();
        }

        @Override public void onSessionDestroyed() {
            pickSession();
        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> pickSession();
    private boolean showingApps;
    private String appsKey = "";
    private String dockKey = "";
    private ValueAnimator colorAnimator;
    private final Map<Integer, Typeface> clockFonts = new HashMap<>();

    /** Clock weight through the day: 250 at night, rising to 430 by mid-morning, easing back after sunset. */
    private static int clockWeight() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        float h = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60f;
        float[][] keys = {{0, 250}, {5, 250}, {9, 430}, {17, 430}, {21, 300}, {24, 250}};
        for (int i = 0; i < keys.length - 1; i++) {
            if (h >= keys[i][0] && h <= keys[i + 1][0]) {
                float t = (h - keys[i][0]) / (keys[i + 1][0] - keys[i][0]);
                return Math.round(keys[i][1] + (keys[i + 1][1] - keys[i][1]) * t);
            }
        }
        return 300;
    }

    private Typeface clockFont(int weight) {
        int w = Math.round(weight / 10f) * 10;
        Typeface t = clockFonts.get(w);
        if (t == null) {
            try {
                t = new Typeface.Builder(getAssets(), "fonts/GoogleSansFlex.ttf")
                        .setFontVariationSettings("'wght' " + w + ", 'opsz' 48, 'ROND' 0").build();
            } catch (RuntimeException e) {
                t = font(300);
            }
            clockFonts.put(w, t);
        }
        return t;
    }

    /** A new minute: the old time lifts away, the new one settles in with a short weight swell. */
    private void changeClock(String time) {
        int base = clockWeight();
        if (Motion.reduced()) {
            clock.setText(time);
            clock.setTypeface(clockFont(base));
            return;
        }
        clock.animate().alpha(0f).translationY(-dp(6)).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.duration(Motion.FAST_EFFECTS_MS))
                .withEndAction(() -> {
                    clock.setText(time);
                    clock.setTranslationY(dp(8));
                    clock.animate().alpha(1f).translationY(0).setInterpolator(Motion.DEFAULT_SPATIAL)
                            .setDuration(Motion.duration(Motion.DEFAULT_SPATIAL_MS)).start();
                    ValueAnimator swell = ValueAnimator.ofInt(base + 320, base).setDuration(Motion.duration(Motion.DEFAULT_SPATIAL_MS));
                    swell.setInterpolator(Motion.DEFAULT_SPATIAL);
                    swell.addUpdateListener(a -> clock.setTypeface(clockFont((int) a.getAnimatedValue())));
                    startUiAnimation(swell);
                }).start();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            String time = new SimpleDateFormat(prefs.clockPattern(HomeActivity.this), Locale.getDefault()).format(now);
            if (clock.getText().length() == 0) {
                clock.setText(time);
                clock.setTypeface(clockFont(clockWeight()));
            } else if (!time.contentEquals(clock.getText())) {
                changeClock(time);
            }
            date.setText(DateText.format(now, Locale.getDefault()));
            handler.postDelayed(this, 15_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        final long startupBeginNs = android.os.SystemClock.elapsedRealtimeNanos();
        android.util.Log.i("BaroStartup", "onCreate.begin elapsedRealtimeNs=" + startupBeginNs);
        super.onCreate(state);
        pm = getPackageManager();
        prefs = new Prefs(this);
        RecentArtwork.prune(this);
        Motion.setReduced(prefs.reducedMotion());
        if (prefs.colorSeed() != 0) palette = Palette.of(prefs.colorSeed());
        setContentView(buildRoot());
        android.util.Log.i("BaroStartup", "onCreate.contentViewReady elapsedRealtimeNs=" + android.os.SystemClock.elapsedRealtimeNanos());
        // First pre-draw is a UI traversal milestone, NOT proof of first visible frame.
        final android.view.View decor = getWindow().getDecorView();
        decor.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                android.util.Log.i("BaroStartup", "firstPreDraw elapsedRealtimeNs=" + android.os.SystemClock.elapsedRealtimeNanos());
                decor.post(() -> {
                    if (decor.getViewTreeObserver().isAlive())
                        decor.getViewTreeObserver().removeOnPreDrawListener(this);
                });
                return true;
            }
        });
        openStartApp();
    }

    private Prefs prefs;
    private static final long START_APP_DELAY_MS = 1200;

    /** Once per boot: the app chosen in Launcher settings opens over Home, which stays one Back or Home press away. */
    private void openStartApp() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        if (!prefs.firstHomeSinceBoot(boot)) return;
        String pkg = prefs.startApp();
        Intent intent = pkg != null ? launchIntent(pkg) : null;
        if (intent != null) handler.postDelayed(() -> launch(intent), START_APP_DELAY_MS);
    }

    @Override protected void onResume() {
        android.util.Log.i("BaroStartup", "onResume.begin elapsedRealtimeNs=" + android.os.SystemClock.elapsedRealtimeNanos());
        super.onResume();
        Set<String> checkedPackages = new java.util.HashSet<>();
        for (WatchNext.Item item : prefs.recent()) {
            if (!checkedPackages.add(item.pkg)) continue;
            try {
                pm.getApplicationInfo(item.pkg, 0);
            } catch (PackageManager.NameNotFoundException removed) {
                prefs.removeRecentPackage(item.pkg);
                RecentArtwork.prune(this);
            }
        }
        fillDock();
        fillChannels();
        fillHomeContinue();
        if (showingApps && !showingContinue) fillApps();
        if (showingContinue) refreshContinue();
        handler.removeCallbacks(tick);
        handler.post(tick);
        drifting = !prefs.reducedMotion();
        driftLast = 0;
        shape.removeCallbacks(drift);
        if (drifting) shape.postOnAnimation(drift);
        // Build the Apps page quietly once Home is showing, so opening it later is instant.
        handler.removeCallbacks(prebuildApps);
        handler.postDelayed(prebuildApps, 1500);
        foreground = true;
        updateContinueObservers();
        if (prefs.randomWallpaper()) applyWallpaper();   // its time may have come while another app was up
        showWeather();
        startNowPlaying();
        android.media.tv.TvInputManager inputs = getSystemService(android.media.tv.TvInputManager.class);
        if (inputs != null) {
            inputs.registerCallback(inputCallback, handler);
            for (String id : deviceDots.keySet()) showDeviceState(id, inputs.getInputState(id));
        }
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        // Nothing animates while another app is on screen: no CPU or GPU use in the background.
        for (ValueAnimator animation : new ArrayList<>(uiAnimations)) animation.end();
        drifting = false;
        shape.removeCallbacks(drift);
        handler.removeCallbacks(prebuildApps);
        if (colorAnimator != null) colorAnimator.end();
        nav.stopAnimations();
        handler.removeCallbacks(settle);
        // The media session stays watched (it only updates fields, never views) so the card remembers what played
        // while another app had the screen; it is let go in onDestroy.
        foreground = false;
        stopContinueObservers();
        homeContinueRequest++;
        continueRequest++;
        if (folderDialog != null) folderDialog.dismiss();
        if (quickDialog != null) quickDialog.dismiss();
        handler.removeCallbacks(refreshWeather);
        handler.removeCallbacks(shuffleWallpaper);
        handler.removeCallbacks(checkResumed);
        android.media.tv.TvInputManager inputs = getSystemService(android.media.tv.TvInputManager.class);
        if (inputs != null) inputs.unregisterCallback(inputCallback);
        hideLoader();
    }

    @Override protected void onDestroy() {
        stopNowPlaying();
        handler.removeCallbacks(expire);
        handler.removeCallbacks(checkResumed);
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        showHome();
    }


    @Override public void onBackPressed() {
        if (showingApps) showHome();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (showingApps) showHome(); else showApps();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- layout ----

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        backdrop = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{palette.surfaceBright, palette.surface});
        root.setBackground(backdrop);

        // An optional photo (Launcher settings -> Wallpaper) under a scrim in the palette's surface color.
        wallpaperView = new ImageView(this);
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperView.setVisibility(View.GONE);
        root.addView(wallpaperView, match());
        wallpaperScrim = new View(this);
        wallpaperScrim.setBackgroundColor(withAlpha(palette.surface, WALLPAPER_DIM));
        wallpaperScrim.setVisibility(View.GONE);
        root.addView(wallpaperScrim, match());
        applyWallpaper();

        // The hero: the focused item's art across the whole screen, cross-faded between two views, under a scrim that
        // darkens toward the left and the bottom so the title and the dock stay readable.
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(this);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            root.addView(v, match());
            heroViews[i] = v;
        }
        heroSide = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[0]);
        heroBottom = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[0]);
        heroScrim = new View(this);
        heroScrim.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[]{heroSide, heroBottom}));
        heroScrim.setAlpha(0f);
        styleHeroScrim(palette.surface);
        root.addView(heroScrim, match());

        // The Expressive "cookie" shape is no longer shown (it read as a stray colored block in the corner, and its drift
        // cost a CPU core on weak TVs); the object stays so the code that recolors or rotates it has a target.
        cookie = new CookieDrawable(palette.primaryContainer, 9, 0.08f);
        ImageView big = new ImageView(this);
        big.setImageDrawable(cookie);
        shape = big;

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(SIDE), dp(32), dp(SIDE), dp(28));
        column.setClipChildren(false);
        column.setClipToPadding(false);
        root.addView(column, match());

        nowCard = buildNowPlaying();
        FrameLayout.LayoutParams nowLp = new FrameLayout.LayoutParams(dp(380), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        nowLp.setMargins(0, dp(140), dp(SIDE), 0);
        root.addView(nowCard, nowLp);

        column.addView(buildTopBar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout pages = new FrameLayout(this);
        column.addView(pages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        homePage = buildHomePage();
        pages.addView(homePage, match());

        appsPage = new ScrollView(this);
        appsPage.setVerticalScrollBarEnabled(false);
        appsPage.setClipToPadding(false);
        appsPage.setClipChildren(false);
        LinearLayout appsColumn = new LinearLayout(this);
        appsColumn.setOrientation(LinearLayout.VERTICAL);
        appsColumn.setClipChildren(false);
        TextView appsTitle = text("Tất cả ứng dụng", 34, palette.onSurface, 760);
        appsTitle.setPadding(dp(4), dp(28), 0, dp(8));
        appsColumn.addView(appsTitle);
        appsGrid = new GridLayout(this);
        appsGrid.setColumnCount(prefs.columns());
        appsGrid.setClipChildren(false);
        appsGrid.setPadding(0, dp(12), 0, dp(32));
        appsColumn.addView(appsGrid);
        appsPage.addView(appsColumn, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        appsPage.setVisibility(View.GONE);
        pages.addView(appsPage, match());
        continuePage = buildContinuePage();
        continuePage.setVisibility(View.GONE);
        pages.addView(continuePage, match());
        managerPage = new ManagerPage(this, new ManagerPage.Style() {
            @Override public TextView text(String s, int sp, int color, int weight) {
                return HomeActivity.this.text(s, sp, color, weight);
            }

            @Override public Palette palette() {
                return palette;
            }
        });
        managerPage.setVisibility(View.GONE);
        pages.addView(managerPage, match());
        // Populate Continue only after buildHomePage initializes homeContinue/homeContinueRow.
        fillHomeContinue();
        applyContinueTab();
        return root;
    }

    private View buildTopBar() {
        FrameLayout bar = new FrameLayout(this);
        // Room for a focused button to grow without its edge being cut off.
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setPadding(0, dp(4), dp(6), dp(4));

        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        clock = text("", 46, palette.onSurface, 300);
        clock.setIncludeFontPadding(false);
        when.addView(clock);
        date = text("", 15, palette.onSurfaceVariant, 500);
        date.setPadding(dp(2), dp(2), 0, 0);
        when.addView(date);
        weather = text("", 15, palette.onSurfaceVariant, 500);
        weather.setPadding(dp(2), dp(2), 0, 0);
        weather.setVisibility(View.GONE);
        when.addView(weather);
        bar.addView(when, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.TOP));

        nav = new ButtonGroup(this, font(600), palette,
                new String[]{"Trang chủ", "Ứng dụng", "Xem tiếp", "Cài đặt"},
                new int[]{R.drawable.ic_home, R.drawable.ic_apps, R.drawable.ic_play, R.drawable.ic_settings},
                new boolean[]{true, true, true, false}, true,   // round icons only: the clock and the hero carry the screen
                index -> {
                    if (index == 0) showHome();
                    else if (index == 1) showApps();
                    else if (index == 2) showContinue();
                    else showQuickPanel();
                });
        nav.select(0);
        nav.button(3).setOnLongClickListener(v -> {
            showLauncherSettings();
            return true;
        });
        bar.addView(nav, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP));
        return bar;
    }

    // Home scrolls: the title and the dock fill the screen, and a Continue watching row peeks in under them; moving
    // down to it scrolls it up, moving back to the dock scrolls back.
    private static final int HOME_PEEK = 34;
    private ScrollView homeScroll;
    private LinearLayout homeContinue, homeContinueRow;

    private View buildHomePage() {
        homeScroll = new ScrollView(this);
        homeScroll.setVerticalScrollBarEnabled(false);   // clips: scrolled-up content must not run under the clock
        homeScroll.setSmoothScrollingEnabled(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        homeScroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View upper = buildHomeUpper();
        content.addView(upper, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The upper part is as tall as the screen less the peek, whatever the screen's size.
        homeScroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int want = (b - t) - (homeContinue.getVisibility() == View.VISIBLE ? dp(HOME_PEEK) : 0);
            ViewGroup.LayoutParams lp = upper.getLayoutParams();
            if (want > 0 && lp.height != want) {
                lp.height = want;
                upper.post(upper::requestLayout);
            }
        });
        homeContinue = new LinearLayout(this);
        homeContinue.setOrientation(LinearLayout.VERTICAL);
        homeContinue.setClipChildren(false);
        homeContinue.setVisibility(View.GONE);
        TextView heading = text("Tiếp tục xem", 18, palette.onSurfaceVariant, 640);
        heading.setPadding(dp(4), dp(10), 0, 0);
        homeContinue.addView(heading);
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        homeContinueRow = new LinearLayout(this);
        homeContinueRow.setOrientation(LinearLayout.HORIZONTAL);
        homeContinueRow.setClipChildren(false);
        homeContinueRow.setClipToPadding(false);
        homeContinueRow.setPadding(dp(14), dp(18), dp(14), dp(24));
        scroller.addView(homeContinueRow);
        homeContinue.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(homeContinue, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return homeScroll;
    }

    private View buildHomeUpper() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.BOTTOM);
        page.setClipChildren(false);

        focusTitle = text("", 64, palette.onSurface, 820);
        focusTitle.setIncludeFontPadding(false);
        focusTitle.setSingleLine(true);   // a long Continue title would otherwise wrap up into the clock
        focusTitle.setEllipsize(TextUtils.TruncateAt.END);
        // Normal spacing and no ligatures: at display sizes "tf" or "fl" would otherwise touch.
        focusTitle.setLetterSpacing(0.005f);
        focusTitle.setFontFeatureSettings("'liga' 0, 'clig' 0, 'dlig' 0");
        focusTitle.setVisibility(View.GONE);

        // The dock: a rounded tonal container holding the favorites, like Material's floating toolbar.
        FrameLayout dockFrame = new FrameLayout(this);
        dockFrame.setClipChildren(false);
        dockShape = new GlassDrawable(dp(32), dp(1));
        // A floating panel over the hero: see-through, with a hairline light edge (glass without a blur).
        dockShape.setGlassColors(palette.container, palette.onSurface);
        dockFrame.setElevation(dp(8));
        dockFrame.setBackground(dockShape);
        dockFrame.setPadding(dp(DOCK_PAD), dp(4), dp(DOCK_PAD), dp(4));

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.setClipChildren(false);
        scroller.setPadding(dp(SCROLL_PAD), dp(14), dp(SCROLL_PAD), dp(4));
        dock = new LinearLayout(this);
        dock.setClipChildren(false);
        scroller.addView(dock);
        dockFrame.addView(scroller);
        page.addView(dockFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The TV channels row: what the TV's own channel apps publish to Android TV's channel row, in a slimmer
        // version of the dock under it. Hidden until the row is turned on and an app has published something.
        channelDock = new FrameLayout(this);
        channelDock.setClipChildren(false);
        channelShape = new GradientDrawable();
        channelShape.setCornerRadius(dp(32));
        channelShape.setColor(withAlpha(palette.container, DOCK_ALPHA));
        channelShape.setStroke(dp(1), withAlpha(palette.onSurface, DOCK_EDGE));
        channelDock.setBackground(channelShape);
        channelDock.setPadding(dp(DOCK_PAD), dp(4), dp(DOCK_PAD), dp(4));
        channelDock.setVisibility(View.GONE);
        HorizontalScrollView channelScroller = new HorizontalScrollView(this);
        channelScroller.setHorizontalScrollBarEnabled(false);
        channelScroller.setClipToPadding(false);
        channelScroller.setClipChildren(false);
        channelScroller.setPadding(dp(SCROLL_PAD), dp(12), dp(SCROLL_PAD), dp(4));
        channelRow = new LinearLayout(this);
        channelRow.setClipChildren(false);
        channelScroller.addView(channelRow);
        channelDock.addView(channelScroller);
        LinearLayout.LayoutParams channelsAt = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        channelsAt.topMargin = dp(CHANNEL_GAP);
        page.addView(channelDock, channelsAt);
        return page;
    }

    // ---- content ----

    private static final int DOCK_SIZE = 8;
    private static final int DOCK_ALPHA = 0x8C, DOCK_EDGE = 0x2E;
    /** Never in the dock: settings, the stock home screen and system helpers. */
    private static final java.util.Set<String> NOT_IN_DOCK = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.android.tv.settings", "com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith",
            "com.google.android.katniss", "com.android.vending", "com.android.systemui"));

    /** The dock: most-used apps of the last 14 days (left = most used), topped up from the TV's other installed apps. */
    private List<String> dockApps() {
        List<String> out = new ArrayList<>();
        Set<String> hidden = prefs.hidden();
        // Apps pinned from the tile menu come first, in the order they were arranged.
        for (String pkg : prefs.pinned()) {
            if (out.size() < DOCK_SIZE && launchIntent(pkg) != null && !out.contains(pkg) && !hidden.contains(pkg)) out.add(pkg);
        }
        // Screensaver apps rack up "use" while the screensaver runs: they never count toward the dock.
        java.util.Set<String> screensavers = new java.util.HashSet<>();
        for (ResolveInfo r : pm.queryIntentServices(new Intent("android.service.dreams.DreamService"), 0))
            screensavers.add(r.serviceInfo.packageName);
        try {
            android.app.usage.UsageStatsManager usm = getSystemService(android.app.usage.UsageStatsManager.class);
            long now = System.currentTimeMillis();
            List<android.app.usage.UsageStats> used = new ArrayList<>(usm.queryAndAggregateUsageStats(now - 14L * 86_400_000L, now).values());
            used.sort((a, b) -> Long.compare(b.getTotalTimeInForeground(), a.getTotalTimeInForeground()));
            for (android.app.usage.UsageStats u : used) {
                if (out.size() >= DOCK_SIZE || u.getTotalTimeInForeground() < 60_000) break;
                String pkg = u.getPackageName();
                if (pkg.equals(getPackageName()) || out.contains(pkg) || hidden.contains(pkg) || NOT_IN_DOCK.contains(pkg) || screensavers.contains(pkg) || launchIntent(pkg) == null) continue;
                out.add(pkg);
            }
        } catch (RuntimeException ignored) {
            // No usage access: the dock fills from installed apps below.
        }
        // HDMI devices that name themselves (CEC), e.g. "PlayStation 5", take the TV input app's place.
        String inputApp = inputApp();
        List<String> devices = cecDevices();
        int at = inputApp == null ? -1 : out.indexOf(inputApp);
        if (at >= 0 && !devices.isEmpty()) {
            out.remove(at);
            out.addAll(at, devices);
            while (out.size() > DOCK_SIZE) out.remove(out.size() - 1);
        } else if (!devices.isEmpty()) {
            // Not used much yet (a fresh TV): consoles still get a tile when there's room.
            for (String d : devices) if (out.size() < DOCK_SIZE) out.add(d);
        }
        boolean devicesShown = !devices.isEmpty() && out.contains(devices.get(0));
        // Top up from this TV's own installed apps: most recently used first, then the newest installs.
        if (out.size() < DOCK_SIZE) {
            java.util.Map<String, Long> lastUsed = new java.util.HashMap<>();
            try {
                android.app.usage.UsageStatsManager usm = getSystemService(android.app.usage.UsageStatsManager.class);
                long now = System.currentTimeMillis();
                for (android.app.usage.UsageStats u : usm.queryAndAggregateUsageStats(now - 90L * 86_400_000L, now).values())
                    lastUsed.put(u.getPackageName(), u.getLastTimeUsed());
            } catch (RuntimeException ignored) {
            }
            List<String> rest = new ArrayList<>();
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
                String pkg = info.activityInfo.packageName;
                if (!out.contains(pkg) && !rest.contains(pkg) && !hidden.contains(pkg) && !pkg.equals(getPackageName()) && !NOT_IN_DOCK.contains(pkg)
                        && !screensavers.contains(pkg) && !(devicesShown && pkg.equals(inputApp))) rest.add(pkg);
            }
            rest.sort((a, b) -> {
                int byUse = Long.compare(lastUsed.getOrDefault(b, 0L), lastUsed.getOrDefault(a, 0L));
                return byUse != 0 ? byUse : Long.compare(installTime(b), installTime(a));
            });
            for (String pkg : rest) {
                if (out.size() >= DOCK_SIZE) break;
                out.add(pkg);
            }
        }
        return out;
    }

    /** CEC-named HDMI devices: dock key "input:<id>" -> {label, parent input id}. */
    private final Map<String, String[]> inputDevices = new HashMap<>();

    private List<String> cecDevices() {
        List<String> keys = new ArrayList<>();
        inputDevices.clear();
        try {
            android.media.tv.TvInputManager tim = getSystemService(android.media.tv.TvInputManager.class);
            if (tim == null) return keys;
            for (android.media.tv.TvInputInfo in : tim.getTvInputList()) {
                if (in.getType() != android.media.tv.TvInputInfo.TYPE_HDMI || in.getParentId() == null) continue;
                CharSequence label = in.loadLabel(this);
                if (label == null || label.length() == 0) continue;
                String key = "input:" + in.getId();
                inputDevices.put(key, new String[]{label.toString(), in.getParentId()});
                // Consoles first.
                if (isConsole(label.toString())) keys.add(0, key); else keys.add(key);
            }
        } catch (RuntimeException ignored) {
        }
        return keys;
    }

    /** The app that plays HDMI inputs (the one a passthrough link opens), e.g. "Live TV". */
    private String inputApp() {
        ResolveInfo r = pm.resolveActivity(new Intent(Intent.ACTION_VIEW,
                android.media.tv.TvContract.buildChannelUriForPassthroughInput("x")), 0);
        return r != null && r.activityInfo != null ? r.activityInfo.packageName : null;
    }

    private static boolean isConsole(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        return l.contains("playstation") || l.matches(".*\\bps[2-5]\\b.*") || l.contains("xbox")
                || l.contains("nintendo") || l.contains("switch");
    }

    /**
     * A 16:9 banner for an HDMI device, Material You themed-icon style: a deep tone of the console's brand hue with a
     * large Material icon in a light tone of the same hue. No text (the name sits under the tile) and no logos.
     */
    private Drawable inputBanner(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        int brand = 0xFF5F6368;
        if (l.contains("playstation") || l.matches(".*\\bps[2-5]\\b.*")) brand = 0xFF0047AB;
        else if (l.contains("xbox")) brand = 0xFF107C10;
        else if (l.contains("nintendo") || l.contains("switch")) brand = 0xFFE60012;
        palettes.TonalPalette tones = palettes.TonalPalette.fromInt(brand);
        int icon = isConsole(label) ? R.drawable.ic_gamepad : R.drawable.ic_input_tv;
        Bitmap b = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(tones.tone(30));
        Drawable d = getDrawable(icon).mutate();
        d.setTint(tones.tone(90));
        int size = 250;
        d.setBounds((640 - size) / 2, (360 - size) / 2, (640 + size) / 2, (360 + size) / 2);
        d.draw(c);
        return new android.graphics.drawable.BitmapDrawable(getResources(), b);
    }

    private Intent dockIntent(String key) {
        String[] dev = inputDevices.get(key);
        if (dev != null) return new Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUriForPassthroughInput(dev[1]));
        return launchIntent(key);
    }

    private long installTime(String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0).firstInstallTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private void fillDock() {
        List<String> present = dockApps();
        // Kept between visits; rebuild only when the dock's order or an HDMI device's name changes.
        StringBuilder names = new StringBuilder();
        for (String p : present) {
            String[] dev = inputDevices.get(p);
            if (dev != null) names.append('|').append(dev[0]);
        }
        String key = TextUtils.join(",", present) + names;
        if (key.equals(dockKey) && dock.getChildCount() > 0) {
            if (!showingApps) dock.getChildAt(0).requestFocus();
            return;
        }
        dockKey = key;
        dock.removeAllViews();
        dockBadges.clear();
        deviceDots.clear();
        warmPalettes(present);
        int n = DOCK_SIZE;
        float screenDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        float inner = screenDp - 2 * (SIDE + DOCK_PAD + SCROLL_PAD) - (n - 1) * GAP - n * 2 * RING;
        // Keep eight tiles within the safe area, including rings and focus spacing.
        int tileDp = (int) Math.max(64, Math.min(220, inner / n));
        int i = 0;
        for (String pkg : present) {
            Intent intent = dockIntent(pkg);
            View tile = tile(pkg, intent, tileDp, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(GAP));
            dock.addView(tile, lp);
            arrive(tile, i++);
        }
        int back = refocusPkg != null ? present.indexOf(refocusPkg) : -1;
        refocusPkg = null;
        if (!showingApps && dock.getChildCount() > 0) dock.getChildAt(Math.max(0, back)).requestFocus();
        updateBadges();
    }

    private String refocusPkg;

    // ---- TV channels ----

    private static final int CHANNEL_TILE = 104, CHANNEL_GAP = 10;

    /** The row: the list kept on the TV at once, then what the TV's apps publish right now (read in the background). */
    private void fillChannels() {
        if (channelDock == null) return;
        if (!prefs.tvChannelsOn()) {
            channelDock.setVisibility(View.GONE);
            return;
        }
        List<TvChannels.Channel> cached = TvChannels.cached(prefs.tvChannelsRaw());
        if (!cached.isEmpty()) showChannels(cached);
        loadChannels();
    }

    private void loadChannels() {
        if (channelsLoading || !prefs.tvChannelsOn()) return;
        channelsLoading = true;
        TvChannels.load(handler, getContentResolver(), getPackageName(), list -> {
            channelsLoading = false;
            if (!prefs.tvChannelsOn()) return;
            List<TvChannels.Channel> channels = list != null ? list : new ArrayList<>();
            prefs.setTvChannelsRaw(TvChannels.json(channels));
            showChannels(channels);
        });
    }

    private void showChannels(List<TvChannels.Channel> channels) {
        if (channels.isEmpty() && channelRow.getChildCount() == 0) {
            channelList.clear();
            channelKey = "";
            channelDock.setVisibility(View.GONE);
            return;
        }
        channelDock.setVisibility(channels.isEmpty() ? View.GONE : View.VISIBLE);
        String key = TvChannels.key(channels);
        if (key.equals(channelKey) && channelRow.getChildCount() > 0) return;
        boolean keepFocus = channelRow.hasFocus();
        channelKey = key;
        channelList.clear();
        channelList.addAll(channels);
        channelRow.removeAllViews();
        for (int i = 0; i < channels.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(CHANNEL_GAP));
            View tile = channelTile(channels.get(i), i < 8);
            channelRow.addView(tile, lp);
            arrive(tile, Math.min(i, 6));
        }
        if (keepFocus && channelRow.getChildCount() > 0) channelRow.getChildAt(0).requestFocus();
    }

    /** A channel: a 16:9 card like a dock tile, with the logo the app published, or its name until that arrives. */
    private View channelTile(TvChannels.Channel ch, boolean eager) {
        int w = dp(CHANNEL_TILE), h = w * 9 / 16;
        int radius = dp(Focus.MEDIA);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setClipChildren(false);
        holder.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable cardShape = new GradientDrawable();
        cardShape.setCornerRadius(radius);
        cardShape.setColor(palette.containerHigh);
        card.setBackground(cardShape);
        card.setClipToOutline(true);

        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setPadding(dp(16), dp(10), dp(16), dp(10));
        card.addView(logo, match());
        // The name sits over the logo until it loads (a transparent logo would otherwise show the name through it).
        TextView mark = text(ch.title, 17, palette.onSurfaceVariant, 700);
        mark.setGravity(Gravity.CENTER);
        mark.setSingleLine(true);
        mark.setEllipsize(TextUtils.TruncateAt.END);
        mark.setPadding(dp(12), 0, dp(12), 0);
        card.addView(mark, match());
        card.setContentDescription(ch.title);
        if (eager) loadLogo(ch, logo, mark);

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius + dp(RING));
        ring.setColor(Color.TRANSPARENT);
        FrameLayout ringFrame = new FrameLayout(this);
        ringFrame.setClipChildren(false);
        ringFrame.setPadding(dp(RING), dp(RING), dp(RING), dp(RING));
        ringFrame.setBackground(ring);
        ringFrame.addView(card, new FrameLayout.LayoutParams(w, h));

        CookieDrawable loaderShape = new CookieDrawable(palette.primary, 4, 0.18f);
        ImageView loader = new ImageView(this);
        loader.setImageDrawable(loaderShape);
        loader.setVisibility(View.GONE);
        ringFrame.addView(loader, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        card.setOnClickListener(v -> {
            Intent intent = channelIntent(ch);
            if (intent != null) pressAndLaunch(ringFrame, loader, loaderShape, intent);
        });

        TextView caption = text(ch.title, 13, palette.onSurfaceVariant, 560);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setAlpha(0.85f);

        card.setOnFocusChangeListener((v, focused) -> {
            float scale = focused ? 1.10f : 1f;
            ringFrame.animate().scaleX(scale).scaleY(scale)
                    .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.duration(Motion.FAST_SPATIAL_MS)).start();
            card.setOutlineSpotShadowColor(palette.primary);
            card.setOutlineAmbientShadowColor(palette.primary);
            card.animate().translationZ(focused ? dp(14) : 0)
                    .setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.duration(Motion.DEFAULT_EFFECTS_MS)).start();
            animateCorners(cardShape, ring, focused ? radius * 0.62f : radius);
            fadeRing(ring, focused);
            shiftNeighbors(holder, focused);
            caption.animate().alpha(focused ? 1f : 0.85f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.duration(Motion.FAST_EFFECTS_MS)).start();
            caption.setTextColor(focused ? palette.onSurface : palette.onSurfaceVariant);
            if (focused) {
                loadLogo(ch, logo, mark);
                setFocusTitle(ch.title);
                if (homeScroll.getScrollY() != 0) homeScroll.smoothScrollTo(0, 0);
            }
        });

        holder.addView(ringFrame, new LinearLayout.LayoutParams(w + dp(2 * RING), h + dp(2 * RING)));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(w + dp(2 * RING), dp(22));
        cl.topMargin = dp(4);
        holder.addView(caption, cl);
        return holder;
    }

    /** The channel's own intent; else the app that published it. Null when neither opens anything. */
    private Intent channelIntent(TvChannels.Channel ch) {
        Intent intent = TvChannels.intent(ch);
        if (intent != null && pm.resolveActivity(intent, 0) != null) return intent;
        return launchIntent(ch.pkg);
    }

    /**
     * The logo from the address the channel app published (only while the row is on), kept in memory. The tile reads
     * the channel's name until it arrives, and keeps it when the app published no logo or the address fails.
     */
    private void loadLogo(TvChannels.Channel ch, ImageView logo, TextView mark) {
        if (ch.logo == null || ch.logo.isEmpty()) return;
        Bitmap known = channelLogos.get(ch.logo);
        if (known != null) {
            showLogo(logo, mark, known);
            return;
        }
        if (!logoLoading.add(ch.logo)) return;
        Photos.image(handler, getContentResolver(), ch.logo, dp(CHANNEL_TILE), bitmap -> {
            logoLoading.remove(ch.logo);
            if (bitmap == null) return;
            channelLogos.put(ch.logo, bitmap);
            showLogo(logo, mark, bitmap);
        });
    }

    private void showLogo(ImageView logo, TextView mark, Bitmap bitmap) {
        logo.setImageBitmap(bitmap);
        mark.setVisibility(View.GONE);
    }

    private void fillApps() {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> hidden = prefs.hidden();
        List<ResolveInfo> found = new ArrayList<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || hidden.contains(pkg) || !seen.add(pkg)) continue;
                found.add(info);
            }
        }
        // The grid is kept between visits; rebuild only when the set of installed apps or the folders changed.
        appsGrid.setColumnCount(prefs.columns());
        String key = prefs.columns() + ":" + TextUtils.join(",", seen) + "|" + prefs.foldersRaw();
        if (key.equals(appsKey) && appsGrid.getChildCount() > 0) return;
        appsKey = key;
        appsGrid.removeAllViews();
        // Read each label once (loading a label is slow), then sort.
        Map<ResolveInfo, String> labels = new HashMap<>();
        for (ResolveInfo info : found) labels.put(info, String.valueOf(info.loadLabel(pm)));
        Collections.sort(found, (a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
        // Five columns that exactly fill the width between the side margins (tile + focus-ring room + gaps).
        int cols = appsGrid.getColumnCount();
        float screenDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        float inner = screenDp - 2 * SIDE - cols * (2 * RING + 2 * APP_MARGIN);
        int tileDp = (int) Math.min(170, inner / cols);
        // Folders first, then the apps that are in none.
        Set<String> inFolders = new java.util.HashSet<>();
        Map<String, List<String>> folders = prefs.folders();
        List<String> names = new ArrayList<>(folders.keySet());
        Collections.sort(names, String::compareToIgnoreCase);
        for (String name : names) {
            List<String> pkgs = new ArrayList<>();
            for (String pkg : folders.get(name)) {
                inFolders.add(pkg);
                if (!hidden.contains(pkg) && launchIntent(pkg) != null) pkgs.add(pkg);
            }
            if (pkgs.isEmpty()) continue;
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(dp(APP_MARGIN), dp(10), dp(APP_MARGIN), dp(16));
            appsGrid.addView(folderTile(name, pkgs, tileDp), lp);
        }
        for (ResolveInfo info : found) {
            String pkg = info.activityInfo.packageName;
            Intent intent = launchIntent(pkg);
            if (intent == null || inFolders.contains(pkg)) continue;
            View tile = tile(pkg, intent, tileDp, false);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(dp(APP_MARGIN), dp(10), dp(APP_MARGIN), dp(16));
            appsGrid.addView(tile, lp);
        }
    }

    /** A 16:9 card: the app's TV banner, or its icon on a tonal card, with the name underneath. */
    private View tile(String pkg, Intent intent, int widthDp, boolean inDock) {
        int w = dp(widthDp);
        int h = w * 9 / 16;
        int radius = dp(Focus.MEDIA);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setClipChildren(false);
        holder.setGravity(Gravity.CENTER_HORIZONTAL);

        CharSequence name = pkg;
        Drawable banner = null;
        Drawable icon = null;
        String[] device = inputDevices.get(pkg);
        if (device != null) {
            name = device[0];
            banner = inputBanner(device[0]);
        } else try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            name = pm.getApplicationLabel(ai);
            banner = pm.getApplicationBanner(ai);
            if (banner == null) icon = pm.getApplicationIcon(ai);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        final CharSequence label = name;
        final Drawable art = banner != null ? banner : icon;

        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable cardShape = new GradientDrawable();
        cardShape.setCornerRadius(radius);
        cardShape.setColor(palette.containerHigh);
        card.setBackground(cardShape);
        card.setClipToOutline(true);
        ImageView image = new ImageView(this);
        if (banner != null) {
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageDrawable(banner);
            card.addView(image, match());
        } else {
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageDrawable(icon);
            card.addView(image, new FrameLayout.LayoutParams(h / 2, h / 2, Gravity.CENTER));
        }
        card.setContentDescription(label);

        // Material focus: the card grows with a little spring, its corners tighten, and a ring in the
        // accent color appears just outside it. The whole screen re-tints to the app's color.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius + dp(RING));
        ring.setColor(Color.TRANSPARENT);
        FrameLayout ringFrame = new FrameLayout(this);
        ringFrame.setClipChildren(false);
        ringFrame.setPadding(dp(RING), dp(RING), dp(RING), dp(RING));
        ringFrame.setBackground(ring);   // the ring sits in the gap around the card; badges draw above it
        ringFrame.addView(card, new FrameLayout.LayoutParams(w, h));

        // Expressive loading indicator: a small shape that morphs while the app takes its time to open.
        CookieDrawable loaderShape = new CookieDrawable(palette.primary, 4, 0.18f);
        ImageView loader = new ImageView(this);
        loader.setImageDrawable(loaderShape);
        loader.setVisibility(View.GONE);
        ringFrame.addView(loader, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        card.setOnClickListener(v -> pressAndLaunch(ringFrame, loader, loaderShape, intent));
        if (device == null) card.setOnLongClickListener(v -> {
            showTileMenu(pkg, label);
            return true;
        });

        TextView caption = text(label, inDock ? 15 : 14, palette.onSurfaceVariant, 560);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setPadding(0, dp(10), 0, 0);
        caption.setAlpha(inDock ? 0f : 0.85f);

        card.setOnFocusChangeListener((v, focused) -> {
            float scale = focused ? 1.10f : 1f;
            ringFrame.animate().scaleX(scale).scaleY(scale)
                    .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.duration(Motion.FAST_SPATIAL_MS)).start();
            // A glow in the app's own color (TV focus guidance) instead of a dark shadow.
            card.setOutlineSpotShadowColor(palette.primary);
            card.setOutlineAmbientShadowColor(palette.primary);
            card.animate().translationZ(focused ? dp(14) : 0)
                    .setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.duration(Motion.DEFAULT_EFFECTS_MS)).start();
            animateCorners(cardShape, ring, focused ? radius * 0.62f : radius);
            fadeRing(ring, focused);
            if (inDock) shiftNeighbors(holder, focused);
            if (inDock && focused && homeScroll.getScrollY() != 0) homeScroll.smoothScrollTo(0, 0);
            caption.animate().cancel();
            if (inDock && focused) {
                caption.setVisibility(View.VISIBLE);
                caption.animate().alpha(1f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.duration(Motion.FAST_EFFECTS_MS)).start();
            } else if (inDock) {
                caption.animate().alpha(0f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.duration(Motion.FAST_EFFECTS_MS))
                        .withEndAction(() -> caption.setVisibility(View.GONE)).start();
            } else {
                caption.animate().alpha(focused ? 1f : 0.85f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.duration(Motion.FAST_EFFECTS_MS)).start();
            }
            caption.setTextColor(focused ? palette.onSurface : palette.onSurfaceVariant);
            if (focused) onTileFocused(pkg, label, art);
        });

        // Status lives under the tile, never on the artwork: an equalizer for the app that's playing, a green dot for
        // an HDMI device that's on. Focused, the name appears beside it.
        LinearLayout captionRow = new LinearLayout(this);
        captionRow.setOrientation(LinearLayout.HORIZONTAL);
        captionRow.setGravity(Gravity.CENTER);
        captionRow.setClipChildren(false);
        caption.setPadding(0, 0, 0, 0);
        caption.setMaxWidth(w + dp(48) - dp(30));
        if (inDock) caption.setVisibility(View.GONE);
        if (inDock && device != null) {
            View dot = new View(this);
            GradientDrawable dotShape = new GradientDrawable();
            dotShape.setShape(GradientDrawable.OVAL);
            dotShape.setColor(0xFF6DD58C);                 // Material green, tone 80: "on"
            dot.setBackground(dotShape);
            dot.setVisibility(View.GONE);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
            dl.setMarginEnd(dp(8));
            captionRow.addView(dot, dl);
            String inputId = pkg.substring("input:".length());
            deviceDots.put(inputId, dot);
            android.media.tv.TvInputManager tim = getSystemService(android.media.tv.TvInputManager.class);
            if (tim != null) showDeviceState(inputId, tim.getInputState(inputId));
        }
        if (inDock) {
            EqualizerBadge badge = new EqualizerBadge(this);
            badge.setVisibility(View.GONE);
            badge.setColors(0x00000000, 0xFFE6E1E5);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(18), dp(22));
            bl.setMarginEnd(dp(6));
            captionRow.addView(badge, bl);
            dockBadges.put(pkg, badge);
        }
        captionRow.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.addView(ringFrame, new LinearLayout.LayoutParams(w + dp(2 * RING), h + dp(2 * RING)));
        // Fixed height so the dock never jumps; a little wider than the tile (negative margins keep the spacing) so a
        // name with a status mark beside it still fits.
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(w + dp(48), dp(24));
        rl.topMargin = dp(4);
        rl.leftMargin = -dp(24);
        rl.rightMargin = -dp(24);
        holder.addView(captionRow, rl);
        return holder;
    }

    /** The focus ring fades in (fast effects) in the palette's focus color instead of snapping on. */
    private void fadeRing(GradientDrawable ring, boolean focused) {
        ValueAnimator previous = ringAnimations.remove(ring);
        int opacity = previous != null ? (int) previous.getAnimatedValue() : focused ? 0 : 255;
        if (previous != null) previous.cancel();
        int color = palette.inverseSurface;
        if (Motion.reduced()) {
            ring.setStroke(dp(3), focused ? color : Color.TRANSPARENT);
            return;
        }
        ValueAnimator a = ValueAnimator.ofInt(opacity, focused ? 255 : 0).setDuration(Motion.FAST_EFFECTS_MS);
        a.setInterpolator(Motion.FAST_EFFECTS);
        a.addUpdateListener(an -> ring.setStroke(dp(3), (color & 0x00FFFFFF) | ((int) an.getAnimatedValue() << 24)));
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                ringAnimations.remove(ring);
            }
        });
        ringAnimations.put(ring, a);
        startUiAnimation(a);
    }

    /** Standard button-group behavior on the dock: the neighbors of the focused tile make a little room. */
    private void shiftNeighbors(View holder, boolean focused) {
        if (!(holder.getParent() instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) holder.getParent();
        int at = row.indexOfChild(holder);
        for (int i = 0; i < row.getChildCount(); i++) {
            float dx = !focused ? 0 : i == at - 1 ? -dp(8) : i == at + 1 ? dp(8) : 0;
            if (!focused && i != at - 1 && i != at + 1) continue;
            row.getChildAt(i).animate().translationX(dx)
                    .setInterpolator(Motion.DEFAULT_SPATIAL).setDuration(Motion.duration(Motion.DEFAULT_SPATIAL_MS)).start();
        }
    }

    private View activeLoader;
    private ValueAnimator loaderAnim;
    private final Runnable[] pendingLoader = new Runnable[1];

    /** Press feedback (a quick squash), then launch; if the app hasn't appeared after 300 ms, a shape loader morphs. */
    private void pressAndLaunch(View tile, ImageView loader, CookieDrawable shape, Intent intent) {
        if (!Motion.reduced()) tile.animate().scaleX(1.0f).scaleY(1.0f).setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.duration(Motion.FAST_EFFECTS_MS))
                .withEndAction(() -> tile.animate().scaleX(1.10f).scaleY(1.10f)
                        .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.duration(Motion.FAST_SPATIAL_MS)).start()).start();
        launch(intent);
        if (pendingLoader[0] != null) handler.removeCallbacks(pendingLoader[0]);
        pendingLoader[0] = () -> {
            hideLoader();
            activeLoader = loader;
            shape.setColor(palette.primary);
            loader.setAlpha(0f);
            loader.setVisibility(View.VISIBLE);
            loader.animate().alpha(1f).setDuration(Motion.duration(Motion.FAST_EFFECTS_MS)).start();
            if (Motion.reduced()) return;
            final int[] seq = {4, 6, 7, 9, 12, 7, 6};
            final int[] step = {0};
            loaderAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(420);
            loaderAnim.setRepeatCount(ValueAnimator.INFINITE);
            loaderAnim.setInterpolator(Motion.DEFAULT_SPATIAL);
            loaderAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationRepeat(android.animation.Animator a) {
                    step[0] = (step[0] + 1) % seq.length;
                    shape.morphTo(seq[step[0]], seq[step[0]] == 4 ? 0.18f : 0.10f);
                }
            });
            shape.morphTo(seq[0], 0.18f);
            loaderAnim.addUpdateListener(an -> {
                shape.setProgress((float) an.getAnimatedValue());
                loader.setRotation(loader.getRotation() + 2.5f);
            });
            loaderAnim.start();
        };
        handler.postDelayed(pendingLoader[0], 300);
    }

    private void hideLoader() {
        if (pendingLoader[0] != null) handler.removeCallbacks(pendingLoader[0]);
        if (loaderAnim != null) loaderAnim.cancel();
        if (activeLoader != null) activeLoader.setVisibility(View.GONE);
        activeLoader = null;
    }

    private void onTileFocused(String pkg, CharSequence label, Drawable art) {
        pendingPkg = pkg;
        pendingLabel = label;
        pendingArt = art;
        handler.removeCallbacks(settle);
        handler.postDelayed(settle, 120);
    }

    /** Runs once focus has rested for a moment: the title, the background shape and the colors follow the app. */
    private void applyFocus(String pkg, CharSequence label, Drawable art) {
        if (pkg == null) return;
        if (!showingApps) setFocusTitle(label);
        retint(paletteFor(pkg, art));
        if (!showingApps) showHeroArt(pkg, heroPosters.get(pkg), art);
    }

    /** The name above the dock: Home only, and only when it changes (a long channel row would re-animate it). */
    private void setFocusTitle(CharSequence label) {
        if (focusTitle.getVisibility() != View.VISIBLE || label == null || TextUtils.equals(focusTitle.getText(), label)) return;
        focusTitle.setText(label);
        focusTitle.setAlpha(0f);
        focusTitle.setTranslationY(dp(12));
        focusTitle.animate().alpha(1f).translationY(0).setInterpolator(Motion.DEFAULT_SPATIAL).setDuration(Motion.duration(Motion.DEFAULT_SPATIAL_MS)).start();
        animateTitleWeight();
    }

    // ---- hero ----

    private final ImageView[] heroViews = new ImageView[2];
    private int heroFront;
    private String heroKey;
    private View heroScrim;
    private GradientDrawable heroSide, heroBottom;
    /** Posters of the Continue items on Home, by focus key: shown sharp. Apps only have a banner: shown as a soft wash. */
    private final Map<String, Bitmap> heroPosters = new HashMap<>();
    private final Map<String, Bitmap> heroWashes = new java.util.LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 12;   // 225 KB each
        }
    };

    private void styleHeroScrim(int surface) {
        heroSide.setColors(new int[]{withAlpha(surface, 0xF2), withAlpha(surface, 0x99), withAlpha(surface, 0x26)});
        heroBottom.setColors(new int[]{withAlpha(surface, 0xF2), withAlpha(surface, 0x73), withAlpha(surface, 0)});
    }

    /** Cross-fade the hero to this item's art: a poster as is, otherwise the app's banner as a soft wash of its colors. */
    private void showHeroArt(String key, Bitmap poster, Drawable art) {
        if (prefs.wallpaper() != null) return;   // a chosen wallpaper is the background: no hero over it
        if (key.equals(heroKey)) return;
        Bitmap b = poster != null ? poster : wash(key, art);
        if (b == null) return;
        heroKey = key;
        ImageView back = heroViews[1 - heroFront];
        ImageView front = heroViews[heroFront];
        back.setImageBitmap(b);
        back.animate().cancel();
        back.animate().alpha(1f).setInterpolator(Motion.DEFAULT_EFFECTS).setDuration(Motion.duration(Motion.DEFAULT_EFFECTS_MS * 2)).start();
        front.animate().cancel();
        front.animate().alpha(0f).setInterpolator(Motion.DEFAULT_EFFECTS).setDuration(Motion.duration(Motion.DEFAULT_EFFECTS_MS * 2)).start();
        heroFront = 1 - heroFront;
        if (heroScrim.getAlpha() < 1f) heroScrim.animate().alpha(1f).setDuration(Motion.duration(Motion.DEFAULT_EFFECTS_MS)).start();
    }

    /**
     * The art drawn at a TV banner's own size (320 x 180), cropped to fill: the view scales it up with filtering, so
     * the app's banner reads clearly behind Home (a 16 x 9 wash was too blurry to tell apps apart). Made once per app.
     */
    private Bitmap wash(String key, Drawable art) {
        if (art == null) return null;
        Bitmap b = heroWashes.get(key);
        if (b != null) return b;
        int w = 320, h = 180;
        b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int iw = Math.max(1, art.getIntrinsicWidth()), ih = Math.max(1, art.getIntrinsicHeight());
        float scale = Math.max((float) w / iw, (float) h / ih);   // cover, like CENTER_CROP
        int dw = Math.round(iw * scale), dh = Math.round(ih * scale);
        Drawable d = art.getConstantState() != null ? art.getConstantState().newDrawable().mutate() : art;
        d.setBounds((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
        d.draw(c);
        heroWashes.put(key, b);
        return b;
    }

    /** The hero belongs to Home: it fades away on the Apps and Continue pages and comes back with Home. */
    private void fadeHero(boolean on) {
        float target = on && heroKey != null && prefs.wallpaper() == null ? 1f : 0f;
        heroViews[heroFront].animate().alpha(target).setDuration(Motion.duration(Motion.DEFAULT_EFFECTS_MS)).start();
        heroScrim.animate().alpha(target).setDuration(Motion.duration(Motion.DEFAULT_EFFECTS_MS)).start();
    }

    private Palette paletteFor(String pkg, Drawable art) {
        int fixed = prefs.colorSeed();
        if (fixed != 0) {                               // a color theme chosen in Launcher settings
            Palette theme = paletteCache.get(fixed);
            if (theme == null) {
                theme = Palette.of(fixed);
                paletteCache.put(fixed, theme);
            }
            return theme;
        }
        Integer seed = seedCache.get(pkg);
        if (seed == null) {
            seed = Palette.seedFrom(art);
            seedCache.put(pkg, seed);
        }
        Palette p = paletteCache.get(seed);
        if (p == null) {
            p = Palette.of(seed);
            paletteCache.put(seed, p);
        }
        return p;
    }

    /** Work out every favorite's colors on a background thread, so the first focus on each app costs nothing. */
    private void warmPalettes(List<String> packages) {
        new Thread(() -> {
            for (String pkg : packages) {
                if (seedCache.containsKey(pkg)) continue;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    Drawable art = pm.getApplicationBanner(ai);
                    if (art == null) art = pm.getApplicationIcon(ai);
                    int seed = Palette.seedFrom(art);
                    seedCache.put(pkg, seed);
                    if (!paletteCache.containsKey(seed)) paletteCache.put(seed, Palette.of(seed));
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                }
            }
        }, "palette-warmup").start();
    }

    /** Expressive type: the app name lands a little lighter and settles into its full weight. */
    private void animateTitleWeight() {
        ValueAnimator a = ValueAnimator.ofInt(0, 6).setDuration(Motion.duration(Motion.DEFAULT_SPATIAL_MS));
        a.setInterpolator(Motion.DEFAULT_EFFECTS);
        a.addUpdateListener(an -> focusTitle.setTypeface(titleFont(560 + 30 * (int) an.getAnimatedValue())));
        startUiAnimation(a);
    }

    private Typeface titleFont(int weight) {
        Typeface t = titleFonts.get(weight);
        if (t == null) {
            try {
                t = new Typeface.Builder(getAssets(), "fonts/GoogleSansFlex.ttf")
                        .setFontVariationSettings("'wght' " + weight + ", 'opsz' 48, 'ROND' 0").build();
            } catch (RuntimeException e) {
                t = font(820);
            }
            titleFonts.put(weight, t);
        }
        return t;
    }

    /** Each app gets its own Expressive shape; moving between apps morphs one outline into the next. */
    private void morphShape(String pkg) {
        // Material 3 Expressive shapes (MaterialShapes): Cookie 9, Cookie 12, Clover 4-leaf, Clover 8-leaf, Sunny,
        // Soft burst, Cookie 6, Cookie 7, Cookie 4.
        int[] lobes = {9, 12, 4, 8, 14, 10, 6, 7, 4};
        float[] depths = {0.08f, 0.06f, 0.26f, 0.16f, 0.05f, 0.11f, 0.12f, 0.10f, 0.16f};
        float[] sharp = {1f, 1f, 0.55f, 0.65f, 1f, 2.4f, 1f, 1f, 1f};
        int k = Math.abs(pkg.hashCode()) % lobes.length;
        cookie.morphTo(lobes[k], depths[k], sharp[k]);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(Motion.duration(Motion.DEFAULT_SPATIAL_MS));
        a.setInterpolator(Motion.DEFAULT_SPATIAL);
        a.addUpdateListener(an -> cookie.setProgress((float) an.getAnimatedValue()));
        startUiAnimation(a);
    }

    /** Cross-fade every tinted surface from the current palette to the next. */
    private void retint(Palette next) {
        if (next.seed == palette.seed) return;
        final Palette from = palette;
        palette = next;
        if (colorAnimator != null) colorAnimator.cancel();
        ArgbEvaluator argb = new ArgbEvaluator();
        colorAnimator = ValueAnimator.ofFloat(0f, 1f);
        colorAnimator.setDuration(Motion.duration(Motion.DEFAULT_EFFECTS_MS));
        colorAnimator.setInterpolator(Motion.DEFAULT_EFFECTS);
        colorAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            int top = (int) argb.evaluate(t, from.surfaceBright, next.surfaceBright);
            int bottom = (int) argb.evaluate(t, from.surface, next.surface);
            backdrop.setColors(new int[]{top, bottom});
            wallpaperScrim.setBackgroundColor(withAlpha(bottom, WALLPAPER_DIM));
            cookie.setColor((int) argb.evaluate(t, from.primaryContainer, next.primaryContainer));
            dockShape.setGlassColors((int) argb.evaluate(t, from.container, next.container),
                    (int) argb.evaluate(t, from.onSurface, next.onSurface));
            if (channelShape != null) {
                channelShape.setColor(withAlpha((int) argb.evaluate(t, from.container, next.container), DOCK_ALPHA));
                channelShape.setStroke(dp(1), withAlpha((int) argb.evaluate(t, from.onSurface, next.onSurface), DOCK_EDGE));
            }
            styleHeroScrim(bottom);
        });
        colorAnimator.start();
        nav.setPalette(next);
        styleNowPlaying();
    }

    private ImageView wallpaperView;
    private View wallpaperScrim;
    private static final int WALLPAPER_DIM = 0xA6;

    private String shownWallpaper;
    private final Runnable shuffleWallpaper = this::applyWallpaper;

    /**
     * Shows the chosen bundled photo behind everything (decoded off the main thread), or just the colors. "Random"
     * picks another photo once its time is up; the change is checked while Home is up (and on every return to it),
     * never in the background.
     */
    private void applyWallpaper() {
        handler.removeCallbacks(shuffleWallpaper);
        String file = prefs.wallpaper();
        if (prefs.randomWallpaper()) {
            List<String> files = Wallpapers.files(getAssets());
            long every = prefs.wallpaperEvery() * 60_000L;
            long age = System.currentTimeMillis() - prefs.wallpaperAt();
            file = prefs.wallpaperNow();
            if (file == null || !files.contains(file) || age >= every || age < 0) {
                List<String> others = new ArrayList<>(files);
                if (others.size() > 1) others.remove(file);
                file = others.isEmpty() ? null : others.get(new java.util.Random().nextInt(others.size()));
                prefs.setWallpaperNow(file);
                age = 0;
            }
            if (foreground) handler.postDelayed(shuffleWallpaper, every - age);
        }
        if (file == null) {
            shownWallpaper = null;
            wallpaperView.setImageBitmap(null);
            wallpaperView.setVisibility(View.GONE);
            wallpaperScrim.setVisibility(View.GONE);
            return;
        }
        if (file.equals(shownWallpaper) && wallpaperView.getVisibility() == View.VISIBLE) return;
        String want = file;
        Photos.asset(handler, getAssets(), Wallpapers.path(want), bitmap -> {
            String now = prefs.randomWallpaper() ? prefs.wallpaperNow() : prefs.wallpaper();
            if (bitmap == null || !want.equals(now)) return;
            shownWallpaper = want;
            wallpaperView.setImageBitmap(bitmap);
            wallpaperView.setAlpha(0f);
            wallpaperView.setVisibility(View.VISIBLE);
            wallpaperView.animate().alpha(1f).setInterpolator(Motion.DEFAULT_EFFECTS).setDuration(Motion.duration(Motion.DEFAULT_EFFECTS_MS * 2)).start();
            wallpaperScrim.setVisibility(View.VISIBLE);
        });
    }

    private String wallpaperLabel() {
        String w = prefs.wallpaper();
        if (w == null) return "Tắt";
        if (prefs.randomWallpaper()) return "Ngẫu nhiên, " + everyLabel(prefs.wallpaperEvery());
        return Wallpapers.label(w);
    }

    private static String everyLabel(int minutes) {
        if (minutes < 60) return "mỗi " + minutes + " phút";
        if (minutes == 60) return "mỗi giờ";
        if (minutes == 1440) return "mỗi ngày";
        return "mỗi " + minutes / 60 + " giờ";
    }

    private void chooseWallpaper() {
        List<String> files = Wallpapers.files(getAssets());
        String[] labels = new String[files.size() + 2];
        labels[0] = "Tắt (chỉ dùng màu nền)";
        labels[1] = "Ngẫu nhiên (" + everyLabel(prefs.wallpaperEvery()) + ")";
        for (int i = 0; i < files.size(); i++) labels[i + 2] = Wallpapers.label(files.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Ảnh nền")
                .setItems(labels, (d, which) -> {
                    prefs.setWallpaper(which == 0 ? null : which == 1 ? Prefs.RANDOM_WALLPAPER : files.get(which - 2));
                    wallpaperChanged(which == 0);
                    if (which == 1) chooseWallpaperEvery();
                    else showLauncherSettings();
                })
                .show();
    }

    private void chooseWallpaperEvery() {
        int[] options = Prefs.WALLPAPER_EVERY;
        String[] labels = new String[options.length];
        int checked = 0;
        for (int i = 0; i < options.length; i++) {
            labels[i] = "Đổi " + everyLabel(options[i]);
            if (options[i] == prefs.wallpaperEvery()) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Ảnh nền ngẫu nhiên")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    prefs.setWallpaperEvery(options[which]);
                    applyWallpaper();
                    d.dismiss();
                    showLauncherSettings();
                })
                .show();
    }

    /** A wallpaper replaces the hero; "Tắt" brings the hero back for the focused app. */
    private void wallpaperChanged(boolean off) {
        shownWallpaper = null;
        applyWallpaper();
        heroKey = null;
        fadeHero(false);
        if (off && pendingPkg != null) showHeroArt(pendingPkg, heroPosters.get(pendingPkg), pendingArt);
    }

    /** After the color theme changed: the fixed palette now, or the focused app's colors again. */
    private void applyColorTheme() {
        if (prefs.colorSeed() != 0) retint(paletteFor("", null));
        else if (pendingPkg != null) applyFocus(pendingPkg, pendingLabel, pendingArt);
    }

    private Intent launchIntent(String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    // ---- tile menu and launcher settings ----

    /** Hold OK on a tile: pin it to the dock, arrange the pinned apps, or open its app info. */
    private void showTileMenu(String pkg, CharSequence label) {
        List<String> pinned = prefs.pinned();
        int at = pinned.indexOf(pkg);
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (at < 0) {
            items.add("Ghim vào thanh ứng dụng");
            actions.add(() -> {
                if (pinned.size() >= DOCK_SIZE) {
                    Toast.makeText(this, "Thanh ứng dụng chứa tối đa " + DOCK_SIZE + " ứng dụng ghim. Hãy bỏ ghim một ứng dụng trước.", Toast.LENGTH_SHORT).show();
                    return;
                }
                pinned.add(pkg);
                savePinned(pinned, pkg);
            });
        } else {
            items.add("Bỏ ghim khỏi thanh ứng dụng");
            actions.add(() -> {
                pinned.remove(pkg);
                savePinned(pinned, null);
            });
            if (at > 0) {
                items.add("Di chuyển sang trái");
                actions.add(() -> {
                    Collections.swap(pinned, at, at - 1);
                    savePinned(pinned, pkg);
                });
            }
            if (at < pinned.size() - 1) {
                items.add("Di chuyển sang phải");
                actions.add(() -> {
                    Collections.swap(pinned, at, at + 1);
                    savePinned(pinned, pkg);
                });
            }
        }
        String inFolder = folderOf(pkg);
        if (inFolder != null) {
            items.add("Bỏ khỏi thư mục (" + inFolder + ")");
            actions.add(() -> {
                Map<String, List<String>> folders = prefs.folders();
                folders.get(inFolder).remove(pkg);
                prefs.setFolders(folders);
                if (folderDialog != null) folderDialog.dismiss();
                refreshApps();
            });
        } else {
            items.add("Thêm vào thư mục");
            actions.add(() -> chooseFolder(pkg));
        }
        items.add("Ẩn ứng dụng");
        actions.add(() -> {
            Set<String> hidden = prefs.hidden();
            hidden.add(pkg);
            prefs.setHidden(hidden);
            pinned.remove(pkg);
            prefs.setPinned(pinned);
            Toast.makeText(this, label + " đã được ẩn. Bạn có thể hiện lại trong cài đặt giao diện.", Toast.LENGTH_LONG).show();
            refreshApps();
        });
        if (canUninstall(pkg)) {
            items.add("Gỡ cài đặt");
            actions.add(() -> launch(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))));
        }
        items.add("Thông tin ứng dụng");
        actions.add(() -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))));
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    /** Apps the user installed; system apps cannot be uninstalled (Android would only offer to disable them). */
    private boolean canUninstall(String pkg) {
        try {
            return (pm.getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The dock and the Apps page again, after pinned or hidden apps changed. */
    private void refreshApps() {
        dockKey = null;
        fillDock();
        if (showingApps) {
            appsKey = null;
            fillApps();
        }
    }

    private void savePinned(List<String> pinned, String focusPkg) {
        prefs.setPinned(pinned);
        refocusPkg = focusPkg;
        dockKey = null;
        fillDock();
    }

    /** Hold OK on Settings: the launcher's own choices. */
    /** Opens Launcher Manager, or reads its values again when it is already up (every setting action ends here). */
    private void showLauncherSettings() {
        showManager();
    }

    private List<ManagerPage.Section> managerSections() {
        List<ManagerPage.Section> out = new ArrayList<>();
        out.add(new ManagerPage.Section("Giao diện")
                .row("Ảnh nền", this::wallpaperLabel, this::chooseWallpaper)
                .row("Chu kỳ đổi ảnh nền", () -> prefs.randomWallpaper() ? everyLabel(prefs.wallpaperEvery()) : "Đang tắt ngẫu nhiên",
                        () -> {
                            if (!prefs.randomWallpaper()) {
                                prefs.setWallpaper(Prefs.RANDOM_WALLPAPER);
                                wallpaperChanged(false);
                            }
                            chooseWallpaperEvery();
                        })
                .row("Màu sắc", () -> Prefs.COLOR_NAMES[prefs.colorTheme()], () -> {
                    prefs.setColorTheme((prefs.colorTheme() + 1) % Prefs.COLOR_NAMES.length);
                    applyColorTheme();
                    showLauncherSettings();
                })
                .row("Hiệu ứng chuyển động", () -> prefs.reducedMotion() ? "Tắt" : "Bật", () -> {
                    prefs.setReducedMotion(!prefs.reducedMotion());
                    Motion.setReduced(prefs.reducedMotion());
                    drifting = !prefs.reducedMotion();
                    shape.removeCallbacks(drift);
                    if (drifting && foreground) {
                        driftLast = 0;
                        shape.postOnAnimation(drift);
                    }
                    showLauncherSettings();
                }));
        out.add(new ManagerPage.Section("Trang chủ và thanh ứng dụng")
                .row("Ứng dụng trên thanh", () -> prefs.pinned().isEmpty() ? "Theo mức sử dụng" : prefs.pinned().size() + " đã ghim", this::chooseDockApps)
                .row("Tiếp tục xem", () -> prefs.continueOn() ? "Bật" : "Tắt", () -> {
                    prefs.setContinueOn(!prefs.continueOn());
                    applyContinueTab();
                    fillHomeContinue();
                    showLauncherSettings();
                })
                .row("Kênh truyền hình", () -> !prefs.tvChannelsOn() ? "Tắt"
                        : channelList.isEmpty() ? "Chưa tìm thấy" : channelList.size() + (channelList.size() == 1 ? " kênh" : " kênh"), () -> {
                    prefs.setTvChannelsOn(!prefs.tvChannelsOn());
                    if (prefs.tvChannelsOn()) {
                        fillChannels();
                    } else {
                        channelDock.setVisibility(View.GONE);
                        channelKey = "";
                    }
                    showLauncherSettings();
                })
                .row("Mở khi khởi động", () -> {
                    String startApp = prefs.startApp();
                    return startApp != null && launchIntent(startApp) != null ? String.valueOf(appLabel(startApp)) : "Tắt";
                }, this::chooseStartApp));
        out.add(new ManagerPage.Section("Ứng dụng")
                .row("Ứng dụng đã ẩn", () -> prefs.hidden().isEmpty() ? "Không có" : String.valueOf(prefs.hidden().size()), this::chooseHiddenApps)
                .row("Thư mục", () -> {
                    int n = prefs.folders().size();
                    return n == 0 ? "Không có" : n + (n == 1 ? " thư mục" : " thư mục");
                }, this::manageFolders)
                .row("Số ứng dụng mỗi hàng", () -> String.valueOf(prefs.columns()), () -> {
                    int next = prefs.columns() + 1;
                    prefs.setColumns(next > Prefs.MAX_COLUMNS ? Prefs.MIN_COLUMNS : next);
                    showLauncherSettings();
                }));
        out.add(new ManagerPage.Section("Đồng hồ và thời tiết")
                .row("Đồng hồ", () -> new String[]{"Theo thiết bị", "12 giờ", "24 giờ"}[prefs.clockMode()], () -> {
                    prefs.setClockMode((prefs.clockMode() + 1) % 3);
                    handler.removeCallbacks(tick);
                    handler.post(tick);
                    showLauncherSettings();
                })
                .row("Thời tiết", () -> !prefs.weatherOn() ? "Tắt" : prefs.weatherManual() ? prefs.weatherCity()
                        : "Vị trí của tôi" + (prefs.weatherCity().isEmpty() ? "" : " · " + prefs.weatherCity().split(",")[0]), this::showWeatherSettings)
                .row("Đơn vị nhiệt độ", () -> prefs.fahrenheit() ? "°F" : "°C", () -> {
                    prefs.setFahrenheit(!prefs.fahrenheit());
                    showWeather();
                    showLauncherSettings();
                }));
        out.add(new ManagerPage.Section("Bảo vệ màn hình")
                .row("Ảnh bảo vệ màn hình", () -> new String[]{"Tắt", "Ảnh trực tuyến (cần mạng)", "Ảnh nền có sẵn (ngoại tuyến)"}[prefs.photosMode()], () -> {
                    prefs.setPhotosMode((prefs.photosMode() + 1) % 3);
                    showLauncherSettings();
                }));
        out.add(new ManagerPage.Section("Dữ liệu")
                .row("Nội dung đã phát", () -> String.valueOf(prefs.recent().size()), () -> {
                    if (prefs.recent().isEmpty()) return;
                    new AlertDialog.Builder(this)
                            .setTitle("Xóa lịch sử phát?")
                            .setMessage("Xóa lịch sử phát đã lưu trên thiết bị khỏi mục Tiếp tục xem.")
                            .setPositiveButton("Xóa", (d, w) -> {
                                prefs.clearRecent();
                                RecentArtwork.prune(this);
                                posters.clear();
                                heroPosters.clear();
                                fillHomeContinue();
                                showLauncherSettings();
                            })
                            .setNegativeButton("Hủy", null)
                            .show();
                })
                .row("Đặt lại cài đặt giao diện", null, this::confirmReset));
        return out;
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Đặt lại cài đặt giao diện?")
                .setMessage("Khôi phục mặc định cho hiệu ứng, số cột, ảnh nền, màu sắc, đồng hồ, thời tiết, bảo vệ màn hình, mục Tiếp tục xem và ứng dụng khởi động. Giữ lại ứng dụng ghim, ứng dụng ẩn và thư mục.")
                .setPositiveButton("Đặt lại", (d, w) -> {
                    prefs.resetSettings();
                    RecentArtwork.prune(this);
                    Motion.setReduced(prefs.reducedMotion());
                    applyColorTheme();
                    wallpaperChanged(true);
                    drifting = true;
                    shape.removeCallbacks(drift);
                    if (foreground) {
                        driftLast = 0;
                        shape.postOnAnimation(drift);
                    }
                    handler.removeCallbacks(tick);
                    handler.post(tick);
                    appsKey = null;
                    showWeather();
                    applyContinueTab();
                    fillHomeContinue();
                    showLauncherSettings();
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    // ---- Launcher Manager: apps ----

    /** Every app with a TV or phone launcher entry except this one, sorted by name: package -> label. */
    private Map<String, String> launchableApps() {
        Map<String, String> names = new HashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = info.activityInfo.packageName;
                if (!pkg.equals(getPackageName()) && !names.containsKey(pkg)) names.put(pkg, String.valueOf(info.loadLabel(pm)));
            }
        }
        List<String> sorted = new ArrayList<>(names.keySet());
        Collections.sort(sorted, (a, b) -> names.get(a).compareToIgnoreCase(names.get(b)));
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String p : sorted) out.put(p, names.get(p));
        return out;
    }

    /** Tick up to six apps for the dock; ticked apps keep their order, new ones go after them. */
    private void chooseDockApps() {
        Map<String, String> apps = launchableApps();
        List<String> pkgs = new ArrayList<>(apps.keySet());
        List<String> pinned = prefs.pinned();
        boolean[] checked = new boolean[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) checked[i] = pinned.contains(pkgs.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Chọn tối đa " + DOCK_SIZE + " ứng dụng; chỗ trống xếp theo mức sử dụng")
                .setMultiChoiceItems(apps.values().toArray(new String[0]), checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton("Lưu", (d, w) -> {
                    List<String> next = new ArrayList<>();
                    for (String p : pinned) if (pkgs.contains(p) && checked[pkgs.indexOf(p)]) next.add(p);
                    for (int i = 0; i < pkgs.size(); i++) if (checked[i] && !next.contains(pkgs.get(i))) next.add(pkgs.get(i));
                    if (next.size() > DOCK_SIZE) {
                        next = new ArrayList<>(next.subList(0, DOCK_SIZE));
                        Toast.makeText(this, "Thanh ứng dụng chứa tối đa " + DOCK_SIZE + " ứng dụng: đã giữ lại " + DOCK_SIZE + " ứng dụng đầu tiên.", Toast.LENGTH_LONG).show();
                    }
                    prefs.setPinned(next);
                    refreshApps();
                    showLauncherSettings();
                })
                .setNeutralButton("Xóa", (d, w) -> {
                    prefs.setPinned(new ArrayList<>());
                    refreshApps();
                    showLauncherSettings();
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void chooseHiddenApps() {
        Map<String, String> apps = launchableApps();
        List<String> pkgs = new ArrayList<>(apps.keySet());
        Set<String> hidden = prefs.hidden();
        boolean[] checked = new boolean[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) checked[i] = hidden.contains(pkgs.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Ẩn ứng dụng (không gỡ cài đặt)")
                .setMultiChoiceItems(apps.values().toArray(new String[0]), checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton("Lưu", (d, w) -> {
                    Set<String> next = new java.util.HashSet<>();
                    for (int i = 0; i < pkgs.size(); i++) if (checked[i]) next.add(pkgs.get(i));
                    prefs.setHidden(next);
                    refreshApps();
                    showLauncherSettings();
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void manageFolders() {
        Map<String, List<String>> folders = prefs.folders();
        List<String> names = new ArrayList<>(folders.keySet());
        Collections.sort(names, String::compareToIgnoreCase);
        String[] labels = new String[names.size() + 1];
        labels[0] = "Tạo thư mục…";
        for (int i = 0; i < names.size(); i++) labels[i + 1] = names.get(i) + " (" + folders.get(names.get(i)).size() + ")";
        new AlertDialog.Builder(this)
                .setTitle("Thư mục")
                .setItems(labels, (d, which) -> {
                    if (which == 0) askFolderName();
                    else editFolder(names.get(which - 1));
                })
                .show();
    }

    private void askFolderName() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Tên thư mục");
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        new AlertDialog.Builder(this)
                .setTitle("Tạo thư mục")
                .setView(input)
                .setPositiveButton("Tiếp tục", (d, w) -> {
                    String name = input.getText().toString().trim().replace(",", " ");
                    if (!name.isEmpty()) editFolder(name);
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    /** Tick the apps in a folder; an app moves here from any other folder (an app is in one folder at most). */
    private void editFolder(String name) {
        Map<String, String> apps = launchableApps();
        List<String> pkgs = new ArrayList<>(apps.keySet());
        Map<String, List<String>> folders = prefs.folders();
        List<String> in = folders.containsKey(name) ? folders.get(name) : new ArrayList<>();
        boolean[] checked = new boolean[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) checked[i] = in.contains(pkgs.get(i));
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Ứng dụng trong “" + name + "”")
                .setMultiChoiceItems(apps.values().toArray(new String[0]), checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton("Lưu", (d, w) -> {
                    Map<String, List<String>> all = prefs.folders();
                    List<String> chosen = new ArrayList<>();
                    for (int i = 0; i < pkgs.size(); i++) if (checked[i]) chosen.add(pkgs.get(i));
                    for (Map.Entry<String, List<String>> e : all.entrySet()) if (!e.getKey().equals(name)) e.getValue().removeAll(chosen);
                    all.put(name, chosen);
                    prefs.setFolders(all);
                    appsKey = "";
                    refreshApps();
                    showLauncherSettings();
                })
                .setNegativeButton("Hủy", null);
        if (folders.containsKey(name)) b.setNeutralButton("Xóa thư mục", (d, w) -> {
            Map<String, List<String>> all = prefs.folders();
            all.remove(name);
            prefs.setFolders(all);
            appsKey = "";
            refreshApps();
            showLauncherSettings();
        });
        b.show();
    }

    private String versionName() {
        try {
            return pm.getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private void chooseStartApp() {
        List<String> pkgs = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || names.containsKey(pkg)) continue;
            names.put(pkg, String.valueOf(info.loadLabel(pm)));
            pkgs.add(pkg);
        }
        Collections.sort(pkgs, (a, b) -> names.get(a).compareToIgnoreCase(names.get(b)));
        String[] labels = new String[pkgs.size() + 1];
        labels[0] = "Tắt (mở trang chủ)";
        for (int i = 0; i < pkgs.size(); i++) labels[i + 1] = names.get(pkgs.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Mở khi khởi động")
                .setItems(labels, (d, which) -> {
                    prefs.setStartApp(which == 0 ? null : pkgs.get(which - 1));
                    showLauncherSettings();
                })
                .show();
    }

    // ---- weather ----

    private static final long WEATHER_EVERY_MS = 30 * 60_000L, LOCATE_EVERY_MS = 6 * 3_600_000L;
    private final Runnable refreshWeather = this::showWeather;
    private boolean locating;

    /** Shows the last weather line and, while Home is up and the data is old, fetches a fresh one. */
    private void showWeather() {
        handler.removeCallbacks(refreshWeather);
        if (!prefs.weatherOn()) {
            weather.setVisibility(View.GONE);
            return;
        }
        // No city typed: find the place first (and again every few hours, in case the TV moved).
        if (!prefs.weatherManual() && (!prefs.weatherKnown() || System.currentTimeMillis() - prefs.autoPlaceAt() >= LOCATE_EVERY_MS)) {
            if (!locating) {
                locating = true;
                Weather.locate(this, handler, place -> {
                    locating = false;
                    if (place != null) prefs.setAutoPlace(place.name, place.lat, place.lon);
                    if (prefs.weatherKnown()) showWeather();
                    else if (foreground) handler.postDelayed(refreshWeather, 60_000L);   // offline: try again in a minute
                });
            }
            if (!prefs.weatherKnown()) {
                weather.setVisibility(View.GONE);
                return;
            }
        }
        String line = prefs.weatherText();
        String city = prefs.weatherCity().split(",")[0];
        weather.setText(line.isEmpty() ? city : city.isEmpty() ? line : city + " · " + line);
        weather.setVisibility(weather.getText().length() > 0 ? View.VISIBLE : View.GONE);
        long age = System.currentTimeMillis() - prefs.weatherAt();
        if (line.isEmpty() || age >= WEATHER_EVERY_MS) {
            Weather.current(handler, prefs.weatherLat(), prefs.weatherLon(), prefs.fahrenheit(), result -> {
                if (result != null) prefs.setWeatherText(result);
                if (foreground) showWeather();
            });
            if (foreground) handler.postDelayed(refreshWeather, 60_000L);   // offline: try again in a minute
        } else if (foreground) {
            handler.postDelayed(refreshWeather, WEATHER_EVERY_MS - age);
        }
    }

    private void showWeatherSettings() {
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!prefs.weatherOn()) {
            items.add("Bật thời tiết (tự tìm vị trí)");
            actions.add(() -> {
                prefs.setWeatherOn(true);
                showWeather();
                showWeatherSettings();
            });
        }
        String where = prefs.weatherManual() ? prefs.weatherCity()
                : "Vị trí của tôi" + (prefs.weatherKnown() && !prefs.weatherCity().isEmpty() ? " (" + prefs.weatherCity() + ")" : "");
        items.add("Thành phố: " + (prefs.weatherOn() ? where : "đang tắt") + " (nhập tên thành phố)");
        actions.add(this::askWeatherCity);
        if (prefs.weatherManual()) {
            items.add("Dùng vị trí của tôi");
            actions.add(() -> {
                prefs.useAutoPlace();
                showWeather();
                showWeatherSettings();
            });
        }
        items.add("Đơn vị: " + (prefs.fahrenheit() ? "°F" : "°C"));
        actions.add(() -> {
            prefs.setFahrenheit(!prefs.fahrenheit());
            showWeather();
            showWeatherSettings();
        });
        if (prefs.weatherOn()) {
            items.add("Tắt thời tiết");
            actions.add(() -> {
                prefs.setWeatherOn(false);
                showWeather();
                showLauncherSettings();
            });
        }
        new AlertDialog.Builder(this)
                .setTitle("Thời tiết (cần kết nối mạng)")
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void askWeatherCity() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Tên thành phố");
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        new AlertDialog.Builder(this)
                .setTitle("Xem thời tiết ở đâu?")
                .setView(input)
                .setPositiveButton("Tìm kiếm", (d, w) -> {
                    String query = input.getText().toString().trim();
                    if (query.isEmpty()) return;
                    Weather.findCity(handler, query, place -> {
                        if (place == null) {
                            Toast.makeText(this, "Không tìm thấy thành phố. Hãy kiểm tra tên và kết nối mạng.", Toast.LENGTH_LONG).show();
                        } else {
                            prefs.setWeatherPlace(place.name, place.lat, place.lon);
                            showWeather();
                            Toast.makeText(this, "Thời tiết tại " + place.name, Toast.LENGTH_SHORT).show();
                        }
                        showWeatherSettings();
                    });
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    // ---- Continue watching ----

    private LinearLayout continuePage;
    private LinearLayout continueRow;
    private TextView continueMessage;
    private TextView continueAllow;
    private TextView continueTitle;
    private boolean showingContinue;
    private static final int POSTER_DP = 230, HOME_POSTER_DP = 180, HOME_CONTINUE_MAX = 8;
    private static final int REQUEST_WATCH_NEXT = 7;
    private int homeContinueRequest, continueRequest;
    private boolean observingContinue, observingPackages;
    private String homeContinueKey = "", continueKey = "";
    private boolean focusContinueOnRefresh;
    private final Runnable refreshContinueContent = () -> {
        if (!HomeActivity.this.foreground || !prefs.continueOn()) return;
        if (showingContinue) refreshContinue();
        else if (!showingApps) fillHomeContinue();
    };
    private final android.database.ContentObserver continueObserver = new android.database.ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { scheduleContinueRefresh(); }
        @Override public void onChange(boolean selfChange, Uri uri) { scheduleContinueRefresh(); }
    };
    private final android.content.BroadcastReceiver removedPackageReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) || intent.getData() == null) return;
            prefs.removeRecentPackage(intent.getData().getSchemeSpecificPart());
            RecentArtwork.prune(HomeActivity.this);
            scheduleContinueRefresh();
        }
    };

    private void scheduleContinueRefresh() {
        handler.removeCallbacks(refreshContinueContent);
        if (foreground && prefs.continueOn()) handler.postDelayed(refreshContinueContent, 150);
    }

    private void updateContinueObservers() {
        stopContinueObservers();
        if (!foreground || !prefs.continueOn()) return;
        if (checkSelfPermission(WatchNext.PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            try {
                getContentResolver().registerContentObserver(android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI,
                        true, continueObserver);
                observingContinue = true;
            } catch (RuntimeException ignored) { }
        }
        android.content.IntentFilter filter = new android.content.IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(removedPackageReceiver, filter, android.content.Context.RECEIVER_EXPORTED);
        else registerReceiver(removedPackageReceiver, filter);
        observingPackages = true;
    }

    private void stopContinueObservers() {
        handler.removeCallbacks(refreshContinueContent);
        if (observingContinue) getContentResolver().unregisterContentObserver(continueObserver);
        if (observingPackages) unregisterReceiver(removedPackageReceiver);
        observingContinue = observingPackages = false;
    }

    private String continueListKey(List<WatchNext.Item> items) {
        StringBuilder key = new StringBuilder().append(palette.seed);
        for (WatchNext.Item item : items) key.append('|').append(WatchNext.key(item)).append('|').append(item.poster)
                .append('|').append(item.position).append('|').append(item.duration).append('|').append(item.subtitle)
                .append('|').append(item.intentUri).append('|').append(item.programId);
        return key.toString();
    }

    private String focusedContinueKey(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) if (row.getChildAt(i).hasFocus()) return (String) row.getChildAt(i).getTag();
        return null;
    }

    private void restoreContinueFocus(LinearLayout row, String key, int fallback) {
        for (int i = 0; i < row.getChildCount(); i++) if (key != null && key.equals(row.getChildAt(i).getTag())) {
            row.getChildAt(i).requestFocus();
            return;
        }
        if (row.getChildCount() > 0) row.getChildAt(Math.min(fallback, row.getChildCount() - 1)).requestFocus();
    }
    private final Map<String, Bitmap> posters = new java.util.LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 12;
        }
    };

    private void applyContinueTab() {
        updateContinueObservers();
        nav.button(2).setVisibility(prefs.continueOn() ? View.VISIBLE : View.GONE);
    }

    private LinearLayout buildContinuePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        continueTitle = text("Tiếp tục xem", 34, palette.onSurface, 760);
        continueTitle.setPadding(dp(4), dp(28), 0, dp(8));
        page.addView(continueTitle);
        continueMessage = text("", 18, palette.onSurfaceVariant, 500);
        continueMessage.setPadding(dp(4), dp(12), dp(40), dp(12));
        continueMessage.setVisibility(View.GONE);
        page.addView(continueMessage);
        continueAllow = text("Cấp quyền", 16, palette.onPrimary, 600);
        continueAllow.setGravity(Gravity.CENTER);
        continueAllow.setPadding(dp(24), dp(12), dp(24), dp(12));
        continueAllow.setFocusable(true);
        continueAllow.setClickable(true);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(40));
        pill.setColor(palette.primary);
        continueAllow.setBackground(pill);
        continueAllow.setOnFocusChangeListener((v, focused) -> {
            pill.setColor(focused ? palette.inverseSurface : palette.primary);
            continueAllow.setTextColor(focused ? palette.inverseOnSurface : palette.onPrimary);
        });
        continueAllow.setOnClickListener(v -> requestPermissions(new String[]{WatchNext.PERMISSION}, REQUEST_WATCH_NEXT));
        continueAllow.setVisibility(View.GONE);
        page.addView(continueAllow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(buildHero(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        continueRow = new LinearLayout(this);
        continueRow.setOrientation(LinearLayout.HORIZONTAL);
        continueRow.setClipChildren(false);
        // A focused poster grows by 5% of its ring frame on each side (12 dp): the padding leaves room for it, and the
        // ring is drawn into that padding rather than cut at its edge.
        continueRow.setClipToPadding(false);
        continueRow.setPadding(dp(14), dp(24), dp(14), dp(16));
        scroller.addView(continueRow);
        page.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return page;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WATCH_NEXT) updateContinueObservers();
        if (requestCode == REQUEST_WATCH_NEXT && showingContinue) refreshContinue();
    }

    private void showContinue() {
        showingApps = true;
        showingContinue = true;
        showingManager = false;
        managerPage.setVisibility(View.GONE);
        homePage.setVisibility(View.GONE);
        appsPage.setVisibility(View.GONE);
        fadeHero(false);
        continuePage.setVisibility(View.VISIBLE);
        nav.select(2);
        if (nowCard != null) nowCard.setVisibility(View.GONE);
        focusContinueOnRefresh = true;
        refreshContinue();
    }

    private void refreshContinue() {
        final int request = ++continueRequest;
        boolean granted = checkSelfPermission(WatchNext.PERMISSION) == PackageManager.PERMISSION_GRANTED;
        Set<String> skip = new java.util.HashSet<>(prefs.hidden());
        skip.add(getPackageName());
        if (!granted) {
            showContinueRow(WatchNext.merge(null, prefs.recent(), skip), false);
            return;
        }
        WatchNext.load(handler, getContentResolver(), skip, items -> {
            if (foreground && showingContinue && prefs.continueOn() && request == continueRequest)
                showContinueRow(WatchNext.merge(items, prefs.recent(), skip), true);
        });
    }

    /** The row under the dock: the same items as the Continue tab, while that tab is on and there is something in it. */
    private void fillHomeContinue() {
        final int request = ++homeContinueRequest;
        if (!prefs.continueOn()) {
            homeContinue.setVisibility(View.GONE);
            homeContinueRow.removeAllViews();
            homeContinueKey = "";
            return;
        }
        Set<String> skip = new java.util.HashSet<>(prefs.hidden());
        skip.add(getPackageName());
        if (checkSelfPermission(WatchNext.PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            WatchNext.load(handler, getContentResolver(), skip, items -> {
                if (foreground && prefs.continueOn() && request == homeContinueRequest)
                    showHomeContinue(WatchNext.merge(items, prefs.recent(), skip));
            });
        } else {
            showHomeContinue(WatchNext.merge(null, prefs.recent(), skip));
        }
    }

    private void showHomeContinue(List<WatchNext.Item> list) {
        boolean keepFocus = homeContinueRow.hasFocus();
        String focused = focusedContinueKey(homeContinueRow);
        List<WatchNext.Item> shown = new ArrayList<>();
        for (WatchNext.Item item : prefs.visibleContinue(list)) {
            if (shown.size() >= HOME_CONTINUE_MAX) break;
            if (launchIntent(item.pkg) != null) shown.add(item);
        }
        String key = continueListKey(shown);
        if (key.equals(homeContinueKey)) return;
        homeContinueKey = key;
        homeContinueRow.removeAllViews();
        for (int i = 0; i < shown.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(GAP));
            homeContinueRow.addView(posterCard(shown.get(i), i, shown.size(), true), lp);
        }
        homeContinue.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        homeScroll.requestLayout();   // the peek appears or goes: the upper part's height follows
        if (keepFocus) {
            if (!shown.isEmpty()) restoreContinueFocus(homeContinueRow, focused, 0);
            else if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
        }
    }

    private void showContinueRow(List<WatchNext.Item> list, boolean granted) {
        boolean keepFocus = continueRow.hasFocus();
        String focused = focusedContinueKey(continueRow);
        boolean focusContent = focusContinueOnRefresh || keepFocus;
        focusContinueOnRefresh = false;
        List<WatchNext.Item> shown = new ArrayList<>();
        for (WatchNext.Item item : prefs.visibleContinue(list)) if (launchIntent(item.pkg) != null) shown.add(item);
        String key = granted + continueListKey(shown);
        if (key.equals(continueKey)) {
            if (focusContent) restoreContinueFocus(continueRow, focused, 0);
            return;
        }
        continueKey = key;
        continueRow.removeAllViews();
        heroItem = null;
        for (int i = 0; i < shown.size(); i++) {
            WatchNext.Item item = shown.get(i);
            View card = posterCard(item, i, shown.size());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (continueRow.getChildCount() > 0) lp.setMarginStart(dp(GAP));
            continueRow.addView(card, lp);
        }
        boolean empty = continueRow.getChildCount() == 0;
        heroBox.setVisibility(empty ? View.GONE : View.VISIBLE);
        continueTitle.setVisibility(empty ? View.VISIBLE : View.GONE);
        String offered = "Cấp quyền để mục Tiếp tục xem hiển thị thêm nội dung từ các ứng dụng.";
        if (empty) {
            continueMessage.setText("Chưa có nội dung để xem tiếp. Nội dung bạn phát trên thiết bị sẽ xuất hiện ở đây"
                    + (granted ? "; các ứng dụng hỗ trợ cũng có thể bổ sung nội dung." : ". " + offered));
        } else {
            continueMessage.setText(offered);
        }
        continueMessage.setVisibility(empty || !granted ? View.VISIBLE : View.GONE);
        continueAllow.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (!empty && focusContent) restoreContinueFocus(continueRow, focused, 0);
        else if (empty && focusContent) {
            if (!granted) continueAllow.requestFocus();
            else nav.button(2).requestFocus();
        }
    }

    private void launchContinue(WatchNext.Item item) {
        if (!item.recent) {
            launchWatchNext(item);
            return;
        }
        Intent deep = resumeIntent(item.pkg, item.mediaId, item.mediaUri, item.position);
        if (deep == null || pm.resolveActivity(deep, 0) == null) deep = launchIntent(item.pkg);
        if (deep != null) launch(deep);
    }

    private void showContinueMenu(WatchNext.Item item) {
        new AlertDialog.Builder(this).setTitle(item.title)
                .setItems(new String[]{"Mở nội dung", "Ẩn khỏi Xem tiếp"}, (dialog, which) -> {
                    if (which == 0) {
                        launchContinue(item);
                        return;
                    }
                    prefs.dismissContinue(item);
                    RecentArtwork.prune(this);
                    fillHomeContinue();
                    if (showingContinue) refreshContinue();
                    if (item.programId >= 0) WatchNext.remove(handler, getContentResolver(), item.programId, removed -> {
                        if (foreground) Toast.makeText(this, removed ? "Đã xóa khỏi Xem tiếp" : "Đã ẩn trên launcher", Toast.LENGTH_SHORT).show();
                    });
                }).show();
    }

    // What was played, kept for the Continue tab (only while that tab is on; never leaves the TV).
    private long recordedAt;
    private int recordedState = -1;
    private String recordedItem;
    private Bitmap savedRecentArt;
    private String savedRecentArtItem;

    private void recordRecent(boolean force) {
        if (!prefs.continueOn() || lastItem == null || lastPkg == null || lastTitle == null || lastTitle.length() == 0) return;
        if (lastPkg.equals(getPackageName())) return;
        long now = System.currentTimeMillis();
        boolean changed = !lastItem.equals(recordedItem) || lastState != recordedState;
        boolean newArt = lastArt != null && (lastArt != savedRecentArt || !lastItem.equals(savedRecentArtItem));
        if (!force && !changed && !newArt && now - recordedAt < 30_000) return;
        recordedItem = lastItem;
        recordedState = lastState;
        recordedAt = now;
        WatchNext.Item it = new WatchNext.Item();
        it.pkg = lastPkg;
        it.title = lastTitle.toString();
        it.subtitle = lastArtist != null ? lastArtist.toString() : "";
        it.mediaId = lastMediaId;
        it.mediaUri = lastMediaUri;
        it.poster = lastPoster;
        it.position = lastPosition < 0 ? -1 : rememberedPosition();
        it.duration = lastDuration;
        it.engaged = now;
        it.recent = true;
        prefs.addRecent(it);
        if (newArt) {
            savedRecentArt = lastArt;
            savedRecentArtItem = lastItem;
            String address = RecentArtwork.address(it.pkg, it.title);
            RecentArtwork.save(this, handler, address, lastArt, saved -> {
                if (!saved && it.pkg.equals(lastPkg) && it.title.contentEquals(lastTitle != null ? lastTitle : "")) {
                    savedRecentArt = null;
                }
                if (saved && prefs.setRecentPoster(it.pkg, it.title, address) && foreground && prefs.continueOn()) {
                    fillHomeContinue();
                    if (showingContinue) refreshContinue();
                }
            });
        } else {
            RecentArtwork.prune(this);
        }
    }

    // The focused item, large: its poster as a backdrop under a scrim, the title, where you were, and which of the row it is.
    private FrameLayout heroBox;
    private ImageView heroImage;
    private TextView heroKicker, heroTitle, heroSub;
    private LinearLayout heroDots;
    private WatchNext.Item heroItem;
    private int heroIndex, heroCount;

    private View buildHero() {
        heroBox = new FrameLayout(this);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(Focus.CARD));
        shape.setColor(palette.containerHigh);
        heroBox.setBackground(shape);
        heroBox.setClipToOutline(true);
        heroBox.setVisibility(View.GONE);
        heroImage = new ImageView(this);
        heroImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroBox.addView(heroImage, match());
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{withAlpha(palette.surface, 0xF2), withAlpha(palette.surface, 0xB0), withAlpha(palette.surface, 0x20)}));
        heroBox.addView(scrim, match());
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setGravity(Gravity.CENTER_VERTICAL);
        words.setPadding(dp(28), dp(12), dp(28), dp(12));
        heroKicker = text("Tiếp tục xem", 13, palette.primary, 650);
        heroTitle = text("", 26, palette.onSurface, 700);
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroSub = text("", 15, palette.onSurfaceVariant, 500);
        heroSub.setSingleLine(true);
        heroSub.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(heroKicker);
        words.addView(heroTitle);
        words.addView(heroSub);
        heroBox.addView(words, new FrameLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
        heroDots = new LinearLayout(this);
        heroDots.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.BOTTOM);
        dl.setMargins(0, 0, dp(24), dp(16));
        heroBox.addView(heroDots, dl);
        return heroBox;
    }

    private Drawable appArtFor(String pkg) {
        try {
            return pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static String minutes(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private static String playbackLabel(WatchNext.Item item) {
        if (item.position < 0) return "Chưa có tiến trình";
        if (item.duration > 0) return minutes(item.position) + " / " + minutes(item.duration);
        return item.position > 0 ? "Đã xem " + minutes(item.position) : "Chưa có tiến trình";
    }

    private void loadPoster(String address, Weather.Callback<Bitmap> callback) {
        if (address.startsWith(RecentArtwork.PREFIX)) RecentArtwork.load(this, handler, address, callback);
        else Photos.image(handler, getContentResolver(), address, dp(380), callback);
    }

    private void showHero(WatchNext.Item item, int index, int count, Drawable icon) {
        boolean sameItem = heroItem == item;
        heroItem = item;
        heroIndex = index;
        heroCount = count;
        heroTitle.setText(item.title);
        StringBuilder sub = new StringBuilder(String.valueOf(appLabel(item.pkg)));
        if (item.subtitle != null && !item.subtitle.isEmpty()) sub.append(" · ").append(item.subtitle);
        sub.append("  ·  ").append(playbackLabel(item));
        heroSub.setText(sub);
        Bitmap poster = item.poster != null ? posters.get(item.poster) : null;
        if (poster != null) {
            heroImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            heroImage.setPadding(0, 0, 0, 0);
            heroImage.setImageBitmap(poster);
        } else {
            heroImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            heroImage.setPadding(dp(560), dp(24), dp(48), dp(24));
            heroImage.setImageDrawable(icon);
        }
        if (!sameItem) {
            heroImage.setAlpha(0f);
            heroImage.animate().alpha(1f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.duration(Motion.FAST_EFFECTS_MS)).start();
        }
        if (heroDots.getChildCount() != count) {
            heroDots.removeAllViews();
            for (int i = 0; i < count; i++) {
                View dot = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(8), dp(8));
                if (i > 0) lp.setMarginStart(dp(6));
                heroDots.addView(dot, lp);
            }
        }
        for (int i = 0; i < heroDots.getChildCount(); i++) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(i == index ? palette.primary : withAlpha(palette.onSurfaceVariant, 0x80));
            heroDots.getChildAt(i).setBackground(d);
        }
    }

    /** A 16:9 poster with a thin progress line, the title and the app's name under it. */
    private View posterCard(WatchNext.Item item, int index, int count) {
        return posterCard(item, index, count, false);
    }

    /** onHome: a smaller card for the row under the dock; focusing it puts its art in the hero instead. */
    private View posterCard(WatchNext.Item item, int index, int count, boolean onHome) {
        int w = dp(onHome ? HOME_POSTER_DP : POSTER_DP);
        int h = w * 9 / 16;
        int radius = dp(Focus.MEDIA);
        LinearLayout holder = new LinearLayout(this);
        holder.setTag(WatchNext.key(item));
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setClipChildren(false);

        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable cardShape = new GradientDrawable();
        cardShape.setCornerRadius(radius);
        cardShape.setColor(palette.containerHigh);
        card.setBackground(cardShape);
        card.setClipToOutline(true);
        ImageView image = new ImageView(this);
        // Keep the fallback icon whole until a real poster has loaded successfully.
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(40), dp(24), dp(40), dp(24));
        card.addView(image, match());
        Drawable appArt = null;
        try {
            appArt = pm.getApplicationIcon(item.pkg);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        final Drawable fallback = appArt;
        if (appArt != null) image.setImageDrawable(appArt);
        if (item.poster != null) {
            Bitmap known = posters.get(item.poster);
            if (known != null) {
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setPadding(0, 0, 0, 0);
                image.setImageBitmap(known);
            } else {
                loadPoster(item.poster, bitmap -> {
                    if (bitmap == null) return;
                    posters.put(item.poster, bitmap);
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    image.setPadding(0, 0, 0, 0);
                    image.setImageBitmap(bitmap);
                    if (onHome) heroPosters.put("watch:" + item.pkg + "|" + item.title, bitmap);
                    if (item.recent && !item.poster.startsWith(RecentArtwork.PREFIX)) {
                        String address = RecentArtwork.address(item.pkg, item.title);
                        RecentArtwork.save(this, handler, address, bitmap, saved -> {
                            if (saved) prefs.setRecentPoster(item.pkg, item.title, address);
                        });
                    }
                    if (heroItem == item) showHero(item, heroIndex, heroCount, appArtFor(item.pkg));
                });
            }
        }
        if (item.duration > 0 && item.position > 0) {
            float done = Math.min(1f, (float) item.position / item.duration);
            FrameLayout track = new FrameLayout(this);
            track.setBackgroundColor(withAlpha(palette.surface, 160));
            View fill = new View(this);
            fill.setBackgroundColor(palette.primary);
            track.addView(fill, new FrameLayout.LayoutParams(Math.max(dp(4), Math.round(w * done)), ViewGroup.LayoutParams.MATCH_PARENT));
            card.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5), Gravity.BOTTOM));
        }
        String label = String.valueOf(appLabel(item.pkg));
        card.setContentDescription(item.title + ", " + label + ", " + playbackLabel(item));

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius + dp(RING));
        ring.setColor(Color.TRANSPARENT);
        FrameLayout ringFrame = new FrameLayout(this);
        ringFrame.setClipChildren(false);
        ringFrame.setPadding(dp(RING), dp(RING), dp(RING), dp(RING));
        ringFrame.setBackground(ring);
        ringFrame.addView(card, new FrameLayout.LayoutParams(w, h));
        card.setOnClickListener(v -> launchContinue(item));
        card.setOnLongClickListener(v -> {
            showContinueMenu(item);
            return true;
        });
        card.setOnFocusChangeListener((v, focused) -> {
            float scale = focused ? 1.10f : 1f;
            ringFrame.animate().scaleX(scale).scaleY(scale)
                    .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.duration(Motion.FAST_SPATIAL_MS)).start();
            card.setOutlineSpotShadowColor(palette.primary);
            card.setOutlineAmbientShadowColor(palette.primary);
            card.animate().translationZ(focused ? dp(14) : 0)
                    .setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.duration(Motion.DEFAULT_EFFECTS_MS)).start();
            animateCorners(cardShape, ring, focused ? radius * 0.62f : radius);
            fadeRing(ring, focused);
            if (focused) {
                String key = "watch:" + item.pkg + "|" + item.title;
                if (onHome) {
                    Bitmap poster = item.poster != null ? posters.get(item.poster) : null;
                    if (poster != null) heroPosters.put(key, poster);
                    homeScroll.post(() -> homeScroll.smoothScrollTo(0, homeScroll.getChildAt(0).getHeight()));
                } else {
                    showHero(item, index, count, fallback);
                }
                Drawable art = image.getDrawable() != null ? image.getDrawable() : fallback;
                if (art != null) onTileFocused(key, item.title, art);
            }
        });
        holder.addView(ringFrame, new LinearLayout.LayoutParams(w + dp(2 * RING), h + dp(2 * RING)));
        TextView name = text(item.title, 15, palette.onSurface, 600);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(RING), dp(8), dp(RING), 0);
        holder.addView(name, new LinearLayout.LayoutParams(w + dp(2 * RING), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView from = text(label + (item.subtitle != null && !item.subtitle.isEmpty() ? " · " + item.subtitle : ""), 13, palette.onSurfaceVariant, 500);
        if (fallback != null && fallback.getConstantState() != null) {
            Drawable sourceIcon = fallback.getConstantState().newDrawable().mutate();
            sourceIcon.setBounds(0, 0, dp(14), dp(14));
            from.setCompoundDrawablesRelative(sourceIcon, null, null, null);
            from.setCompoundDrawablePadding(dp(5));
        }
        from.setSingleLine(true);
        from.setPadding(dp(RING), dp(2), dp(RING), 0);
        holder.addView(from, new LinearLayout.LayoutParams(w + dp(2 * RING), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView progress = text(playbackLabel(item), 12, palette.onSurfaceVariant, 500);
        progress.setSingleLine(true);
        progress.setPadding(dp(RING), dp(2), dp(RING), 0);
        holder.addView(progress, new LinearLayout.LayoutParams(w + dp(2 * RING), ViewGroup.LayoutParams.WRAP_CONTENT));
        return holder;
    }

    /** Opens the item the app published; only for that app, and the app's own launch intent when it gave none. */
    private void launchWatchNext(WatchNext.Item item) {
        Intent intent = null;
        try {
            if (item.intentUri != null) intent = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
        } catch (java.net.URISyntaxException ignored) {
        }
        if (intent != null) {
            intent.setSelector(null);
            ComponentName target = intent.getComponent();
            if (target != null && !target.getPackageName().equals(item.pkg)) intent = null;
            else if (target == null) intent.setPackage(item.pkg);
        }
        if (intent == null || pm.resolveActivity(intent, 0) == null) intent = launchIntent(item.pkg);
        if (intent != null) launch(intent);
    }

    // ---- quick panel ----

    private Dialog quickDialog;

    /** The Settings tab: the launcher's preferences and the TV's own pages, as tiles over Home. */
    private void showQuickPanel() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(14));
        GradientDrawable cardShape = new GradientDrawable();
        cardShape.setCornerRadius(dp(Focus.CARD));
        cardShape.setColor(palette.containerHigh);
        card.setBackground(cardShape);
        TextView head = text("Cài đặt nhanh", 14, palette.onSurfaceVariant, 600);
        head.setPadding(dp(8), dp(4), 0, dp(8));
        card.addView(head);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        Object[][] entries = {
                {R.drawable.ic_tune, "Cài đặt giao diện", (Runnable) this::showLauncherSettings},
                {R.drawable.ic_wifi, "Wi-Fi", (Runnable) () -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS))},
                {R.drawable.ic_display, "Màn hình", (Runnable) () -> launch(new Intent(Settings.ACTION_DISPLAY_SETTINGS))},
                {R.drawable.ic_sound, "Âm thanh", (Runnable) () -> launch(new Intent(Settings.ACTION_SOUND_SETTINGS))},
                {R.drawable.ic_moon, "Bảo vệ màn hình", (Runnable) () -> launch(new Intent("android.settings.DREAM_SETTINGS"))},
                {R.drawable.ic_home, "Màn hình chính mặc định", (Runnable) () -> launch(new Intent(Settings.ACTION_HOME_SETTINGS))},
                {R.drawable.ic_settings, "Cài đặt thiết bị", (Runnable) () -> launch(new Intent(Settings.ACTION_SETTINGS))},
        };
        View first = null;
        for (Object[] e : entries) {
            View tile = quickTile((Integer) e[0], (String) e[1], (Runnable) e[2], d);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(112);
            lp.height = dp(86);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(tile, lp);
            if (first == null) first = tile;
        }
        card.addView(grid);
        d.setContentView(card);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams p = w.getAttributes();
            p.gravity = Gravity.TOP | Gravity.END;
            p.x = dp(SIDE);
            p.y = dp(96);
            p.dimAmount = 0.35f;
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setAttributes(p);
        }
        quickDialog = d;
        d.setOnDismissListener(x -> quickDialog = null);
        d.show();
        if (first != null) first.requestFocus();
    }

    private View quickTile(int icon, String label, Runnable action, Dialog dialog) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setFocusable(true);
        tile.setClickable(true);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(Focus.MEDIA));
        shape.setColor(palette.container);
        tile.setBackground(shape);
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(palette.onSurface));
        tile.addView(image, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView name = text(label, 13, palette.onSurface, 560);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setPadding(dp(4), dp(8), dp(4), 0);
        tile.addView(name);
        tile.setOnFocusChangeListener((v, focused) -> {
            shape.setColor(focused ? palette.inverseSurface : palette.container);
            image.setImageTintList(ColorStateList.valueOf(focused ? palette.inverseOnSurface : palette.onSurface));
            name.setTextColor(focused ? palette.inverseOnSurface : palette.onSurface);
            v.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f)
                    .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.duration(Motion.FAST_SPATIAL_MS)).start();
        });
        tile.setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });
        return tile;
    }

    // ---- folders ----

    private Dialog folderDialog;

    private String folderOf(String pkg) {
        for (Map.Entry<String, List<String>> e : prefs.folders().entrySet()) {
            if (e.getValue().contains(pkg)) return e.getKey();
        }
        return null;
    }

    private void addToFolder(String name, String pkg) {
        Map<String, List<String>> folders = prefs.folders();
        List<String> list = folders.get(name);
        if (list == null) {
            list = new ArrayList<>();
            folders.put(name, list);
        }
        if (!list.contains(pkg)) list.add(pkg);
        prefs.setFolders(folders);
        refreshApps();
    }

    private void chooseFolder(String pkg) {
        List<String> names = new ArrayList<>(prefs.folders().keySet());
        List<String> items = new ArrayList<>(names);
        items.add("Tạo thư mục");
        new AlertDialog.Builder(this)
                .setTitle("Thêm vào thư mục")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which < names.size()) addToFolder(names.get(which), pkg);
                    else askName("Tạo thư mục", "", name -> addToFolder(name, pkg));
                })
                .show();
    }

    private void askName(String title, String initial, java.util.function.Consumer<String> done) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(initial);
        input.setSelection(initial.length());
        input.setHint("Tên thư mục");
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    String name = input.getText().toString().trim().replace("\"", "");
                    if (!name.isEmpty()) done.accept(name);
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    /** Hold OK on a folder: rename it, or delete it (its apps go back to the grid). */
    private void showFolderMenu(String name) {
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(new String[]{"Đổi tên thư mục", "Xóa thư mục"}, (d, which) -> {
                    Map<String, List<String>> folders = prefs.folders();
                    if (which == 0) {
                        askName("Đổi tên thư mục", name, newName -> {
                            Map<String, List<String>> all = prefs.folders();
                            List<String> apps = all.remove(name);
                            if (apps == null) return;
                            List<String> merged = all.get(newName);
                            if (merged != null) apps.addAll(merged);
                            all.put(newName, apps);
                            prefs.setFolders(all);
                            refreshApps();
                        });
                    } else {
                        folders.remove(name);
                        prefs.setFolders(folders);
                        refreshApps();
                    }
                })
                .show();
    }

    /** A folder on the Apps page: up to four of its apps' icons on a tonal card, the name underneath. */
    private View folderTile(String name, List<String> pkgs, int widthDp) {
        int w = dp(widthDp);
        int h = w * 9 / 16;
        int radius = dp(Focus.MEDIA);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setClipChildren(false);
        holder.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable cardShape = new GradientDrawable();
        cardShape.setCornerRadius(radius);
        cardShape.setColor(palette.containerHigh);
        card.setBackground(cardShape);
        card.setClipToOutline(true);
        GridLayout minis = new GridLayout(this);
        minis.setColumnCount(2);
        Drawable firstIcon = null;
        int shown = 0;
        for (String pkg : pkgs) {
            if (shown >= 4) break;
            Drawable icon = appArtFor(pkg);
            if (icon == null) continue;
            if (firstIcon == null) firstIcon = icon;
            ImageView mini = new ImageView(this);
            mini.setImageDrawable(icon);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = Math.round(h * 0.36f);
            lp.height = Math.round(h * 0.36f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            minis.addView(mini, lp);
            shown++;
        }
        card.addView(minis, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        card.setContentDescription(name);

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius + dp(RING));
        ring.setColor(Color.TRANSPARENT);
        FrameLayout ringFrame = new FrameLayout(this);
        ringFrame.setClipChildren(false);
        ringFrame.setPadding(dp(RING), dp(RING), dp(RING), dp(RING));
        ringFrame.setBackground(ring);
        ringFrame.addView(card, new FrameLayout.LayoutParams(w, h));
        card.setOnClickListener(v -> showFolder(name));
        card.setOnLongClickListener(v -> {
            showFolderMenu(name);
            return true;
        });
        final Drawable art = firstIcon;
        card.setOnFocusChangeListener((v, focused) -> {
            float scale = focused ? 1.10f : 1f;
            ringFrame.animate().scaleX(scale).scaleY(scale)
                    .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.duration(Motion.FAST_SPATIAL_MS)).start();
            card.setOutlineSpotShadowColor(palette.primary);
            card.setOutlineAmbientShadowColor(palette.primary);
            card.animate().translationZ(focused ? dp(14) : 0)
                    .setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.duration(Motion.DEFAULT_EFFECTS_MS)).start();
            animateCorners(cardShape, ring, focused ? radius * 0.62f : radius);
            fadeRing(ring, focused);
            if (focused && art != null) onTileFocused("folder:" + name, name, art);
        });
        holder.addView(ringFrame, new LinearLayout.LayoutParams(w + dp(2 * RING), h + dp(2 * RING)));
        TextView caption = text(name + "  ·  " + pkgs.size(), 14, palette.onSurfaceVariant, 560);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setPadding(0, dp(10), 0, 0);
        holder.addView(caption, new LinearLayout.LayoutParams(w + dp(2 * RING), ViewGroup.LayoutParams.WRAP_CONTENT));
        return holder;
    }

    /** The folder's apps in a panel over the Apps page. */
    private void showFolder(String name) {
        List<String> pkgs = new ArrayList<>();
        Set<String> hidden = prefs.hidden();
        List<String> all = prefs.folders().get(name);
        if (all != null) for (String pkg : all) if (!hidden.contains(pkg) && launchIntent(pkg) != null) pkgs.add(pkg);
        if (pkgs.isEmpty()) return;
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(28), dp(20), dp(28), dp(20));
        box.setClipChildren(false);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(Focus.CARD));
        shape.setColor(palette.container);
        box.setBackground(shape);
        TextView title = text(name, 22, palette.onSurface, 700);
        title.setPadding(dp(4), 0, 0, dp(6));
        box.addView(title);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(Math.min(4, pkgs.size()));
        grid.setClipChildren(false);
        View first = null;
        for (String pkg : pkgs) {
            View tile = tile(pkg, launchIntent(pkg), 140, false);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(dp(8), dp(8), dp(8), dp(12));
            grid.addView(tile, lp);
            if (first == null) first = tile;
        }
        box.addView(grid);
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.45f);
        }
        folderDialog = d;
        d.setOnDismissListener(x -> folderDialog = null);
        d.show();
        if (first != null) first.requestFocus();
    }

    // ---- pages ----

    private ManagerPage managerPage;
    private boolean showingManager;

    /** Launcher Manager: a page like the others (Back returns Home); no tab is selected while it shows. */
    private void showManager() {
        boolean opening = !showingManager;
        showingApps = true;
        showingContinue = false;
        showingManager = true;
        homePage.setVisibility(View.GONE);
        appsPage.setVisibility(View.GONE);
        continuePage.setVisibility(View.GONE);
        fadeHero(false);
        if (nowCard != null) nowCard.setVisibility(View.GONE);
        managerPage.setVisibility(View.VISIBLE);
        nav.select(-1);
        managerPage.setTitle("Cài đặt giao diện · v" + versionName());
        managerPage.show(managerSections());
        if (opening) managerPage.focusRail();
    }

    private void showHome() {
        showingApps = false;
        showingContinue = false;
        showingManager = false;
        managerPage.setVisibility(View.GONE);
        appsPage.setVisibility(View.GONE);
        continuePage.setVisibility(View.GONE);
        homePage.setVisibility(View.VISIBLE);
        homePage.scrollTo(0, 0);
        fadeHero(true);
        nav.select(0);
        if (nowCard != null) showNowPlaying();
        if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    private void showApps() {
        showingApps = true;
        showingContinue = false;
        showingManager = false;
        managerPage.setVisibility(View.GONE);
        continuePage.setVisibility(View.GONE);
        fillApps();
        homePage.setVisibility(View.GONE);
        fadeHero(false);
        appsPage.setVisibility(View.VISIBLE);
        appsPage.scrollTo(0, 0);
        nav.select(1);
        if (nowCard != null) nowCard.setVisibility(View.GONE);
        if (appsGrid.getChildCount() > 0) appsGrid.getChildAt(0).requestFocus();
    }

    // ---- Now Playing ----

    private View buildNowPlaying() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(14), dp(12));
        nowShape = new GradientDrawable();
        // Use the measured height so the card stays a pill when text or font scaling changes its height.
        card.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                nowShape.setCornerRadius((bottom - top) / 2f));
        card.setBackground(nowShape);
        card.setVisibility(View.GONE);

        nowArt = new ImageView(this);
        nowArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable artShape = new GradientDrawable();
        artShape.setCornerRadius(dp(Focus.MEDIA));
        artShape.setColor(0x33FFFFFF);
        nowArt.setBackground(artShape);
        nowArt.setClipToOutline(true);
        card.addView(nowArt, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(14), 0, dp(10), 0);
        TextView label = text("ĐANG PHÁT", 12, palette.onSurfaceVariant, 760);
        label.setLetterSpacing(0.2f);
        words.addView(label);
        nowTitle = text("", 16, palette.onSurface, 760);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowTitle.setPadding(0, dp(3), 0, 0);
        words.addView(nowTitle);
        nowArtist = text("", 14, palette.onSurfaceVariant, 500);
        nowArtist.setSingleLine(true);
        nowArtist.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(nowArtist);
        nowProgress = new WavyProgress(this);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12));
        pl.topMargin = dp(6);
        words.addView(nowProgress, pl);
        card.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        nowButton = new ImageView(this);
        nowButton.setScaleType(ImageView.ScaleType.CENTER);
        nowButtonShape = new GradientDrawable();
        nowButtonShape.setShape(GradientDrawable.OVAL);
        nowButton.setBackground(nowButtonShape);
        nowButton.setFocusable(true);
        nowButton.setClickable(true);
        nowButton.setContentDescription("Phát hoặc tạm dừng");
        nowButton.setOnClickListener(v -> togglePlayback());
        nowButton.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.12f : 1f).scaleY(focused ? 1.12f : 1f).setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.duration(Motion.FAST_SPATIAL_MS)).start();
            styleNowPlaying();
        });
        card.addView(nowButton, new LinearLayout.LayoutParams(dp(52), dp(52)));
        styleNowPlaying();
        return card;
    }

    private void styleNowPlaying() {
        if (nowShape == null) return;
        nowShape.setColor(withAlpha(palette.container, 0xD9));
        boolean focused = nowButton.hasFocus();
        nowButtonShape.setColor(focused ? palette.inverseSurface : palette.primary);
        nowButton.setImageTintList(ColorStateList.valueOf(focused ? palette.inverseOnSurface : palette.onPrimary));
        nowTitle.setTextColor(palette.onSurface);
        nowArtist.setTextColor(palette.onSurfaceVariant);
        nowProgress.setColors(palette.primary, withAlpha(palette.onSurfaceVariant, 0x55));
    }

    private void startNowPlaying() {
        try {
            if (sessions == null) {
                sessions = getSystemService(MediaSessionManager.class);
                mediaListener = new ComponentName(this, MediaListener.class);
            }
            if (!listening) {
                sessions.addOnActiveSessionsChangedListener(sessionsChanged, mediaListener);
                listening = true;
            }
            pickSession();
        } catch (SecurityException notAllowed) {
            // Notification access not granted (Android ignores it on low-RAM TVs): read the session dump instead,
            // if DUMP was granted; otherwise the card simply stays hidden.
            if (checkSelfPermission(SessionDump.PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                polling = true;
                handler.removeCallbacks(poll);   // back on Home: read now, not at the end of the slow away interval
                handler.post(poll);
            } else {
                nowCard.setVisibility(View.GONE);
            }
        }
    }

    // The dump fallback: read every 2 s on Home, every 10 s while another app has the screen (YouTube lets its session
    // go as soon as you leave it, so what played is noted while it plays). One dumpsys run at a time, off the main thread.
    private static final long POLL_HOME_MS = 2000, POLL_AWAY_MS = 10_000;
    private boolean polling, dumping;
    private final Runnable poll = this::pollDump;

    private void pollDump() {
        if (!polling || dumping) return;
        dumping = true;
        new Thread(() -> {
            List<SessionDump.Session> list = SessionDump.read();
            handler.post(() -> {
                dumping = false;
                if (!polling) return;
                pickDumped(list);
                handler.removeCallbacks(poll);
                handler.postDelayed(poll, foreground ? POLL_HOME_MS : POLL_AWAY_MS);
            });
        }, "session-dump").start();
    }

    /** The same pick as pickSession, from the dump: an active session with metadata, a playing one first. */
    private void pickDumped(List<SessionDump.Session> list) {
        SessionDump.Session best = null;
        for (SessionDump.Session s : list) {
            if (!s.active || s.meta == null) continue;
            if (best == null || (s.state != null && s.state.getState() == PlaybackState.STATE_PLAYING)) best = s;
        }
        livePkg = best != null ? best.pkg : null;
        liveMeta = best != null ? best.meta : null;
        liveState = best != null ? best.state : null;
        showNowPlaying();
    }

    private void stopNowPlaying() {
        if (listening) {
            try {
                sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
            } catch (RuntimeException ignored) {
            }
            listening = false;
        }
        polling = false;
        handler.removeCallbacks(poll);
        if (playing != null) playing.unregisterCallback(mediaCallback);
        playing = null;
        livePkg = null;
        liveMeta = null;
        liveState = null;
    }

    private void pickSession() {
        if (!listening) return;   // a change posted before onDestroy let the listener go: nothing to pick for
        MediaController best = null;
        MediaMetadata bestMeta = null;
        PlaybackState bestState = null;
        try {
            for (MediaController c : sessions.getActiveSessions(mediaListener)) {
                MediaMetadata m = c.getMetadata();
                if (m == null) continue;
                PlaybackState st = c.getPlaybackState();
                if (best == null || (st != null && st.getState() == PlaybackState.STATE_PLAYING)) {
                    best = c;
                    bestMeta = m;
                    bestState = st;
                }
            }
        } catch (SecurityException e) {
            best = null;
        }
        // getActiveSessions hands out a new controller object for the same session each time: the callback is
        // registered on exactly one object per token, so keep that object when the token is unchanged (a register or
        // unregister on a fresh twin would leave the old registration writing liveMeta/liveState for a stale session).
        boolean same = playing != null && best != null && best.getSessionToken().equals(playing.getSessionToken());
        if (same) {
            best = playing;
        } else {
            if (playing != null) playing.unregisterCallback(mediaCallback);
            if (best != null) {
                best.registerCallback(mediaCallback, handler);
                sessionPlayFailed = false;
            }
        }
        playing = best;
        livePkg = best != null ? best.getPackageName() : null;
        liveMeta = bestMeta;
        liveState = bestState;
        showNowPlaying();
    }

    /** The device's "on" dot: visible while the HDMI device reports connected (on), hidden in standby or unplugged. */
    private void showDeviceState(String inputId, int state) {
        View dot = deviceDots.get(inputId);
        if (dot == null) return;
        boolean on = state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED;
        if (on == (dot.getVisibility() == View.VISIBLE)) return;
        if (on) {
            dot.setScaleX(0f);
            dot.setScaleY(0f);
            dot.setVisibility(View.VISIBLE);
            dot.animate().scaleX(1f).scaleY(1f).setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.duration(Motion.FAST_SPATIAL_MS)).start();
        } else {
            dot.animate().scaleX(0f).scaleY(0f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.duration(Motion.FAST_EFFECTS_MS))
                    .withEndAction(() -> dot.setVisibility(View.GONE)).start();
        }
    }

    /** Show the equalizer badge on the dock tile of the app that's playing (if it's in the dock). */
    private void updateBadges() {
        PlaybackState st = playing != null ? playing.getPlaybackState() : livePkg != null ? liveState : null;
        String pkg = st != null && st.getState() == PlaybackState.STATE_PLAYING ? livePkg : null;
        for (Map.Entry<String, EqualizerBadge> e : dockBadges.entrySet()) {
            boolean on = e.getKey().equals(pkg);
            EqualizerBadge b = e.getValue();
            if (on && b.getVisibility() != View.VISIBLE) {
                b.setAlpha(0f);
                b.setScaleX(0.6f);
                b.setScaleY(0.6f);
                b.setVisibility(View.VISIBLE);
                b.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.duration(Motion.FAST_SPATIAL_MS)).start();
            } else if (!on && b.getVisibility() == View.VISIBLE) {
                b.animate().alpha(0f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.duration(Motion.FAST_EFFECTS_MS))
                        .withEndAction(() -> b.setVisibility(View.GONE)).start();
            }
        }
    }

    // The last thing that played, kept after its app lets the session go (YouTube stops as soon as you leave it),
    // with whatever the app gave us to get back to it: an item id/uri or the session's own activity when it posts
    // one (Spotify and YouTube on this TV post neither, so for them play reopens the app and the card is a reminder).
    // The fields follow every session callback, also while another app has the screen; views are touched on Home only.
    private static final long REMEMBER_MS = 6 * 3_600_000L;
    private static final long RESUME_CHECK_MS = 1500;
    private boolean listening;               // the active-sessions listener is registered (until onDestroy)
    private boolean foreground;              // between onResume and onPause: the card may be drawn
    private MediaMetadata liveMeta;          // what the picked session last reported (one fetch per pick, then callbacks)
    private PlaybackState liveState;
    private CharSequence lastTitle, lastArtist;
    private Bitmap lastArt, shownArt;
    private String lastPkg, lastItem, lastMediaId, lastMediaUri, lastPoster;
    private PendingIntent lastSessionActivity;
    private int lastState;
    private float lastSpeed;
    private long lastPosition, lastUpdateTime, lastDuration, lastSeenAt;
    private boolean wasActive, showingRemembered, sessionPlayFailed;
    private final Runnable expire = () -> {
        forget();
        if (foreground && nowCard != null) showNowPlaying();
    };
    // play() went to a session that only said it could play: if nothing is playing after a moment, go on to the app.
    private final Runnable checkResumed = () -> {
        if (!foreground || !showingRemembered) return;
        sessionPlayFailed = true;
        resumeRemembered();
    };

    private void showNowPlaying() {
        MediaMetadata m = livePkg != null ? liveMeta : null;
        PlaybackState st = livePkg != null ? liveState : null;
        int state = st != null ? st.getState() : PlaybackState.STATE_NONE;
        boolean running = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
        boolean active = m != null && (running || state == PlaybackState.STATE_PAUSED);
        if (active) remember(m, st);
        else if (wasActive) freeze();
        wasActive = active;
        if (!foreground || nowCard == null) return;
        updateBadges();
        if (!active && !showingApps && lastPkg != null
                && System.currentTimeMillis() - lastSeenAt < REMEMBER_MS && launchIntent(lastPkg) != null) {
            showRemembered();
            return;
        }
        showingRemembered = false;
        if (!active || showingApps) {
            if (nowCard.getVisibility() == View.VISIBLE) {
                nowCard.animate().alpha(0f).setDuration(Motion.duration(Motion.DEFAULT_EFFECTS_MS)).withEndAction(() -> nowCard.setVisibility(View.GONE)).start();
            }
            return;
        }
        handler.removeCallbacks(checkResumed);
        nowTitle.setText(lastTitle);
        nowArtist.setText(lastArtist);
        showArt();
        nowProgress.setVisibility(lastDuration > 0 ? View.VISIBLE : View.GONE);
        nowProgress.setState(st.getPosition(), st.getLastPositionUpdateTime(), st.getPlaybackSpeed(), lastDuration, running);
        nowButton.setImageResource(running ? R.drawable.ic_pause : R.drawable.ic_play);
        styleNowPlaying();
        // Cancel a pending fade-out first: its end action would otherwise hide the card again.
        nowCard.animate().cancel();
        if (nowCard.getVisibility() != View.VISIBLE || nowCard.getAlpha() < 1f) {
            nowCard.setAlpha(0f);
            nowCard.setTranslationY(dp(10));
            nowCard.setVisibility(View.VISIBLE);
            nowCard.animate().alpha(1f).translationY(0).setInterpolator(Motion.DEFAULT_SPATIAL).setDuration(Motion.duration(Motion.DEFAULT_SPATIAL_MS)).start();
        }
    }

    /**
     * Note what the live session shows, on every callback, so the card can bring it back once the session is gone.
     * A session reports several times a second, so anything that costs a binder or PackageManager call (the session
     * activity, the app label standing in for a missing artist) or copies the art is done once per item only.
     */
    private void remember(MediaMetadata m, PlaybackState st) {
        CharSequence title = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence artist = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = m.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        String pkg = livePkg;
        String item = pkg + "|" + m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) + "|" + title + "|" + artist + "|"
                + m.getText(MediaMetadata.METADATA_KEY_ALBUM);
        if (!item.equals(lastItem)) {
            lastItem = item;
            lastTitle = title != null ? title : "";
            lastArtist = artist != null ? artist : appLabel(pkg);
            lastPkg = pkg;
            lastSessionActivity = playing != null ? playing.getSessionActivity() : null;
            lastArt = null;
            lastPoster = null;
            lastDuration = 0;
        }
        // The art copies made the card flicker: fetch it for a new item only, or until it arrives for the current one.
        if (lastArt == null) {
            Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            lastArt = art;
        }
        String poster = m.getString(MediaMetadata.METADATA_KEY_ART_URI);
        if (poster == null) poster = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        if (poster == null) poster = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        if (poster != null) {
            String scheme = Uri.parse(poster).getScheme();
            if ("https".equals(scheme) || "content".equals(scheme) || "android.resource".equals(scheme)) lastPoster = poster;
        }
        lastMediaId = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        lastMediaUri = m.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
        long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (duration > 0) lastDuration = duration;
        lastState = st.getState();
        lastPosition = st.getPosition();
        lastUpdateTime = st.getLastPositionUpdateTime();
        lastSpeed = st.getPlaybackSpeed();
        lastSeenAt = System.currentTimeMillis();
        if (lastState == PlaybackState.STATE_PLAYING || lastState == PlaybackState.STATE_BUFFERING) sessionPlayFailed = false;
        handler.removeCallbacks(expire);
        recordRecent(false);
    }

    /** The session stopped or went away: pin the position it had at this moment and start the 6 h clock. */
    private void freeze() {
        lastPosition = rememberedPosition();
        lastState = PlaybackState.STATE_PAUSED;
        lastSpeed = 0f;
        lastUpdateTime = android.os.SystemClock.elapsedRealtime();
        lastSeenAt = System.currentTimeMillis();
        sessionPlayFailed = false;
        handler.removeCallbacks(expire);
        handler.postDelayed(expire, REMEMBER_MS);
        recordRecent(true);
    }

    /** Where the remembered item is now: the last reported position, run forward while it was playing, within its length. */
    private long rememberedPosition() {
        long p = lastPosition;
        if (lastState == PlaybackState.STATE_PLAYING) {
            p += (long) ((android.os.SystemClock.elapsedRealtime() - lastUpdateTime) * lastSpeed);
        }
        if (lastDuration > 0) p = Math.min(p, lastDuration);
        return Math.max(0, p);
    }

    /** Six hours on: nothing to come back to, and no artwork or app intent kept around (the view lets go of it too). */
    private void forget() {
        lastPkg = lastItem = lastMediaId = lastMediaUri = null;
        lastPoster = savedRecentArtItem = null;
        savedRecentArt = null;
        lastTitle = lastArtist = null;
        lastArt = shownArt = null;
        if (nowArt != null) nowArt.setImageBitmap(null);
        lastSessionActivity = null;
        lastPosition = lastDuration = 0;
        handler.removeCallbacks(expire);
    }

    private void showArt() {
        if (shownArt == lastArt) return;
        nowArt.setImageBitmap(lastArt);
        shownArt = lastArt;
    }

    /** The remembered card: paused at where you left off; play brings that item back (see resumeRemembered). */
    private void showRemembered() {
        showingRemembered = true;
        nowTitle.setText(lastTitle != null ? lastTitle : "");
        nowArtist.setText(lastArtist != null ? lastArtist : appLabel(lastPkg));
        showArt();
        nowProgress.setVisibility(lastDuration > 0 ? View.VISIBLE : View.GONE);
        nowProgress.setState(rememberedPosition(), android.os.SystemClock.elapsedRealtime(), 0f, lastDuration, false);
        // Never fake it: the pause icon appears only once a session reports playing again (through showNowPlaying).
        nowButton.setImageResource(R.drawable.ic_play);
        styleNowPlaying();
        nowCard.animate().cancel();
        if (nowCard.getVisibility() != View.VISIBLE || nowCard.getAlpha() < 1f) {
            nowCard.setAlpha(1f);
            nowCard.setTranslationY(0);
            nowCard.setVisibility(View.VISIBLE);
        }
    }

    private void togglePlayback() {
        if (showingRemembered) {
            resumeRemembered();
            return;
        }
        if (playing == null) {
            // Dump fallback: no controller to talk to, so press the remote's play/pause for the session that has
            // media buttons, and read the dump again shortly to show the result.
            if (livePkg == null) return;
            android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
            for (int action : new int[] {android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP}) {
                audio.dispatchMediaKeyEvent(new android.view.KeyEvent(action, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
            }
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, 500);
            return;
        }
        PlaybackState st = playing.getPlaybackState();
        boolean running = st != null
                && (st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING);
        if (running) playing.getTransportControls().pause();
        else playing.getTransportControls().play();
    }

    /**
     * Play the remembered item the most direct way its app still allows, in this order: the session itself if it
     * quietly came back (keeps playing in the background), a deep link to the exact item where we can read its id,
     * the session's own activity for apps whose ids we cannot read, and last the app as it would open from the dock.
     */
    private void resumeRemembered() {
        if (lastPkg == null) return;
        handler.removeCallbacks(checkResumed);
        PlaybackState st = playing != null && lastPkg.equals(playing.getPackageName()) ? playing.getPlaybackState() : null;
        if (!sessionPlayFailed && st != null && st.getState() != PlaybackState.STATE_PLAYING
                && (st.getActions() & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)) != 0) {
            playing.getTransportControls().play();
            handler.postDelayed(checkResumed, RESUME_CHECK_MS);
            return;
        }
        Intent deep = resumeIntent(lastPkg, lastMediaId, lastMediaUri, rememberedPosition());
        if (deep != null && pm.resolveActivity(deep, 0) != null) {
            launch(deep);
            return;
        }
        if (lastSessionActivity != null) {
            try {
                lastSessionActivity.send();
                return;
            } catch (PendingIntent.CanceledException gone) {
                lastSessionActivity = null;
            }
        }
        Intent back = launchIntent(lastPkg);
        if (back != null) launch(back);
    }

    private static final String YOUTUBE = "com.google.android.youtube.tv";
    // An 11-character YouTube video id: after a watch?v= / youtu.be/ / embed/ marker in a URL, or standing alone in
    // anything that is not a URL. A bare token must carry a digit, capital, '-' or '_' so that an ordinary word is not
    // taken for an id ("recommended" and "googlevideo" are 11 letters too). Checked on the JVM with the same patterns:
    //   dQw4w9WgXcQ, yt:video:dQw4w9WgXcQ, watch?v=.., watch?feature=share&v=..&t=10, youtu.be/.., /embed/.. -> the id
    //   recommended, PL<34-char playlist id>, spotify:track:.., https://r3---sn-abc.googlevideo.com/.. -> nothing
    private static final Pattern YOUTUBE_URL_ID = Pattern.compile("(?:[?&]v=|youtu\\.be/|/embed/|/v/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])");
    private static final Pattern YOUTUBE_BARE_ID = Pattern.compile("(?<![A-Za-z0-9_-])(?=[a-z]*[A-Z0-9_-])([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])");

    /**
     * A deep link to the remembered item for the apps whose ids we know how to read, or null to leave it to the
     * session's own activity or a plain launch. Measured on the TV on 2026-10-18, neither app we care most about
     * gives us an item id, so for both this returns null and play reopens the app:
     *   Spotify  posts neither METADATA_KEY_MEDIA_ID nor MEDIA_URI (only display icon, duration, album-art uri,
     *            advertisement, album, title, artist, album art) and has no session activity.
     *   YouTube  (com.google.android.youtube.tv) posts only DURATION, an empty ALBUM and TITLE (ARTIST/ART possibly
     *            filtered); no MEDIA_ID, no MEDIA_URI, no session activity, empty extras, null queue.
     * The branches below are kept for apps or builds that do post such ids; their formats are UNVERIFIED:
     *   "spotify:track:<22 chars>" style URIs are opened as is; an 11-character YouTube video id, bare or inside a
     *   watch / youtu.be URL, is opened as https://www.youtube.com/watch?v=<id> with &t=<seconds> when a position is
     *   known; any other https URL is opened in the same app.
     */
    static Intent resumeIntent(String pkg, String mediaId, String mediaUri, long positionMs) {
        String[] keys = {mediaUri, mediaId};
        for (String k : keys) {
            if (k != null && k.startsWith("spotify:")) return view(pkg, k);
        }
        if (YOUTUBE.equals(pkg)) {
            String id = youtubeId(mediaUri);
            if (id == null) id = youtubeId(mediaId);
            if (id != null) {
                String url = "https://www.youtube.com/watch?v=" + id;
                if (positionMs > 0) url += "&t=" + positionMs / 1000;
                return view(pkg, url);
            }
        }
        for (String k : keys) {
            if (k != null && k.startsWith("https://")) return view(pkg, k);
        }
        return null;
    }

    private static Intent view(String pkg, String uri) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg);
    }

    private static String youtubeId(String s) {
        if (s == null || s.isEmpty()) return null;
        Matcher m = (s.contains("://") ? YOUTUBE_URL_ID : YOUTUBE_BARE_ID).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private CharSequence appLabel(String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // ---- motion ----

    private void arrive(View v, int index) {
        if (Motion.reduced()) {
            v.setAlpha(1f);
            v.setTranslationY(0f);
            return;
        }
        v.setAlpha(0f);
        v.setTranslationY(dp(18));
        v.animate().alpha(1f).translationY(0).setStartDelay(40L * index).setInterpolator(Motion.DEFAULT_SPATIAL).setDuration(Motion.duration(Motion.DEFAULT_SPATIAL_MS)).start();
    }

    // ---- helpers ----

    /** Google Sans Flex at a given weight, standard terminals (axes 'wght', 'opsz'; 'ROND' 0); falls back to sans-serif. */
    private Typeface font(int requested) {
        int weight = requested <= 400 ? 300 : requested <= 650 ? 560 : requested <= 790 ? 760 : 820;
        Typeface t = fonts.get(weight);
        if (t == null) {
            try {
                t = new Typeface.Builder(getAssets(), "fonts/GoogleSansFlex.ttf")
                        .setFontVariationSettings("'wght' " + weight + ", 'opsz' 24, 'ROND' 0")
                        .build();
            } catch (RuntimeException e) {
                t = null;
            }
            if (t == null) t = Typeface.create("sans-serif", weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
            fonts.put(weight, t);
        }
        return t;
    }

    private TextView text(CharSequence value, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font(weight));
        return t;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
