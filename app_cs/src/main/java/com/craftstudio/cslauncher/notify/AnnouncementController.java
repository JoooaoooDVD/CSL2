package com.craftstudio.cslauncher.notify;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.update.UpdateManager;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * In-app announcements published from the admin panel. Two styles:
 * <ul>
 *   <li>{@code fullscreen} — dimmed launcher + centred card (blocking until dismissed)</li>
 *   <li>{@code card} — a small card sliding in from the right edge of the home screen</li>
 * </ul>
 * Checked on every launcher start and resume (fetch throttled to once a minute); the fullscreen
 * one goes first. Waits while first-launch dialogs or a guide are on screen and shows as soon as
 * they are gone; gives up quietly if the activity is gone. Per-item "shown" bookkeeping lives in
 * {@link NotifyStore}, so an item never shows twice even across checks.
 */
public final class AnnouncementController {
    public interface BusyCheck { boolean isBusy(); }

    private static final long CARD_AUTO_HIDE_MS = 18_000L;
    private static final int WAIT_TRIES = 400; // × 1.5 s = 10 min of waiting for the UI to be free
    private static final long MIN_CHECK_INTERVAL_MS = 60_000L;
    private static long sLastCheck;
    private static boolean sActive;

    private final WeakReference<Activity> mActivity;
    private final BusyCheck mBusy;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    @Nullable private JSONObject mFull, mCard;
    @Nullable private Bitmap mFullImage, mCardImage;
    private int mTries;

    private AnnouncementController(Activity a, BusyCheck busy) {
        mActivity = new WeakReference<>(a);
        mBusy = busy;
    }

    /** Fetches and shows pending announcements (call from the launcher's onCreate and onResume) */
    public static void showWhenIdle(@NonNull Activity activity, @NonNull BusyCheck busy) {
        long now = System.currentTimeMillis();
        if (sActive || now - sLastCheck < MIN_CHECK_INTERVAL_MS) return;
        sLastCheck = now;
        sActive = true;
        final AnnouncementController c = new AnnouncementController(activity, busy);
        Thread t = new Thread(() -> {
            try {
                c.load();
            } finally {
                if (c.mFull == null && c.mCard == null) sActive = false; // nothing queued for the UI thread
            }
        }, "cs-announcements");
        t.setDaemon(true);
        t.start();
    }

    private void done() {
        sActive = false;
    }

    private void load() {
        Activity a = mActivity.get();
        if (a == null) return;
        List<JSONObject> rows;
        try {
            rows = NotifyApi.fetch("announcements",
                    "select=id,style,title,body,image_url,accent,button_label,button_url,dismissible,show_once,priority,min_version_code,max_version_code"
                            + "&order=priority.desc,created_at.desc&limit=30");
        } catch (Throwable t) {
            return;
        }
        int code = UpdateManager.currentCode();
        for (JSONObject r : rows) {
            String id = r.optString("id", "");
            if (id.isEmpty() || !NotificationSync.versionMatches(r, code)) continue;
            boolean once = r.optBoolean("show_once", true);
            if (once && NotifyStore.wasShown(a, id)) continue;
            if (!once && NotifyStore.wasDismissed(a, id)) continue;
            String style = r.optString("style", "card");
            if ("fullscreen".equals(style)) { if (mFull == null) mFull = r; }
            else if (mCard == null) mCard = r;
            if (mFull != null && mCard != null) break;
        }
        if (mFull == null && mCard == null) return;
        if (mFull != null) mFullImage = image(mFull);
        if (mCard != null) mCardImage = image(mCard);
        mMain.postDelayed(this::tryShow, 1500L);
    }

    @Nullable
    private static Bitmap image(JSONObject r) {
        String u = r.isNull("image_url") ? "" : r.optString("image_url", "");
        if (u.isEmpty()) return null;
        return NotificationSync.decode(NotifyApi.download(u, 4 * 1024 * 1024));
    }

    private void tryShow() {
        Activity a = mActivity.get();
        if (a == null || a.isFinishing() || a.isDestroyed()) {
            sLastCheck = 0L; // the next activity checks again right away
            done();
            return;
        }
        if (mBusy.isBusy()) {
            if (++mTries < WAIT_TRIES) mMain.postDelayed(this::tryShow, 1500L);
            else { sLastCheck = 0L; done(); }
            return;
        }
        if (mFull != null) {
            JSONObject f = mFull;
            mFull = null;
            showFullscreen(a, f, mFullImage);
        } else if (mCard != null) {
            JSONObject c = mCard;
            mCard = null;
            showCard(a, c, mCardImage);
        } else {
            done();
        }
    }

    // ───────────────────────────── fullscreen ─────────────────────────────

    private void showFullscreen(final Activity a, final JSONObject r, @Nullable Bitmap image) {
        final String id = r.optString("id", "");
        final Dialog d = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        d.setContentView(R.layout.announcement_fullscreen);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawableResource(android.R.color.transparent);
            // keep the launcher's immersive bars
            w.getDecorView().setSystemUiVisibility(a.getWindow().getDecorView().getSystemUiVisibility());
        }
        boolean dismissible = r.optBoolean("dismissible", true);
        d.setCancelable(dismissible);
        d.setCanceledOnTouchOutside(false);
        bind(d.findViewById(R.id.ann_card), r, image, 16f);
        View close = d.findViewById(R.id.ann_close);
        View secondary = d.findViewById(R.id.ann_secondary);
        TextView primary = d.findViewById(R.id.ann_primary);
        final String url = r.isNull("button_url") ? "" : r.optString("button_url", "");
        final boolean[] acted = {false};
        View.OnClickListener dismiss = v -> { acted[0] = true; report(a, id, "dismissed"); NotifyStore.markDismissed(a, id); d.dismiss(); };
        close.setOnClickListener(dismiss);
        secondary.setOnClickListener(dismiss);
        close.setVisibility(dismissible ? View.VISIBLE : View.INVISIBLE);
        secondary.setVisibility(dismissible && !url.isEmpty() ? View.VISIBLE : View.GONE);
        primary.setOnClickListener(v -> {
            acted[0] = true;
            NotifyStore.markDismissed(a, id);
            if (!url.isEmpty()) { report(a, id, "clicked"); Tools.openURL(a, url); }
            else report(a, id, "dismissed");
            d.dismiss();
        });
        d.setOnDismissListener(dlg -> {
            if (!acted[0]) { report(a, id, "dismissed"); NotifyStore.markDismissed(a, id); }
            // the side card (if any) follows after the big one
            mMain.postDelayed(this::tryShow, 600L);
        });
        View card = d.findViewById(R.id.ann_card);
        card.setAlpha(0f); card.setScaleX(0.92f); card.setScaleY(0.92f); card.setTranslationY(dp(a, 18));
        try { d.show(); } catch (Throwable t) { done(); return; }
        NotifyStore.markShown(a, id);
        report(a, id, "shown");
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(380)
                .setInterpolator(new OvershootInterpolator(0.9f)).start();
    }

    // ───────────────────────────── side card ─────────────────────────────

    private void showCard(final Activity a, final JSONObject r, @Nullable Bitmap image) {
        final String id = r.optString("id", "");
        final ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null) { done(); return; }
        final View card = a.getLayoutInflater().inflate(R.layout.announcement_card, content, false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        int m = dp(a, 14);
        lp.setMargins(m, dp(a, 62), m, m);
        card.setLayoutParams(lp);
        bind(card, r, image, 14f);
        final String url = r.isNull("button_url") ? "" : r.optString("button_url", "");
        boolean dismissible = r.optBoolean("dismissible", true);
        final Runnable[] autoHide = new Runnable[1];
        final Runnable hide = () -> {
            mMain.removeCallbacks(autoHide[0]);
            card.animate().translationX(card.getWidth() + m).alpha(0f).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> content.removeView(card)).start();
        };
        autoHide[0] = () -> { if (card.getParent() != null) hide.run(); };
        View close = card.findViewById(R.id.ann_close);
        close.setVisibility(dismissible ? View.VISIBLE : View.GONE);
        close.setOnClickListener(v -> { report(a, id, "dismissed"); NotifyStore.markDismissed(a, id); hide.run(); });
        TextView primary = card.findViewById(R.id.ann_primary);
        primary.setOnClickListener(v -> {
            NotifyStore.markDismissed(a, id);
            if (!url.isEmpty()) { report(a, id, "clicked"); Tools.openURL(a, url); }
            else report(a, id, "dismissed");
            hide.run();
        });
        card.setOnClickListener(v -> mMain.removeCallbacks(autoHide[0])); // touching keeps it
        card.setTranslationX(dp(a, 340));
        card.setAlpha(0f);
        content.addView(card);
        NotifyStore.markShown(a, id);
        report(a, id, "shown");
        done(); // the card lives on its own from here
        card.animate().translationX(0f).alpha(1f).setDuration(420).setInterpolator(new OvershootInterpolator(0.8f)).start();
        if (dismissible) mMain.postDelayed(autoHide[0], CARD_AUTO_HIDE_MS);
    }

    // ───────────────────────────── shared ─────────────────────────────

    private static void bind(View root, JSONObject r, @Nullable Bitmap image, float imageRadiusDp) {
        TextView title = root.findViewById(R.id.ann_title);
        TextView body = root.findViewById(R.id.ann_body);
        TextView primary = root.findViewById(R.id.ann_primary);
        TextView eyebrow = root.findViewById(R.id.ann_eyebrow);
        View dot = root.findViewById(R.id.ann_dot);
        ImageView img = root.findViewById(R.id.ann_image);
        title.setText(r.optString("title", ""));
        String b = r.isNull("body") ? "" : r.optString("body", "");
        body.setText(b);
        body.setVisibility(b.isEmpty() ? View.GONE : View.VISIBLE);
        if (body.getMaxLines() >= 7) body.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        String label = r.isNull("button_label") ? "" : r.optString("button_label", "");
        if (!label.isEmpty()) primary.setText(label);
        else if (!r.isNull("button_url") && !r.optString("button_url", "").isEmpty()) primary.setText(R.string.ann_open);
        String accent = r.isNull("accent") ? "" : r.optString("accent", "");
        if (accent.startsWith("#")) {
            try {
                int c = Color.parseColor(accent);
                eyebrow.setTextColor(c);
                dot.getBackground().mutate().setColorFilter(c, android.graphics.PorterDuff.Mode.SRC_IN);
            } catch (Throwable ignored) {}
        }
        if (image != null) {
            img.setImageBitmap(image);
            img.setVisibility(View.VISIBLE);
            final float radius = imageRadiusDp * root.getResources().getDisplayMetrics().density;
            img.setClipToOutline(true);
            img.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
            });
        }
    }

    private static void report(Activity a, final String id, final String event) {
        final android.content.Context app = a.getApplicationContext();
        Thread t = new Thread(() -> NotifyApi.delivery("announcement", id, event, NotifyStore.deviceHash(app), UpdateManager.currentCode()));
        t.setDaemon(true);
        t.start();
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
