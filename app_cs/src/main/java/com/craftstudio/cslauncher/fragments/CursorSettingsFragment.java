package com.craftstudio.cslauncher.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.cursor.CursorStore;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.io.IOException;

/**
 * Settings → Cursor. The player picks any still image for the in-game pointer, sets where it
 * clicks (tap the preview or a preset), and the size (the same "mousescale" the Controls page
 * and the CS Engine mouse sheet use). The game processes read the result through
 * {@link CursorStore} when their cursor view is created.
 */
public class CursorSettingsFragment extends Fragment {
    public static final String TAG = "CursorSettingsFragment";
    private static final int SIZE_MIN = 25;

    private ImageView mPreview;
    private View mMarker, mStage;
    private TextView mBadge, mFileLine, mSizeValue, mHotValue, mChipTl, mChipTop, mChipCenter;
    @Nullable private Bitmap mCustom;
    private float mHotX, mHotY;

    private final ActivityResultLauncher<String[]> mPick = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onPicked);

    public CursorSettingsFragment() { super(R.layout.fragment_cursor_settings); }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Context c = requireContext();
        mPreview = view.findViewById(R.id.cur_preview);
        mMarker = view.findViewById(R.id.cur_hot_marker);
        mStage = view.findViewById(R.id.cur_stage);
        mBadge = view.findViewById(R.id.cur_badge);
        mFileLine = view.findViewById(R.id.cur_file_line);
        mSizeValue = view.findViewById(R.id.cur_size_value);
        mHotValue = view.findViewById(R.id.cur_hot_value);
        mChipTl = view.findViewById(R.id.cur_hot_tl);
        mChipTop = view.findViewById(R.id.cur_hot_top);
        mChipCenter = view.findViewById(R.id.cur_hot_center);

        mCustom = CursorStore.load(c);
        mHotX = CursorStore.hotX(c);
        mHotY = CursorStore.hotY(c);

        View pick = view.findViewById(R.id.cur_pick);
        View reset = view.findViewById(R.id.cur_reset);
        CSAnimationEngine.attachPressFeedback(pick);
        CSAnimationEngine.attachPressFeedback(reset);
        pick.setOnClickListener(v -> mPick.launch(new String[]{"image/*"}));
        reset.setOnClickListener(v -> {
            CursorStore.reset(c);
            mCustom = null;
            mHotX = mHotY = 0f;
            Toast.makeText(c, R.string.cursor_reset_done, Toast.LENGTH_SHORT).show();
            bindPreview(true);
        });

        // Tap the stage: the click point becomes the pixel under the finger (within the image)
        mStage.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) v.setPressed(true);
            if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                v.setPressed(false);
                v.performClick();
                setHotFromStage(ev.getX(), ev.getY());
            }
            if (ev.getActionMasked() == MotionEvent.ACTION_CANCEL) v.setPressed(false);
            return true;
        });
        mChipTl.setOnClickListener(v -> setHot(0f, 0f));
        mChipTop.setOnClickListener(v -> setHot(0.5f, 0f));
        mChipCenter.setOnClickListener(v -> setHot(0.5f, 0.5f));

        SeekBar size = view.findViewById(R.id.cur_size);
        int current = LauncherPreferences.DEFAULT_PREF.getInt("mousescale", 100);
        size.setProgress(Math.max(0, Math.min(size.getMax(), current - SIZE_MIN)));
        mSizeValue.setText(getString(R.string.cursor_size_value, current));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int v = progress + SIZE_MIN;
                mSizeValue.setText(getString(R.string.cursor_size_value, v));
                scalePreview(v);
                if (fromUser) {
                    LauncherPreferences.DEFAULT_PREF.edit().putInt("mousescale", v).apply();
                    LauncherPreferences.PREF_MOUSESCALE = v / 100f;
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });

        bindPreview(false);
        scalePreview(current);

        if (savedInstanceState == null && Motion.content(view)) {
            Motion.riseIn(view.findViewById(R.id.cur_left), 0, 18);
            Motion.riseIn(view.findViewById(R.id.cur_card_size), 60, 18);
            Motion.riseIn(view.findViewById(R.id.cur_card_hot), 110, 18);
            Motion.riseIn(view.findViewById(R.id.cur_card_formats), 160, 18);
        }
    }

    private void onPicked(@Nullable Uri uri) {
        if (uri == null) return;
        Context c = getContext();
        if (c == null) return;
        try {
            mCustom = CursorStore.importFrom(c, uri);
            // A fresh image starts with the usual arrow hotspot (top-left)
            mHotX = mHotY = 0f;
            CursorStore.setHotspot(c, 0f, 0f);
            bindPreview(true);
        } catch (IOException | RuntimeException e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Toast.makeText(c, getString(R.string.cursor_import_failed, why), Toast.LENGTH_LONG).show();
        }
    }

    private void setHotFromStage(float x, float y) {
        if (mPreview.getWidth() == 0) return;
        // The image is fitCenter inside the preview view: map the touch into the drawn bitmap
        float dw = mPreview.getWidth(), dh = mPreview.getHeight();
        float bw = dw, bh = dh;
        if (mPreview.getDrawable() != null) {
            float iw = mPreview.getDrawable().getIntrinsicWidth(), ih = mPreview.getDrawable().getIntrinsicHeight();
            if (iw > 0 && ih > 0) {
                float f = Math.min(dw / iw, dh / ih);
                bw = iw * f; bh = ih * f;
            }
        }
        float left = mPreview.getLeft() + (dw - bw) / 2f, top = mPreview.getTop() + (dh - bh) / 2f;
        float fx = (x - left) / bw, fy = (y - top) / bh;
        if (fx < -0.15f || fx > 1.15f || fy < -0.15f || fy > 1.15f) return; // tapped the empty stage
        setHot(Math.max(0f, Math.min(1f, fx)), Math.max(0f, Math.min(1f, fy)));
    }

    private void setHot(float x, float y) {
        Context c = getContext();
        if (c == null) return;
        mHotX = x; mHotY = y;
        CursorStore.setHotspot(c, x, y);
        placeMarker(true);
        bindHotTexts();
    }

    private void bindPreview(boolean animate) {
        Context c = requireContext();
        if (mCustom != null) {
            mPreview.setImageDrawable(new BitmapDrawable(getResources(), mCustom));
            mBadge.setText(R.string.cursor_badge_custom);
            mFileLine.setText(getString(R.string.cursor_file_line, mCustom.getWidth(), mCustom.getHeight()));
        } else {
            mPreview.setImageResource(R.drawable.ic_mouse_pointer);
            mBadge.setText(R.string.cursor_badge_default);
            mFileLine.setText(R.string.cursor_file_none);
        }
        if (animate && Motion.content(mPreview)) Motion.popIn(mPreview, 0, 0.7f);
        mPreview.post(() -> placeMarker(false));
        bindHotTexts();
    }

    private void bindHotTexts() {
        mHotValue.setText(getString(R.string.cursor_hot_value, Math.round(mHotX * 100), Math.round(mHotY * 100)));
        mChipTl.setSelected(mHotX == 0f && mHotY == 0f);
        mChipTop.setSelected(mHotX == 0.5f && mHotY == 0f);
        mChipCenter.setSelected(mHotX == 0.5f && mHotY == 0.5f);
    }

    /** Puts the ring on the click point of the drawn image. */
    private void placeMarker(boolean animate) {
        if (mPreview.getWidth() == 0) return;
        float dw = mPreview.getWidth(), dh = mPreview.getHeight();
        float bw = dw, bh = dh;
        if (mPreview.getDrawable() != null) {
            float iw = mPreview.getDrawable().getIntrinsicWidth(), ih = mPreview.getDrawable().getIntrinsicHeight();
            if (iw > 0 && ih > 0) {
                float f = Math.min(dw / iw, dh / ih);
                bw = iw * f; bh = ih * f;
            }
        }
        float s = mPreview.getScaleX();
        float cx = mPreview.getLeft() + dw / 2f, cy = mPreview.getTop() + dh / 2f;
        float px = cx + ((dw - bw) / 2f + mHotX * bw - dw / 2f) * s;
        float py = cy + ((dh - bh) / 2f + mHotY * bh - dh / 2f) * s;
        float tx = px - mMarker.getWidth() / 2f, ty = py - mMarker.getHeight() / 2f;
        mMarker.animate().cancel();
        if (animate && Motion.content(mMarker)) {
            mMarker.animate().x(tx).y(ty).setDuration(Motion.SHORT).setInterpolator(Motion.STANDARD).start();
        } else {
            mMarker.setX(tx);
            mMarker.setY(ty);
        }
    }

    private void scalePreview(int percent) {
        float s = percent / 100f;
        mPreview.setPivotX(mPreview.getWidth() / 2f);
        mPreview.setPivotY(mPreview.getHeight() / 2f);
        mPreview.setScaleX(s);
        mPreview.setScaleY(s);
        placeMarker(false);
    }
}
