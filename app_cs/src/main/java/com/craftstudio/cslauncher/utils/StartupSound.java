package com.craftstudio.cslauncher.utils;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;

/**
 * The Craft Studio startup chime (res/raw/cs_startup): a soft power-on swell, a rising boot
 * tone and a two-note bell, 1.9 s. Plays once per process start together with the brand
 * intro; respects the user's switch and the device's silent / vibrate mode. SoundPool is
 * loaded on demand and released right after playback, so nothing stays resident.
 */
public final class StartupSound {
    public static final String PREF_KEY = "startup_sound";
    private static final String TAG = "StartupSound";
    private static boolean sPlayed;

    private StartupSound() {}

    public static boolean isEnabled(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_KEY, true);
    }

    /** Plays the chime once (no-op if already played, disabled, or the phone is muted). */
    public static void playOnce(@NonNull Context context) {
        if (sPlayed) return;
        sPlayed = true;
        final Context app = context.getApplicationContext();
        if (!isEnabled(app)) return;
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return;
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) return;
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
