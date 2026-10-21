package com.craftstudio.cslauncher.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.instances.DisplayInstance;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.InstanceIconProvider;
import com.craftstudio.cslauncher.instances.Instances;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Home-screen shortcuts for profiles. A pinned (or long-press dynamic) shortcut opens the
 * launcher with {@link #EXTRA_PROFILE}; {@code LauncherActivity} then selects that profile and
 * starts the game straight away, so one tap goes from the home screen into Minecraft.
 */
public final class ProfileShortcuts {
    private static final String TAG = "ProfileShortcuts";
    public static final String ACTION_LAUNCH_PROFILE = "com.craftstudio.cslauncher.LAUNCH_PROFILE";
    public static final String EXTRA_PROFILE = "cs.launch_profile";
    private static final String ID_PREFIX = "profile:";
    private static final int MAX_DYNAMIC = 4;

    private ProfileShortcuts() {}

    @NonNull
    public static Intent launchIntent(@NonNull Context context, @NonNull String key) {
        Intent intent = new Intent(context, com.craftstudio.cslauncher.ShortcutLaunchActivity.class);
        intent.setAction(ACTION_LAUNCH_PROFILE);
        intent.putExtra(EXTRA_PROFILE, key);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    public static boolean isPinSupported(@NonNull Context context) {
        try {
            return ShortcutManagerCompat.isRequestPinShortcutSupported(context);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Asks the home screen to pin a shortcut for the profile (the system shows its own confirmation). */
    public static void pin(@NonNull Activity activity, @NonNull Instance instance) {
        String key = instance.getKey();
        if (key == null || key.isEmpty()) return;
        if (!isPinSupported(activity)) {
            Toast.makeText(activity, R.string.shortcut_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            ShortcutInfoCompat info = build(activity, instance, key);
            boolean ok = ShortcutManagerCompat.requestPinShortcut(activity, info, null);
            Toast.makeText(activity, ok ? R.string.shortcut_requested : R.string.shortcut_unsupported, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "pin failed", t);
            Toast.makeText(activity, R.string.shortcut_unsupported, Toast.LENGTH_LONG).show();
        }
    }

    /** Long-press menu of the app icon: the first profiles, kept in sync with the instance list. */
    public static void refreshDynamic(@NonNull Context context) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                List<Instance> all = Instances.loadAllInstances();
                List<ShortcutInfoCompat> list = new ArrayList<>();
                for (Instance instance : all) {
                    if (list.size() >= MAX_DYNAMIC) break;
                    String key = instance.getKey();
                    if (key == null || key.isEmpty()) continue;
                    list.add(build(app, instance, key));
                }
                final List<ShortcutInfoCompat> fList = list;
                Tools.MAIN_HANDLER.post(() -> {
                    try {
                        ShortcutManagerCompat.setDynamicShortcuts(app, fList);
                    } catch (Throwable t) {
                        Log.w(TAG, "dynamic shortcuts: " + t);
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "dynamic shortcuts: " + t);
            }
        }, "cs-shortcuts").start();
    }

    /** Called when a shortcut points at a profile that no longer exists. */
    public static void disable(@NonNull Context context, @NonNull String key) {
        try {
            ShortcutManagerCompat.disableShortcuts(context, Collections.singletonList(ID_PREFIX + key),
                    context.getString(R.string.shortcut_profile_missing));
        } catch (Throwable ignored) {
        }
    }

    private static ShortcutInfoCompat build(@NonNull Context context, @NonNull Instance instance, @NonNull String key) {
        String name = instance.name == null || instance.name.trim().isEmpty() ? key : instance.name.trim();
        String version = instance.versionId == null ? "" : instance.versionId;
        return new ShortcutInfoCompat.Builder(context, ID_PREFIX + key)
                .setShortLabel(name)
                .setLongLabel(version.isEmpty() ? name : name + " \u00b7 " + version)
                .setIcon(icon(context, instance))
                .setIntent(launchIntent(context, key))
                .build();
    }

    /** Adaptive icon: the profile icon on the launcher's dark tile with a soft platinum rim. */
    private static IconCompat icon(@NonNull Context context, @NonNull DisplayInstance instance) {
        int size = 432; // 108dp @ 4x, the adaptive icon canvas (inner 72dp safe zone)
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF121216);
        canvas.drawRect(0, 0, size, size, paint);
        // inner tile
        float inset = size * 0.18f;
        RectF tile = new RectF(inset, inset, size - inset, size - inset);
        paint.setColor(0xFF1C1C21);
        canvas.drawRoundRect(tile, size * 0.11f, size * 0.11f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(size * 0.012f);
        paint.setColor(0x66C6C6CE);
        canvas.drawRoundRect(tile, size * 0.11f, size * 0.11f, paint);
        paint.setStyle(Paint.Style.FILL);
        try {
            Drawable d = InstanceIconProvider.fetchIcon(context.getResources(), instance);
            int pad = Math.round(size * 0.26f);
            d.setBounds(pad, pad, size - pad, size - pad);
            d.draw(canvas);
        } catch (Throwable t) {
            Log.w(TAG, "icon: " + t);
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap);
    }

    @Nullable
    public static String profileFrom(@Nullable Intent intent) {
        if (intent == null) return null;
        String key = intent.getStringExtra(EXTRA_PROFILE);
        return key == null || key.isEmpty() ? null : key;
    }
}
