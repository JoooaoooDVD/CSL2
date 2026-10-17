package com.craftstudio.cslauncher.multiplayer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.multiplayer.core.VirtualLanState;

import java.util.ArrayList;
import java.util.List;

/**
 * UI-side handle on {@link VirtualLanService}. Binds across processes, keeps the latest state and
 * delivers updates on the main thread. Both the launcher screen and the in-game popup use it.
 */
public final class VirtualLanClient {
    public interface Listener {
        @MainThread void onState(@NonNull VirtualLanState state);
        @MainThread default void onEngineLog(@NonNull List<String> lines) {}
        @MainThread default void onServiceConnected() {}
    }

    private final Context mContext;
    private final Listener mListener;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Messenger mIncoming = new Messenger(new Handler(Looper.getMainLooper(), this::handle));
    @Nullable private Messenger mService;
    private boolean mBound;
    @NonNull private VirtualLanState mState = VirtualLanState.IDLE;
    private final List<Message> mQueued = new ArrayList<>();

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = new Messenger(binder);
            sendNow(VirtualLanService.MSG_REGISTER, null);
            for (Message m : mQueued) deliver(m);
            mQueued.clear();
            mListener.onServiceConnected();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
            // The game process went away (game closed / crashed): whatever session it had is gone.
            if (mState.isActive()) {
                mState = mState.buildUpon().phase(VirtualLanState.Phase.ENDED)
                        .reason(VirtualLanState.Reason.GAME_CLOSED, "").build();
                mListener.onState(mState);
            }
        }
    };

    public VirtualLanClient(@NonNull Context context, @NonNull Listener listener) {
        mContext = context.getApplicationContext();
        mListener = listener;
    }

    @NonNull public VirtualLanState state() { return mState; }
    public boolean isBound() { return mService != null; }

    @MainThread
    public void bind() {
        if (mBound) return;
        mBound = true;
        Intent intent = new Intent(mContext, VirtualLanService.class);
        try {
            mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            mBound = false;
        }
    }

    @MainThread
    public void unbind() {
        if (!mBound) return;
        mBound = false;
        if (mService != null) sendNow(VirtualLanService.MSG_UNREGISTER, null);
        try { mContext.unbindService(mConnection); } catch (Throwable ignored) {}
        mService = null;
    }

    /** Hosts a new session; the service runs it in the foreground */
    public void createSession(@NonNull String playerName, @NonNull List<String> customRelays) {
        Bundle b = new Bundle();
        b.putString(VirtualLanService.KEY_PLAYER, playerName);
        b.putStringArrayList(VirtualLanService.KEY_RELAYS, new ArrayList<>(customRelays));
        startForegroundService();
        send(VirtualLanService.MSG_CREATE, b);
    }

    public void joinSession(@NonNull String code, @NonNull String playerName, @NonNull List<String> customRelays) {
        Bundle b = new Bundle();
        b.putString(VirtualLanService.KEY_CODE, code);
        b.putString(VirtualLanService.KEY_PLAYER, playerName);
        b.putStringArrayList(VirtualLanService.KEY_RELAYS, new ArrayList<>(customRelays));
        startForegroundService();
        send(VirtualLanService.MSG_JOIN, b);
    }

    public void endSession() { send(VirtualLanService.MSG_END, null); }

    public void kick(@NonNull String memberId) {
        Bundle b = new Bundle();
        b.putString(VirtualLanService.KEY_MEMBER, memberId);
        send(VirtualLanService.MSG_KICK, b);
    }

    public void requestLog() { send(VirtualLanService.MSG_GET_LOG, null); }

    /** The UI has shown the final state; the service goes back to idle */
    public void dismissFinalState() {
        if (!mState.isActive()) mState = VirtualLanState.IDLE;
        send(VirtualLanService.MSG_DISMISS, null);
    }

    private void startForegroundService() {
        Intent intent = new Intent(mContext, VirtualLanService.class);
        try {
            // A started service survives the UI unbinding while a session runs
            ContextCompat.startForegroundService(mContext, intent);
        } catch (Throwable t) {
            try { mContext.startService(intent); } catch (Throwable ignored) {}
        }
        bind();
    }

    private void send(int what, @Nullable Bundle data) {
        Message m = Message.obtain(null, what);
        if (data != null) m.setData(data);
        m.replyTo = mIncoming;
        if (mService == null) {
            mQueued.add(m);
            bind();
            return;
        }
        deliver(m);
    }

    private void sendNow(int what, @Nullable Bundle data) {
        Message m = Message.obtain(null, what);
        if (data != null) m.setData(data);
        m.replyTo = mIncoming;
        deliver(m);
    }

    private void deliver(Message m) {
        Messenger service = mService;
        if (service == null) return;
        try {
            service.send(m);
        } catch (RemoteException e) {
            mService = null;
        }
    }

    private boolean handle(Message msg) {
        if (msg.what == VirtualLanService.MSG_STATE) {
            Bundle data = msg.getData();
            data.setClassLoader(getClass().getClassLoader());
            mState = VirtualLanStateBundler.fromBundle(data);
            mListener.onState(mState);
            return true;
        }
        if (msg.what == VirtualLanService.MSG_LOG) {
            ArrayList<String> lines = msg.getData().getStringArrayList(VirtualLanService.KEY_LOG);
            mListener.onEngineLog(lines != null ? lines : new ArrayList<String>());
            return true;
        }
        return false;
    }
}
