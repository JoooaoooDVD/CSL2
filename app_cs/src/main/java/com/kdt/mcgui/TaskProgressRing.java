package com.kdt.mcgui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.utils.AnimationManager;

/**
 * Circular determinate progress used by the download console task cards.
 * Draws a faint full ring plus a bright arc that eases towards the reported
 * percent, so slow download ticks read as motion instead of jumps.
 */
public class TaskProgressRing extends View {

    private static final long SWEEP_DURATION_MS = 420L;

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mProgressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float mStrokeWidthPx;
    private int mRingColor = Color.WHITE;
    private boolean mIndeterminate;

    private int mTarget = 0;
    private float mDisplayed;

    @Nullable
    private ValueAnimator mAnimator;

    public TaskProgressRing(@NonNull Context context) {
        super(context);
        init();
    }

    public TaskProgressRing(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TaskProgressRing(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        mStrokeWidthPx = 3f * density;

        mTrackPaint.setStyle(Paint.Style.STROKE);
        mTrackPaint.setStrokeCap(Paint.Cap.ROUND);
        mTrackPaint.setStrokeWidth(mStrokeWidthPx);
        mTrackPaint.setColor(mRingColor);
        mTrackPaint.setAlpha(48);

        mProgressPaint.setStyle(Paint.Style.STROKE);
        mProgressPaint.setStrokeCap(Paint.Cap.ROUND);
        mProgressPaint.setStrokeWidth(mStrokeWidthPx);
        mProgressPaint.setColor(mRingColor);
        mProgressPaint.setAlpha(255);
    }

    /** Accent colour of the arc, the track keeps a faded version of it. */
    public void setRingColor(int color) {
        if (mRingColor == color) return;
        mRingColor = color;
        mProgressPaint.setColor(color);
        mTrackPaint.setColor(color);
        mTrackPaint.setAlpha(48);
        invalidate();
    }

    /** Shows the sweeping "unknown progress" state instead of a determinate arc. */
    public void setIndeterminate(boolean indeterminate) {
        if (mIndeterminate == indeterminate) return;
        mIndeterminate = indeterminate;
        if (!indeterminate && mAnimator != null) mAnimator.cancel();
        invalidate();
    }

    public void setProgress(int progress) {
        int clamped = Math.max(0, Math.min(100, progress));
        if (mTarget == clamped && mAnimator == null) return;
        mTarget = clamped;
        if (mAnimator != null) mAnimator.cancel();
        if (!AnimationManager.isEnabled(getContext(), AnimationManager.TYPE_CONTENT)
                || !isAttachedToWindow()) {
            mDisplayed = clamped;
            invalidate();
            return;
        }
        mAnimator = ValueAnimator.ofFloat(mDisplayed, clamped);
        mAnimator.setDuration(SWEEP_DURATION_MS);
        mAnimator.setInterpolator(new DecelerateInterpolator());
        mAnimator.addUpdateListener(animation -> {
            mDisplayed = (float) animation.getAnimatedValue();
            invalidate();
        });
        mAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mAnimator != null) mAnimator.cancel();
        mAnimator = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int padding = (int) (mStrokeWidthPx / 2f + 1f);
        float left = padding;
        float top = padding;
        float right = getWidth() - padding;
        float bottom = getHeight() - padding;
        if (right <= left || bottom <= top) return;

        canvas.drawArc(left, top, right, bottom, 0, 360, false, mTrackPaint);

        if (mIndeterminate) {
            float sweep = 60f;
            float phase = (System.currentTimeMillis() % 1400L) / 1400f * 360f;
            canvas.drawArc(left, top, right, bottom, phase, sweep, false, mProgressPaint);
            if (isAttachedToWindow()) postInvalidateOnAnimation();
            return;
        }

        float fraction = Math.max(0, Math.min(100, mDisplayed)) / 100f;
        if (fraction > 0f) {
            canvas.drawArc(left, top, right, bottom, -90, 360f * fraction, false, mProgressPaint);
        }
    }
}
