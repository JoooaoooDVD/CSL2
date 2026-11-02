package com.craftstudio.cslauncher.multiplayer.friends;

import android.app.Activity;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.engine.horizon.HorizonProfile;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;

import java.util.List;

/**
 * Join a friend's public address: the selected CS Engine profile when it is one, else the first
 * ready CS Engine profile, started with {@code --server address} (any public e4mc address works
 * from the CS Engine; the Java engine's Direct Connect takes the same address by hand).
 */
public final class FriendsJoin {
    private FriendsJoin() {}

    @Nullable
    public static Instance pickProfile() {
        Instance selected = Instances.loadSelectedInstance();
        if (selected != null && selected.isHorizon()) return selected;
        try {
            List<Instance> all = Instances.loadAllInstances();
            Instance fallback = null;
            for (Instance i : all) {
                if (!i.isHorizon()) continue;
                if (fallback == null) fallback = i;
            }
            return fallback;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void join(@NonNull Activity activity, @Nullable String address) {
        if (address == null || address.trim().isEmpty()) return;
        Instance profile = pickProfile();
        if (profile == null) {
            Toast.makeText(activity, R.string.friends_join_no_profile, Toast.LENGTH_LONG).show();
            return;
        }
        if (!HorizonProfile.isReady(activity, profile)) {
            Toast.makeText(activity, R.string.friends_join_not_ready, Toast.LENGTH_LONG).show();
            return;
        }
        if (activity instanceof LauncherActivity) {
            Toast.makeText(activity, activity.getString(R.string.friends_joining_fmt, address.trim()), Toast.LENGTH_SHORT).show();
            ((LauncherActivity) activity).launchHorizonServer(profile, address.trim());
        }
    }
}
