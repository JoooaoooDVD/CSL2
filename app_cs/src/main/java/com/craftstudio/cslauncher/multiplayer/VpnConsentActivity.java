package com.craftstudio.cslauncher.multiplayer;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;

/**
 * Invisible activity that shows Android's official VPN consent dialog and then starts (or
 * refuses to start) the requested session. It is process-agnostic on purpose: the result is
 * delivered to {@link VirtualLanService} as an Intent, so the launcher UI (":launcher") and the
 * in-game popup (":game") both observe it through the normal state broadcast.
 * <p>
 * Robustness notes (real devices): some ROMs show the consent as a full-screen activity, some
 * never deliver {@code onActivityResult} (they recreate the caller instead), some return
 * {@code RESULT_CANCELED} even though consent was granted. The single source of truth is therefore
 * {@link VpnService#prepare}: whenever we come back to the foreground after asking, {@code null}
 * means granted. The result is delivered exactly once.
 */
public final class VpnConsentActivity extends Activity {
    private static final int REQ_VPN = 0x56504e;
    public static final String EXTRA_ROLE = "role";      // "host" | "guest"
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_PLAYER = "player";
    public static final String EXTRA_RELAYS = "relays";

    private boolean mAsked;
    private boolean mLeftForDialog;
    private boolean mDelivered;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mAsked = savedInstanceState.getBoolean("asked");
            mLeftForDialog = savedInstanceState.getBoolean("left");
            mDelivered = savedInstanceState.getBoolean("delivered");
        }
        if (mDelivered) { finish(); return; }
        if (mAsked) return; // waiting for the system dialog result across a recreation
        Intent consent;
        try {
            consent = VpnService.prepare(this);
        } catch (Throwable t) {
            deliver(false, "VPN not available on this device: " + t.getMessage());
            return;
        }
        if (consent == null) {
            deliver(true, "");
            return;
        }
        mAsked = true;
        try {
            startActivityForResult(consent, REQ_VPN);
        } catch (Throwable t) {
            // Some ROMs have no consent UI (or it is disabled by policy)
            deliver(false, "VPN consent dialog unavailable: " + t.getMessage());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mAsked && !mDelivered) mLeftForDialog = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mAsked || mDelivered || !mLeftForDialog) return;
        // Back from the system dialog without a usable onActivityResult: ask the OS directly
        boolean granted;
        try {
            granted = VpnService.prepare(this) == null;
        } catch (Throwable t) {
            granted = false;
        }
        deliver(granted, granted ? "" : "VPN permission denied");
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("asked", mAsked);
        outState.putBoolean("left", mLeftForDialog);
        outState.putBoolean("delivered", mDelivered);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VPN) return;
        boolean granted = resultCode == RESULT_OK;
        if (!granted) {
            // Trust the system state over the result code (a few ROMs return CANCELED after granting)
            try { granted = VpnService.prepare(this) == null; } catch (Throwable ignored) {}
        }
        deliver(granted, granted ? "" : "VPN permission denied");
    }

    private void deliver(boolean granted, String why) {
        if (mDelivered) return;
        mDelivered = true;
        Intent src = getIntent();
        Intent svc = new Intent(this, VirtualLanService.class);
        if (granted) {
            svc.setAction(VirtualLanService.ACTION_START);
            svc.putExtra(EXTRA_ROLE, src.getStringExtra(EXTRA_ROLE));
            svc.putExtra(EXTRA_CODE, src.getStringExtra(EXTRA_CODE));
            svc.putExtra(EXTRA_PLAYER, src.getStringExtra(EXTRA_PLAYER));
            ArrayList<String> relays = src.getStringArrayListExtra(EXTRA_RELAYS);
            svc.putStringArrayListExtra(EXTRA_RELAYS, relays != null ? relays : new ArrayList<String>());
        } else {
            svc.setAction(VirtualLanService.ACTION_VPN_DENIED);
            svc.putExtra(EXTRA_ROLE, src.getStringExtra(EXTRA_ROLE));
            svc.putExtra(EXTRA_CODE, src.getStringExtra(EXTRA_CODE));
            svc.putExtra("why", why);
        }
        Log.i(AndroidTunnelEnvironment.TAG, "VPN consent " + (granted ? "granted" : "refused: " + why));
        try {
            ContextCompat.startForegroundService(this, svc);
        } catch (Throwable t) {
            Log.w(AndroidTunnelEnvironment.TAG, "consent result delivery failed: " + t);
            try { startService(svc); } catch (Throwable ignored) {}
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
