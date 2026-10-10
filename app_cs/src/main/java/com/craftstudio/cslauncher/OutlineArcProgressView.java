package com.craftstudio.cslauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.utils.AnimationManager;
import com.craftstudio.cslauncher.utils.ThemeColors;

/**
 * Download progress drawn directly on the outline of the home PLAY button:
 * a faint full track, a solid arc for the known percent, and a bright segment
 * that travels around the rounded-rectangle outline while a download runs
 * (replacing the old floating progress bar on the home screen).
 * <p>
 * Motion rules follow CSAnimationEngine's philosophy: the traveling segment is
 * disabled together with the content-animation preference - the track and the
 * percent arc still render, so the information never depends on the animation.
 * The animator only runs while the view is attached, visible and downloading.
 */
public class OutlineArcProgressView extends View {

    private static final long TRAVEL_DURATION_MS = 1300L;

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mProgressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSegmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mOutlinePath = new Path();
    private final PathMeasure mMeasure = new PathMeasure();

    private float mStrokeWidthPx;
    private float mCornerRadiusPx;

    private boolean mDownloading;
    private int mProgress = -1;
    private float mPhase;
    private float mMotionFraction = 1f;
    private boolean mMotionAllowed = true;

    @Nullable
    private ValueAnimator mAnimator;

    public OutlineArcProgressView(@NonNull Context context) {
        this(context, null);
    }

    public OutlineArcProgressView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OutlineArcProgressView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = context.getResources().getDisplayMetrics().density;
        mStrokeWidthPx = 3.5f * density;
        mCornerRadiusPx = 14f * density;

        int color = ThemeColors.onSurface(context);
        mTrackPaint.setStyle(Paint.Style.STROKE);
        mTrackPaint.setStrokeCap(Paint.Cap.ROUND);
        mTrackPaint.setStrokeWidth(mStrokeWidthPx);
        mTrackPaint.setColor(color);
        mTrackPaint.setAlpha(70);

        mProgressPaint.setStyle(Paint.Style.STROKE);
        mProgressPaint.setStrokeCap(Paint.Cap.ROUND);
        mProgressPaint.setStrokeWidth(mStrokeWidthPx);
        mProgressPaint.setColor(color);
        mProgressPaint.setAlpha(160);

        mSegmentPaint.setStyle(Paint.Style.STROKE);
        mSegmentPaint.setStrokeCap(Paint.Cap.ROUND);
        mSegmentPaint.setStrokeWidth(mStrokeWidthPx + density);
        mSegmentPaint.setColor(color);
        mSegmentPaint.setAlpha(255);
    }

    /** Enters/leaves the downloading state: starts or stops the traveling segment. */
    public void setDownloading(boolean downloading) {
        if (mDownloading == downloading) return;
        mDownloading = downloading;
        if (mDownloading) {
            mMotionAllowed = AnimationManager.isEnabled(getContext(), AnimationManager.TYPE_CONTENT);
            if (mMotionAllowed) startTravel();
        } else {
            stopTravel();
            mPhase = 0f;
        }
        invalidate();
    }

    /** Overall percent (0-100) or -1 while unknown - only affects the solid arc. */
    public void setProgress(int progress) {
        if (mProgress == progress) return;
        mProgress = progress;
        invalidate();
    }

    private void startTravel() {
        if (!isAttachedToWindow() || getVisibility() != VISIBLE) return;
        ValueAnimator animator = travelAnimator();
        if (!animator.isRunning()) animator.start();
    }

    private void stopTravel() {
        if (mAnimator != null) mAnimator.cancel();
    }

    private ValueAnimator travelAnimator() {
        if (mAnimator == null) {
            mAnimator = ValueAnimator.ofFloat(0f, 1f);
            mAnimator.setDuration(TRAVEL_DURATION_MS);
            mAnimator.setInterpolator(new LinearInterpolator());
            mAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mAnimator.addUpdateListener(animation -> {
                mPhase = (float) animation.getAnimatedValue();
                invalidate();
            });
        }
        return mAnimator;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mDownloading && mMotionAllowed) startTravel();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) stopTravel();
        else if (mDownloading && mMotionAllowed) startTravel();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopTravel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (!mDownloading) return;

        float inset = mStrokeWidthPx / 2f + 1f;
        float left = inset;
        float top = inset;
        float right = getWidth() - inset;
        float bottom = getHeight() - inset;
        if (right <= left || bottom <= top) return;

        mOutlinePath.reset();
        mOutlinePath.addRoundRect(new RectF(left, top, right, bottom),
                mCornerRadiusPx, mCornerRadiusPx, Path.Direction.CW);
        mMeasure.setPath(mOutlinePath, false);
        float total = mMeasure.getLength();
        if (total <= 0f) return;

        // Faint track around the whole outline
        canvas.drawPath(mOutlinePath, mTrackPaint);
        mOutlinePath.rewind();

        // Solid arc for the known percent (determinate feedback)
        if (mProgress >= 0) {
            float done = total * Math.min(Math.max(mProgress, 0), 100) / 100f;
            if (done > 1f) {
                mMeasure.getSegment(0f, done, mOutlinePath, true);
                canvas.drawPath(mOutlinePath, mProgressPaint);
                mOutlinePath.rewind();
            }
        }

        // Traveling segment: the "spinning" part
        float segment = total * 0.28f;
        float start = mPhase * total;
        if (start + segment > total) {
            mMeasure.getSegment(start, total, mOutlinePath, true);
            mMeasure.getSegment(0f, (start + segment) - total, mOutlinePath, true);
        } else {
            mMeasure.getSegment(start, start + segment, mOutlinePath, true);
        }
        canvas.drawPath(mOutlinePath, mSegmentPaint);
    }
}
