package com.craftstudio.cslauncher.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.update.UpdateManager;

import org.json.JSONObject;

import java.util.List;

/**
 * Pulls the admin-published notifications and posts each new one as a real Android notification.
 * Runs from the periodic job and once per launcher start; everything is idempotent (delivered ids
 * are remembered) and silent on any network error.
 */
public final class NotificationSync {
    public static final String EXTRA_URL = "cs_notify_url";
    public static final String EXTRA_KIND = "cs_notify_kind";
    public static final String EXTRA_ID = "cs_notify_id";
    private static final long MIN_INTERVAL_MS = 10 * 60 * 1000L; // app-start sync is throttled
    private static final int MAX_PER_SYNC = 3;

    private NotificationSync() {}

    /** Async sync from the launcher UI (throttled, never blocks) */
    public static void runAsync(@NonNull Context context) {
        final Context app = context.getApplicationContext();
        if (System.currentTimeMillis() - NotifyStore.lastSync(app) < MIN_INTERVAL_MS) return;
        Thread t = new Thread(() -> sync(app), "cs-notify-sync");
        t.setDaemon(true);
        t.start();
    }

    /** Blocking sync; returns true when the network call succeeded */
    public static boolean sync(@NonNull Context context) {
        Context app = context.getApplicationContext();
        ensureChannels(app);
        List<JSONObject> rows;
        try {
            rows = NotifyApi.fetch("notifications",
                    "select=id,kind,title,body,url,image_url,version_name,version_code,min_version_code,max_version_code,created_at"
                            + "&order=created_at.desc&limit=20");
        } catch (Throwable t) {
            return false;
        }
        NotifyStore.setLastSync(app, System.currentTimeMillis());
        int code = UpdateManager.currentCode();
        long firstSeen = NotifyStore.firstSeen(app);
        int posted = 0;
        // oldest first so the newest ends up on top of the shade
        for (int i = rows.size() - 1; i >= 0 && posted < MAX_PER_SYNC; i--) {
            JSONObject n = rows.get(i);
            String id = n.optString("id", "");
            if (id.isEmpty() || NotifyStore.wasDelivered(app, id)) continue;
            if (!versionMatches(n, code)) { NotifyStore.markDelivered(app, id); continue; }
            String kind = n.optString("kind", "general");
            if ("update".equals(kind) && n.optInt("version_code", 0) > 0 && n.optInt("version_code") <= code) {
                NotifyStore.markDelivered(app, id); // already on that version or newer
                continue;
            }
            // items published before this install never existed for this device
            long created = parseIso(n.optString("created_at", ""));
            if (created > 0 && created < firstSeen - 24L * 3600_000L) { NotifyStore.markDelivered(app, id); continue; }
            if (post(app, n, id, kind)) {
                NotifyStore.markDelivered(app, id);
                NotifyApi.delivery("notification", id, "shown", NotifyStore.deviceHash(app), code);
                posted++;
            }
        }
        return true;
    }

    static boolean versionMatches(JSONObject row, int code) {
        if (!row.isNull("min_version_code") && code < row.optInt("min_version_code")) return false;
        if (!row.isNull("max_version_code") && code > row.optInt("max_version_code")) return false;
        return true;
    }

    private static boolean post(Context app, JSONObject n, String id, String kind) {
        try {
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false;
            String title = n.optString("title", "");
            String body = n.optString("body", "");
            String url = n.isNull("url") ? "" : n.optString("url", "");
            String imageUrl = n.isNull("image_url") ? "" : n.optString("image_url", "");
            if ("update".equals(kind)) {
                String v = n.isNull("version_name") ? "" : n.optString("version_name", "");
                if (title.isEmpty()) title = app.getString(R.string.app_short_name) + (v.isEmpty() ? "" : " " + v);
            }
            if (title.isEmpty()) title = app.getString(R.string.app_short_name);

            Intent open = new Intent(app, LauncherActivity.class)
                    .setAction("com.craftstudio.cslauncher.NOTIFY_" + id)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_URL, url).putExtra(EXTRA_KIND, kind).putExtra(EXTRA_ID, id);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            int reqCode = NotifyConfig.NOTIFICATION_BASE_ID + (id.hashCode() & 0xFFFF);
            PendingIntent pi = PendingIntent.getActivity(app, reqCode, open, flags);

            NotificationCompat.Builder b = new NotificationCompat.Builder(app, NotifyConfig.CHANNEL_ID)
                    .setSmallIcon(R.drawable.notif_icon)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setCategory("update".equals(kind) ? NotificationCompat.CATEGORY_RECOMMENDATION : NotificationCompat.CATEGORY_PROMO);
            if ("update".equals(kind)) {
                b.addAction(0, app.getString(R.string.notify_update_action), pi);
            } else if (!url.isEmpty()) {
                b.addAction(0, app.getString(R.string.ann_open), pi);
            }
            if (!imageUrl.isEmpty()) {
                Bitmap bmp = decode(NotifyApi.download(imageUrl, 3 * 1024 * 1024));
                if (bmp != null) {
                    b.setLargeIcon(bmp);
                    b.setStyle(new NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon((Bitmap) null).setSummaryText(body));
                }
            }
            NotificationManagerCompat.from(app).notify(reqCode, b.build());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    static Bitmap decode(@Nullable byte[] data) {
        if (data == null) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / sample > 1280 || o.outHeight / sample > 1280) sample *= 2;
            BitmapFactory.Options r = new BitmapFactory.Options();
            r.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, r);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Creates the notification channels (idempotent) */
    public static void ensureChannels(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel c = new NotificationChannel(NotifyConfig.CHANNEL_ID,
                    context.getString(R.string.notify_channel_name), NotificationManager.IMPORTANCE_HIGH);
            c.setDescription(context.getString(R.string.notify_channel_desc));
            nm.createNotificationChannel(c);
            NotificationChannel lan = new NotificationChannel(NotifyConfig.CHANNEL_LAN_ID,
                    context.getString(R.string.notify_lan_channel_name), NotificationManager.IMPORTANCE_HIGH);
            lan.setDescription(context.getString(R.string.notify_lan_channel_desc));
            nm.createNotificationChannel(lan);
        } catch (Throwable ignored) {
        }
    }

    /** Called by LauncherActivity when the user taps one of our notifications */
    public static boolean handleIntent(@NonNull android.app.Activity activity, @Nullable Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_KIND)) return false;
        String kind = intent.getStringExtra(EXTRA_KIND);
        String url = intent.getStringExtra(EXTRA_URL);
        String id = intent.getStringExtra(EXTRA_ID);
        intent.removeExtra(EXTRA_KIND);
        if (id != null) {
            final Context app = activity.getApplicationContext();
            final String fid = id;
            new Thread(() -> NotifyApi.delivery("notification", fid, "clicked", NotifyStore.deviceHash(app), UpdateManager.currentCode())).start();
        }
        if ("update".equals(kind) && (url == null || url.isEmpty())) {
            UpdateManager.manualCheck(activity);
            return true;
        }
        if (url != null && !url.isEmpty()) {
            com.craftstudio.cslauncher.Tools.openURL(activity, url);
            return true;
        }
        return false;
    }

    static long parseIso(String iso) {
        // 2026-10-24T09:30:12.123456+00:00 → epoch ms (second precision is enough)
        try {
            if (iso.length() < 19) return 0;
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = f.parse(iso.substring(0, 19));
            return d == null ? 0 : d.getTime();
        } catch (Throwable t) {
            return 0;
        }
    }
}
