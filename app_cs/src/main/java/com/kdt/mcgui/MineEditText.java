package com.kdt.mcgui;

import android.content.Context;
import android.util.AttributeSet;

import com.craftstudio.cslauncher.R;

public class MineEditText extends androidx.appcompat.widget.AppCompatEditText {
	public MineEditText(Context ctx) {
		super(ctx);
		init();
	}

	public MineEditText(Context ctx, AttributeSet attrs) {
		super(ctx, attrs);
		init();
	}

	public void init() {
		setBackgroundResource(R.drawable.bg_input_field);
	}
}
