package com.craftstudio.cslauncher.game.resources;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

import com.craftstudio.cslauncher.R;

/** ImageView that clips its drawable to the card icon radius (no bitmap copies, cheap for lists). */
public class RoundedImageView extends AppCompatImageView {
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();
    private float mRadius;

    public RoundedImageView(Context context) { this(context, null); }
    public RoundedImageView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public RoundedImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mRadius = getResources().getDimension(R.dimen.rb_icon_radius);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mRect.set(0, 0, w, h);
        mPath.reset();
        mPath.addRoundRect(mRect, mRadius, mRadius, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getDrawable() == null) return;
        int saved = canvas.save();
        canvas.clipPath(mPath);
        super.onDraw(canvas);
        canvas.restoreToCount(saved);
    }
}
