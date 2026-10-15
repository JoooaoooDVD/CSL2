package com.craftstudio.cslauncher.fragments;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.extra.ExtraConstants;
import com.craftstudio.cslauncher.extra.ExtraCore;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.util.Random;
import java.util.regex.Pattern;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.skins.PlayerPreviewView;
import com.craftstudio.cslauncher.skins.PremiumProfileLookup;
import com.craftstudio.cslauncher.skins.SkinTextures;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Offline account: a name field with live validation (rules tick off as you type), a name
 * suggester and a big "Create & play" button.
 */
public class LocalLoginFragment extends Fragment {
    public static final String TAG = "LOCAL_LOGIN_FRAGMENT";

    private static final Pattern VALID_CHARS = Pattern.compile("^[a-zA-Z0-9_]*$");
    private static final float NARROW_DP = 600f;
    private static final String[] NAME_HEADS = {"Pixel", "Ender", "Nether", "Creeper", "Redstone", "Diamond", "Shadow",
            "Blaze", "Frost", "Storm", "Lucky", "Turbo", "Zen", "Nova", "Crafty", "Mossy", "Copper", "Glow"};
    private static final String[] NAME_TAILS = {"Miner", "Knight", "Fox", "Wolf", "Steve", "Builder", "Hero", "Ghost",
            "Rider", "Bolt", "Wizard", "Ninja", "Pilot", "Panda", "Bee", "Axolotl", "Golem", "Raider"};

    private EditText mName;
    private TextView mGreeting, mAvatarLetter, mCounter, mError;
    private View mPlay, mAvatar;
    private final View[] mRuleRows = new View[3];
    private final boolean[] mRuleState = new boolean[3];

    // Premium lookup (debounced while typing)
    private static final long LOOKUP_DELAY = 650L;
    private View mPremiumRow, mPreviewWrap;
    private ProgressBar mPremiumSpinner;
    private ImageView mPremiumIcon, mAvatarFace;
    private TextView mPremiumTitle, mPremiumSub, mPlayText;
    private MaterialSwitch mPremiumUse;
    private PlayerPreviewView mPreview;
    @Nullable private PremiumProfileLookup.Result mPremium;
    @Nullable private String mLookedUpName;
    private int mLookupSerial;
    private boolean mSubmitting;
    private final Runnable mLookupRunnable = this::startLookup;

    public LocalLoginFragment(){
        super(R.layout.fragment_local_login);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View banner = view.findViewById(R.id.local_login_banner);
        banner.setClipToOutline(true);
        view.findViewById(R.id.local_login_back).setOnClickListener(v -> Tools.removeCurrentFragment(requireActivity()));
        CSAnimationEngine.attachPressFeedback(view.findViewById(R.id.local_login_back));

        mName = view.findViewById(R.id.login_edit_email);
        mGreeting = view.findViewById(R.id.ll_greeting);
        mAvatarLetter = view.findViewById(R.id.ll_avatar_letter);
        mAvatar = view.findViewById(R.id.ll_avatar);
        mCounter = view.findViewById(R.id.ll_counter);
        mError = view.findViewById(R.id.ll_error);
        mPlay = view.findViewById(R.id.login_button);
        View random = view.findViewById(R.id.ll_random);
        LinearLayout rules = view.findViewById(R.id.ll_rules);
        mPremiumRow = view.findViewById(R.id.ll_premium);
        mPremiumSpinner = view.findViewById(R.id.ll_premium_spinner);
        mPremiumIcon = view.findViewById(R.id.ll_premium_icon);
        mPremiumTitle = view.findViewById(R.id.ll_premium_title);
        mPremiumSub = view.findViewById(R.id.ll_premium_sub);
        mPremiumUse = view.findViewById(R.id.ll_premium_use);
        mAvatarFace = view.findViewById(R.id.ll_avatar_face);
        mPreviewWrap = view.findViewById(R.id.ll_preview_wrap);
        mPreview = view.findViewById(R.id.ll_preview);
        mPlayText = view.findViewById(R.id.ll_play_text);
        mPremiumRow.setOnClickListener(v -> { if (mPremium == null && isValid()) { mLookedUpName = null; startLookup(); } });
        mPremiumUse.setOnCheckedChangeListener((b, checked) -> {
            if (mPremium == null) return;
            showFace(checked ? mPremium.face : null);
            if (checked) { if (mPreviewWrap.getVisibility() != View.VISIBLE) { mPreviewWrap.setVisibility(View.VISIBLE); Motion.popIn(mPreviewWrap, 0L, 0.9f); } }
            else Motion.hide(mPreviewWrap, true);
        });

        int[] ruleTexts = {R.string.ll_rule_length, R.string.ll_rule_chars, R.string.ll_rule_space};
        LayoutInflater inflater = LayoutInflater.from(view.getContext());
        for (int i = 0; i < ruleTexts.length; i++) {
            View row = inflater.inflate(R.layout.item_ll_rule, rules, false);
            ((TextView) row.findViewById(R.id.rule_text)).setText(ruleTexts[i]);
            rules.addView(row);
            mRuleRows[i] = row;
        }

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

        view.post(() -> adaptToWidth(view));
        onNameChanged(mName.getText().toString(), false);

        // Entrance
        Motion.riseIn(banner, 30L, 12f);
        Motion.settleZoom(view.findViewById(R.id.local_login_banner_art), 60L);
        Motion.riseIn(view.findViewById(R.id.login_menu), 120L, 18f);
        Motion.riseIn(view.findViewById(R.id.ll_rules_card), 200L, 18f);
        Motion.popIn(mAvatar, 260L, 0.4f);
        Motion.cascadeChildren(rules, 320L, 60L, 10f);
    }

    private void onNameChanged(@NonNull String name, boolean animate) {
        boolean lengthOk = name.length() >= 3 && name.length() <= 16;
        boolean charsOk = !name.isEmpty() && VALID_CHARS.matcher(name.replace(" ", "")).find();
        boolean spaceOk = !name.isEmpty() && !name.contains(" ");
        boolean[] states = {lengthOk, charsOk, spaceOk};
        for (int i = 0; i < 3; i++) setRule(i, states[i], animate);

        mCounter.setText(name.length() + "/16");
        if (name.isEmpty()) {
            mGreeting.setText(R.string.ll_greeting_empty);
            if (!"?".contentEquals(mAvatarLetter.getText())) swapLetter("?");
        } else {
            mGreeting.setText(getString(R.string.ll_greeting_fmt, name));
            String letter = name.substring(0, 1).toUpperCase();
            if (!letter.contentEquals(mAvatarLetter.getText())) swapLetter(letter);
        }
        boolean valid = lengthOk && charsOk && spaceOk;
        mPlay.setAlpha(valid ? 1f : 0.55f);
        if (valid && mError.getVisibility() == View.VISIBLE) Motion.hide(mError, true);

        // Premium check: debounced, only for valid names; anything else clears the result
        mName.removeCallbacks(mLookupRunnable);
        if (valid) {
            if (!name.equalsIgnoreCase(mLookedUpName)) mName.postDelayed(mLookupRunnable, LOOKUP_DELAY);
        } else {
            clearPremium();
        }
    }

    private void clearPremium() {
        mLookupSerial++;
        mPremium = null;
        mLookedUpName = null;
        if (mPremiumRow.getVisibility() == View.VISIBLE) Motion.hide(mPremiumRow, true);
        if (mPreviewWrap.getVisibility() == View.VISIBLE) Motion.hide(mPreviewWrap, true);
        showFace(null);
    }

    private void startLookup() {
        if (getView() == null) return;
        final String name = mName.getText().toString();
        if (!isValid()) return;
        final int serial = ++mLookupSerial;
        mLookedUpName = name;
        mPremium = null;
        showFace(null);
        if (mPreviewWrap.getVisibility() == View.VISIBLE) Motion.hide(mPreviewWrap, true);
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
            // "reachable" = we got a real answer (found or not found); otherwise show the offline text
            final boolean reachable = lookup != null && lookup.status != PremiumProfileLookup.Status.ERROR;
            final PremiumProfileLookup.Result found = lookup != null && lookup.found() ? lookup : null;
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

    private void onLookupDone(@Nullable PremiumProfileLookup.Result result, boolean online) {
        mPremium = result;
        mPremiumSpinner.setVisibility(View.GONE);
        mPremiumIcon.setVisibility(View.VISIBLE);
        mPremiumSub.setVisibility(View.VISIBLE);
        if (result != null && result.skinPng != null) {
            mPremiumIcon.setImageResource(R.drawable.ic_pp_check);
            mPremiumIcon.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF22C55E));
            mPremiumTitle.setText(getString(R.string.ll_premium_found, result.name));
            mPremiumSub.setText(result.capePng != null ? R.string.ll_premium_found_sub : R.string.ll_premium_found_skin_only);
            mPremiumUse.setVisibility(View.VISIBLE);
            mPremiumUse.setChecked(true);
            Motion.popIn(mPremiumIcon, 0L, 0.3f);
            // Exact casing from Mojang (e.g. "notch" -> "Notch"), keeps the cursor at the end
            if (!result.name.equals(mName.getText().toString()) && result.name.equalsIgnoreCase(mName.getText().toString())) {
                mLookedUpName = result.name;
                mName.setText(result.name);
                mName.setSelection(result.name.length());
            }
            showFace(result.face);
            Bitmap skin = BitmapFactory.decodeByteArray(result.skinPng, 0, result.skinPng.length);
            if (skin != null) {
                mPreview.setSkin(SkinTextures.normalizeSkin(skin), result.slim);
                if (result.capePng != null) {
                    Bitmap cape = BitmapFactory.decodeByteArray(result.capePng, 0, result.capePng.length);
                    mPreview.setCape(cape == null ? null : SkinTextures.normalizeCape(cape));
                } else {
                    mPreview.setCape(null);
                }
                mPreviewWrap.setVisibility(View.VISIBLE);
                Motion.popIn(mPreviewWrap, 0L, 0.85f);
            }
        } else {
            mPremiumIcon.setImageResource(R.drawable.ic_pe_person);
            mPremiumIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.hm_text_dim)));
            mPremiumTitle.setText(online ? R.string.ll_premium_none : R.string.ll_premium_offline);
            mPremiumSub.setText(online ? R.string.ll_premium_none_sub : R.string.ll_premium_offline_sub);
            mPremiumUse.setVisibility(View.GONE);
        }
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

    private void swapLetter(String letter) {
        mAvatarLetter.setText(letter);
        Motion.popIn(mAvatarLetter, 0L, 0.5f);
    }

    private void setRule(int index, boolean ok, boolean animate) {
        if (mRuleState[index] == ok && mRuleRows[index].getTag() != null) return;
        mRuleState[index] = ok;
        View row = mRuleRows[index];
        row.setTag(Boolean.TRUE);
        View dot = row.findViewById(R.id.rule_dot);
        View check = row.findViewById(R.id.rule_check);
        TextView text = row.findViewById(R.id.rule_text);
        dot.setBackgroundResource(ok ? R.drawable.bg_ll_rule_ok : R.drawable.bg_ss_step_num);
        check.setVisibility(ok ? View.VISIBLE : View.INVISIBLE);
        if (ok) ((android.widget.ImageView) check).setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        text.setTextColor(androidx.core.content.ContextCompat.getColor(row.getContext(), ok ? R.color.hm_text : R.color.hm_text_dim));
        if (animate && ok) Motion.bounce(dot);
    }

    private boolean isValid() {
        return mRuleState[0] && mRuleState[1] && mRuleState[2];
    }

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
        if (premium == null || premium.skinPng == null) {
            ExtraCore.setValue(ExtraConstants.MOJANG_LOGIN_TODO, new String[]{ name, "" });
            Tools.swapToMainMenu(requireActivity());
            return;
        }
        // Store skin + cape for this name first so the account shows the right head immediately
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

    @Override
    public void onDestroyView() {
        if (mName != null) mName.removeCallbacks(mLookupRunnable);
        mLookupSerial++;
        super.onDestroyView();
    }

    /** Phones: rules card goes under the form */
    private void adaptToWidth(@NonNull View root) {
        if (getView() == null) return;
        float density = getResources().getDisplayMetrics().density;
        float widthDp = root.getWidth() / density;
        if (widthDp <= 0 || widthDp >= NARROW_DP) return;
        LinearLayout columns = root.findViewById(R.id.ll_columns);
        columns.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < columns.getChildCount(); i++) {
            View child = columns.getChildAt(i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) params.topMargin = Math.round(12 * density);
            child.setLayoutParams(params);
        }
    }
}
