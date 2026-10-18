package com.craftstudio.cslauncher.game;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The PLAY-button rocket continues on the game screen: it idles above the status pill with a
 * pixel exhaust while the window boots, then (when the game surface appears) accelerates up
 * and bursts into a Minecraft-style pixel explosion. Pure canvas, no touch handling, so the
 * controls underneath stay usable the whole time.
 */
public class LaunchRocketView extends View {
    private static final int IDLE = 0, FLY = 1, BURST = 2, DONE = 3;

    private static final int[] FIRE = {0xFFFFFFFF, 0xFFFFF4B0, 0xFFFFD35C, 0xFFFFA03A, 0xFFFF6A2B, 0xFFE0401E};
    private static final int[] SMOKE = {0xFFD9D9DE, 0xFFA8A8B0, 0xFF737379, 0xFF4C4C52};

    private final Paint mPixel = new Paint();
    private final Paint mFlash = new Paint();
    private final Random mRnd = new Random();
    private final List<Particle> mParticles = new ArrayList<>();
    private final Drawable mRocket;
    private final float mDensity;
    private final float mGrid;

    private int mPhase = IDLE;
    private long mLast;
    private float mTime;
    private float mRocketX, mRocketY;   // centre of the rocket
    private float mRocketSize;
    private float mRestY;
    private float mApexY;
    private float mFlyT;
    private float mBurstT;
    private float mExhaustAcc;
    @Nullable private ValueAnimator mClock;
    @Nullable private Runnable mOnDone;

    private static final class Particle {
        float x, y, vx, vy, size, life, age;
        int color;
        boolean smoke;
    }

    public LaunchRocketView(Context context) { this(context, null); }

    public LaunchRocketView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mGrid = 4f * mDensity;
        mPixel.setAntiAlias(false);
        mPixel.setStyle(Paint.Style.FILL);
        mFlash.setAntiAlias(true);
        mFlash.setStyle(Paint.Style.FILL);
        mRocket = ContextCompat.getDrawable(context, R.drawable.ic_home_rocket);
        mRocketSize = 44f * mDensity;
        mRestY = 104f * mDensity;
        setClickable(false);
        setFocusable(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Pixel distance from the bottom of the view to the rocket's resting centre. */
    public void setRestOffsetFromBottom(float px) {
        mRestY = px;
        if (getHeight() > 0) mRocketY = getHeight() - mRestY;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mRocketX = w / 2f;
        if (mPhase == IDLE) mRocketY = h - mRestY;
        mApexY = h * 0.30f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startClock();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopClock();
        super.onDetachedFromWindow();
    }

    private void startClock() {
        if (mClock != null || mPhase == DONE) return;
        mLast = 0L;
        mClock = ValueAnimator.ofFloat(0f, 1f);
        mClock.setDuration(1000L);
        mClock.setRepeatCount(ValueAnimator.INFINITE);
        mClock.setInterpolator(new LinearInterpolator());
        mClock.addUpdateListener(a -> step());
        mClock.start();
    }

    private void stopClock() {
        if (mClock != null) {
            mClock.cancel();
            mClock = null;
        }
    }

    /** Lift off: fly up, explode, then run {@code onDone} (always on the main thread). */
    public void launch(@Nullable Runnable onDone) {
        if (mPhase != IDLE) {
            if (mPhase == DONE && onDone != null) onDone.run();
            else mOnDone = onDone;
            return;
        }
        mOnDone = onDone;
        mPhase = FLY;
        mFlyT = 0f;
        startClock();
    }

    /** Skip straight to the end state (animations off / activity closing). */
    public void finishNow() {
        stopClock();
        mPhase = DONE;
        mParticles.clear();
        invalidate();
        Runnable r = mOnDone;
        mOnDone = null;
        if (r != null) r.run();
    }

    private void step() {
        long now = System.nanoTime() / 1_000_000L;
        if (mLast == 0L) mLast = now;
        float dt = Math.min(0.05f, (now - mLast) / 1000f);
        mLast = now;
        mTime += dt;
        int h = getHeight();
        if (h <= 0) return;

        switch (mPhase) {
            case IDLE: {
                // Hover with a little thrust shiver and a steady pixel exhaust
                mRocketY = h - mRestY + (float) Math.sin(mTime * 9.0) * 1.2f * mDensity;
                emitExhaust(dt, 26f);
                break;
            }
            case FLY: {
                mFlyT += dt;
                float dur = 0.78f;
                float p = Math.min(1f, mFlyT / dur);
                float e = p * p * p;                         // hard acceleration
                mRocketY = (h - mRestY) + (mApexY - (h - mRestY)) * e;
                mRocketX = getWidth() / 2f + (float) Math.sin(mTime * 40.0) * (1f - p) * 1.4f * mDensity;
                emitExhaust(dt, 90f + 120f * p);
                if (p >= 1f) {
                    mPhase = BURST;
                    mBurstT = 0f;
                    explode();
                }
                break;
            }
            case BURST: {
                mBurstT += dt;
                if (mBurstT > 1.4f && mParticles.isEmpty()) {
                    mPhase = DONE;
                    stopClock();
                    Runnable r = mOnDone;
                    mOnDone = null;
                    if (r != null) post(r);
                }
                break;
            }
            default:
                break;
        }
        updateParticles(dt);
        invalidate();
    }

    private void emitExhaust(float dt, float perSecond) {
        mExhaustAcc += dt * perSecond;
        while (mExhaustAcc >= 1f) {
            mExhaustAcc -= 1f;
            Particle p = new Particle();
            p.x = mRocketX + (mRnd.nextFloat() - 0.5f) * 8f * mDensity;
            p.y = mRocketY + mRocketSize * 0.42f;
            p.vx = (mRnd.nextFloat() - 0.5f) * 30f * mDensity;
            p.vy = (80f + mRnd.nextFloat() * 120f) * mDensity;
            p.size = (1f + mRnd.nextInt(2)) * mGrid;
            p.life = 0.25f + mRnd.nextFloat() * 0.3f;
            boolean smoke = mRnd.nextFloat() < 0.35f;
            p.smoke = smoke;
            p.color = smoke ? SMOKE[mRnd.nextInt(SMOKE.length)] : FIRE[mRnd.nextInt(FIRE.length)];
            mParticles.add(p);
        }
    }

    private void explode() {
        float cx = mRocketX, cy = mRocketY;
        // Fire core: fast, bright, short-lived
        for (int i = 0; i < 70; i++) {
            Particle p = new Particle();
            double ang = mRnd.nextDouble() * Math.PI * 2;
            float speed = (120f + mRnd.nextFloat() * 340f) * mDensity;
            p.x = cx; p.y = cy;
            p.vx = (float) Math.cos(ang) * speed;
            p.vy = (float) Math.sin(ang) * speed;
            p.size = (1f + mRnd.nextInt(3)) * mGrid;
            p.life = 0.55f + mRnd.nextFloat() * 0.45f;
            p.color = FIRE[mRnd.nextInt(FIRE.length)];
            mParticles.add(p);
        }
        // Smoke puffs: slower, bigger, linger and drift up
        for (int i = 0; i < 34; i++) {
            Particle p = new Particle();
            double ang = mRnd.nextDouble() * Math.PI * 2;
            float speed = (40f + mRnd.nextFloat() * 150f) * mDensity;
            p.x = cx + (mRnd.nextFloat() - 0.5f) * 10f * mDensity;
            p.y = cy + (mRnd.nextFloat() - 0.5f) * 10f * mDensity;
            p.vx = (float) Math.cos(ang) * speed;
            p.vy = (float) Math.sin(ang) * speed - 25f * mDensity;
            p.size = (2f + mRnd.nextInt(4)) * mGrid;
            p.life = 0.7f + mRnd.nextFloat() * 0.55f;
            p.smoke = true;
            p.color = SMOKE[mRnd.nextInt(SMOKE.length)];
            mParticles.add(p);
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    }

    private void updateParticles(float dt) {
        float gravity = 260f * mDensity;
        for (int i = mParticles.size() - 1; i >= 0; i--) {
            Particle p = mParticles.get(i);
            p.age += dt;
            if (p.age >= p.life) {
                mParticles.remove(i);
                continue;
            }
            float drag = p.smoke ? 0.90f : 0.94f;
            float k = (float) Math.pow(drag, dt * 60f);
            p.vx *= k;
            p.vy *= k;
            if (!p.smoke) p.vy += gravity * dt * 0.35f;
            else p.vy -= 18f * mDensity * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (mPhase == DONE) return;

        // Pixel particles: snapped to a coarse grid, no anti-aliasing (Minecraft look)
        for (int i = 0, n = mParticles.size(); i < n; i++) {
            Particle p = mParticles.get(i);
            float t = p.age / p.life;
            int a = (int) (255 * (t < 0.6f ? 1f : (1f - (t - 0.6f) / 0.4f)));
            float s = p.size * (p.smoke ? (0.8f + 0.5f * t) : (1.1f - 0.5f * t));
            float gx = (float) Math.floor(p.x / mGrid) * mGrid;
            float gy = (float) Math.floor(p.y / mGrid) * mGrid;
            mPixel.setColor(p.color);
            mPixel.setAlpha(Math.max(0, Math.min(255, a)));
            canvas.drawRect(gx - s / 2f, gy - s / 2f, gx + s / 2f, gy + s / 2f, mPixel);
        }

        if (mPhase == BURST) {
            // Short white flash + expanding ring at the burst point
            float t = Math.min(1f, mBurstT / 0.32f);
            if (t < 1f) {
                float r = (14f + 110f * t) * mDensity;
                mFlash.setColor(0xFFFFFFFF);
                mFlash.setAlpha((int) (200 * (1f - t)));
                canvas.drawCircle(mRocketX, mRocketY, r * 0.35f, mFlash);
                mFlash.setStyle(Paint.Style.STROKE);
                mFlash.setStrokeWidth(4f * mDensity * (1f - t) + 1f);
                mFlash.setAlpha((int) (160 * (1f - t)));
                canvas.drawCircle(mRocketX, mRocketY, r, mFlash);
                mFlash.setStyle(Paint.Style.FILL);
            }
            return;
        }

        if (mRocket != null) {
            float half = mRocketSize / 2f;
            // Flame glow under the rocket (grows with thrust)
            float thrust = mPhase == FLY ? 1f : 0.55f + 0.2f * (float) Math.sin(mTime * 14.0);
            mFlash.setColor(0xFFFFB347);
            mFlash.setAlpha((int) (90 * thrust));
            canvas.drawCircle(mRocketX, mRocketY + half * 0.75f, half * 0.55f * thrust, mFlash);
            mRocket.setBounds((int) (mRocketX - half), (int) (mRocketY - half),
                    (int) (mRocketX + half), (int) (mRocketY + half));
            mRocket.draw(canvas);
        }
    }
}
