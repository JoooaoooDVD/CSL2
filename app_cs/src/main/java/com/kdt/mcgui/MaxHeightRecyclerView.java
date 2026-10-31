package com.kdt.mcgui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerView with a programmatic height cap. Used by popups whose card wraps its content:
 * a long list (60+ loader versions) must scroll inside the card instead of pushing the card
 * past the screen edges, where its header and first rows become unreachable.
 */
public class MaxHeightRecyclerView extends RecyclerView {
    private int mMaxHeight = Integer.MAX_VALUE;

    public MaxHeightRecyclerView(Context context) { super(context); }
    public MaxHeightRecyclerView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
    public MaxHeightRecyclerView(Context context, @Nullable AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); }

    public void setMaxHeight(int px) {
        if (px <= 0 || px == mMaxHeight) return;
        mMaxHeight = px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int mode = MeasureSpec.getMode(heightSpec);
        int size = MeasureSpec.getSize(heightSpec);
        if (mMaxHeight != Integer.MAX_VALUE && (mode == MeasureSpec.UNSPECIFIED || size > mMaxHeight)) {
            heightSpec = MeasureSpec.makeMeasureSpec(mMaxHeight, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
