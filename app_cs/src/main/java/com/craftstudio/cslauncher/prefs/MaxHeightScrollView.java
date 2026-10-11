package com.craftstudio.cslauncher.prefs;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/** ScrollView that wraps its content up to a maximum height (set in code), then scrolls */
public class MaxHeightScrollView extends ScrollView {
    private int mMaxHeight = Integer.MAX_VALUE;

    public MaxHeightScrollView(Context context) {
        super(context);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setMaxHeight(int maxHeight) {
        if (mMaxHeight == maxHeight) return;
        mMaxHeight = maxHeight;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        int size = MeasureSpec.getSize(heightMeasureSpec);
        int limit = mode == MeasureSpec.UNSPECIFIED ? mMaxHeight : Math.min(size, mMaxHeight);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
    }
}
