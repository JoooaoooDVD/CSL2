package com.craftstudio.cslauncher.stats;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * Seven animated bars (last 7 days, today on the right). Bars grow in with a staggered spring
 * when data arrives, the selected / today bar is highlighted, values sit above the bars and
 * the weekday initials below. Tapping a bar selects it and notifies the listener.
 */
public class WeekChartView extends View {

    public interface OnDaySelectedListener {
        void onDaySelected(int index);
    }

    private final long[] mValues = new long[7];
    private final float[] mShown = new float[7];
    private final String[] mLabels = {"", "", "", "", "", "", ""};
    private int mSelected = 6;
    private long mMax = 1;
    @Nullable private ValueAnimator mAnimator;
    @Nullable private OnDaySelectedListener mListener;

    private final Paint mBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mValuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final float mDensity;
    private final int mColorBar, mColorBarDim, mColorText, mColorTextDim, mColorTrack, mColorGlow;
    private float mPulse = 0f;
    @Nullable private ValueAnimator mPulseAnimator;
    private boolean mLiveToday;

    public WeekChartView(Context context) {
        this(context, null);
    }

    public WeekChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        mColorBar = ContextCompat.getColor(context, R.color.vl_platinum);
        mColorBarDim = 0xFF4A4F5C;
        mColorText = ContextCompat.getColor(context, R.color.vl_text);
        mColorTextDim = ContextCompat.getColor(context, R.color.vl_text_faint);
        mColorTrack = ContextCompat.getColor(context, R.color.vl_tile);
        mColorGlow = ContextCompat.getColor(context, R.color.vl_ok);
        mTrackPaint.setColor(mColorTrack);
        mTextPaint.setColor(mColorTextDim);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setTextSize(10f * mDensity);
        mTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mValuePaint.setColor(mColorText);
        mValuePaint.setTextAlign(Paint.Align.CENTER);
        mValuePaint.setTextSize(9.5f * mDensity);
        mValuePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mGlowPaint.setColor(mColorGlow);
        setClickable(true);
    }

    public void setOnDaySelectedListener(@Nullable OnDaySelectedListener listener) {
        mListener = listener;
    }

    public int getSelected() {
        return mSelected;
    }

    /** Pulsing glow under today's bar while a game is running */
    public void setLiveToday(boolean live) {
        if (mLiveToday == live) return;
        mLiveToday = live;
        if (mPulseAnimator != null) {
            mPulseAnimator.cancel();
            mPulseAnimator = null;
        }
        if (live && Motion.content(this)) {
            mPulseAnimator = ValueAnimator.ofFloat(0f, 1f, 0f);
            mPulseAnimator.setDuration(1600);
            mPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mPulseAnimator.addUpdateListener(a -> {
                mPulse = (float) a.getAnimatedValue();
                invalidate();
            });
            mPulseAnimator.start();
        } else {
            mPulse = 0f;
            invalidate();
        }
    }

    /**
     * @param values  play time per day, index 6 = today
     * @param labels  weekday initials, same indexing
     * @param animate grow the bars in (first data) or morph to the new heights
     */
    public void setData(@NonNull long[] values, @NonNull String[] labels, boolean animate) {
        boolean changed = false;
        for (int i = 0; i < 7; i++) {
            long v = i < values.length ? values[i] : 0;
            if (mValues[i] != v) changed = true;
            mValues[i] = v;
            mLabels[i] = i < labels.length ? labels[i] : "";
        }
        long max = 1;
        for (long v : mValues) max = Math.max(max, v);
        mMax = max;
        if (!changed && mAnimator == null) {
            invalidate();
            return;
        }
        if (mAnimator != null) mAnimator.cancel();
        final float[] from = mShown.clone();
        final float[] to = new float[7];
        for (int i = 0; i < 7; i++) to[i] = mValues[i] / (float) mMax;
        if (!animate || !Motion.content(this)) {
            System.arraycopy(to, 0, mShown, 0, 7);
            invalidate();
            return;
        }
        boolean firstShow = true;
        for (float f : from) if (f > 0f) { firstShow = false; break; }
        final boolean stagger = firstShow;
        mAnimator = ValueAnimator.ofFloat(0f, 1f);
        mAnimator.setDuration(stagger ? 1100 : 450);
        mAnimator.setInterpolator(stagger ? Motion.SPRING : Motion.STANDARD);
        mAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            for (int i = 0; i < 7; i++) {
                float local = t;
                if (stagger) {
                    // Each bar starts a bit after the previous one, all finish together-ish
                    float start = i * 0.09f;
                    local = Math.max(0f, Math.min(1f, (t - start) / (1f - start)));
                }
                mShown[i] = from[i] + (to[i] - from[i]) * local;
            }
            invalidate();
        });
        mAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAnimator != null) mAnimator.cancel();
        if (mPulseAnimator != null) mPulseAnimator.cancel();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = (int) (132 * mDensity);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (heightMode == MeasureSpec.EXACTLY) height = MeasureSpec.getSize(heightMeasureSpec);
        else if (heightMode == MeasureSpec.AT_MOST) height = Math.min(height, MeasureSpec.getSize(heightMeasureSpec));
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            int index = indexAt(event.getX());
            if (index >= 0 && index != mSelected) {
                mSelected = index;
                invalidate();
                if (mListener != null) mListener.onDaySelected(index);
            }
            performClick();
            return true;
        }
        return event.getAction() == MotionEvent.ACTION_DOWN || super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private int indexAt(float x) {
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        if (width <= 0) return -1;
        int index = (int) ((x - getPaddingLeft()) / (width / 7f));
        return index < 0 || index > 6 ? -1 : index;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = getPaddingLeft();
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        float slot = width / 7f;
        float barWidth = Math.min(slot * 0.52f, 30f * mDensity);
        float labelH = 16f * mDensity;
        float valueH = 14f * mDensity;
        float top = getPaddingTop() + valueH;
        float bottom = getHeight() - getPaddingBottom() - labelH;
        float trackH = bottom - top;
        float radius = barWidth / 2f;
        long now = System.currentTimeMillis();

        for (int i = 0; i < 7; i++) {
            float cx = left + slot * i + slot / 2f;
            // Track
            mRect.set(cx - barWidth / 2f, top, cx + barWidth / 2f, bottom);
            canvas.drawRoundRect(mRect, radius, radius, mTrackPaint);

            boolean today = i == 6;
            boolean selected = i == mSelected;
            float h = Math.max(mShown[i] * trackH, mValues[i] > 0 ? barWidth : 0f);
            if (h > 0f) {
                float barTop = bottom - h;
                if (today && mLiveToday && mPulse > 0f) {
                    mGlowPaint.setAlpha((int) (70 * mPulse));
                    mRect.set(cx - barWidth / 2f - 3f * mDensity * mPulse, barTop - 3f * mDensity * mPulse,
                            cx + barWidth / 2f + 3f * mDensity * mPulse, bottom + 3f * mDensity * mPulse);
                    canvas.drawRoundRect(mRect, radius + 3f * mDensity, radius + 3f * mDensity, mGlowPaint);
                }
                int color = selected || today ? mColorBar : mColorBarDim;
                if (selected || today) {
                    mBarPaint.setShader(new LinearGradient(0, barTop, 0, bottom, color, 0xFFB7BBC6, Shader.TileMode.CLAMP));
                } else {
                    mBarPaint.setShader(null);
                    mBarPaint.setColor(color);
                }
                mRect.set(cx - barWidth / 2f, barTop, cx + barWidth / 2f, bottom);
                canvas.drawRoundRect(mRect, radius, radius, mBarPaint);
                mBarPaint.setShader(null);
                // Value above the bar
                if (mShown[i] > 0.001f) {
                    mValuePaint.setColor(selected ? mColorText : mColorTextDim);
                    mValuePaint.setAlpha((int) (255 * Math.min(1f, mShown[i] / Math.max(0.0001f, mValues[i] / (float) mMax))));
                    canvas.drawText(shortDuration(mValues[i]), cx, barTop - 4f * mDensity, mValuePaint);
                }
            }
            // Label
            mTextPaint.setColor(selected ? mColorText : mColorTextDim);
            mTextPaint.setFakeBoldText(selected || today);
            canvas.drawText(mLabels[i], cx, getHeight() - getPaddingBottom() - 3f * mDensity, mTextPaint);
            if (today) {
                // Dot under today's label
                mGlowPaint.setAlpha(255);
                mGlowPaint.setColor(mLiveToday ? mColorGlow : mColorBar);
                canvas.drawCircle(cx, getHeight() - getPaddingBottom() - 0.5f * mDensity, 1.6f * mDensity, mGlowPaint);
                mGlowPaint.setColor(mColorGlow);
            }
        }
    }

    private static String shortDuration(long ms) {
        long m = ms / 60000;
        if (m < 1) return ms >= 1000 ? (ms / 1000) + "s" : "";
        if (m < 60) return m + "m";
        long h = m / 60;
        long rest = m % 60;
        return rest == 0 || h >= 10 ? h + "h" : h + "h" + rest;
    }
}
