package com.kdt.mcgui;

import com.craftstudio.cslauncher.utils.AnimationManager;
import com.craftstudio.cslauncher.utils.ThemeColors;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;
import android.widget.ProgressBar;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.res.ResourcesCompat;

import com.craftstudio.cslauncher.R;

public class TextProgressBar extends ProgressBar {

    private static final long SHIMMER_DURATION_MS = 1700L;

    private int mTextPadding = 0;
    public TextProgressBar(Context context) {super(context, null, android.R.attr.progressBarStyleHorizontal); init();}

    public TextProgressBar(Context context, AttributeSet attrs) {super(context, attrs, android.R.attr.progressBarStyleHorizontal); init();}
    public TextProgressBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, android.R.attr.progressBarStyleHorizontal);
        init();
    }
    public TextProgressBar(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, android.R.attr.progressBarStyleHorizontal, defStyleRes);
        init();
    }

    private Paint mTextPaint;
    private final Paint mShimmerPaint = new Paint();
    private String mText = "";
    private float mShimmerPhase;
    private boolean mMotionAllowed = true;

    @Nullable
    private ValueAnimator mShimmerAnimator;

    private void init(){
        setProgressDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.view_text_progressbar, getContext().getTheme()));
        setProgress(35);
        mTextPaint = new Paint();
        mTextPaint.setColor(ThemeColors.get(getContext(), com.google.android.material.R.attr.colorOnPrimaryContainer));
        mTextPaint.setFlags(Paint.FAKE_BOLD_TEXT_FLAG);
        mTextPaint.setAntiAlias(true);
        mShimmerPaint.setColor(0xFFFFFFFF);
        mShimmerPaint.setAlpha(70);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mMotionAllowed = AnimationManager.isEnabled(getContext(), AnimationManager.TYPE_CONTENT);
        if (mMotionAllowed) startShimmer();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopShimmer();
        super.onDetachedFromWindow();
    }

    private void startShimmer() {
        if (mShimmerAnimator == null) {
            mShimmerAnimator = ValueAnimator.ofFloat(0f, 1f);
            mShimmerAnimator.setDuration(SHIMMER_DURATION_MS);
            mShimmerAnimator.setInterpolator(new LinearInterpolator());
            mShimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mShimmerAnimator.addUpdateListener(animation -> {
                mShimmerPhase = (float) animation.getAnimatedValue();
                invalidate();
            });
        }
        if (isAttachedToWindow() && !mShimmerAnimator.isRunning()) mShimmerAnimator.start();
    }

    private void stopShimmer() {
        if (mShimmerAnimator != null) mShimmerAnimator.cancel();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        mTextPaint.setTextSize((float) ((getHeight()- getPaddingBottom() - getPaddingTop()) * 0.55));
        int xPos = (int) Math.max(Math.min((getProgress() * getWidth() / getMax()) + mTextPadding, getWidth() - mTextPaint.measureText(mText) - mTextPadding) , mTextPadding);
        int yPos = (int) ((getHeight() / 2) - ((mTextPaint.descent() + mTextPaint.ascent()) / 2)) ;

        canvas.drawText(mText, xPos, yPos, mTextPaint);

        // Shimmer band sweeping across the filled part while work runs
        int max = getMax();
        if (mMotionAllowed && max > 0 && getProgress() > 0 && getProgress() < max) {
            float fill = (float) getProgress() * getWidth() / max;
            if (fill > 8f) {
                float band = Math.max(48f, getWidth() * 0.12f);
                float x = mShimmerPhase * (fill + band) - band / 2f;
                int save = canvas.save();
                canvas.clipRect(getPaddingLeft(), getPaddingTop(), fill, getHeight() - getPaddingBottom());
                canvas.drawRect(x - band / 2f, 0, x + band / 2f, getHeight(), mShimmerPaint);
                canvas.restoreToCount(save);
            }
        }
    }


    public final void setText(@StringRes int resid) {
        setText(getContext().getResources().getText(resid).toString());
    }

    public final void setText(String text){
        mText = text;
        invalidate();
    }

    public final void setTextPadding(int padding){
        mTextPadding = padding;
    }
}
