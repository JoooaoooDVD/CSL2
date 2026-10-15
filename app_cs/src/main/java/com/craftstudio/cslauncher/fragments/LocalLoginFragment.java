package com.craftstudio.cslauncher.fragments;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.extra.ExtraConstants;
import com.craftstudio.cslauncher.extra.ExtraCore;
import com.craftstudio.cslauncher.skins.PlayerPreviewView;
import com.craftstudio.cslauncher.skins.PremiumProfileLookup;
import com.craftstudio.cslauncher.skins.SkinTextures;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.Motion;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Random;
import java.util.regex.Pattern;

/**
 * Offline account as "create your player": a live 3D stage (default skin, or the premium
 * player's skin + cape once the typed name belongs to one), name rules that tick off while
 * typing, a name suggester and one big "Create & play" button.
 */
public class LocalLoginFragment extends Fragment {
    public static final String TAG = "LOCAL_LOGIN_FRAGMENT";

    private static final Pattern VALID_CHARS = Pattern.compile("^[a-zA-Z0-9_]*$");
    private static final float NARROW_DP = 600f;
    private static final long LOOKUP_DELAY = 650L;
    private static final String[] NAME_HEADS = {"Pixel", "Ender", "Nether", "Creeper", "Redstone", "Diamond", "Shadow",
            "Blaze", "Frost", "Storm", "Lucky", "Turbo", "Zen", "Nova", "Crafty", "Mossy", "Copper", "Glow"};
    private static final String[] NAME_TAILS = {"Miner", "Knight", "Fox", "Wolf", "Steve", "Builder", "Hero", "Ghost",
            "Rider", "Bolt", "Wizard", "Ninja", "Pilot", "Panda", "Bee", "Axolotl", "Golem", "Raider"};

    private EditText mName;
    private TextView mGreeting, mAvatarLetter, mCounter, mError, mNametag, mPreviewTag;
    private TextView mPremiumTitle, mPremiumSub, mPlayText;
    private View mPlay, mAvatar, mPremiumRow, mPreviewWrap;
    private ProgressBar mPremiumSpinner;
    private ImageView mPremiumIcon, mAvatarFace;
    private MaterialSwitch mPremiumUse;
    private PlayerPreviewView mPreview;
    private final View[] mRuleRows = new View[3];
    private final boolean[] mRuleState = new boolean[3];

    @Nullable private PremiumProfileLookup.Result mPremium;
    @Nullable private String mLookedUpName;
    private int mLookupSerial;
    private boolean mSubmitting;
    private boolean mShowingPremiumSkin;
    private final Runnable mLookupRunnable = this::startLookup;

    public LocalLoginFragment(){
        super(R.layout.fragment_local_login);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View header = view.findViewById(R.id.local_login_banner);
        View back = view.findViewById(R.id.local_login_back);
        back.setOnClickListener(v -> Tools.removeCurrentFragment(requireActivity()));
        CSAnimationEngine.attachPressFeedback(back);

        mName = view.findViewById(R.id.login_edit_email);
        mGreeting = view.findViewById(R.id.ll_greeting);
        mAvatarLetter = view.findViewById(R.id.ll_avatar_letter);
        mAvatarFace = view.findViewById(R.id.ll_avatar_face);
        mAvatar = view.findViewById(R.id.ll_avatar);
        mCounter = view.findViewById(R.id.ll_counter);
        mError = view.findViewById(R.id.ll_error);
        mPlay = view.findViewById(R.id.login_button);
        mPlayText = view.findViewById(R.id.ll_play_text);
        mNametag = view.findViewById(R.id.ll_nametag);
        mPreviewTag = view.findViewById(R.id.ll_preview_tag);
        mPreviewWrap = view.findViewById(R.id.ll_preview_wrap);
        mPreview = view.findViewById(R.id.ll_preview);
        mPremiumRow = view.findViewById(R.id.ll_premium);
        mPremiumSpinner = view.findViewById(R.id.ll_premium_spinner);
        mPremiumIcon = view.findViewById(R.id.ll_premium_icon);
        mPremiumTitle = view.findViewById(R.id.ll_premium_title);
        mPremiumSub = view.findViewById(R.id.ll_premium_sub);
        mPremiumUse = view.findViewById(R.id.ll_premium_use);
        View random = view.findViewById(R.id.ll_random);
        LinearLayout rules = view.findViewById(R.id.ll_rules);
        mPreviewWrap.setClipToOutline(true);

        // Rule chips
        int[] ruleTexts = {R.string.ll_rule_length, R.string.ll_rule_chars, R.string.ll_rule_space};
        LayoutInflater inflater = LayoutInflater.from(view.getContext());
        for (int i = 0; i < ruleTexts.length; i++) {
            View row = inflater.inflate(R.layout.item_ll_rule, rules, false);
            ((TextView) row.findViewById(R.id.rule_text)).setText(ruleTexts[i]);
            rules.addView(row);
            mRuleRows[i] = row;
        }

        // Stage: the default player is there from the start
        showDefaultSkin(false);

        mName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { onNameChanged(s.toString(), true); }
        });
        mName.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); return true; }
            return false;
        });
        random.setOnClickListener(v -> {
            Motion.bounce(v);
            Random rnd = new Random();
            String name = NAME_HEADS[rnd.nextInt(NAME_HEADS.length)] + NAME_TAILS[rnd.nextInt(NAME_TAILS.length)];
            if (rnd.nextBoolean()) name += (rnd.nextInt(90) + 10);
            mName.setText(name);
            mName.setSelection(name.length());
        });
        CSAnimationEngine.attachPressFeedback(random);
        CSAnimationEngine.attachPressFeedback(mPlay);
        mPlay.setOnClickListener(v -> submit());
        mPremiumRow.setOnClickListener(v -> { if (mPremium == null && isValid()) { mLookedUpName = null; startLookup(); } });
        mPremiumUse.setOnCheckedChangeListener((b, checked) -> {
            if (mPremium == null) return;
            if (checked) showPremiumSkin(mPremium); else showDefaultSkin(true);
        });

        view.post(() -> adaptToWidth(view));
        onNameChanged(mName.getText().toString(), false);

        // Entrance: stage slides in from the left, the player pops up on it, form cascades
        Motion.slideInX(mPreviewWrap, 30L, -40f);
        Motion.popIn(mPreview, 260L, 0.6f);
        Motion.popIn(mNametag, 420L, 0.5f);
        Motion.fadeIn(view.findViewById(R.id.ll_floor), 300L, Motion.MEDIUM);
        Motion.riseIn(header, 80L, 12f);
        Motion.cascadeChildren((ViewGroup) view.findViewById(R.id.login_menu), 160L, 55L, 14f);
        Motion.popChildren(rules, 520L, 60L, 0.6f);
        mPreview.postDelayed(() -> { if (getView() != null) mPreview.startWave(); }, 900L);
    }

    // ------------------------------------------------------------------ stage

    private void showDefaultSkin(boolean animate) {
        mShowingPremiumSkin = false;
        Bitmap skin = BitmapFactory.decodeResource(getResources(), R.drawable.skin_default_cs);
        if (skin != null) mPreview.setSkin(skin, false);
        mPreview.setCape(null);
        mPreviewTag.setText(R.string.ll_tag_default);
        mPreviewTag.setBackgroundResource(R.drawable.bg_lib_badge_neutral);
        mPreviewTag.setTextColor(ContextCompat.getColor(requireContext(), R.color.hm_text_dim));
        showFace(null);
        if (animate) { Motion.popIn(mPreview, 0L, 0.85f); Motion.popIn(mPreviewTag, 0L, 0.6f); }
    }

    private void showPremiumSkin(@NonNull PremiumProfileLookup.Result result) {
        if (result.skinPng == null) return;
        Bitmap skin = BitmapFactory.decodeByteArray(result.skinPng, 0, result.skinPng.length);
        if (skin == null) return;
        mShowingPremiumSkin = true;
        // Quick flash: shrink + fade, swap, pop back and wave
        mPreview.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(140L).withEndAction(() -> {
            if (getView() == null) return;
            mPreview.setSkin(SkinTextures.normalizeSkin(skin), result.slim);
            Bitmap cape = result.capePng == null ? null : BitmapFactory.decodeByteArray(result.capePng, 0, result.capePng.length);
            mPreview.setCape(cape == null ? null : SkinTextures.normalizeCape(cape));
            mPreview.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320L)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
            mPreview.postDelayed(mPreview::startWave, 500L);
        }).start();
        mPreviewTag.setText(result.capePng != null ? R.string.ll_tag_premium_cape : R.string.ll_premium_badge);
        mPreviewTag.setBackgroundResource(R.drawable.bg_lib_badge_green);
        mPreviewTag.setTextColor(ContextCompat.getColor(requireContext(), R.color.lib_badge_green_text));
        Motion.popIn(mPreviewTag, 100L, 0.6f);
        showFace(result.face);
    }

    private void showFace(@Nullable Bitmap face) {
        if (face == null) {
            if (mAvatarFace.getVisibility() == View.VISIBLE) { mAvatarFace.setVisibility(View.GONE); mAvatarLetter.setVisibility(View.VISIBLE); }
            return;
        }
        mAvatarFace.setImageBitmap(face);
        mAvatarFace.setVisibility(View.VISIBLE);
        mAvatarLetter.setVisibility(View.GONE);
        Motion.popIn(mAvatarFace, 0L, 0.4f);
    }

    // ------------------------------------------------------------------ name

    private void onNameChanged(@NonNull String name, boolean animate) {
        boolean lengthOk = name.length() >= 3 && name.length() <= 16;
        boolean charsOk = !name.isEmpty() && VALID_CHARS.matcher(name.replace(" ", "")).find();
        boolean spaceOk = !name.isEmpty() && !name.contains(" ");
        boolean[] states = {lengthOk, charsOk, spaceOk};
        for (int i = 0; i < 3; i++) setRule(i, states[i], animate);

        mCounter.setText(name.length() + "/16");
        if (name.isEmpty()) {
            mGreeting.setText(R.string.ll_title);
            mNametag.setText(R.string.ll_nametag_default);
            if (!"?".contentEquals(mAvatarLetter.getText())) swapLetter("?");
        } else {
            mGreeting.setText(getString(R.string.ll_greeting_fmt, name));
            mNametag.setText(name);
            String letter = name.substring(0, 1).toUpperCase();
            if (!letter.contentEquals(mAvatarLetter.getText())) swapLetter(letter);
        }
        boolean valid = lengthOk && charsOk && spaceOk;
        mPlay.animate().alpha(valid ? 1f : 0.55f).setDuration(160L).start();
        if (valid && mError.getVisibility() == View.VISIBLE) Motion.hide(mError, true);

        mName.removeCallbacks(mLookupRunnable);
        if (valid) {
            if (!name.equalsIgnoreCase(mLookedUpName)) mName.postDelayed(mLookupRunnable, LOOKUP_DELAY);
        } else {
            clearPremium();
        }
    }

    private void swapLetter(String letter) {
        mAvatarLetter.setText(letter);
        Motion.popIn(mAvatarLetter, 0L, 0.5f);
    }

    private void setRule(int index, boolean ok, boolean animate) {
        if (mRuleState[index] == ok && mRuleRows[index].getTag() != null) return;
        mRuleState[index] = ok;
        View row = mRuleRows[index];
        row.setTag(Boolean.TRUE);
        row.setActivated(ok);
        View dot = row.findViewById(R.id.rule_dot);
        View check = row.findViewById(R.id.rule_check);
        TextView text = row.findViewById(R.id.rule_text);
        dot.setBackgroundResource(ok ? R.drawable.bg_ll_rule_ok : R.drawable.bg_ss_step_num);
        check.setVisibility(ok ? View.VISIBLE : View.INVISIBLE);
        text.setTextColor(ContextCompat.getColor(row.getContext(), ok ? R.color.hm_text : R.color.hm_text_dim));
        if (animate && ok) Motion.bounce(dot);
    }

    private boolean isValid() {
        return mRuleState[0] && mRuleState[1] && mRuleState[2];
    }

    // ------------------------------------------------------------------ premium lookup

    private void clearPremium() {
        mLookupSerial++;
        mPremium = null;
        mLookedUpName = null;
        if (mPremiumRow.getVisibility() == View.VISIBLE) Motion.hide(mPremiumRow, true);
        if (mShowingPremiumSkin) showDefaultSkin(true);
    }

    private void startLookup() {
        if (getView() == null) return;
        final String name = mName.getText().toString();
        if (!isValid()) return;
        final int serial = ++mLookupSerial;
        mLookedUpName = name;
        mPremium = null;
        if (mShowingPremiumSkin) showDefaultSkin(true);
        mPremiumSpinner.setVisibility(View.VISIBLE);
        mPremiumIcon.setVisibility(View.GONE);
        mPremiumUse.setVisibility(View.GONE);
        mPremiumTitle.setText(R.string.ll_premium_checking);
        mPremiumSub.setText("");
        mPremiumSub.setVisibility(View.GONE);
        if (mPremiumRow.getVisibility() != View.VISIBLE) { mPremiumRow.setVisibility(View.VISIBLE); Motion.riseIn(mPremiumRow, 0L, 8f); }
        final boolean online = isOnline();
        CSLauncherApplication.sExecutorService.execute(() -> {
            final PremiumProfileLookup.Result lookup = online ? PremiumProfileLookup.lookup(name) : null;
            final boolean reachable = lookup != null && lookup.status != PremiumProfileLookup.Status.ERROR;
            final PremiumProfileLookup.Result found = lookup != null && lookup.found() && lookup.skinPng != null ? lookup : null;
            Tools.runOnUiThread(() -> {
                if (getView() == null || serial != mLookupSerial) return;
                onLookupDone(found, reachable);
            });
        });
    }

    private boolean isOnline() {
        try {
            android.net.ConnectivityManager manager = (android.net.ConnectivityManager)
                    requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (manager == null) return true;
            android.net.NetworkInfo info = manager.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    private void onLookupDone(@Nullable PremiumProfileLookup.Result result, boolean reachable) {
        mPremium = result;
        mPremiumSpinner.setVisibility(View.GONE);
        mPremiumIcon.setVisibility(View.VISIBLE);
        mPremiumSub.setVisibility(View.VISIBLE);
        if (result != null) {
            mPremiumIcon.setImageResource(R.drawable.ic_pp_check);
            mPremiumIcon.setImageTintList(ColorStateList.valueOf(0xFF22C55E));
            mPremiumTitle.setText(getString(R.string.ll_premium_found, result.name));
            mPremiumSub.setText(result.capePng != null ? R.string.ll_premium_found_sub : R.string.ll_premium_found_skin_only);
            mPremiumUse.setVisibility(View.VISIBLE);
            mPremiumUse.setChecked(true);
            Motion.popIn(mPremiumIcon, 0L, 0.3f);
            // Exact casing from Mojang ("notch" -> "Notch")
            if (!result.name.equals(mName.getText().toString()) && result.name.equalsIgnoreCase(mName.getText().toString())) {
                mLookedUpName = result.name;
                mName.setText(result.name);
                mName.setSelection(result.name.length());
            }
            showPremiumSkin(result);
        } else {
            mPremiumIcon.setImageResource(R.drawable.ic_pe_person);
            mPremiumIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.hm_text_dim)));
            mPremiumTitle.setText(reachable ? R.string.ll_premium_none : R.string.ll_premium_offline);
            mPremiumSub.setText(reachable ? R.string.ll_premium_none_sub : R.string.ll_premium_offline_sub);
            mPremiumUse.setVisibility(View.GONE);
            if (!reachable) mLookedUpName = null; // tapping the row retries
        }
    }

    // ------------------------------------------------------------------ submit

    private void submit() {
        if (!isValid()) {
            mError.setText(R.string.ll_err_fix);
            if (mError.getVisibility() != View.VISIBLE) { mError.setVisibility(View.VISIBLE); Motion.riseIn(mError, 0L, 6f); }
            Motion.shake(mError);
            Motion.shake(requireView().findViewById(R.id.ll_field));
            return;
        }
        if (mSubmitting) return;
        mName.removeCallbacks(mLookupRunnable);
        final String name = mName.getText().toString();
        final PremiumProfileLookup.Result premium = mPremium != null && mPremiumUse.isChecked()
                && name.equalsIgnoreCase(mLookedUpName) ? mPremium : null;
        Motion.bounce(mPreview);
        mPreview.startWave();
        if (premium == null) {
            ExtraCore.setValue(ExtraConstants.MOJANG_LOGIN_TODO, new String[]{ name, "" });
            Tools.swapToMainMenu(requireActivity());
            return;
        }
        mSubmitting = true;
        mPlayText.setText(R.string.ll_applying);
        CSLauncherApplication.sExecutorService.execute(() -> {
            try {
                PremiumProfileLookup.applyToLocalAccount(premium, name);
            } catch (Exception e) {
                android.util.Log.w(TAG, "Could not apply the premium skin", e);
            }
            Tools.runOnUiThread(() -> {
                mSubmitting = false;
                if (getView() == null || !isAdded()) return;
                ExtraCore.setValue(ExtraConstants.MOJANG_LOGIN_TODO, new String[]{ name, "" });
                Tools.swapToMainMenu(requireActivity());
            });
        });
    }

    /** Phones in portrait: the stage sits above the form */
    private void adaptToWidth(@NonNull View root) {
        if (getView() == null) return;
        float density = getResources().getDisplayMetrics().density;
        float widthDp = root.getWidth() / density;
        if (widthDp <= 0 || widthDp >= NARROW_DP) return;
        LinearLayout columns = root.findViewById(R.id.ll_columns);
        columns.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stage = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(230 * density));
        mPreviewWrap.setLayoutParams(stage);
        LinearLayout.LayoutParams form = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        form.topMargin = Math.round(12 * density);
        root.findViewById(R.id.login_menu).setLayoutParams(form);
    }

    @Override
    public void onDestroyView() {
        if (mName != null) mName.removeCallbacks(mLookupRunnable);
        mLookupSerial++;
        super.onDestroyView();
    }
}
