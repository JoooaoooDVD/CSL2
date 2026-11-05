package com.craftstudio.cslauncher.engine.horizon;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.util.Log;

/**
 * CS Engine voice chat: the microphone, recorded by the launcher and streamed to the engine
 * ({@link HorizonNative#nativeHostMic}). 16 kHz mono PCM16 with the VOICE_COMMUNICATION source,
 * so the platform's echo cancellation and noise suppression apply (speaker-phone play without
 * feedback). The engine encodes and sends; it asks for start/stop through its host poll.
 */
public final class HorizonMic {
    private static final String TAG = "HorizonMic";
    private static final int RATE = 16000;
    private static Thread sThread;
    private static volatile boolean sRun;

    private HorizonMic() {}

    public static boolean hasPermission(Context context) {
        return Build.VERSION.SDK_INT < 23
                || context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    public static synchronized void start() {
        if (sThread != null && sThread.isAlive()) return;
        sRun = true;
        sThread = new Thread(HorizonMic::run, "cs-voice-mic");
        sThread.setDaemon(true);
        sThread.start();
    }

    public static synchronized void stop() {
        sRun = false;
        sThread = null;
    }

    private static void run() {
        AudioRecord rec = null;
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        AutomaticGainControl agc = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) min = 3200;
            int buffer = Math.max(min, RATE / 5 * 2); // >= 200 ms
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer);
            }
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord not initialised");
                HorizonNative.nativeHostMicState(2);
                return;
            }
            try {
                if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(rec.getAudioSessionId()); if (aec != null) aec.setEnabled(true); }
                if (NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(rec.getAudioSessionId()); if (ns != null) ns.setEnabled(true); }
                // Quiet mics / far-from-mouth phones: level the input before it reaches the codec.
                if (AutomaticGainControl.isAvailable()) { agc = AutomaticGainControl.create(rec.getAudioSessionId()); if (agc != null) agc.setEnabled(true); }
            } catch (Throwable ignored) {}
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "AudioRecord did not start");
                HorizonNative.nativeHostMicState(2);
                return;
            }
            HorizonNative.nativeHostMicState(1);
            short[] chunk = new short[320]; // 20 ms
            while (sRun) {
                int n = rec.read(chunk, 0, chunk.length);
                if (n > 0) {
                    HorizonNative.nativeHostMic(chunk, n);
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord read " + n);
                    HorizonNative.nativeHostMicState(2);
                    break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "microphone failed", t);
            try { HorizonNative.nativeHostMicState(2); } catch (Throwable ignored) {}
        } finally {
            try { if (aec != null) aec.release(); } catch (Throwable ignored) {}
            try { if (ns != null) ns.release(); } catch (Throwable ignored) {}
            try { if (agc != null) agc.release(); } catch (Throwable ignored) {}
            try { if (rec != null) { rec.stop(); rec.release(); } } catch (Throwable ignored) {}
            if (!sRun) { try { HorizonNative.nativeHostMicState(0); } catch (Throwable ignored) {} }
        }
    }
}
