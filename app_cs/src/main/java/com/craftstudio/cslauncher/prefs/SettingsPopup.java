package com.craftstudio.cslauncher.prefs;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;
import com.kdt.mcgui.CSPopupDialog;

/**
 * The launcher's own popup for everything the settings ask: single choice, multi choice,
 * text input, confirmations and custom content. Built from XML ({@code dialog_settings_popup}),
 * single colour, with the shared popup enter / exit animation and a short content cascade.
 * <pre>
 *     new SettingsPopup(context).title(..).singleChoice(entries, checked, index -> ..).show();
 * </pre>
 */
public class SettingsPopup extends CSPopupDialog {

    public interface OnPick { void onPick(int index); }
    public interface OnMultiSave { void onSave(@NonNull boolean[] checked); }
    /** @return true to close, false to keep the popup open (invalid value: show {@link #setError}) */
    public interface OnTextSave { boolean onSave(@NonNull String text); }
    public interface OnInputReady { void onReady(@NonNull EditText input); }

    private CharSequence mTitle;
    private CharSequence mSubtitle;
    private Drawable mIcon;
    private CharSequence mMessage;

    private CharSequence[] mEntries;
    private int mChecked = -1;
    private OnPick mOnPick;
    private boolean[] mMultiChecked;
    private OnMultiSave mOnMultiSave;

    private boolean mHasInput;
    private CharSequence mInputText;
    private CharSequence mInputHint;
    private CharSequence mInputHelper;
    private int mInputType = InputType.TYPE_CLASS_TEXT;
    private boolean mInputSingleLine = true;
    private OnTextSave mOnTextSave;
    private OnInputReady mOnInputReady;

    private View mCustomView;

    private CharSequence mPositive, mNegative, mNeutral;
    private Runnable mOnPositive, mOnNegative, mOnNeutral;
    private boolean mNeutralCloses = true;
    private Runnable mOnClosed;

    private LinearLayout mContent;
    private EditText mInput;
    private TextView mHelper;

    public SettingsPopup(@NonNull Context context) {
        super(context);
    }

    // ------------------------------------------------------------------ builder

    public SettingsPopup title(@Nullable CharSequence title) { mTitle = title; return this; }
    public SettingsPopup title(@StringRes int title) { return title(getContext().getText(title)); }
    public SettingsPopup subtitle(@Nullable CharSequence subtitle) { mSubtitle = subtitle; return this; }
    public SettingsPopup icon(@Nullable Drawable icon) { mIcon = icon; return this; }
    public SettingsPopup message(@Nullable CharSequence message) { mMessage = message; return this; }
    public SettingsPopup message(@StringRes int message) { return message(getContext().getText(message)); }

    /** Tapping a choice applies it and closes the popup */
    public SettingsPopup singleChoice(@NonNull CharSequence[] entries, int checked, @NonNull OnPick onPick) {
        mEntries = entries;
        mChecked = checked;
        mOnPick = onPick;
        return this;
    }

    /** Check boxes; {@code onSave} gets the final state when the main button is pressed */
    public SettingsPopup multiChoice(@NonNull CharSequence[] entries, @NonNull boolean[] checked, @NonNull OnMultiSave onSave) {
        mEntries = entries;
        mMultiChecked = checked.clone();
        mOnMultiSave = onSave;
        if (mPositive == null) mPositive = getContext().getText(R.string.sp_save);
        if (mNegative == null) mNegative = getContext().getText(android.R.string.cancel);
        return this;
    }

    public SettingsPopup input(@Nullable CharSequence text, @Nullable CharSequence hint, @NonNull OnTextSave onSave) {
        mHasInput = true;
        mInputText = text;
        mInputHint = hint;
        mOnTextSave = onSave;
        if (mPositive == null) mPositive = getContext().getText(R.string.sp_save);
        if (mNegative == null) mNegative = getContext().getText(android.R.string.cancel);
        return this;
    }

    public SettingsPopup inputType(int inputType) { mInputType = inputType; return this; }
    public SettingsPopup inputSingleLine(boolean singleLine) { mInputSingleLine = singleLine; return this; }
    public SettingsPopup inputHelper(@Nullable CharSequence helper) { mInputHelper = helper; return this; }
    /** Extra setup of the field (filters, watchers) once it exists */
    public SettingsPopup onInputReady(@Nullable OnInputReady ready) { mOnInputReady = ready; return this; }

    public SettingsPopup customView(@Nullable View view) { mCustomView = view; return this; }

    public SettingsPopup positive(@Nullable CharSequence text, @Nullable Runnable action) {
        mPositive = text;
        mOnPositive = action;
        return this;
    }

    public SettingsPopup positive(@StringRes int text, @Nullable Runnable action) {
        return positive(getContext().getText(text), action);
    }

    public SettingsPopup negative(@Nullable CharSequence text, @Nullable Runnable action) {
        mNegative = text;
        mOnNegative = action;
        return this;
    }

    public SettingsPopup negative(@StringRes int text, @Nullable Runnable action) {
        return negative(getContext().getText(text), action);
    }

    public SettingsPopup neutral(@Nullable CharSequence text, @Nullable Runnable action, boolean closes) {
        mNeutral = text;
        mOnNeutral = action;
        mNeutralCloses = closes;
        return this;
    }

    public SettingsPopup onClosed(@Nullable Runnable onClosed) { mOnClosed = onClosed; return this; }

    /** Replaces the neutral button label (e.g. a mode toggle that keeps the popup open) */
    public void setNeutralText(@Nullable CharSequence text) {
        mNeutral = text;
        TextView neutral = findViewById(R.id.sp_neutral);
        if (neutral != null) neutral.setText(text);
    }

    /** Error / helper line under the text field */
    public void setError(@Nullable CharSequence error) {
        if (mHelper == null) return;
        if (error == null) {
            showHelper(mInputHelper);
            return;
        }
        mHelper.setVisibility(View.VISIBLE);
        mHelper.setText(error);
        mHelper.setTextColor(getContext().getResources().getColor(R.color.hm_text));
        if (mInput != null) Motion.shake(mInput);
    }

    @Nullable
    public EditText getInput() {
        return mInput;
    }

    // ------------------------------------------------------------------ build

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_settings_popup);
        View card = findViewById(R.id.sp_card);
        setupPopup(findViewById(R.id.sp_scrim), card, true);
        sizeCard(card);

        TextView title = findViewById(R.id.sp_title);
        TextView subtitle = findViewById(R.id.sp_subtitle);
        View iconTile = findViewById(R.id.sp_icon_tile);
        ImageView icon = findViewById(R.id.sp_icon);
        title.setText(mTitle);
        if (!TextUtils.isEmpty(mSubtitle)) {
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setText(mSubtitle);
        }
        if (mIcon != null) {
            Drawable copy = mIcon.getConstantState() != null
                    ? mIcon.getConstantState().newDrawable(getContext().getResources()).mutate() : mIcon;
            icon.setImageDrawable(copy);
        } else {
            iconTile.setVisibility(View.GONE);
        }

        View close = findViewById(R.id.sp_close);
        close.setOnClickListener(v -> close(mOnClosed));
        CSAnimationEngine.attachPressFeedback(close);

        mContent = findViewById(R.id.sp_content);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        if (!TextUtils.isEmpty(mMessage)) {
            TextView message = (TextView) inflater.inflate(R.layout.item_settings_popup_message, mContent, false);
            message.setText(mMessage);
            mContent.addView(message);
        }
        if (mEntries != null) buildChoices(inflater);
        if (mHasInput) buildInput(inflater);
        if (mCustomView != null) {
            ViewParent parent = mCustomView.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(mCustomView);
            mContent.addView(mCustomView);
        }
        if (mContent.getChildCount() == 0) findViewById(R.id.sp_scroll).setVisibility(View.GONE);

        buildFooter();

        CreatePopupMotion.enter(mScrim, mCard);
        Motion.cascadeChildren(mContent, 120, 28, 10);
        if (mHasInput && mInput != null) {
            Window window = getWindow();
            if (window != null) {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
            mInput.postDelayed(() -> {
                if (!isShowing() || isClosing()) return;
                mInput.requestFocus();
                InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(mInput, InputMethodManager.SHOW_IMPLICIT);
            }, 260);
        }
    }

    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        int wanted = (int) ((mCustomView != null ? 500 : 440) * d);
        params.width = Math.min(wanted, metrics.widthPixels - (int) (32 * d));
        card.setLayoutParams(params);
        MaxHeightScrollView scroll = findViewById(R.id.sp_scroll);
        // Header + footer stay visible; the content scrolls inside what is left of the screen
        scroll.setMaxHeight(Math.max((int) (120 * d), metrics.heightPixels - (int) (190 * d)));
    }

    private void buildChoices(@NonNull LayoutInflater inflater) {
        final boolean multi = mMultiChecked != null;
        final View[] rows = new View[mEntries.length];
        for (int i = 0; i < mEntries.length; i++) {
            final int index = i;
            View row = inflater.inflate(R.layout.item_settings_popup_option, mContent, false);
            ImageView mark = row.findViewById(R.id.sp_opt_mark);
            TextView label = row.findViewById(R.id.sp_opt_label);
            mark.setImageResource(multi ? R.drawable.ic_sp_check : R.drawable.ic_sp_radio);
            label.setText(mEntries[i]);
            row.setSelected(multi ? mMultiChecked[i] : i == mChecked);
            row.setOnClickListener(v -> {
                if (isClosing()) return;
                if (multi) {
                    mMultiChecked[index] = !mMultiChecked[index];
                    v.setSelected(mMultiChecked[index]);
                    Motion.bounce(mark);
                    return;
                }
                for (View other : rows) other.setSelected(other == v);
                Motion.bounce(mark);
                // A short beat so the new mark is seen, then apply + close
                v.postDelayed(() -> close(() -> {
                    if (mOnPick != null) mOnPick.onPick(index);
                    if (mOnClosed != null) mOnClosed.run();
                }), 140);
            });
            rows[i] = row;
            mContent.addView(row);
        }
        // Long lists open on the current value
        if (!multi && mChecked > 3) {
            final View target = mChecked < rows.length ? rows[mChecked] : null;
            if (target != null) {
                View scroll = findViewById(R.id.sp_scroll);
                scroll.post(() -> scroll.scrollTo(0, Math.max(0, target.getTop() - target.getHeight())));
            }
        }
    }

    private void buildInput(@NonNull LayoutInflater inflater) {
        View block = inflater.inflate(R.layout.item_settings_popup_input, mContent, false);
        mInput = block.findViewById(R.id.sp_input);
        mHelper = block.findViewById(R.id.sp_input_helper);
        mInput.setInputType(mInputType);
        if (mInputSingleLine) {
            mInput.setSingleLine(true);
        } else {
            mInput.setSingleLine(false);
            mInput.setMaxLines(6);
            mInput.setInputType(mInputType | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        }
        mInput.setHint(mInputHint);
        mInput.setText(mInputText);
        mInput.setSelection(mInput.length());
        showHelper(mInputHelper);
        mInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                showHelper(mInputHelper);
            }
        });
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                submitPositive();
                return true;
            }
            return false;
        });
        if (mOnInputReady != null) mOnInputReady.onReady(mInput);
        mContent.addView(block);
    }

    private void showHelper(@Nullable CharSequence helper) {
        if (mHelper == null) return;
        mHelper.setTextColor(getContext().getResources().getColor(R.color.hm_text_dim));
        if (TextUtils.isEmpty(helper)) {
            mHelper.setVisibility(View.GONE);
        } else {
            mHelper.setVisibility(View.VISIBLE);
            mHelper.setText(helper);
        }
    }

    private void buildFooter() {
        TextView positive = findViewById(R.id.sp_positive);
        TextView negative = findViewById(R.id.sp_negative);
        TextView neutral = findViewById(R.id.sp_neutral);
        boolean any = false;
        if (mPositive != null) {
            any = true;
            positive.setVisibility(View.VISIBLE);
            positive.setText(mPositive);
            positive.setOnClickListener(v -> submitPositive());
            CSAnimationEngine.attachPressFeedback(positive);
        }
        if (mNegative != null) {
            any = true;
            negative.setVisibility(View.VISIBLE);
            negative.setText(mNegative);
            negative.setOnClickListener(v -> close(() -> {
                if (mOnNegative != null) mOnNegative.run();
                if (mOnClosed != null) mOnClosed.run();
            }));
            CSAnimationEngine.attachPressFeedback(negative);
        }
        if (mNeutral != null) {
            any = true;
            neutral.setVisibility(View.VISIBLE);
            neutral.setText(mNeutral);
            neutral.setOnClickListener(v -> {
                if (isClosing()) return;
                if (!mNeutralCloses) {
                    if (mOnNeutral != null) mOnNeutral.run();
                    return;
                }
                close(() -> {
                    if (mOnNeutral != null) mOnNeutral.run();
                    if (mOnClosed != null) mOnClosed.run();
                });
            });
            CSAnimationEngine.attachPressFeedback(neutral);
        }
        if (!any) findViewById(R.id.sp_footer).setVisibility(View.GONE);
        else Motion.fadeIn(findViewById(R.id.sp_footer), 200, Motion.MEDIUM);
    }

    private void submitPositive() {
        if (isClosing()) return;
        if (mHasInput && mOnTextSave != null && mInput != null) {
            String text = mInput.getText() == null ? "" : mInput.getText().toString();
            if (!mOnTextSave.onSave(text)) return; // keep open, the callback showed the error
        }
        final boolean[] multi = mMultiChecked == null ? null : mMultiChecked.clone();
        hideKeyboard();
        close(() -> {
            if (multi != null && mOnMultiSave != null) mOnMultiSave.onSave(multi);
            if (mOnPositive != null) mOnPositive.run();
            if (mOnClosed != null) mOnClosed.run();
        });
    }

    private void hideKeyboard() {
        if (mInput == null) return;
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mInput.getWindowToken(), 0);
    }

    @Override
    protected void onDismissRequested() {
        hideKeyboard();
        close(mOnClosed);
    }
}
