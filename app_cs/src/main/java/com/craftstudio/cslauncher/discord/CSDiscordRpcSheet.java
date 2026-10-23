package com.craftstudio.cslauncher.discord;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.instances.InstanceIconProvider;
import com.craftstudio.cslauncher.prefs.SettingsPopup;

import java.util.Locale;

/**
 * "Discord Rich Presence" popup: delivery status, a live preview of the Discord activity card
 * (mirrors exactly what {@link DiscordPresenceManager} publishes, even before any account is
 * linked). There is nothing to configure and nothing to log in to.
 */
public final class CSDiscordRpcSheet {

    private CSDiscordRpcSheet() {}

    public static void show(@NonNull final Activity activity) {
        final Context context = activity;
        final DiscordPresenceManager presence = DiscordPresenceManager.getInstance(context);
        presence.attach(activity);
        final Handler handler = new Handler(Looper.getMainLooper());

        final View content = LayoutInflater.from(context).inflate(R.layout.cs_discord_rpc_sheet, null, false);
        final View statusDot = content.findViewById(R.id.cs_discord_status_dot);
        final TextView statusText = content.findViewById(R.id.cs_discord_status_text);
        final TextView statusSub = content.findViewById(R.id.cs_discord_status_sub);
        final TextView sceneChip = content.findViewById(R.id.cs_discord_preview_scene);
        final ImageView previewLarge = content.findViewById(R.id.cs_discord_preview_large);
        final View previewSmallWrap = content.findViewById(R.id.cs_discord_preview_small_wrap);
        final ImageView previewSmall = content.findViewById(R.id.cs_discord_preview_small);
        final TextView previewDetails = content.findViewById(R.id.cs_discord_preview_details);
        final TextView previewState = content.findViewById(R.id.cs_discord_preview_state);
        final TextView previewElapsed = content.findViewById(R.id.cs_discord_preview_elapsed);
        final TextView previewBtn1 = content.findViewById(R.id.cs_discord_preview_btn1);
        final TextView previewBtn2 = content.findViewById(R.id.cs_discord_preview_btn2);

        previewLarge.setClipToOutline(true);
        previewBtn1.setOnClickListener(v -> openUrl(context, DiscordPresenceManager.DISCORD_INVITE_URL));
        previewBtn2.setOnClickListener(v -> openUrl(context, DiscordPresenceManager.YOUTUBE_URL));

        final long[] startMs = {0L};
        final boolean[] closed = {false};

        // ---- live preview -------------------------------------------------------------
        final DiscordPresenceManager.Listener listener = snapshot -> {
            if (closed[0]) return;
            previewDetails.setText(snapshot.details);
            previewState.setText(snapshot.state);
            sceneChip.setText(sceneLabel(context, snapshot.scene));
            startMs[0] = snapshot.startMs;

            Drawable large;
            if (snapshot.scene == DiscordPresenceManager.Scene.HOME || snapshot.instance == null) {
                large = ContextCompat.getDrawable(context, R.drawable.ic_cs_full);
            } else {
                try {
                    large = InstanceIconProvider.fetchIcon(context.getResources(), snapshot.instance);
                    // Snapshots carry fresh Instance objects: do not let them pile up in the icon cache
                    InstanceIconProvider.dropIcon(snapshot.instance);
                } catch (Throwable t) {
                    large = ContextCompat.getDrawable(context, R.drawable.ic_cs_full);
                }
            }
            previewLarge.setImageDrawable(large);

            if (snapshot.scene == DiscordPresenceManager.Scene.HOME) {
                previewSmallWrap.setVisibility(View.GONE);
            } else {
                previewSmallWrap.setVisibility(View.VISIBLE);
                previewSmall.setImageResource(smallBadgeFor(snapshot));
            }
            bindDelivery(context, presence, statusDot, statusText, statusSub);
        };

        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (closed[0]) return;
                long start = startMs[0];
                if (start > 0L) {
                    long seconds = Math.max(0L, (System.currentTimeMillis() - start) / 1000L);
                    previewElapsed.setText(context.getString(R.string.cs_discord_preview_elapsed,
                            String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)));
                    previewElapsed.setVisibility(View.VISIBLE);
                } else {
                    previewElapsed.setVisibility(View.GONE);
                }
                handler.postDelayed(this, 1000L);
            }
        };
        final Runnable pollDelivery = new Runnable() {
            @Override
            public void run() {
                if (closed[0]) return;
                bindDelivery(context, presence, statusDot, statusText, statusSub);
                handler.postDelayed(this, 2000L);
            }
        };

        // ---- popup ----------------------------------------------------------------------
        final SettingsPopup popup = new SettingsPopup(context)
                .title(R.string.cs_discord_rpc_title)
                .subtitle(context.getString(R.string.cs_discord_rpc_sub))
                .icon(ContextCompat.getDrawable(context, R.drawable.ic_discord_mark))
                .customView(content)
                .positive(R.string.cs_discord_done, null);

        final Runnable cleanup = () -> {
            closed[0] = true;
            presence.removeListener(listener);
            handler.removeCallbacks(tick);
            handler.removeCallbacks(pollDelivery);
        };
        popup.onClosed(cleanup);
        popup.setOnDismissListener(d -> cleanup.run());

        popup.show();
        presence.addListener(listener);
        presence.refreshPreview();
        handler.post(tick);
        handler.postDelayed(pollDelivery, 2000L);
    }

    private static void bindDelivery(@NonNull Context context, @NonNull DiscordPresenceManager presence,
                                     @NonNull View dot, @NonNull TextView text, @NonNull TextView sub) {
        DiscordPresenceManager.Delivery delivery = presence.getDelivery();
        int dotRes;
        int textRes;
        CharSequence subText;
        switch (delivery) {
            case LIVE:
                dotRes = R.drawable.cs_discord_dot_green;
                textRes = R.string.cs_discord_status_live;
                subText = context.getString(R.string.cs_discord_status_live_desc);
                break;
            case NO_DISCORD_APP:
                dotRes = R.drawable.cs_discord_dot_red;
                textRes = R.string.cs_discord_status_no_app;
                subText = context.getString(R.string.cs_discord_status_no_app_desc);
                break;
            case DISABLED:
                dotRes = R.drawable.cs_discord_dot_gray;
                textRes = R.string.cs_discord_status_off;
                subText = context.getString(R.string.cs_discord_status_off_desc);
                break;
            case ERROR:
                dotRes = R.drawable.cs_discord_dot_red;
                textRes = R.string.cs_discord_status_error;
                String error = presence.getDeliveryError();
                subText = error.isEmpty() ? context.getString(R.string.cs_discord_status_error_desc)
                        : context.getString(R.string.cs_discord_status_error_desc) + "\n" + error;
                break;
            case CONNECTING:
            default:
                dotRes = R.drawable.cs_discord_dot_yellow;
                textRes = R.string.cs_discord_status_connecting;
                subText = context.getString(R.string.cs_discord_status_connecting_desc);
                break;
        }
        dot.setBackgroundResource(dotRes);
        text.setText(textRes);
        sub.setText(subText);
    }

    @NonNull
    private static String sceneLabel(@NonNull Context context, @NonNull DiscordPresenceManager.Scene scene) {
        switch (scene) {
            case LAUNCHING: return context.getString(R.string.cs_discord_scene_launching);
            case LOADING: return context.getString(R.string.cs_discord_scene_loading);
            case MENU: return context.getString(R.string.cs_discord_scene_menu);
            case SINGLEPLAYER: return context.getString(R.string.cs_discord_scene_singleplayer);
            case LAN_HOST: return context.getString(R.string.cs_discord_scene_lan);
            case MULTIPLAYER: return context.getString(R.string.cs_discord_scene_multiplayer);
            case HOME:
            default: return context.getString(R.string.cs_discord_scene_home);
        }
    }

    private static int smallBadgeFor(@NonNull DiscordPresenceManager.Snapshot snapshot) {
        if (!snapshot.isInGame() || snapshot.scene == DiscordPresenceManager.Scene.LOADING
                || snapshot.scene == DiscordPresenceManager.Scene.MENU) {
            return R.drawable.ic_cs_full;
        }
        switch (snapshot.loader) {
            case "fabric": return R.drawable.ic_fabric;
            case "forge": return R.drawable.ic_forge;
            case "neoforge": return R.drawable.ic_neoforge;
            case "quilt": return R.drawable.ic_quilt;
            case "optifine": return R.drawable.ic_loader_optifine;
            default: return R.drawable.ic_pe_px_grass;
        }
    }

    private static void openUrl(@NonNull Context context, @NonNull String url) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Toast.makeText(context, url, Toast.LENGTH_SHORT).show();
        }
    }
}
