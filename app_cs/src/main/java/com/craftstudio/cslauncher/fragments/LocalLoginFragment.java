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
        ExtraCore.setValue(ExtraConstants.MOJANG_LOGIN_TODO, new String[]{ mName.getText().toString(), "" });
        Tools.swapToMainMenu(requireActivity());
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
