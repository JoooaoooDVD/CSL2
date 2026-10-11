package com.craftstudio.cslauncher;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.gson.JsonSyntaxException;

import com.craftstudio.cslauncher.input.ControlData;
import com.craftstudio.cslauncher.input.ControlDrawerData;
import com.craftstudio.cslauncher.input.ControlJoystickData;
import com.craftstudio.cslauncher.input.ControlLayout;
import com.craftstudio.cslauncher.input.EditorExitable;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.utils.CropperUtils;

import java.io.IOException;


import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.Motion;

public class CustomControlsActivity extends BaseActivity implements EditorExitable, CropperUtils.CropperReceiver {
	private DrawerLayout mDrawerLayout;
	private View mDrawerNavigationView;
	private ControlLayout mControlLayout;
	private CropperUtils.CropperReceiver mCropperReceiver;
	private ActivityResultLauncher<?> mCropperLauncher;
	private View mHint;
	private TextView mHintText;
	private TextView mLayoutName;
	private final Handler mHandler = new Handler(Looper.getMainLooper());
	private final Runnable mHideHint = () -> Motion.hide(mHint, true);

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		mCropperLauncher = CropperUtils.registerCropper(this, this);

		setContentView(R.layout.activity_custom_controls);

		mControlLayout = findViewById(R.id.customctrl_controllayout);
		mDrawerLayout = findViewById(R.id.customctrl_drawerlayout);
		mDrawerNavigationView = findViewById(R.id.customctrl_navigation_view);
		mHint = findViewById(R.id.ce_hint);
		mHintText = (TextView) mHint;
		mLayoutName = findViewById(R.id.ce_layout_name);
		View handle = findViewById(R.id.drawer_button);
		CSAnimationEngine.attachPressFeedback(handle);

		handle.setOnClickListener(v -> openPanel());
		mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
		mDrawerLayout.setScrimColor(0x8C030712);
		mDrawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
			@Override
			public void onDrawerClosed(@NonNull View drawerView) {
				// The handle comes back once the panel is gone
				handle.setVisibility(View.VISIBLE);
				Motion.popIn(handle, 0, 0.7f);
			}
		});

		// Panel actions
		bindAction(R.id.ce_add_button, () -> {
			mControlLayout.addControlButton(new ControlData("New"));
			showHint(R.string.ce_added, 3500);
		});
		bindAction(R.id.ce_add_drawer, () -> {
			mControlLayout.addDrawer(new ControlDrawerData());
			showHint(R.string.ce_added, 3500);
		});
		bindAction(R.id.ce_add_joystick, () -> {
			mControlLayout.addJoystickButton(new ControlJoystickData());
			showHint(R.string.ce_added, 3500);
		});
		bindAction(R.id.ce_load, () -> mControlLayout.openLoadDialog());
		bindAction(R.id.ce_save, () -> mControlLayout.openSaveDialog(this));
		bindAction(R.id.ce_default, () -> mControlLayout.openSetDefaultDialog());
		bindAction(R.id.ce_export, this::exportLayout);
		bindAction(R.id.ce_exit, () -> mControlLayout.askToExit(this));
		// Hint: shown on start, goes away by itself (or when tapped)
		mHint.setOnClickListener(v -> {
			mHandler.removeCallbacks(mHideHint);
			Motion.hide(mHint, true);
		});
		Motion.riseIn(handle, 120, -10);
		Motion.fadeIn(mHint, 400, Motion.MEDIUM);
		mHandler.postDelayed(mHideHint, 6000);

		mControlLayout.setModifiable(true);
	}

	/** Panel action: closes the panel first, then runs the action */
	private void bindAction(int id, Runnable action) {
		View view = findViewById(id);
		CSAnimationEngine.attachPressFeedback(view);
		view.setOnClickListener(v -> {
			mDrawerLayout.closeDrawers();
			action.run();
		});
	}

	/** Opens the right panel with a staggered entrance of its sections */
	private void openPanel() {
		if (mControlLayout.mLayoutFileName != null && !mControlLayout.mLayoutFileName.isEmpty()) {
			String name = new java.io.File(mControlLayout.mLayoutFileName).getName();
			if (name.endsWith(".json")) name = name.substring(0, name.length() - 5);
			mLayoutName.setVisibility(View.VISIBLE);
			mLayoutName.setText(getString(R.string.ce_layout_name, name));
		} else {
			mLayoutName.setVisibility(View.GONE);
		}
		mHandler.removeCallbacks(mHideHint);
		Motion.hide(mHint, true);
		Motion.hide(findViewById(R.id.drawer_button), false);
		mDrawerLayout.openDrawer(mDrawerNavigationView);
		ViewGroup content = findViewById(R.id.ce_panel_content);
		Motion.cascadeChildren(content, 80, 28, 10);
	}

	private void showHint(int textRes, long durationMs) {
		mHandler.removeCallbacks(mHideHint);
		mHintText.setText(textRes);
		mHint.setVisibility(View.VISIBLE);
		Motion.fadeIn(mHint, 250, Motion.MEDIUM);
		mHandler.postDelayed(mHideHint, durationMs);
	}

	/** Shares the currently shown layout as a .json file */
	private void exportLayout() {
		try {
			Uri contentUri = DocumentsContract.buildDocumentUri(getString(R.string.storageProviderAuthorities), mControlLayout.saveToDirectory(mControlLayout.mLayoutFileName));

			Intent shareIntent = new Intent();
			shareIntent.setAction(Intent.ACTION_SEND);
			shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
			shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			shareIntent.setType("application/json");
			// One chooser only (the share sheet used to open twice)
			startActivity(Intent.createChooser(shareIntent, mControlLayout.mLayoutFileName));
		} catch (Exception e) {
			Tools.showError(this, e);
		}
	}

	@Override
	protected void onDestroy() {
		mHandler.removeCallbacksAndMessages(null);
		super.onDestroy();
	}

	@Override
	public void onAttachedToWindow() {
		mControlLayout.post(()->{
			try {
				mControlLayout.loadLayout(LauncherPreferences.PREF_DEFAULTCTRL_PATH);
			}catch (IOException | JsonSyntaxException e) {
				Tools.showError(this, e);
			}
		});
	}

	public void startCropping(CropperUtils.CropperReceiver cropperReceiver) {
		mCropperReceiver = cropperReceiver;
		CropperUtils.startCropper(mCropperLauncher);
	}

	@Override
	public void onBackPressed() {
		if (mDrawerLayout != null && mDrawerLayout.isDrawerOpen(mDrawerNavigationView)) {
			mDrawerLayout.closeDrawers();
			return;
		}
		mControlLayout.askToExit(this);
	}

	@Override
	public void exitEditor() {
		super.onBackPressed();
	}

	@Override
	public float getAspectRatio() {
		if(mCropperReceiver != null) return mCropperReceiver.getAspectRatio();
		return 1f;
	}

	@Override
	public int getTargetMaxSide() {
		if(mCropperReceiver != null) return mCropperReceiver.getTargetMaxSide();
		return 128;
	}

	@Override
	public void onCropped(Bitmap contentBitmap) {
		if(mCropperReceiver != null) mCropperReceiver.onCropped(contentBitmap);
	}

	@Override
	public void onFailed(Exception exception) {
		if(mCropperReceiver != null) mCropperReceiver.onFailed(exception);
	}
}
