package com.kdt.mcgui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.skins.PlayerPreviewView;
import com.craftstudio.cslauncher.skins.PremiumProfileLookup;
import com.craftstudio.cslauncher.skins.SkinTextures;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.regex.Pattern;

/**
 * Skin editor: type a premium player's name, see their skin + cape in 3D, import either or both.
 * The import itself (library writes, applying) is done by the caller.
 */
public class PlayerImportDialog extends CSPopupDialog {

    public interface OnImport {
        /** Runs on the UI thread; {@code result} is FOUND and has a skin and/or cape */
        void onImport(@NonNull PremiumProfileLookup.Result result, boolean skin, boolean cape);
    }

    private static final Pattern VALID = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");
    private static final long DELAY = 600L;

    private final boolean mAllowCape;
    private final OnImport mOnImport;
    private EditText mName;
    private TextView mStatus, mTag, mModel, mCapeSub, mImportText;
    private View mSpinner, mPreviewEmpty, mImport, mOptCape;
    private ImageView mFace;
    private PlayerPreviewView mPreview;
    private MaterialSwitch mUseSkin, mUseCape;
    @Nullable private PremiumProfileLookup.Result mResult;
    @Nullable private String mLookedUp;
    private int mSerial;
    private boolean mImporting;
    private final Runnable mLookup = this::startLookup;

    public PlayerImportDialog(@NonNull Context context, boolean allowCape, @NonNull OnImport onImport) {
        super(context);
        mAllowCape = allowCape;
        mOnImport = onImport;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_player_import);
        View card = findViewById(R.id.pi_card);
        setupPopup(findViewById(R.id.pi_scrim), card, true);
        sizeCard(card);

        mName = findViewById(R.id.pi_name);
        mStatus = findViewById(R.id.pi_status);
        mTag = findViewById(R.id.pi_preview_tag);
        mModel = findViewById(R.id.pi_opt_skin_model);
        mCapeSub = findViewById(R.id.pi_opt_cape_sub);
        mImportText = findViewById(R.id.pi_import_text);
        mSpinner = findViewById(R.id.pi_spinner);
        mPreviewEmpty = findViewById(R.id.pi_preview_empty);
        mImport = findViewById(R.id.pi_import);
        mOptCape = findViewById(R.id.pi_opt_cape);
        mFace = findViewById(R.id.pi_face);
        mPreview = findViewById(R.id.pi_preview);
        mUseSkin = findViewById(R.id.pi_use_skin);
        mUseCape = findViewById(R.id.pi_use_cape);

        View close = findViewById(R.id.pi_close);
        close.setOnClickListener(v -> onDismissRequested());
        CSAnimationEngine.attachPressFeedback(close);
        CSAnimationEngine.attachPressFeedback(mImport);

        if (!mAllowCape) {
            mUseCape.setChecked(false);
            mUseCape.setEnabled(false);
            mCapeSub.setText(R.string.pi_opt_cape_ms);
        }
        mUseSkin.setOnCheckedChangeListener((b, c) -> updateImportState());
        mUseCape.setOnCheckedChangeListener((b, c) -> updateImportState());

        mName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { onTyped(s.toString()); }
        });
        mName.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { mName.removeCallbacks(mLookup); mLookedUp = null; startLookup(); return true; }
            return false;
        });
        mImport.setOnClickListener(v -> doImport());
        updateImportState();

        CreatePopupMotion.chooserContent(findViewById(R.id.pi_header), new View[0], findViewById(R.id.pi_columns));
        card.post(() -> adapt(card));
        mName.requestFocus();
    }

    private void onTyped(@NonNull String name) {
        mName.removeCallbacks(mLookup);
        if (!VALID.matcher(name).matches()) {
            mSerial++;
            mResult = null;
            mLookedUp = null;
            mSpinner.setVisibility(View.GONE);
            mStatus.setText(name.isEmpty() ? R.string.pi_status_idle : R.string.pi_status_short);
            clearPreview();
            updateImportState();
            return;
        }
        if (!name.equalsIgnoreCase(mLookedUp)) mName.postDelayed(mLookup, DELAY);
    }

    private void startLookup() {
        final String name = mName.getText().toString();
        if (!VALID.matcher(name).matches()) return;
        final int serial = ++mSerial;
        mLookedUp = name;
        mResult = null;
        clearPreview();
        updateImportState();
        mSpinner.setVisibility(View.VISIBLE);
        mStatus.setText(getContext().getString(R.string.pi_status_checking, name));
        CSLauncherApplication.sExecutorService.execute(() -> {
            final PremiumProfileLookup.Result result = PremiumProfileLookup.lookup(name);
            Tools.runOnUiThread(() -> {
                if (serial != mSerial || !isShowing()) return;
                mSpinner.setVisibility(View.GONE);
                onLookup(result, name);
            });
        });
    }

    private void onLookup(@NonNull PremiumProfileLookup.Result result, @NonNull String typed) {
        if (result.status == PremiumProfileLookup.Status.ERROR) {
            mLookedUp = null; // retype / search again retries
            mStatus.setText(R.string.pi_status_error);
            Motion.shake(mStatus);
            return;
        }
        if (!result.found() || result.skinPng == null) {
            mStatus.setText(getContext().getString(R.string.pi_status_none, typed));
            Motion.shake(mStatus);
            return;
        }
        mResult = result;
        mStatus.setText(getContext().getString(result.capePng != null ? R.string.pi_status_found_cape : R.string.pi_status_found, result.name));
        if (result.face != null) {
            mFace.setImageTintList(null);
            mFace.setImageBitmap(result.face);
            Motion.popIn(mFace, 0L, 0.4f);
        }
        Bitmap skin = BitmapFactory.decodeByteArray(result.skinPng, 0, result.skinPng.length);
        if (skin != null) mPreview.setSkin(SkinTextures.normalizeSkin(skin), result.slim);
        Bitmap cape = result.capePng == null ? null : BitmapFactory.decodeByteArray(result.capePng, 0, result.capePng.length);
        mPreview.setCape(cape == null ? null : SkinTextures.normalizeCape(cape));
        mPreviewEmpty.setVisibility(View.GONE);
        mTag.setText(result.name.toUpperCase());
        mTag.setVisibility(View.VISIBLE);
        Motion.popIn(mTag, 0L, 0.6f);
        Motion.popIn(mPreview, 0L, 0.9f);
        mModel.setText(result.slim ? R.string.pi_model_slim : R.string.pi_model_classic);
        mModel.setVisibility(View.VISIBLE);
        if (mAllowCape) {
            boolean hasCape = result.capePng != null;
            mUseCape.setEnabled(hasCape);
            mUseCape.setChecked(hasCape);
            mCapeSub.setText(hasCape ? R.string.pi_opt_cape_local : R.string.pi_opt_cape_none);
        }
        updateImportState();
    }

    private void clearPreview() {
        mPreviewEmpty.setVisibility(View.VISIBLE);
        mTag.setVisibility(View.GONE);
        mModel.setVisibility(View.GONE);
        mPreview.setSkin(null, false);
        mPreview.setCape(null);
        mFace.setImageResource(R.drawable.ic_pe_person);
        mFace.setImageTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(getContext(), R.color.hm_text_dim)));
        if (mAllowCape) { mUseCape.setEnabled(true); mUseCape.setChecked(true); mCapeSub.setText(R.string.pi_opt_cape_local); }
    }

    private boolean canImport() {
        if (mResult == null || mImporting) return false;
        boolean skin = mUseSkin.isChecked() && mResult.skinPng != null;
        boolean cape = mAllowCape && mUseCape.isChecked() && mResult.capePng != null;
        return skin || cape;
    }

    private void updateImportState() {
        mImport.animate().alpha(canImport() ? 1f : 0.5f).setDuration(160L).start();
    }

    private void doImport() {
        if (!canImport()) { Motion.shake(mImport); return; }
        final PremiumProfileLookup.Result result = mResult;
        final boolean skin = mUseSkin.isChecked() && result.skinPng != null;
        final boolean cape = mAllowCape && mUseCape.isChecked() && result.capePng != null;
        mImporting = true;
        mImportText.setText(R.string.pi_importing);
        CreatePopupMotion.optionChosen(mImport, () -> close(() -> mOnImport.onImport(result, skin, cape)));
    }

    /** Narrow screens: preview above the form */
    private void adapt(@NonNull View card) {
        float density = getContext().getResources().getDisplayMetrics().density;
        if (card.getWidth() / density >= 500f) return;
        LinearLayout columns = findViewById(R.id.pi_columns);
        columns.setOrientation(LinearLayout.VERTICAL);
        View preview = findViewById(R.id.pi_preview_wrap);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (170 * density));
        preview.setLayoutParams(p);
        View form = findViewById(R.id.pi_form);
        LinearLayout.LayoutParams f = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        f.topMargin = (int) (12 * density);
        form.setLayoutParams(f);
    }

    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = Math.min((int) (560 * d), metrics.widthPixels - (int) (32 * d));
        if (metrics.heightPixels / d < 460f) {
            // Phone landscape: take the full height and let the content scroll
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            View scroll = findViewById(R.id.pi_scroll);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
            sp.weight = 1f;
            scroll.setLayoutParams(sp);
        }
        card.setLayoutParams(params);
    }

    @Override
    protected void onDismissRequested() {
        if (mName != null) mName.removeCallbacks(mLookup);
        mSerial++;
        super.onDismissRequested();
    }
}
