package com.craftstudio.cslauncher.modding.modpacks.imagecache;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * GIF playback for Android < 9 (where {@link android.graphics.ImageDecoder} is not available),
 * backed by the legacy {@link Movie} decoder. Frames are drawn on demand; the drawable
 * schedules itself with {@link #invalidateSelf()} so it only costs CPU while visible.
 */
@SuppressWarnings("deprecation")
public final class MovieDrawable extends Drawable implements Animatable {
    private final Movie mMovie;
    private final int mDuration;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private long mStart;
    private boolean mRunning;

    public MovieDrawable(@NonNull Movie movie) {
        mMovie = movie;
        mDuration = Math.max(1, movie.duration());
    }

    @Override public int getIntrinsicWidth() { return mMovie.width(); }
    @Override public int getIntrinsicHeight() { return mMovie.height(); }

    @Override
    public void draw(@NonNull Canvas canvas) {
        int w = mMovie.width(), h = mMovie.height();
        if (w <= 0 || h <= 0) return;
        if (mRunning) {
            long now = SystemClock.uptimeMillis();
            if (mStart == 0) mStart = now;
            mMovie.setTime((int) ((now - mStart) % mDuration));
        } else {
            mMovie.setTime(0);
        }
        android.graphics.Rect b = getBounds();
        int save = canvas.save();
        canvas.translate(b.left, b.top);
        canvas.scale(b.width() / (float) w, b.height() / (float) h);
        mMovie.draw(canvas, 0, 0, mPaint);
        canvas.restoreToCount(save);
        if (mRunning) scheduleSelf(mTick, SystemClock.uptimeMillis() + 40); // ~25 fps is plenty for icons
    }

    private final Runnable mTick = this::invalidateSelf;

    @Override public void start() { if (mRunning) return; mRunning = true; mStart = 0; invalidateSelf(); }
    @Override public void stop() { mRunning = false; unscheduleSelf(mTick); invalidateSelf(); }
    @Override public boolean isRunning() { return mRunning; }
    @Override public void setAlpha(int alpha) { mPaint.setAlpha(alpha); }
    @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { mPaint.setColorFilter(colorFilter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (!visible) unscheduleSelf(mTick); else if (mRunning) invalidateSelf();
        return changed;
    }
}
