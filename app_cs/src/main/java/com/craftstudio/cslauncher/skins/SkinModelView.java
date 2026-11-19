package com.craftstudio.cslauncher.skins;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.AnimationUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A textured 3D player model drawn with the Canvas: the six parts of the classic / slim model and
 * their overlay layers as cuboids, rotated by the finger (fling keeps spinning) and slowly turning
 * on their own otherwise, with a gentle idle sway of arms and legs. Faces are projected with a
 * light perspective and painted back to front ({@link Matrix#setPolyToPoly}: one bitmap draw per
 * visible face, nearest-neighbour so pixels stay crisp). No GL surface, so it lives happily inside
 * scrolling pages and dialogs.
 */
public class SkinModelView extends View {
    /** Standard 64x64 skin layout, in texture pixels: part box (x,y,z origin and size) and the UV origin of its net. */
    private static final class Part {
        final float x, y, z, w, h, d;   // model units (1/16 block), y up, origin at the model's feet centre
        final int u, v;                 // top-left of the part's texture net
        final int ou, ov;               // overlay net (-1 = none)
        final int swing;                // 0 none, 1 arm, -1 leg (idle sway phase)
        Part(float x, float y, float z, float w, float h, float d, int u, int v, int ou, int ov, int swing) {
            this.x = x; this.y = y; this.z = z; this.w = w; this.h = h; this.d = d;
            this.u = u; this.v = v; this.ou = ou; this.ov = ov; this.swing = swing;
        }
    }

    private static final class Face {
        final float[] pts = new float[8];
        Bitmap tex;
        float depth, light;
    }

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShade = new Paint();
    private final Matrix mMatrix = new Matrix();
    private final List<Face> mFaces = new ArrayList<>(96);
    private final float[] mTmp = new float[3];

    @Nullable private Bitmap mSkin;
    private boolean mSlim;
    private final List<Part> mParts = new ArrayList<>(6);
    private final List<Bitmap[]> mPartFaces = new ArrayList<>(6);      // 6 textures per part (base)
    private final List<Bitmap[]> mPartOverlay = new ArrayList<>(6);    // 6 textures per part (overlay, may be null)

    private float mYaw = -28f, mPitch = 12f;
    private float mSpin = 0.35f;            // degrees per frame when idle
    private float mFling;                   // degrees per frame after a fling
    private float mIdle;                    // idle sway phase
    private boolean mDragging, mAutoSpin = true;
    private float mLastX, mLastY;
    private VelocityTracker mVelocity;
    private long mLastFrame;

    public SkinModelView(Context context) { this(context, null); }
    public SkinModelView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mPaint.setFilterBitmap(false);
        mPaint.setDither(false);
        mShade.setStyle(Paint.Style.FILL);
    }

    /** A 64x64 (or HD multiple) skin as {@link SkinTextures#normalizeSkin} returns it. */
    public void setSkin(@Nullable Bitmap skin, boolean slim) {
        mSkin = skin;
        mSlim = slim;
        rebuild();
        invalidate();
    }

    public void setAutoSpin(boolean on) { mAutoSpin = on; invalidate(); }

    private void rebuild() {
        mParts.clear(); mPartFaces.clear(); mPartOverlay.clear();
        if (mSkin == null) return;
        int scale = Math.max(1, mSkin.getWidth() / 64);
        float aw = mSlim ? 3f : 4f;
        // Parts: head, body, right arm, left arm, right leg, left leg. Model x goes to the viewer's right.
        mParts.add(new Part(-4, 24, -4, 8, 8, 8, 0, 0, 32, 0, 0));
        mParts.add(new Part(-4, 12, -2, 8, 12, 4, 16, 16, 16, 32, 0));
        mParts.add(new Part(-4 - aw, 12, -2, aw, 12, 4, 40, 16, 40, 32, 1));
        mParts.add(new Part(4, 12, -2, aw, 12, 4, 32, 48, 48, 48, -1));
        mParts.add(new Part(-4, 0, -2, 4, 12, 4, 0, 16, 0, 32, -1));
        mParts.add(new Part(0, 0, -2, 4, 12, 4, 16, 48, 0, 48, 1));
        for (Part p : mParts) {
            mPartFaces.add(net(mSkin, scale, p.u, p.v, Math.round(p.w), Math.round(p.h), Math.round(p.d)));
            mPartOverlay.add(p.ou < 0 ? null : net(mSkin, scale, p.ou, p.ov, Math.round(p.w), Math.round(p.h), Math.round(p.d)));
        }
    }

    /** The six faces of a box net: top, bottom, right(+x), front(+z), left(-x), back(-z). */
    @Nullable
    private static Bitmap[] net(Bitmap skin, int s, int u, int v, int w, int h, int d) {
        try {
            Bitmap[] f = new Bitmap[6];
            f[0] = sub(skin, s, u + d, v, w, d);
            f[1] = sub(skin, s, u + d + w, v, w, d);
            f[2] = sub(skin, s, u + d + w, v + d, d, h);   // viewer's right side of the part (model +x)
            f[3] = sub(skin, s, u + d, v + d, w, h);       // front
            f[4] = sub(skin, s, u, v + d, d, h);           // viewer's left side (model -x)
            f[5] = sub(skin, s, u + 2 * d + w, v + d, w, h); // back
            boolean any = false;
            for (Bitmap b : f) if (b != null && !isTransparent(b)) { any = true; break; }
            return any ? f : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static Bitmap sub(Bitmap skin, int s, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return null;
        if ((x + w) * s > skin.getWidth() || (y + h) * s > skin.getHeight()) return null;
        return Bitmap.createBitmap(skin, x * s, y * s, w * s, h * s);
    }

    private static boolean isTransparent(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int p : px) if ((p >>> 24) > 8) return false;
        return true;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragging = true;
                mFling = 0f;
                mLastX = e.getX(); mLastY = e.getY();
                if (mVelocity == null) mVelocity = VelocityTracker.obtain(); else mVelocity.clear();
                mVelocity.addMovement(e);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!mDragging) return false;
                mVelocity.addMovement(e);
                float dx = e.getX() - mLastX, dy = e.getY() - mLastY;
                mLastX = e.getX(); mLastY = e.getY();
                mYaw += dx * 0.6f;
                mPitch = clamp(mPitch - dy * 0.3f, -35f, 45f);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mVelocity != null) {
                    mVelocity.computeCurrentVelocity(1000);
                    mFling = clamp(mVelocity.getXVelocity() * 0.6f / 60f, -18f, 18f);
                    mVelocity.recycle(); mVelocity = null;
                }
                mDragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : Math.min(v, hi); }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (mSkin == null || mParts.isEmpty()) return;
        long now = AnimationUtils.currentAnimationTimeMillis();
        float dt = mLastFrame == 0 ? 1f : Math.min(3f, (now - mLastFrame) / 16.67f);
        mLastFrame = now;
        if (!mDragging) {
            if (Math.abs(mFling) > 0.05f) {
                mYaw += mFling * dt;
                mFling *= (float) Math.pow(0.94, dt);
            } else if (mAutoSpin) {
                mYaw += mSpin * dt;
            }
            mIdle += 0.045f * dt;
        }
        if (mYaw > 360f) mYaw -= 360f; else if (mYaw < -360f) mYaw += 360f;

        int W = getWidth(), H = getHeight();
        float pad = Math.min(W, H) * 0.06f;
        // The model is 32 units tall (with a hat 33+); fit it with a little air.
        float unit = Math.min((H - 2 * pad) / 36f, (W - 2 * pad) / 24f);
        float cx = W / 2f, cy = H / 2f + 16f * unit; // feet-centred origin 16 units below the middle
        double yaw = Math.toRadians(mYaw), pitch = Math.toRadians(mPitch);
        float sy = (float) Math.sin(yaw), cyaw = (float) Math.cos(yaw);
        float sp = (float) Math.sin(pitch), cp = (float) Math.cos(pitch);
        float focal = 60f; // units; a touch of perspective
        float sway = (float) Math.sin(mIdle) * 4f;

        mFaces.clear();
        for (int i = 0; i < mParts.size(); i++) {
            Part p = mParts.get(i);
            Bitmap[] base = mPartFaces.get(i);
            Bitmap[] over = mPartOverlay.get(i);
            float swing = p.swing == 0 ? 0f : sway * p.swing;
            if (base != null) emit(p, base, 0f, swing, sy, cyaw, sp, cp, unit, cx, cy, focal);
            if (over != null) emit(p, over, p.swing == 0 && p.y >= 24 ? 0.5f : 0.25f, swing, sy, cyaw, sp, cp, unit, cx, cy, focal);
        }
        Collections.sort(mFaces, (a, b) -> Float.compare(a.depth, b.depth)); // far (small z) first
        for (Face f : mFaces) {
            if (f.tex == null) continue;
            mMatrix.setPolyToPoly(new float[]{0, 0, f.tex.getWidth(), 0, f.tex.getWidth(), f.tex.getHeight(), 0, f.tex.getHeight()},
                    0, f.pts, 0, 4);
            canvas.drawBitmap(f.tex, mMatrix, mPaint);
            if (f.light < 0.98f) {
                int a = Math.round((1f - f.light) * 255f);
                mShade.setColor(Color.argb(a, 0, 0, 0));
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(f.pts[0], f.pts[1]); path.lineTo(f.pts[2], f.pts[3]);
                path.lineTo(f.pts[4], f.pts[5]); path.lineTo(f.pts[6], f.pts[7]); path.close();
                canvas.save();
                canvas.clipPath(path);
                // Shade only where the texture has pixels: draw the texture again as a mask via DST_IN is
                // costly; a translucent fill clipped to the quad is enough for solid faces, and the
                // overlay faces (transparent parts) are drawn without shade (light == 1 below).
                canvas.drawPath(path, mShade);
                canvas.restore();
            }
        }
        if (!mDragging && (mAutoSpin || Math.abs(mFling) > 0.05f)) postInvalidateOnAnimation();
    }

    /** Projects one cuboid (with an optional overlay inflation) and queues its visible faces. */
    private void emit(Part p, Bitmap[] tex, float inflate, float swing, float sy, float cyaw, float sp, float cp,
                      float unit, float cx, float cy, float focal) {
        float x0 = p.x - inflate, x1 = p.x + p.w + inflate;
        float y0 = p.y - inflate, y1 = p.y + p.h + inflate;
        float z0 = p.z - inflate, z1 = p.z + p.d + inflate;
        // 8 corners, then rotate around the part's pivot for the sway (shoulders / hips at the top of the limb)
        float[][] c = {
                {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1},  // top ring  (0..3)
                {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}}; // bottom ring (4..7)
        float pivotY = p.y + p.h;
        double sw = Math.toRadians(swing);
        float ss = (float) Math.sin(sw), cs = (float) Math.cos(sw);
        float[][] out = new float[8][3];
        for (int i = 0; i < 8; i++) {
            float x = c[i][0], y = c[i][1], z = c[i][2];
            if (swing != 0f) { // rotate about the x axis through the pivot
                float dy = y - pivotY, dz = z - (p.z + p.d / 2f);
                y = pivotY + dy * cs - dz * ss;
                z = (p.z + p.d / 2f) + dy * ss + dz * cs;
            }
            y -= 16f; // rotate about the model's centre
            float rx = x * cyaw + z * sy, rz = -x * sy + z * cyaw;       // yaw (+ turns the front to the right)
            float ry = y * cp - rz * sp, rz2 = y * sp + rz * cp;        // pitch
            out[i][0] = rx; out[i][1] = ry; out[i][2] = rz2;
        }
        // faces: top, bottom, +x (viewer's right), front (+z), -x, back (-z); corner order = texture TL,TR,BR,BL
        int[][] idx = {
                {0, 1, 2, 3}, {7, 6, 5, 4}, {2, 1, 5, 6}, {3, 2, 6, 7}, {0, 3, 7, 4}, {1, 0, 4, 5}};
        float[] lights = {1f, 0.55f, 0.78f, 0.95f, 0.78f, 0.68f};
        boolean overlay = inflate > 0f;
        for (int f = 0; f < 6; f++) {
            if (tex[f] == null) continue;
            int[] q = idx[f];
            // back-face cull in screen space (after projection)
            Face face = new Face();
            float depth = 0f;
            for (int k = 0; k < 4; k++) {
                float[] v = out[q[k]];
                float persp = focal / (focal + 24f - v[2]); // camera on +z
                face.pts[k * 2] = cx + v[0] * unit * persp;
                face.pts[k * 2 + 1] = cy - (v[1] + 16f) * unit * persp;
                depth += v[2];
            }
            float ax = face.pts[2] - face.pts[0], ay = face.pts[3] - face.pts[1];
            float bx = face.pts[6] - face.pts[0], by = face.pts[7] - face.pts[1];
            if (ax * by - ay * bx <= 0f) continue; // facing away (texture nets are laid out so visible faces wind clockwise)
            face.tex = tex[f];
            face.depth = depth / 4f;
            face.light = overlay ? 1f : lights[f];
            mFaces.add(face);
        }
    }
}
