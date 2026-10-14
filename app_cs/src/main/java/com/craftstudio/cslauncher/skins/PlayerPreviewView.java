package com.craftstudio.cslauncher.skins;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.utils.AnimationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A small software 3D renderer that shows a Minecraft player with a skin and (optionally) a cape.
 * Every body part is a textured box, drawn with an orthographic camera that can be rotated
 * by dragging. It auto-rotates while idle.
 */
public class PlayerPreviewView extends View {
    // Face order used everywhere in this class
    private static final int F_FRONT = 0;  // +z
    private static final int F_BACK = 1;   // -z
    private static final int F_RIGHT = 2;  // -x, the right side of the player
    private static final int F_LEFT = 3;   // +x, the left side of the player
    private static final int F_TOP = 4;    // +y
    private static final int F_BOTTOM = 5; // -y

    private static final float CAPE_TILT_DEGREES = 8f;
    private static final float AUTO_ROTATE_DEGREES_PER_SECOND = 28f;
    private static final float MODEL_CENTER_Y = 16f;
    private static final float MODEL_FIT_HEIGHT = 36f;
    private static final float MODEL_FIT_WIDTH = 24f;

    /** One textured box (plus, for body parts, its outer layer) */
    private static final class Part {
        final boolean cape;
        final float[][] baseFaces = new float[6][12];
        final float[][] overlayFaces = new float[6][12];
        final Rect[] baseRects = new Rect[6];
        final Rect[] overlayRects = new Rect[6];
        boolean hasOverlay;
        float centerX, centerY, centerZ;
        float depth;
        // Per-frame idle-pose rotation (radians) around the pivot, set before every draw
        float animRotX, animRotY, animRotZ, animPivX, animPivY, animPivZ;
        Part(boolean cape) { this.cape = cape; }
    }

    private final List<Part> mParts = new ArrayList<>();
    private final Comparator<Part> mDepthOrder = (a, b) -> Float.compare(a.depth, b.depth);

    private Bitmap mSkin;
    private boolean mSlim;
    private Bitmap mCape;
    private boolean mModelDirty = true;

    private float mYaw = 25f;
    private float mPitch = 12f;
    private float mLastTouchX, mLastTouchY;
    private boolean mDragging;
    private long mLastFrameTime;
    /** Idle-life clock (seconds); frozen when content animation is disabled */
    private float mAnimT;
    private boolean mMotionAllowed = true;

    // ─── Wave ("hello") gesture ───────────────────────────────────────────
    /** Seconds the whole wave takes: raise, a few shakes, lower */
    private static final float WAVE_DURATION = 2.6f;
    private static final float WAVE_RAISE = 0.4f;
    /** Idle seconds between spontaneous waves */
    private static final float WAVE_EVERY = 11f;
    /** Animation clock value when the current wave started, or -1 when not waving */
    private float mWaveStart = -1f;
    private float mNextAutoWave = 1.0f;
    private float mTouchDownX, mTouchDownY;
    private boolean mTouchMoved;

    private final Paint mPaint = new Paint();
    private final Matrix mMatrix = new Matrix();
    private final RectF mDst = new RectF();
    private final float[] mSrcPoints = new float[6];
    private final float[] mDstPoints = new float[6];
    private final float[] mView = new float[12];

    public PlayerPreviewView(Context context) {
        super(context);
        init();
    }

    public PlayerPreviewView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PlayerPreviewView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mPaint.setAntiAlias(false);
        mPaint.setFilterBitmap(false); // keep the pixels crisp
        mPaint.setDither(false);
    }

    /** @param skin any skin bitmap, it gets normalized to the 64x64 layout; null clears the player */
    public void setSkin(@Nullable Bitmap skin, boolean slim) {
        mSkin = skin == null ? null : SkinTextures.normalizeSkin(skin);
        mSlim = slim;
        mModelDirty = true;
        // Say hello shortly after a new skin shows up
        if (mSkin != null) mNextAutoWave = Math.min(mNextAutoWave, mAnimT + 0.7f);
        invalidate();
    }

    /** Changes only the arm model of the current skin */
    public void setSlim(boolean slim) {
        if (mSlim == slim) return;
        mSlim = slim;
        mModelDirty = true;
        invalidate();
    }

    public void setCape(@Nullable Bitmap cape) {
        mCape = cape == null ? null : SkinTextures.normalizeCape(cape);
        mModelDirty = true;
        invalidate();
    }

    // ------------------------------------------------------------------ model

    private void rebuildModel() {
        mModelDirty = false;
        mParts.clear();
        if (mSkin == null) return;
        int s = mSkin.getWidth() / 64;
        float armWidth = mSlim ? 3 : 4;

        //                x               y   z   w         h   d  u   v   ou  ov  inflate
        addBody(s, -4,            24, -4, 8,        8,  8,  0,  0, 32,  0, 0.5f);  // head
        addBody(s, -4,            12, -2, 8,        12, 4, 16, 16, 16, 32, 0.25f); // body
        addBody(s, -4 - armWidth, 12, -2, armWidth, 12, 4, 40, 16, 40, 32, 0.25f); // right arm
        addBody(s,  4,            12, -2, armWidth, 12, 4, 32, 48, 48, 48, 0.25f); // left arm
        addBody(s, -4,            0,  -2, 4,        12, 4,  0, 16,  0, 32, 0.25f); // right leg
        addBody(s,  0,            0,  -2, 4,        12, 4, 16, 48,  0, 48, 0.25f); // left leg

        if (mCape != null) addCape(mCape.getWidth() / 64);
    }

    private void addBody(int s, float x, float y, float z, float w, float h, float d,
                         int u, int v, int ou, int ov, float inflate) {
        Part part = new Part(false);
        fillFaces(part.baseFaces, x, y, z, w, h, d, 0);
        fillRects(part.baseRects, s, u, v, (int) w, (int) h, (int) d, false);
        part.hasOverlay = true;
        fillFaces(part.overlayFaces, x, y, z, w, h, d, inflate);
        fillRects(part.overlayRects, s, ou, ov, (int) w, (int) h, (int) d, false);
        part.centerX = x + w / 2; part.centerY = y + h / 2; part.centerZ = z + d / 2;
        mParts.add(part);
    }

    private void addCape(int s) {
        Part part = new Part(true);
        // 10x16x1 box hanging off the back of the body
        float x = -5, y = 8, z = -3, w = 10, h = 16, d = 1;
        fillFaces(part.baseFaces, x, y, z, w, h, d, 0);
        fillRects(part.baseRects, s, 0, 0, 10, 16, 1, true);
        // Let it hang slightly away from the body, pivoting on the shoulders
        tilt(part.baseFaces, 24f, -2f, (float) Math.toRadians(CAPE_TILT_DEGREES));
        part.centerX = 0; part.centerY = 16; part.centerZ = -2.5f;
        float[] c = {part.centerX, part.centerY, part.centerZ};
        tiltPoint(c, 24f, -2f, (float) Math.toRadians(CAPE_TILT_DEGREES));
        part.centerX = c[0]; part.centerY = c[1]; part.centerZ = c[2];
        mParts.add(part);
    }

    private static void tilt(float[][] faces, float pivotY, float pivotZ, float angle) {
        for (float[] face : faces) {
            for (int i = 0; i < 12; i += 3) {
                float[] p = {face[i], face[i + 1], face[i + 2]};
                tiltPoint(p, pivotY, pivotZ, angle);
                face[i] = p[0]; face[i + 1] = p[1]; face[i + 2] = p[2];
            }
        }
    }

    /** Rotates around the X axis so that points below the pivot swing backwards (-z) */
    private static void tiltPoint(float[] p, float pivotY, float pivotZ, float angle) {
        float dy = p[1] - pivotY, dz = p[2] - pivotZ;
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        p[1] = pivotY + dy * cos - dz * sin;
        p[2] = pivotZ + dy * sin + dz * cos;
    }

    /**
     * Writes the 4 corners (top-left, top-right, bottom-left, bottom-right, as seen from outside
     * the box) of each face. Each corner is x,y,z.
     */
    private static void fillFaces(float[][] out, float x, float y, float z, float w, float h, float d, float e) {
        float x0 = x - e, x1 = x + w + e;
        float y0 = y - e, y1 = y + h + e;
        float z0 = z - e, z1 = z + d + e;
        set(out[F_FRONT],  x0, y1, z1,  x1, y1, z1,  x0, y0, z1,  x1, y0, z1);
        set(out[F_BACK],   x1, y1, z0,  x0, y1, z0,  x1, y0, z0,  x0, y0, z0);
        set(out[F_RIGHT],  x0, y1, z0,  x0, y1, z1,  x0, y0, z0,  x0, y0, z1);
        set(out[F_LEFT],   x1, y1, z1,  x1, y1, z0,  x1, y0, z1,  x1, y0, z0);
        set(out[F_TOP],    x0, y1, z0,  x1, y1, z0,  x0, y1, z1,  x1, y1, z1);
        set(out[F_BOTTOM], x0, y0, z1,  x1, y0, z1,  x0, y0, z0,  x1, y0, z0);
    }

    private static void set(float[] dst, float... values) {
        System.arraycopy(values, 0, dst, 0, 12);
    }

    /** Texture rectangle of each face of a box whose layout starts at (u, v) */
    private static void fillRects(Rect[] out, int s, int u, int v, int w, int h, int d, boolean cape) {
        Rect front = rect(s, u + d, v + d, w, h);
        Rect back = rect(s, u + d + w + d, v + d, w, h);
        Rect right = rect(s, u, v + d, d, h);
        Rect left = rect(s, u + d + w, v + d, d, h);
        if (cape) {
            // The outside of a cape faces backwards, so front/back and left/right trade places
            out[F_FRONT] = back; out[F_BACK] = front;
            out[F_RIGHT] = left; out[F_LEFT] = right;
        } else {
            out[F_FRONT] = front; out[F_BACK] = back;
            out[F_RIGHT] = right; out[F_LEFT] = left;
        }
        out[F_TOP] = rect(s, u + d, v, w, d);
        out[F_BOTTOM] = rect(s, u + d + w, v, w, d);
    }

    private static Rect rect(int s, int x, int y, int w, int h) {
        return new Rect(x * s, y * s, (x + w) * s, (y + h) * s);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (mModelDirty) rebuildModel();
        if (mSkin == null || mParts.isEmpty()) return;

        long now = SystemClock.uptimeMillis();
        float dt = 0f;
        if (mLastFrameTime != 0 && !mDragging) {
            dt = Math.min(now - mLastFrameTime, 100) / 1000f;
            mYaw = (mYaw + AUTO_ROTATE_DEGREES_PER_SECOND * dt) % 360f;
        }
        mLastFrameTime = now;

        mMotionAllowed = AnimationManager.isEnabled(getContext(), AnimationManager.TYPE_CONTENT);
        if (mMotionAllowed && dt > 0f) mAnimT += dt;
        if (mMotionAllowed && mWaveStart < 0f && mAnimT >= mNextAutoWave && !mDragging) startWave();
        poseIdle(dt > 0f && mMotionAllowed);

        float yawRad = (float) Math.toRadians(mYaw);
        float pitchRad = (float) Math.toRadians(mPitch);
        float cy = (float) Math.cos(yawRad), sy = (float) Math.sin(yawRad);
        float cp = (float) Math.cos(pitchRad), sp = (float) Math.sin(pitchRad);

        float scale = Math.min(getWidth() / MODEL_FIT_WIDTH, getHeight() / MODEL_FIT_HEIGHT);
        float originX = getWidth() / 2f;
        float originY = getHeight() / 2f + (float) (Math.sin(mAnimT * 1.6) * scale * 0.12);

        // Far parts first
        for (Part part : mParts) {
            part.depth = transformZ(part.centerX, part.centerY, part.centerZ, cy, sy, cp, sp);
        }
        Collections.sort(mParts, mDepthOrder);

        for (Part part : mParts) {
            Bitmap texture = part.cape ? mCape : mSkin;
            drawFaces(canvas, texture, part, part.baseFaces, part.baseRects, cy, sy, cp, sp, scale, originX, originY);
            if (part.hasOverlay) {
                drawFaces(canvas, texture, part, part.overlayFaces, part.overlayRects, cy, sy, cp, sp, scale, originX, originY);
            }
        }
        postInvalidateOnAnimation();
    }

    /** Idle life: arms sway opposite each other, the head looks around, the cape follows. */
    private void poseIdle(boolean alive) {
        float arm = 0f, head = 0f, cape = 0f;
        if (alive) {
            arm = (float) Math.sin(mAnimT * 2.2) * 4f;
            head = (float) Math.sin(mAnimT * 0.7) * 6f;
            cape = (float) Math.sin(mAnimT * 2.2 + 0.6) * 1.5f;
        }
        // Wave: 0..1 envelope for how far the right arm is raised, plus the hand shake
        float waveLift = 0f, waveShake = 0f, waveHeadTilt = 0f;
        if (mWaveStart >= 0f) {
            float t = mAnimT - mWaveStart;
            if (t >= WAVE_DURATION) {
                mWaveStart = -1f;
                mNextAutoWave = mAnimT + WAVE_EVERY;
            } else {
                float lower = WAVE_DURATION - WAVE_RAISE;
                if (t < WAVE_RAISE) waveLift = easeOutBack(t / WAVE_RAISE);
                else if (t > lower) waveLift = 1f - easeInOut((t - lower) / WAVE_RAISE);
                else waveLift = 1f;
                float shakeT = Math.max(0f, t - WAVE_RAISE * 0.6f);
                waveShake = (float) Math.sin(shakeT * 11.0) * 22f * waveLift;
                waveHeadTilt = (float) Math.sin(Math.min(1f, t / 0.6f) * Math.PI) * 6f;
            }
        }
        for (int i = 0; i < mParts.size(); i++) {
            Part part = mParts.get(i);
            part.animRotX = 0f;
            part.animRotY = 0f;
            part.animRotZ = 0f;
            part.animPivX = 0f;
            part.animPivY = 24f;
            part.animPivZ = 0f;
            if (i == 2) {          // right arm swings from the shoulder, or waves hello
                part.animPivX = -4f;
                if (waveLift > 0f) {
                    // Raise the arm sideways-up (Z), then shake the forearm around the raised pose
                    part.animRotZ = -150f * waveLift + waveShake;
                    part.animRotX = arm * (1f - waveLift) - 10f * waveLift;
                } else {
                    part.animRotX = arm;
                }
            } else if (i == 3) {   // left arm swings the opposite way
                part.animRotX = -arm;
                part.animPivX = 4f;
            } else if (i == 0) {   // head looks around the neck, tilts a little while waving
                part.animRotY = head;
                part.animRotZ = waveHeadTilt;
            } else if (part.cape) {
                part.animRotX = cape;
                part.animPivZ = -2f;
            }
        }
    }

    private final float[] mPt = new float[3];

    private static float easeOutBack(float x) {
        float c1 = 1.70158f, c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(x - 1, 3) + c1 * (float) Math.pow(x - 1, 2);
    }

    private static float easeInOut(float x) {
        return x < 0.5f ? 2f * x * x : 1f - (float) Math.pow(-2f * x + 2f, 2) / 2f;
    }

    /** Starts the hello wave if the model is alive and not already waving */
    public void startWave() {
        if (mParts.isEmpty() || mSkin == null) return;
        if (mWaveStart >= 0f) return;
        mWaveStart = mAnimT;
        postInvalidateOnAnimation();
    }

    private static void rotatePoint(float[] p, float rotXDeg, float rotYDeg, float rotZDeg,
                                    float px, float py, float pz) {
        if (rotZDeg != 0f) {
            double a = Math.toRadians(rotZDeg);
            float dx = p[0] - px, dy = p[1] - py;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            p[0] = px + dx * cos - dy * sin;
            p[1] = py + dx * sin + dy * cos;
        }
        if (rotXDeg != 0f) {
            double a = Math.toRadians(rotXDeg);
            float dy = p[1] - py, dz = p[2] - pz;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            p[1] = py + dy * cos - dz * sin;
            p[2] = pz + dy * sin + dz * cos;
        }
        if (rotYDeg != 0f) {
            double a = Math.toRadians(rotYDeg);
            float dx = p[0] - px, dz = p[2] - pz;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            p[0] = px + dx * cos + dz * sin;
            p[2] = pz - dx * sin + dz * cos;
        }
    }
    private static float transformZ(float x, float y, float z, float cy, float sy, float cp, float sp) {
        float z1 = -x * sy + z * cy;
        return (y - MODEL_CENTER_Y) * sp + z1 * cp;
    }

    /** Projects model space to screen space; result in out[0..2] = screenX, screenY, depth */
    private static void project(float x, float y, float z, float cy, float sy, float cp, float sp,
                                float scale, float originX, float originY, float[] out, int offset) {
        float y0 = y - MODEL_CENTER_Y;
        float x1 = x * cy + z * sy;
        float z1 = -x * sy + z * cy;
        float y2 = y0 * cp - z1 * sp;
        float z2 = y0 * sp + z1 * cp;
        out[offset] = originX + x1 * scale;
        out[offset + 1] = originY - y2 * scale;
        out[offset + 2] = z2;
    }

    private void drawFaces(Canvas canvas, Bitmap texture, Part part, float[][] faces, Rect[] rects,
                           float cy, float sy, float cp, float sp,
                           float scale, float originX, float originY) {
        for (int f = 0; f < 6; f++) {
            float[] c = faces[f];
            for (int i = 0; i < 4; i++) {
                mPt[0] = c[i * 3];
                mPt[1] = c[i * 3 + 1];
                mPt[2] = c[i * 3 + 2];
                rotatePoint(mPt, part.animRotX, part.animRotY, part.animRotZ,
                        part.animPivX, part.animPivY, part.animPivZ);
                project(mPt[0], mPt[1], mPt[2], cy, sy, cp, sp,
                        scale, originX, originY, mView, i * 3);
            }
            // Back-face culling: normal = down x right, visible when it points to the camera (+z)
            float rx = mView[3] - mView[0], ry = mView[4] - mView[1];
            float dx = mView[6] - mView[0], dy = mView[7] - mView[1];
            // Screen y points down, so the sign of the 2D cross product tells the facing
            float facing = rx * dy - ry * dx;
            if (facing <= 0.5f) continue;

            Rect src = rects[f];
            float sw = src.width(), sh = src.height();
            mSrcPoints[0] = 0;  mSrcPoints[1] = 0;
            mSrcPoints[2] = sw; mSrcPoints[3] = 0;
            mSrcPoints[4] = 0;  mSrcPoints[5] = sh;
            mDstPoints[0] = mView[0]; mDstPoints[1] = mView[1];
            mDstPoints[2] = mView[3]; mDstPoints[3] = mView[4];
            mDstPoints[4] = mView[6]; mDstPoints[5] = mView[7];
            if (!mMatrix.setPolyToPoly(mSrcPoints, 0, mDstPoints, 0, 3)) continue;

            mDst.set(0, 0, sw, sh);
            canvas.save();
            canvas.concat(mMatrix);
            canvas.drawBitmap(texture, src, mDst, mPaint);
            canvas.restore();
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                mDragging = true;
                mTouchMoved = false;
                mTouchDownX = mLastTouchX = event.getX();
                mTouchDownY = mLastTouchY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - mTouchDownX) > 8f || Math.abs(event.getY() - mTouchDownY) > 8f) mTouchMoved = true;
                mYaw += (event.getX() - mLastTouchX) * 0.6f;
                mPitch = Math.max(-30f, Math.min(45f, mPitch + (event.getY() - mLastTouchY) * 0.3f));
                mLastTouchX = event.getX();
                mLastTouchY = event.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (!mTouchMoved) {
                    performClick();
                    startWave();
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                mLastFrameTime = SystemClock.uptimeMillis();
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }
}
