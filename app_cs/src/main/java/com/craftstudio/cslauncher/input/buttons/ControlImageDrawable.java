package com.craftstudio.cslauncher.input.buttons;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Background used by controls that carry a custom image.
 * <p>
 * The bitmap is scaled centre-crop into the control bounds, clipped to the control corner
 * radius, and the regular stroke is drawn on top so an image button looks like every other
 * button of the layout instead of a raw rectangle.
 */
public class ControlImageDrawable extends Drawable {
    private final Paint mBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mShaderMatrix = new Matrix();
    private final RectF mRect = new RectF();
    private final RectF mStrokeRect = new RectF();

    private Bitmap mBitmap;
    private float mCornerRadius;
    private float mStrokeWidth;
    private int mStrokeColor;
    private int mAlpha = 255;

    public ControlImageDrawable(@Nullable Bitmap bitmap, float cornerRadius, float strokeWidth, int strokeColor) {
        mStrokePaint.setStyle(Paint.Style.STROKE);
        update(bitmap, cornerRadius, strokeWidth, strokeColor);
    }

    /** Re-configures the drawable in place, avoiding re-allocation while the user drags sliders */
    public void update(@Nullable Bitmap bitmap, float cornerRadius, float strokeWidth, int strokeColor) {
        if (bitmap != mBitmap) {
            mBitmap = bitmap;
            mBitmapPaint.setShader(bitmap == null ? null
                    : new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            updateShaderMatrix();
        }
        mCornerRadius = cornerRadius;
        mStrokeWidth = strokeWidth;
        mStrokeColor = strokeColor;
        mStrokePaint.setStrokeWidth(strokeWidth);
        mStrokePaint.setColor(strokeColor);
        invalidateSelf();
    }

    public Bitmap getBitmap() {
        return mBitmap;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        mRect.set(bounds);
        updateShaderMatrix();
    }

    private void updateShaderMatrix() {
        Shader shader = mBitmapPaint.getShader();
        if (shader == null || mBitmap == null || mRect.isEmpty()) return;
        float bw = mBitmap.getWidth(), bh = mBitmap.getHeight();
        float scale = Math.max(mRect.width() / bw, mRect.height() / bh);
        float dx = mRect.left + (mRect.width() - bw * scale) / 2f;
        float dy = mRect.top + (mRect.height() - bh * scale) / 2f;
        mShaderMatrix.reset();
        mShaderMatrix.setScale(scale, scale);
        mShaderMatrix.postTranslate(dx, dy);
        shader.setLocalMatrix(mShaderMatrix);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mRect.isEmpty()) return;
        float radius = Math.min(mCornerRadius, Math.min(mRect.width(), mRect.height()) / 2f);
        if (mBitmap != null) {
            canvas.drawRoundRect(mRect, radius, radius, mBitmapPaint);
        }
        if (mStrokeWidth > 0.5f && (mStrokeColor >>> 24) != 0) {
            float inset = mStrokeWidth / 2f;
            mStrokeRect.set(mRect.left + inset, mRect.top + inset, mRect.right - inset, mRect.bottom - inset);
            float strokeRadius = Math.max(0f, radius - inset);
            canvas.drawRoundRect(mStrokeRect, strokeRadius, strokeRadius, mStrokePaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        mAlpha = alpha;
        mBitmapPaint.setAlpha(alpha);
        mStrokePaint.setAlpha(alpha * (mStrokeColor >>> 24) / 255);
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mBitmapPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
