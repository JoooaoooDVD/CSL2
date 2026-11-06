package com.craftstudio.cslauncher.utils;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;

/**
 * The CS Launcher Plus startup chime (res/raw/cs_startup.wav, 1.9 s). Played once per process
 * start when the user's switch is on. MediaPlayer is the primary path — it decodes on every
 * Android version and OEM without SoundPool's sample-size and codec limits; SoundPool is the
 * fallback if MediaPlayer cannot be created. Only the user's switch and a zero media volume
 * keep it quiet (vibrate / silent ringer modes do not affect media playback on Android).
 */
public final class StartupSound {
    private static final String TAG = "StartupSound";
    public static final String PREF_KEY = "startup_sound";
    private static volatile boolean sPlayed;

    private StartupSound() {}

    public static boolean isEnabled(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_KEY, true);
    }

    /** Plays the chime once (no-op if already played or disabled). */
    public static void playOnce(@NonNull Context context) {
        if (sPlayed) return;
        sPlayed = true;
        final Context app = context.getApplicationContext();
        if (!isEnabled(app)) return;
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) return;
        } catch (Throwable ignored) {}
        if (!playWithMediaPlayer(app)) playWithSoundPool(app);
    }

    private static boolean playWithMediaPlayer(Context app) {
        MediaPlayer mp = null;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            mp = MediaPlayer.create(app, R.raw.cs_startup, attrs, 0);
            if (mp == null) return false;
            final MediaPlayer player = mp;
            player.setVolume(0.85f, 0.85f);
            player.setOnCompletionListener(MediaPlayer::release);
            player.setOnErrorListener((p, what, extra) -> { p.release(); return true; });
            player.start();
            // Safety net: never keep the player (and the audio session) around longer than needed
            Tools.MAIN_HANDLER.postDelayed(() -> { try { player.release(); } catch (Throwable ignored) {} }, 5000L);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "MediaPlayer path failed: " + t);
            if (mp != null) try { mp.release(); } catch (Throwable ignored) {}
            return false;
        }
    }

    private static void playWithSoundPool(Context app) {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            final SoundPool pool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build();
            pool.setOnLoadCompleteListener((p, sampleId, status) -> {
                if (status == 0) {
                    p.play(sampleId, 0.85f, 0.85f, 1, 0, 1f);
                    Tools.MAIN_HANDLER.postDelayed(p::release, 3500L);
                } else {
                    p.release();
                }
            });
            if (pool.load(app, R.raw.cs_startup, 1) == 0) pool.release();
        } catch (Throwable t) {
            Log.w(TAG, "startup sound unavailable: " + t);
        }
    }
}
