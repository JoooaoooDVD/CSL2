package com.craftstudio.cslauncher.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.gson.JsonObject;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.authenticator.yggdrasil.YggdrasilClient;
import com.craftstudio.cslauncher.extra.ExtraConstants;
import com.craftstudio.cslauncher.extra.ExtraCore;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * Sign in on any Yggdrasil / authlib-injector server. The server is checked while typing (its name
 * and register link come from the metadata); the sign-in itself runs in
 * {@link com.craftstudio.cslauncher.authenticator.impl.CustomYggdrasilLogin} through the account
 * spinner, like the other account types.
 */
public class CustomLoginFragment extends Fragment {
    public static final String TAG = "CUSTOM_LOGIN_FRAGMENT";
    /** Arguments: a server picked on the Add-account page (the field is then not shown) */
    public static final String ARG_API_ROOT = "api_root";
    public static final String ARG_NAME = "name";
    public static final String ARG_HOMEPAGE = "homepage";
    public static final String ARG_REGISTER = "register";

    public static Bundle argsFor(@NonNull com.craftstudio.cslauncher.authenticator.yggdrasil.AuthServers.Entry entry) {
        Bundle b = new Bundle();
        b.putString(ARG_API_ROOT, entry.apiRoot);
        b.putString(ARG_NAME, entry.name);
        b.putString(ARG_HOMEPAGE, entry.homepage);
        b.putString(ARG_REGISTER, entry.register);
        return b;
    }

    /** Well-known servers, shown as quick picks */
    private static final String[][] PRESETS = {
            {"Ely.by", "authserver.ely.by/api/authlib-injector"},
            {"LittleSkin", "littleskin.cn/api/yggdrasil"},
    };

    private EditText mServer, mUsername, mPassword;
    private TextView mServerStatus, mError, mRegister, mSubmitText, mShowPassword;
    private ProgressBar mSpinner;
    private View mSubmit;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mCheckSerial;
    @Nullable private YggdrasilClient.Server mResolved;
    private boolean mSubmitting;

    public CustomLoginFragment() {
        super(R.layout.fragment_custom_login);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View back = view.findViewById(R.id.cl_back);
        back.setOnClickListener(v -> Tools.removeCurrentFragment(requireActivity()));
        CSAnimationEngine.attachPressFeedback(back);

        mServer = view.findViewById(R.id.cl_server);
        mUsername = view.findViewById(R.id.cl_username);
        mPassword = view.findViewById(R.id.cl_password);
        mServerStatus = view.findViewById(R.id.cl_server_status);
        mError = view.findViewById(R.id.cl_error);
        mRegister = view.findViewById(R.id.cl_register);
        mSubmit = view.findViewById(R.id.cl_submit);
        mSubmitText = view.findViewById(R.id.cl_submit_text);
        mSpinner = view.findViewById(R.id.cl_spinner);
        mShowPassword = view.findViewById(R.id.cl_show_password);

        LinearLayout presets = view.findViewById(R.id.cl_presets);
        LayoutInflater inflater = LayoutInflater.from(view.getContext());
        for (String[] preset : PRESETS) {
            TextView chip = (TextView) inflater.inflate(R.layout.item_cl_preset, presets, false);
            chip.setText(preset[0]);
            chip.setOnClickListener(v -> {
                mServer.setText(preset[1]);
                mServer.setSelection(mServer.getText().length());
                mUsername.requestFocus();
            });
            CSAnimationEngine.attachPressFeedback(chip);
            presets.addView(chip);
        }

        mServer.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (getArguments() != null && getArguments().getString(ARG_API_ROOT) != null) return;
                mResolved = null;
                mRegister.setVisibility(View.GONE);
                hideError();
                mHandler.removeCallbacks(mCheck);
                if (s.toString().trim().length() >= 4) {
                    mServerStatus.setText(R.string.cl_server_checking);
                    mServerStatus.setVisibility(View.VISIBLE);
                    mHandler.postDelayed(mCheck, 650L);
                } else {
                    mServerStatus.setVisibility(View.GONE);
                }
            }
        });
        TextWatcher clearError = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { hideError(); }
        };
        mUsername.addTextChangedListener(clearError);
        mPassword.addTextChangedListener(clearError);
        mPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); return true; }
            return false;
        });
        mShowPassword.setOnClickListener(v -> {
            boolean hidden = (mPassword.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
            mPassword.setInputType(InputType.TYPE_CLASS_TEXT | (hidden
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            mPassword.setSelection(mPassword.getText().length());
            mShowPassword.setText(hidden ? R.string.cl_hide : R.string.cl_show);
        });
        mSubmit.setOnClickListener(v -> submit());
        CSAnimationEngine.attachPressFeedback(mSubmit);
        mRegister.setOnClickListener(v -> {
            YggdrasilClient.Server server = mResolved;
            String url = server == null ? null : (server.register != null ? server.register : server.homepage);
            if (url == null) return;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {}
        });

        Bundle args = getArguments();
        String lockedRoot = args == null ? null : args.getString(ARG_API_ROOT);
        if (lockedRoot != null) {
            String name = args.getString(ARG_NAME, lockedRoot);
            mResolved = new YggdrasilClient.Server(lockedRoot, name, args.getString(ARG_HOMEPAGE), args.getString(ARG_REGISTER));
            view.findViewById(R.id.cl_server_block).setVisibility(View.GONE);
            View locked = view.findViewById(R.id.cl_server_locked);
            locked.setVisibility(View.VISIBLE);
            ((TextView) view.findViewById(R.id.cl_locked_name)).setText(name);
            ((TextView) view.findViewById(R.id.cl_locked_host)).setText(
                    new com.craftstudio.cslauncher.authenticator.yggdrasil.AuthServers.Entry(lockedRoot, name, null, null).host());
            ((TextView) view.findViewById(R.id.cl_locked_initial)).setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
            ((TextView) view.findViewById(R.id.cl_title)).setText(getString(R.string.cl_sign_in_on, name));
            mServer.setText(lockedRoot);
            mRegister.setVisibility(mResolved.register != null || mResolved.homepage != null ? View.VISIBLE : View.GONE);
            mUsername.requestFocus();
            Motion.riseIn(locked, 60L, 14f);
        }

        // Phones in portrait: the explainer goes under the form
        view.post(() -> {
            if (getView() == null) return;
            float widthDp = view.getWidth() / getResources().getDisplayMetrics().density;
            if (widthDp > 0 && widthDp < 560f) {
                LinearLayout columns = view.findViewById(R.id.cl_columns);
                columns.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < columns.getChildCount(); i++) {
                    View child = columns.getChildAt(i);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (i > 0) lp.topMargin = Math.round(10 * getResources().getDisplayMetrics().density);
                    child.setLayoutParams(lp);
                }
            }
        });
    }

    @Override
    public void onDestroyView() {
        mHandler.removeCallbacks(mCheck);
        super.onDestroyView();
    }

    private final Runnable mCheck = this::checkServer;

    /** Resolves the typed server in the background and shows its name (metadata) */
    private void checkServer() {
        final String typed = mServer.getText().toString().trim();
        final int serial = ++mCheckSerial;
        CSLauncherApplication.sExecutorService.execute(() -> {
            YggdrasilClient.Server server = null;
            String failure = null;
            try {
                server = YggdrasilClient.resolveApiRoot(typed);
            } catch (Exception e) {
                failure = e.getMessage();
            }
            final YggdrasilClient.Server result = server;
            final String error = failure;
            mHandler.post(() -> {
                if (serial != mCheckSerial || getView() == null) return;
                if (result != null) {
                    mResolved = result;
                    mServerStatus.setText(getString(R.string.cl_server_ok, result.name));
                    mServerStatus.setTextColor(getResources().getColor(R.color.hm_accent));
                    mRegister.setVisibility(result.register != null || result.homepage != null ? View.VISIBLE : View.GONE);
                } else {
                    mServerStatus.setText(getString(R.string.cl_server_bad, error == null ? "" : error));
                    mServerStatus.setTextColor(getResources().getColor(R.color.vl_warn));
                }
                mServerStatus.setVisibility(View.VISIBLE);
            });
        });
    }

    private void submit() {
        if (mSubmitting) return;
        String server = mServer.getText().toString().trim();
        String username = mUsername.getText().toString().trim();
        String password = mPassword.getText().toString();
        if (server.isEmpty()) { showError(R.string.cl_error_server_empty); mServer.requestFocus(); return; }
        if (username.isEmpty()) { showError(R.string.cl_error_username_empty); mUsername.requestFocus(); return; }
        if (password.isEmpty()) { showError(R.string.cl_error_password_empty); mPassword.requestFocus(); return; }
        mSubmitting = true;
        mSpinner.setVisibility(View.VISIBLE);
        mSubmitText.setText(R.string.cl_signing_in);
        JsonObject request = new JsonObject();
        request.addProperty("server", mResolved != null ? mResolved.apiRoot : server);
        request.addProperty("username", username);
        request.addProperty("password", password);
        // The account spinner runs CustomYggdrasilLogin and shows progress / errors in the top bar
        ExtraCore.setValue(ExtraConstants.CUSTOM_LOGIN_TODO, request.toString());
        mHandler.postDelayed(() -> {
            if (getActivity() != null && isAdded()) Tools.removeCurrentFragment(requireActivity());
        }, 250L);
    }

    private void showError(int text) {
        mError.setText(text);
        mError.setVisibility(View.VISIBLE);
        Motion.shake(mError);
    }

    private void hideError() {
        mError.setVisibility(View.GONE);
    }
}
