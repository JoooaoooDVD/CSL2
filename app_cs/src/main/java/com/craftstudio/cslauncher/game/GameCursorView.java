package com.craftstudio.cslauncher.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.game.platform.Platform;
import com.craftstudio.cslauncher.game.platform.cursor.PlatformCursor;
import com.craftstudio.cslauncher.game.platform.cursor.PlatformCursorImplementor;

import com.craftstudio.cslauncher.R;

/**
 * A view that draws the platform cursor on the screen
 */
public class GameCursorView extends View implements PlatformCursorImplementor {
    private final Paint customCursorPaint = new Paint();
    private final Drawable cursorDrawable;
    private boolean noDraw = false;
    private float mouseScale = 1f;

    public GameCursorView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public GameCursorView(Context context) {
        this(context, null);
    }

    public GameCursorView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GameCursorView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        cursorDrawable = ContextCompat.getDrawable(context, R.drawable.ic_mouse_pointer);
        assert cursorDrawable != null;
        cursorDrawable.setBounds(0, 0, 36, 54);
        customCursorPaint.setFilterBitmap(true);
        loadCustomPointer(context);
    }

    // The player's own pointer (Settings → Cursor): drawn in place of the launcher's arrow when
    // the game shows the arrow cursor. Fitted into the same 54 px box as the default pointer so
    // the Cursor size setting means the same thing for both.
    private static final int CUSTOM_BOX = 54;
    private android.graphics.Bitmap customPointer;
    private final android.graphics.Rect customSrc = new android.graphics.Rect();
    private final android.graphics.RectF customDst = new android.graphics.RectF();

    private void loadCustomPointer(Context context) {
        try {
            android.graphics.Bitmap bmp = com.craftstudio.cslauncher.utils.cursor.CursorStore.load(context);
            if (bmp == null) return;
            float f = Math.min(CUSTOM_BOX / (float) bmp.getWidth(), CUSTOM_BOX / (float) bmp.getHeight());
            float w = bmp.getWidth() * f, h = bmp.getHeight() * f;
            float hx = com.craftstudio.cslauncher.utils.cursor.CursorStore.hotX(context) * w;
            float hy = com.craftstudio.cslauncher.utils.cursor.CursorStore.hotY(context) * h;
            customSrc.set(0, 0, bmp.getWidth(), bmp.getHeight());
            customDst.set(-hx, -hy, w - hx, h - hy);
            customPointer = bmp;
        } catch (Throwable t) {
            customPointer = null;
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (noDraw) return;
        // Scale coordinates back to the full unresized screen size
        int dx = (int) (Platform.cursorX * ((GameView)getParent()).cursorRatioX);
        int dy = (int) (Platform.cursorY * ((GameView)getParent()).cursorRatioY);
        canvas.translate(dx, dy);
        PlatformCursor cursor = Platform.getCursor();
        canvas.scale(mouseScale, mouseScale);
        if (cursor == null) {
            if (customPointer != null) canvas.drawBitmap(customPointer, customSrc, customDst, customCursorPaint);
            else cursorDrawable.draw(canvas);
        } else {
            canvas.drawBitmap(cursor.bitmap, -cursor.hotX, -cursor.hotY, customCursorPaint);
        }
    }

    @Override
    public void onCursorPosition() {
        if (!noDraw) post(this::invalidate);
    }

    @Override
    public void onCursorChanged() {
        post(this::invalidate);
    }

    @Override
    public void onGrabState(boolean isGrabbing) {
        noDraw = isGrabbing;
        invalidate();
    }

    public void setCursorScale(float scale) {
        this.mouseScale = scale;
    }
}
