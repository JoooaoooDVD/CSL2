package com.craftstudio.cslauncher.tutorial;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.AnimationManager;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * The spotlight overlay: dims the launcher, cuts a rounded hole around the current target and
 * shows the tooltip card next to it. It is a plain child of {@code android.R.id.content}, so
 * dialogs (separate windows) stay on top of it and system bars are handled by the activity.
 * <p>
 * Touch policy: the card handles its own buttons; every other touch is swallowed, so the launcher
 * cannot change under a running guide (pass-through exists for completeness but is unused). Geometry follows the target through the view tree
 * observer (layout / scroll), reading one {@code getLocationInWindow} - no hierarchy scans.
 */
final class TutorialOverlay extends FrameLayout {

    interface Callbacks {
        void onNext();
        void onBack();
        void onSkipStep();
        void onClose();
        /** The target moved / was re-laid out and could not be found any more */
        void onTargetLost();
    }

    private static final int DIM = 0xB8000000;
    private static final int RING = 0xFFE4E4EA;

    private final Paint mDimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mCutout = new RectF();
    private final Rect mTmp = new Rect();
    private final int[] mLoc = new int[2];
    private final float mDensity;
    private final float mCornerRadius;
    private final float mCutoutPadding;

    private final View mCard;
    private final TextView mEyebrow, mTitle, mBody, mInstruction, mNext, mBack, mSkip;
    private final View mInstructionRow, mClose, mPulseDot;
    private final ProgressBar mProgress;

    @Nullable private View mTarget;
    private boolean mPassThrough;
    private boolean mHasCutout;
    private boolean mSuspended;
    @Nullable private Callbacks mCallbacks;
    @Nullable private ValueAnimator mPulse;
    private float mPulseAlpha = 1f;
    private int mLostTicks;
    private boolean mAnimateNextMove;
    @Nullable private ValueAnimator mCutoutAnim;
    private final RectF mAnimFrom = new RectF();
    private final RectF mAnimTo = new RectF();

    private final ViewTreeObserver.OnGlobalLayoutListener mLayoutListener = this::syncGeometry;
    private final ViewTreeObserver.OnScrollChangedListener mScrollListener = this::syncGeometry;

    TutorialOverlay(@NonNull Context context) {
        super(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mCornerRadius = 14f * mDensity;
        mCutoutPadding = 6f * mDensity;
        setWillNotDraw(false);
        setClickable(true);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        mDimPaint.setColor(DIM);
        mDimPaint.setStyle(Paint.Style.FILL);
        mRingPaint.setColor(RING);
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(1.5f * mDensity);
        mPath.setFillType(Path.FillType.EVEN_ODD);

        mCard = LayoutInflater.from(context).inflate(R.layout.tutorial_tooltip, this, false);
        addView(mCard);
        mEyebrow = mCard.findViewById(R.id.tut_eyebrow);
        mTitle = mCard.findViewById(R.id.tut_title);
        mBody = mCard.findViewById(R.id.tut_body);
        mInstruction = mCard.findViewById(R.id.tut_instruction);
        mInstructionRow = mCard.findViewById(R.id.tut_instruction_row);
        mPulseDot = mCard.findViewById(R.id.tut_instruction_dot);
        mNext = mCard.findViewById(R.id.tut_next);
        mBack = mCard.findViewById(R.id.tut_back);
        mSkip = mCard.findViewById(R.id.tut_skip);
        mClose = mCard.findViewById(R.id.tut_close);
        mProgress = mCard.findViewById(R.id.tut_progress);

        mNext.setOnClickListener(v -> { if (mCallbacks != null) mCallbacks.onNext(); });
        mBack.setOnClickListener(v -> { if (mCallbacks != null) mCallbacks.onBack(); });
        mSkip.setOnClickListener(v -> { if (mCallbacks != null) mCallbacks.onSkipStep(); });
        mClose.setOnClickListener(v -> { if (mCallbacks != null) mCallbacks.onClose(); });
    }

    void setCallbacks(@Nullable Callbacks callbacks) {
        mCallbacks = callbacks;
    }

    // ───────────────────────────── content ─────────────────────────────

    /**
     * @param nextLabel null hides the Next button
     * @param skipLabel null hides the Skip button
     * @param progress  0..1 of the module, negative hides the bar
     */
    void setContent(@Nullable CharSequence eyebrow, @NonNull CharSequence title, @NonNull CharSequence body,
                    @Nullable CharSequence instruction, boolean showBack, @Nullable CharSequence nextLabel,
                    @Nullable CharSequence skipLabel, float progress) {
        mEyebrow.setText(eyebrow);
        mEyebrow.setVisibility(eyebrow == null || eyebrow.length() == 0 ? GONE : VISIBLE);
        mTitle.setText(title);
        mBody.setText(body);
        boolean hasInstruction = instruction != null && instruction.length() > 0;
        mInstructionRow.setVisibility(hasInstruction ? VISIBLE : GONE);
        mInstruction.setText(hasInstruction ? instruction : "");
        mBack.setVisibility(showBack ? VISIBLE : GONE);
        mNext.setVisibility(nextLabel == null ? GONE : VISIBLE);
        if (nextLabel != null) mNext.setText(nextLabel);
        mSkip.setVisibility(skipLabel == null ? GONE : VISIBLE);
        if (skipLabel != null) mSkip.setText(skipLabel);
        mProgress.setVisibility(progress < 0f ? GONE : VISIBLE);
        if (progress >= 0f) mProgress.setProgress(Math.round(Math.max(0f, Math.min(1f, progress)) * 1000f));
        mCard.setContentDescription(title + ". " + body + (hasInstruction ? ". " + instruction : ""));
        announceForAccessibility(mCard.getContentDescription());
        mCard.setVisibility(VISIBLE);
        animateCardIn();
        requestLayout();
    }

    /** Between screens: keep the dim, drop the cutout, hide the card until the next setContent */
    void showTransition() {
        mTarget = null;
        mPassThrough = false;
        startPulse(false);
        mCard.animate().cancel();
        mCard.setVisibility(INVISIBLE);
        if (mCutoutAnim != null) { mCutoutAnim.cancel(); mCutoutAnim = null; }
        mHasCutout = false;
        mAnimateNextMove = false;
        invalidate();
    }

    /** First appearance of the overlay */
    void fadeIn() {
        if (!AnimationManager.isEnabled(getContext(), AnimationManager.TYPE_DIALOGS)) return;
        setAlpha(0f);
        animate().alpha(1f).setDuration(Motion.MEDIUM).setInterpolator(Motion.ENTER).start();
    }

    /**
     * @param target      the real control, null = no cutout (card centred)
     * @param passThrough whether touches inside the cutout reach the control
     */
    void setTarget(@Nullable View target, boolean passThrough) {
        mTarget = target;
        mPassThrough = passThrough && target != null;
        mLostTicks = 0;
        boolean pulse = passThrough && target != null;
        mPulseDot.setVisibility(pulse ? VISIBLE : GONE);
        startPulse(pulse);
        mAnimateNextMove = mHasCutout && target != null;
        syncGeometry();
    }

    /** Hidden while a dialog opened from the target is up; geometry keeps following */
    void setSuspended(boolean suspended) {
        if (mSuspended == suspended) return;
        mSuspended = suspended;
        setVisibility(suspended ? INVISIBLE : VISIBLE);
        if (!suspended) syncGeometry();
    }

    boolean isSuspended() {
        return mSuspended;
    }

    /** Current cutout in window coordinates (empty when there is no target) */
    void getCutoutInWindow(@NonNull Rect out) {
        if (!mHasCutout) {
            out.setEmpty();
            return;
        }
        getLocationInWindow(mLoc);
        out.set(Math.round(mCutout.left) + mLoc[0], Math.round(mCutout.top) + mLoc[1],
                Math.round(mCutout.right) + mLoc[0], Math.round(mCutout.bottom) + mLoc[1]);
    }

    View card() {
        return mCard;
    }

    // ───────────────────────────── geometry ─────────────────────────────

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewTreeObserver observer = getViewTreeObserver();
        observer.addOnGlobalLayoutListener(mLayoutListener);
        observer.addOnScrollChangedListener(mScrollListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        ViewTreeObserver observer = getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnGlobalLayoutListener(mLayoutListener);
            observer.removeOnScrollChangedListener(mScrollListener);
        }
        startPulse(false);
        if (mCutoutAnim != null) { mCutoutAnim.cancel(); mCutoutAnim = null; }
        mTarget = null;
        mCallbacks = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // The card's own layout pass happens here; place it relative to the cutout afterwards
        placeCard();
    }

    private void syncGeometry() {
        if (getWidth() == 0 || getHeight() == 0) return;
        View target = mTarget;
        boolean hadCutout = mHasCutout;
        RectF before = new RectF(mCutout);
        if (target == null || !target.isAttachedToWindow() || !target.isShown()
                || target.getWidth() == 0 || target.getHeight() == 0) {
            mHasCutout = false;
            if (target != null && ++mLostTicks > 12 && mCallbacks != null && !mSuspended) {
                mLostTicks = 0;
                mCallbacks.onTargetLost();
            }
        } else {
            mLostTicks = 0;
            target.getLocationInWindow(mLoc);
            int tx = mLoc[0], ty = mLoc[1];
            getLocationInWindow(mLoc);
            float l = tx - mLoc[0] - mCutoutPadding;
            float t = ty - mLoc[1] - mCutoutPadding;
            mAnimTo.set(l, t, l + target.getWidth() + 2 * mCutoutPadding, t + target.getHeight() + 2 * mCutoutPadding);
            mHasCutout = true;
            if (mCutoutAnim != null && mCutoutAnim.isRunning()) {
                // A layout pass during the move: the animation keeps going towards the new end rect
                placeCard();
                return;
            }
            if (mAnimateNextMove && hadCutout && !before.equals(mAnimTo)
                    && AnimationManager.isEnabled(getContext(), AnimationManager.TYPE_CONTENT)) {
                mAnimateNextMove = false;
                mAnimFrom.set(before);
                ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
                anim.setDuration(Motion.MEDIUM);
                anim.setInterpolator(Motion.STANDARD);
                anim.addUpdateListener(a -> {
                    float f = (float) a.getAnimatedValue();
                    mCutout.set(lerp(mAnimFrom.left, mAnimTo.left, f), lerp(mAnimFrom.top, mAnimTo.top, f),
                            lerp(mAnimFrom.right, mAnimTo.right, f), lerp(mAnimFrom.bottom, mAnimTo.bottom, f));
                    invalidate();
                });
                anim.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator animation) {
                        mCutoutAnim = null;
                        mCutout.set(mAnimTo);
                        placeCard();
                        invalidate();
                    }
                });
                mCutoutAnim = anim;
                anim.start();
                placeCard();
                return;
            }
            mAnimateNextMove = false;
            mCutout.set(mAnimTo);
        }
        if (hadCutout != mHasCutout || !before.equals(mCutout)) {
            placeCard();
            invalidate();
        }
    }

    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    private void placeCard() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        int margin = Math.round(12 * mDensity);
        int gap = Math.round(10 * mDensity);
        int maxWidth = Math.min(Math.round(380 * mDensity), w - 2 * margin);
        LayoutParams lp = (LayoutParams) mCard.getLayoutParams();
        if (lp.width != maxWidth) {
            lp.width = maxWidth;
            mCard.setLayoutParams(lp);
        }
        mCard.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h - 2 * margin, MeasureSpec.AT_MOST));
        int cw = mCard.getMeasuredWidth(), ch = mCard.getMeasuredHeight();
        int x, y;
        if (!mHasCutout) {
            x = (w - cw) / 2;
            y = (h - ch) / 2;
        } else {
            RectF r = mCutoutAnim != null ? mAnimTo : mCutout;
            int spaceBelow = h - Math.round(r.bottom) - margin;
            int spaceAbove = Math.round(r.top) - margin;
            int spaceRight = w - Math.round(r.right) - margin;
            int spaceLeft = Math.round(r.left) - margin;
            int cx = Math.round(r.centerX());
            int cy = Math.round(r.centerY());
            if (spaceBelow >= ch + gap) {
                y = Math.round(r.bottom) + gap;
                x = clamp(cx - cw / 2, margin, w - margin - cw);
            } else if (spaceAbove >= ch + gap) {
                y = Math.round(r.top) - gap - ch;
                x = clamp(cx - cw / 2, margin, w - margin - cw);
            } else if (spaceRight >= cw + gap) {
                x = Math.round(r.right) + gap;
                y = clamp(cy - ch / 2, margin, h - margin - ch);
            } else if (spaceLeft >= cw + gap) {
                x = Math.round(r.left) - gap - cw;
                y = clamp(cy - ch / 2, margin, h - margin - ch);
            } else {
                // Nothing fits cleanly (huge target): use the side with more room, overlapping as little as possible
                boolean right = spaceRight >= spaceLeft;
                x = right ? w - margin - cw : margin;
                y = clamp(cy - ch / 2, margin, h - margin - ch);
            }
        }
        mCard.layout(x, y, x + cw, y + ch);
    }

    private static int clamp(int v, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, v));
    }

    // ───────────────────────────── drawing ─────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (!mHasCutout) {
            canvas.drawColor(DIM);
            return;
        }
        mPath.rewind();
        mPath.addRect(0, 0, w, h, Path.Direction.CW);
        mPath.addRoundRect(mCutout, mCornerRadius, mCornerRadius, Path.Direction.CW);
        canvas.drawPath(mPath, mDimPaint);
        mRingPaint.setAlpha(Math.round(255 * mPulseAlpha));
        canvas.drawRoundRect(mCutout, mCornerRadius, mCornerRadius, mRingPaint);
    }

    private void startPulse(boolean on) {
        if (mPulse != null) {
            mPulse.cancel();
            mPulse = null;
        }
        mPulseAlpha = 1f;
        if (!on || !AnimationManager.isEnabled(getContext(), AnimationManager.TYPE_CONTENT)) return;
        ValueAnimator animator = ValueAnimator.ofFloat(1f, 0.45f, 1f);
        animator.setDuration(1800L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            mPulseAlpha = (float) a.getAnimatedValue();
            if (mHasCutout) {
                int pad = Math.round(4 * mDensity);
                mTmp.set((int) mCutout.left - pad, (int) mCutout.top - pad, (int) mCutout.right + pad, (int) mCutout.bottom + pad);
                invalidate(mTmp);
            }
        });
        animator.start();
        mPulse = animator;
    }

    private void animateCardIn() {
        if (!AnimationManager.isEnabled(getContext(), AnimationManager.TYPE_DIALOGS)) {
            mCard.setAlpha(1f);
            mCard.setTranslationY(0f);
            return;
        }
        mCard.animate().cancel();
        mCard.setAlpha(0f);
        mCard.setTranslationY(8f * mDensity);
        mCard.animate().alpha(1f).translationY(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.ENTER).start();
    }

    // ───────────────────────────── touch ─────────────────────────────

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mSuspended) return false;
        if (mPassThrough && mHasCutout && ev.getActionMasked() == MotionEvent.ACTION_DOWN
                && mCutout.contains(ev.getX(), ev.getY())) {
            // Let the real control receive this gesture untouched
            return false;
        }
        super.dispatchTouchEvent(ev);
        // Anything outside the card is swallowed so unrelated controls are not pressed by accident
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return true;
    }

    static ViewGroup.LayoutParams fullSize() {
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
