package com.craftstudio.cslauncher.input;

import static com.craftstudio.cslauncher.game.platform.Platform.PLATFORM;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.CallbackBridge;
import com.craftstudio.cslauncher.CustomControlsActivity;
import com.craftstudio.cslauncher.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the script of a command button inside the game: opens the chat, types the command and
 * sends it, one line after another. Everything goes through the same input path a keyboard
 * uses, so it works with any Minecraft version, mods and servers (what the player may run is
 * decided by the server, exactly as if they typed it).
 *
 * Timing matters: Minecraft opens the chat screen on its next tick after the chat key, so
 * the text is typed only after a short pause, and Enter follows once the text is in the box.
 */
public final class CommandRunner {
    private static final String TAG = "CommandRunner";
    /** Chat key -> screen ready */
    private static final long OPEN_DELAY_MS = 140;
    /** Text typed -> Enter */
    private static final long SEND_DELAY_MS = 90;
    /** Between two commands (lets the server process the previous one, avoids chat spam kicks) */
    private static final long NEXT_DELAY_MS = 260;
    private static final int MAX_LINES = 16;
    private static final int MAX_LINE_LENGTH = 256;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static boolean sBusy;

    private CommandRunner() {}

    /** The lines that will actually be sent (trimmed, comments and blanks removed, slash applied) */
    @NonNull
    public static List<String> prepare(@Nullable String script, boolean autoSlash) {
        List<String> out = new ArrayList<>();
        if (script == null) return out;
        for (String raw : script.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (autoSlash && !line.startsWith("/")) line = "/" + line;
            if (line.length() > MAX_LINE_LENGTH) line = line.substring(0, MAX_LINE_LENGTH);
            out.add(line);
            if (out.size() >= MAX_LINES) break;
        }
        return out;
    }

    public static boolean isBusy() {
        return sBusy;
    }

    /** Tap handler of a command button */
    public static void run(@NonNull Context context, @NonNull ControlData data) {
        if (context instanceof CustomControlsActivity) {
            // Editor outside the game: nothing to send to, just confirm the script
            List<String> lines = prepare(data.command, data.commandAutoSlash);
            Toast.makeText(context, lines.isEmpty() ? context.getString(R.string.customctrl_command_empty)
                    : context.getString(R.string.customctrl_command_preview, lines.size(), lines.get(0)), Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> lines = prepare(data.command, data.commandAutoSlash);
        if (lines.isEmpty()) {
            Toast.makeText(context, R.string.customctrl_command_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (sBusy) return; // a previous script is still being typed
        sBusy = true;
        sendSequence(lines, 0);
    }

    private static void sendSequence(final List<String> lines, final int index) {
        if (index >= lines.size()) {
            sBusy = false;
            return;
        }
        final String line = lines.get(index);
        try {
            // Open the chat (key.chat, T by default). If a screen is already open this does nothing
            // and the text lands in that screen's text box at worst, which Minecraft ignores.
            CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_T);
        } catch (Throwable t) {
            Log.w(TAG, "chat key: " + t);
            sBusy = false;
            return;
        }
        HANDLER.postDelayed(() -> {
            try {
                PLATFORM.sendBulkUnicodeEvent(line, 0);
            } catch (Throwable t) {
                Log.w(TAG, "type: " + t);
            }
            HANDLER.postDelayed(() -> {
                try {
                    CallbackBridge.sendKeyPress(KeyEvent.KEYCODE_ENTER);
                } catch (Throwable t) {
                    Log.w(TAG, "enter: " + t);
                }
                HANDLER.postDelayed(() -> sendSequence(lines, index + 1), NEXT_DELAY_MS);
            }, SEND_DELAY_MS);
        }, OPEN_DELAY_MS);
    }
}
