package com.craftstudio.cslauncher.friends;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * CS Friends → Android notifications. Not every chat rings the phone: the player pins the
 * friends whose messages matter, and only those (plus every world invite and friend request)
 * become system notifications. Runs in whichever process has the friends client (launcher and
 * game), keeps a slow poll alive in the background through
 * {@link FriendsClient#addBackgroundListener}, and stays quiet for the chat that is open on
 * screen.
 */
public final class FriendsNotifier implements FriendsClient.Listener {
    public static final String CHANNEL_ID = "cs_friends";
    public static final String EXTRA_CHAT = "cs.friends.chat";
    /** A world to join right away (address / engine / version / host id), from a hosting or invite notification. */
    public static final String EXTRA_JOIN_ADDRESS = "cs.friends.join.address", EXTRA_JOIN_ENGINE = "cs.friends.join.engine",
            EXTRA_JOIN_VERSION = "cs.friends.join.version", EXTRA_JOIN_HOST = "cs.friends.join.host";
    private static final String K_PINNED = "cs.friends.pinned";
    public static final int PERMISSION_REQUEST = 7312;

    private static FriendsNotifier sInstance;
    private static volatile String sOpenChat;

    private final Context mApp;

    private FriendsNotifier(Context app) { mApp = app; }

    /** Once per process: listens for notes as long as the account is signed in. */
    public static synchronized void install(@NonNull Context context) {
        if (sInstance != null) return;
        sInstance = new FriendsNotifier(context.getApplicationContext());
        if (CsAccount.isSignedIn()) FriendsClient.get().addBackgroundListener(sInstance);
    }

    /** The account changed: start or stop the background poll accordingly. */
    public static synchronized void onAccountChanged() {
        if (sInstance == null) return;
        if (CsAccount.isSignedIn()) FriendsClient.get().addBackgroundListener(sInstance);
        else FriendsClient.get().removeBackgroundListener(sInstance);
    }

    /** The chat shown on screen right now (its messages are not notified), or null. */
    public static void setOpenChat(@Nullable String friendId) { sOpenChat = friendId; }

    // ---------------------------------------------------------------- pins

    private static SharedPreferences prefs() { return LauncherPreferences.DEFAULT_PREF; }

    @NonNull
    public static Set<String> pinned() {
        return new HashSet<>(prefs().getStringSet(K_PINNED, new HashSet<String>()));
    }

    public static boolean isPinned(@Nullable String friendId) {
        return friendId != null && pinned().contains(friendId);
    }

    public static void setPinned(@NonNull String friendId, boolean pinned) {
        Set<String> set = pinned();
        if (pinned) set.add(friendId); else set.remove(friendId);
        prefs().edit().putStringSet(K_PINNED, set).apply();
    }

    /** Android 13+: notifications need the runtime permission; asked when the first friend is pinned. */
    public static void ensurePermission(@NonNull Activity activity) {
        if (Build.VERSION.SDK_INT < 33) return;
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST);
    }

    // ---------------------------------------------------------------- listener

    @Override public void onFriendsChanged(@NonNull FriendsClient.Snapshot snapshot) {}

    @Override public void onNote(@NonNull FriendsClient.Note note) {
        boolean ring;
        switch (note.kind) {
            case FriendsClient.Note.INVITE:
            case FriendsClient.Note.REQUEST:
            case FriendsClient.Note.HOSTING:
                ring = true;
                break;
            case FriendsClient.Note.MESSAGE:
                ring = isPinned(note.fromId) && (sOpenChat == null || !sOpenChat.equals(note.fromId));
                break;
            default:
                ring = false;
        }
        if (ring) show(note);
    }

    private void show(FriendsClient.Note note) {
        NotificationManager nm = (NotificationManager) mApp.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, mApp.getString(R.string.friends_channel_name), NotificationManager.IMPORTANCE_HIGH);
            c.setDescription(mApp.getString(R.string.friends_channel_desc));
            nm.createNotificationChannel(c);
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(mApp, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;

        Intent open = new Intent(mApp, LauncherActivity.class);
        open.setAction(Intent.ACTION_MAIN);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (note.fromId != null) open.putExtra(EXTRA_CHAT, note.fromId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        int id = (note.fromId == null ? note.key : note.fromId).hashCode();
        PendingIntent pi = PendingIntent.getActivity(mApp, id, open, flags);
        // Invites and "hosting again" notes get a Join action that starts the game straight away
        PendingIntent joinPi = null;
        if ((note.kind == FriendsClient.Note.INVITE || note.kind == FriendsClient.Note.HOSTING) && note.address != null) {
            Intent join = new Intent(mApp, LauncherActivity.class);
            join.setAction("cs.friends.JOIN");
            join.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            join.putExtra(EXTRA_JOIN_ADDRESS, note.address).putExtra(EXTRA_JOIN_ENGINE, note.engine)
                    .putExtra(EXTRA_JOIN_VERSION, note.version).putExtra(EXTRA_JOIN_HOST, note.fromId);
            joinPi = PendingIntent.getActivity(mApp, id + 1, join, flags);
        }

        String title = note.title == null ? mApp.getString(R.string.friends_title) : note.title;
        String body = note.body == null ? "" : note.body;
        NotificationCompat.Builder b = new NotificationCompat.Builder(mApp, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_friends_bell)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(note.kind == FriendsClient.Note.MESSAGE ? NotificationCompat.CATEGORY_MESSAGE : NotificationCompat.CATEGORY_SOCIAL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setWhen(note.at > 0 ? note.at : System.currentTimeMillis())
                .setShowWhen(true);
        if (joinPi != null) b.addAction(R.drawable.ic_friends_bell, mApp.getString(R.string.friends_join), joinPi);
        try {
            nm.notify(id, b.build());
        } catch (SecurityException ignored) {
            // Notifications turned off for the app: nothing to ring
        }
    }
}
