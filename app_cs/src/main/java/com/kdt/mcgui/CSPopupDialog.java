package com.kdt.mcgui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;

/**
 * Base of the launcher's full-window popups (scrim + card drawn by the layout).
 * Keeps the launcher immersive state when shown and gives every popup the same animated
 * enter / exit: subclasses call {@link #setupPopup} from onCreate and {@link #close} to leave.
 */
public abstract class CSPopupDialog extends Dialog {

    protected View mScrim, mCard;
    private boolean mClosing;

    protected CSPopupDialog(@NonNull Context context) {
        super(context, R.style.CS_ProfilePickerDialog);
    }

    /** Full window, transparent background, scrim closes the popup when {@code scrimCloses} */
    protected void setupPopup(@NonNull View scrim, @NonNull View card, boolean scrimCloses) {
        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        mScrim = scrim;
        mCard = card;
        mCard.setClipToOutline(true);
        if (scrimCloses) mScrim.setOnClickListener(v -> onDismissRequested());
        else mScrim.setOnClickListener(v -> CreatePopupMotion.nudge(mCard));
    }

    protected boolean isClosing() {
        return mClosing;
    }

    /** Back / scrim: subclasses may refuse (e.g. while working) */
    protected void onDismissRequested() {
        close(null);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        onDismissRequested();
    }

    /** Public animated close for owners of the popup (see UpdateManager) */
    public void closeThen(@Nullable Runnable then) {
        close(then);
    }

    /** Animated close, {@code then} runs once the popup is gone */
    protected void close(@Nullable Runnable then) {
        if (mClosing) return;
        mClosing = true;
        Runnable end = () -> {
            try {
                if (isShowing()) dismiss();
            } catch (IllegalArgumentException ignored) {
                // Window already detached (activity finishing)
            }
            if (then != null) then.run();
        };
        if (mScrim == null || mCard == null) end.run();
        else CreatePopupMotion.exit(mScrim, mCard, end);
    }

    @Override
    public void show() {
        // Keep the launcher's immersive / edge-to-edge state: show without focus first so the
        // system bars do not pop in, copy the activity state, then take focus back.
        Window window = getWindow();
        Activity activity = findActivity(getContext());
        if (window == null || activity == null) {
            super.show();
            return;
        }
        window.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.layoutInDisplayCutoutMode = activity.getWindow().getAttributes().layoutInDisplayCutoutMode;
            window.setAttributes(attrs);
        }
        super.show();
        applySystemBars(window, activity);
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    @SuppressWarnings("deprecation")
    static void applySystemBars(@NonNull Window window, @NonNull Activity activity) {
        View activityDecor = activity.getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsets insets = activityDecor.getRootWindowInsets();
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null && insets != null && !insets.isVisible(WindowInsets.Type.statusBars())) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            window.getDecorView().setSystemUiVisibility(activityDecor.getSystemUiVisibility());
        }
    }

    @Nullable
    static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    protected float dp() {
        return getContext().getResources().getDisplayMetrics().density;
    }
}
