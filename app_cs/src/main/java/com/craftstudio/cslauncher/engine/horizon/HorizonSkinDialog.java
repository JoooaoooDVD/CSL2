package com.craftstudio.cslauncher.engine.horizon;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.skins.LocalSkinStore;
import com.craftstudio.cslauncher.skins.PlayerPreviewView;
import com.craftstudio.cslauncher.skins.SkinEditorFragment;
import com.craftstudio.cslauncher.skins.SkinLibrary;
import com.craftstudio.cslauncher.skins.SkinTextures;

import java.io.File;
import java.util.List;

/**
 * In-game "Account · Skin &amp; Cape" for CS Engine: the launcher's skin library and local capes
 * ({@link SkinLibrary}, {@link LocalSkinStore}, {@link PlayerPreviewView}) for the running profile.
 * Apply saves the choice per profile ({@link HorizonSkins#save}) and hot-switches the running engine
 * ({@link HorizonSkins#pushToEngine}); the next launch gets it from {@code cs_launch}.
 * Microsoft accounts: the official skin stays (the engine prefers the profile's textures); the page says so.
 */
final class HorizonSkinDialog extends Dialog {
    interface ImportRequester {
        /** Ask the activity to open the system file picker; the result comes back over {@link #onPicked}. */
        void request(boolean cape);
    }

    private static final int BG = 0xFF101014, CARD = 0xFF16161A, STROKE = 0xFF2A2A30, TEXT = 0xFFF2F5FA, ACCENT = 0xFFC6C6CE;

    private final String mProfileKey;
    private final boolean mMicrosoft;
    private final ImportRequester mImporter;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private PlayerPreviewView mPreview;
    private LinearLayout mSkinRow, mCapeRow;
    private TextView mSteve, mAlex, mModelHint;
    @Nullable private SkinLibrary.Entry mSkin;
    @Nullable private LocalSkinStore.Cape mCape;
    private boolean mCapeDisabled;
    private boolean mSlim;

    HorizonSkinDialog(@NonNull Context themed, @NonNull String profileKey, @Nullable String accountName,
                      boolean microsoft, @Nullable String accountKey, @NonNull ImportRequester importer) {
        super(themed);
        mProfileKey = profileKey;
        mMicrosoft = microsoft;
        mImporter = importer;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = LayoutInflater.from(themed).inflate(R.layout.dialog_horizon_skin, null, false);
        setContentView(root);
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
        root.setBackground(rounded(BG, STROKE, 20));

        ((TextView) root.findViewById(R.id.hs_account_name)).setText(accountName == null || accountName.isEmpty()
                ? themed.getString(R.string.horizon_skin_no_account) : accountName);
        ((TextView) root.findViewById(R.id.hs_account_type)).setText(microsoft
                ? R.string.horizon_skin_account_microsoft : R.string.horizon_skin_account_offline);
        root.findViewById(R.id.hs_microsoft_note).setVisibility(microsoft ? View.VISIBLE : View.GONE);

        mPreview = root.findViewById(R.id.hs_preview);
        mSkinRow = root.findViewById(R.id.hs_skins);
        mCapeRow = root.findViewById(R.id.hs_capes);
        mSteve = root.findViewById(R.id.hs_model_steve);
        mAlex = root.findViewById(R.id.hs_model_alex);
        mModelHint = root.findViewById(R.id.hs_model_hint);
        mSteve.setOnClickListener(v -> setSlim(false));
        mAlex.setOnClickListener(v -> setSlim(true));
        root.findViewById(R.id.hs_import_skin).setOnClickListener(v -> mImporter.request(false));
        root.findViewById(R.id.hs_import_cape).setOnClickListener(v -> mImporter.request(true));
        root.findViewById(R.id.hs_cancel).setOnClickListener(v -> dismiss());
        root.findViewById(R.id.hs_apply).setOnClickListener(v -> apply());

        HorizonSkins.Selection current = HorizonSkins.resolve(profileKey, accountKey);
        mSkin = current.skin;
        mCape = current.cape;
        mCapeDisabled = current.capeDisabled;
        mSlim = mSkin != null && mSkin.slim;
        rebuild();
    }

    // ------------------------------------------------------------------ state

    private void setSlim(boolean slim) {
        mSlim = slim;
        mPreview.setSlim(slim);
        styleToggle(mSteve, !slim);
        styleToggle(mAlex, slim);
    }

    private void selectSkin(@Nullable SkinLibrary.Entry entry) {
        mSkin = entry;
        mSlim = entry != null && entry.slim;
        rebuild();
    }

    private void selectCape(@Nullable LocalSkinStore.Cape cape, boolean disabled) {
        mCape = cape;
        mCapeDisabled = disabled;
        rebuild();
    }

    private void rebuild() {
        Context c = getContext();
        // Preview
        Bitmap skinBitmap = mSkin == null ? null : BitmapFactory.decodeFile(SkinLibrary.fileOf(mSkin).getAbsolutePath());
        if (skinBitmap == null) {
            skinBitmap = BitmapFactory.decodeResource(c.getResources(), R.drawable.skin_default_cs);
        }
        mPreview.setSkin(skinBitmap, mSlim);
        Bitmap capeBitmap = mCape == null || mCapeDisabled ? null : BitmapFactory.decodeFile(LocalSkinStore.capeFile(mCape).getAbsolutePath());
        mPreview.setCape(capeBitmap);
        styleToggle(mSteve, !mSlim);
        styleToggle(mAlex, mSlim);
        mModelHint.setText(mSlim ? R.string.horizon_skin_model_alex_hint : R.string.horizon_skin_model_steve_hint);

        // Skins
        mSkinRow.removeAllViews();
        List<SkinLibrary.Entry> skins = SkinLibrary.list();
        mSkinRow.addView(chip(c.getString(R.string.horizon_skin_default), mSkin == null, v -> selectSkin(null)));
        for (SkinLibrary.Entry e : skins) {
            File f = SkinLibrary.fileOf(e);
            if (!f.isFile()) continue;
            boolean selected = mSkin != null && mSkin.id.equals(e.id);
            View chip = chip(e.name, selected, v -> selectSkin(e));
            Bitmap face = faceOf(f);
            if (face != null) ((ImageView) chip.findViewById(R.id.hs_chip_icon)).setImageBitmap(face);
            else chip.findViewById(R.id.hs_chip_icon).setVisibility(View.GONE);
            mSkinRow.addView(chip);
        }
        // Capes
        mCapeRow.removeAllViews();
        mCapeRow.addView(chip(c.getString(R.string.skin_editor_no_cape), mCape == null || mCapeDisabled, v -> selectCape(null, true)));
        for (LocalSkinStore.Cape cape : LocalSkinStore.listCapes()) {
            File f = LocalSkinStore.capeFile(cape);
            if (!f.isFile()) continue;
            boolean selected = !mCapeDisabled && mCape != null && mCape.id.equals(cape.id);
            View chip = chip(cape.name, selected, v -> selectCape(cape, false));
            Bitmap raw = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (raw != null) {
                ((ImageView) chip.findViewById(R.id.hs_chip_icon)).setImageBitmap(SkinTextures.capeFront(SkinTextures.normalizeCape(raw)));
            } else chip.findViewById(R.id.hs_chip_icon).setVisibility(View.GONE);
            mCapeRow.addView(chip);
        }
    }

    @Nullable
    private static Bitmap faceOf(File skinFile) {
        Bitmap raw = BitmapFactory.decodeFile(skinFile.getAbsolutePath());
        if (raw == null) return null;
        Bitmap skin = SkinTextures.normalizeSkin(raw);
        int s = skin.getWidth() / 64;
        Bitmap face = Bitmap.createBitmap(skin, 8 * s, 8 * s, 8 * s, 8 * s);
        Bitmap hat = Bitmap.createBitmap(skin, 40 * s, 8 * s, 8 * s, 8 * s);
        Bitmap out = Bitmap.createBitmap(8 * s, 8 * s, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawBitmap(face, 0, 0, null);
        canvas.drawBitmap(hat, 0, 0, null);
        return Bitmap.createScaledBitmap(out, 64, 64, false);
    }

    // ------------------------------------------------------------------ apply / import

    private void apply() {
        if (mSkin != null && mSkin.slim != mSlim) {
            try { SkinLibrary.setSlim(mSkin.id, mSlim); mSkin.slim = mSlim; } catch (Exception ignored) {}
        }
        HorizonSkins.Selection s = new HorizonSkins.Selection();
        s.skin = mSkin;
        s.cape = mCape;
        s.capeDisabled = mCapeDisabled;
        try {
            HorizonSkins.save(mProfileKey, mSkin == null ? null : mSkin.id,
                    mCapeDisabled ? HorizonSkins.CAPE_NONE : (mCape == null ? null : mCape.id));
        } catch (Exception e) {
            Toast.makeText(getContext(), getContext().getString(R.string.skin_editor_error, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        HorizonSkins.pushToEngine(s);
        Toast.makeText(getContext(), mMicrosoft ? R.string.horizon_skin_applied_microsoft : R.string.horizon_skin_applied, Toast.LENGTH_SHORT).show();
        dismiss();
    }

    /** A PNG picked by the user (system picker, see {@link ImportRequester}). */
    void onPicked(@NonNull Uri uri, boolean cape) {
        final Context app = getContext().getApplicationContext();
        CSLauncherApplication.sExecutorService.execute(() -> {
            String error = null;
            SkinLibrary.Entry addedSkin = null;
            LocalSkinStore.Cape addedCape = null;
            try {
                byte[] data = SkinEditorFragment.readLimited(app, uri);
                Bitmap bitmap = SkinEditorFragment.isPng(data) ? BitmapFactory.decodeByteArray(data, 0, data.length) : null;
                if (cape) {
                    if (bitmap == null || !LocalSkinStore.isValidCapeSize(bitmap.getWidth(), bitmap.getHeight())) {
                        error = app.getString(R.string.horizon_skin_invalid_cape);
                    } else {
                        addedCape = LocalSkinStore.addCape(data, SkinEditorFragment.displayName(app, uri));
                    }
                } else {
                    if (bitmap == null || !SkinTextures.isValidSkinSize(bitmap.getWidth(), bitmap.getHeight())) {
                        error = app.getString(R.string.skin_editor_invalid_skin);
                    } else {
                        addedSkin = SkinLibrary.add(data, SkinEditorFragment.displayName(app, uri), SkinTextures.looksSlim(bitmap));
                    }
                }
                if (bitmap != null) bitmap.recycle();
            } catch (Exception e) {
                error = app.getString(R.string.skin_editor_error, e.getMessage());
            }
            final String finalError = error;
            final SkinLibrary.Entry finalSkin = addedSkin;
            final LocalSkinStore.Cape finalCape = addedCape;
            mMain.post(() -> {
                if (!isShowing()) return;
                if (finalError != null) {
                    Toast.makeText(getContext(), finalError, Toast.LENGTH_LONG).show();
                } else if (finalSkin != null) {
                    selectSkin(finalSkin);
                } else if (finalCape != null) {
                    selectCape(finalCape, false);
                }
            });
        });
    }

    // ------------------------------------------------------------------ small widgets

    private View chip(String label, boolean selected, View.OnClickListener click) {
        View chip = LayoutInflater.from(getContext()).inflate(R.layout.item_horizon_skin_chip, mSkinRow, false);
        TextView text = chip.findViewById(R.id.hs_chip_text);
        text.setText(label);
        text.setTextColor(selected ? BG : TEXT);
        chip.setBackground(rounded(selected ? ACCENT : CARD, selected ? ACCENT : STROKE, 12));
        chip.setOnClickListener(click);
        return chip;
    }

    private void styleToggle(TextView view, boolean on) {
        view.setBackground(rounded(on ? ACCENT : CARD, on ? ACCENT : STROKE, 12));
        view.setTextColor(on ? BG : TEXT);
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        float d = getContext().getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.round(d), stroke);
        g.setCornerRadius(radiusDp * d);
        return g;
    }
}
