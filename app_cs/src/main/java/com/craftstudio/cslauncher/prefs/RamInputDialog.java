package com.craftstudio.cslauncher.prefs;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;

/**
 * Popup that lets the user type the amount of RAM to allocate, instead of dragging the slider.
 * Shows the amount of RAM that is currently free on the device, refreshed while it is open.
 * Uses the launcher's XML settings popup ({@link SettingsPopup}).
 */
public final class RamInputDialog {
    private static final long FREE_RAM_REFRESH_MS = 1000L;

    /** Receives the validated value, already within [min, max] */
    public interface OnRamSelectedListener {
        void onRamSelected(int megabytes);
    }

    private RamInputDialog() {}

    public static void show(@NonNull Context context, int current, int min, int max,
                            @NonNull OnRamSelectedListener listener) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final SettingsPopup popup = new SettingsPopup(context);
        final Runnable[] refresher = new Runnable[1];

        popup.title(R.string.mcl_memory_allocation)
                .icon(ContextCompat.getDrawable(context, R.drawable.ic_px_ram))
                .subtitle(context.getString(R.string.mcl_memory_allocation_dialog_free, Tools.getFreeDeviceMemory(context)))
                .input(String.valueOf(current), context.getString(R.string.mcl_memory_allocation_dialog_hint), text -> {
                    int value = parse(text);
                    if (value < min || value > max) {
                        popup.setError(context.getString(R.string.mcl_memory_allocation_dialog_error, min, max));
                        return false;
                    }
                    listener.onRamSelected(value);
                    return true;
                })
                .inputType(InputType.TYPE_CLASS_NUMBER)
                .inputHelper(context.getString(R.string.mcl_memory_allocation_dialog_helper, min, max))
                .onClosed(() -> handler.removeCallbacks(refresher[0]));

        // Keep the "free RAM" line up to date while the popup is visible
        refresher[0] = new Runnable() {
            @Override
            public void run() {
                if (!popup.isShowing()) return;
                TextView subtitle = popup.findViewById(R.id.sp_subtitle);
                if (subtitle != null) {
                    subtitle.setText(context.getString(R.string.mcl_memory_allocation_dialog_free,
                            Tools.getFreeDeviceMemory(context)));
                }
                handler.postDelayed(this, FREE_RAM_REFRESH_MS);
            }
        };
        popup.setOnDismissListener(d -> handler.removeCallbacks(refresher[0]));
        popup.show();
        handler.postDelayed(refresher[0], FREE_RAM_REFRESH_MS);
    }

    /** @return the parsed value, or -1 if the text is empty or not a valid number */
    private static int parse(CharSequence text) {
        if (text == null || text.length() == 0) return -1;
        try {
            return Integer.parseInt(text.toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
