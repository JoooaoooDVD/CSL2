package com.craftstudio.cslauncher.fragments;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.ProgressLayout;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.authenticator.yggdrasil.AuthServers;
import com.craftstudio.cslauncher.authenticator.yggdrasil.YggdrasilClient;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.util.List;

/**
 * Add account, HMCL / Zalith style and built for phones: one row per built-in sign-in (Microsoft,
 * Offline), then "Other login servers" - Ely.by and every authlib-injector server the user added by
 * URL - each as its own row, an "Add a server" row that opens a small sheet, and the Discord Rich
 * Presence row. On wide screens the two groups sit side by side.
 */
public class SelectAuthFragment extends Fragment {
    public static final String TAG = "AUTH_SELECT_FRAGMENT";

    /** Well-known servers offered as quick picks in the add-server sheet */
    private static final String[][] PRESETS = {
            {"LittleSkin", "littleskin.cn/api/yggdrasil"},
            {"Drasl demo", "drasl.unmojang.org/authlib-injector"},
    };

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private View[] mOptions = new View[0];
    private LinearLayout mServerList;
    @Nullable private Dialog mAddDialog;

    public SelectAuthFragment(){
        super(R.layout.fragment_select_auth_method);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View header = view.findViewById(R.id.select_auth_banner);
        View back = view.findViewById(R.id.select_auth_back);
        View microsoft = view.findViewById(R.id.button_microsoft_authentication);
        View local = view.findViewById(R.id.button_local_authentication);
        View elyBy = view.findViewById(R.id.button_elyby_authentication);
        View addServer = view.findViewById(R.id.button_add_auth_server);
        View discord = view.findViewById(R.id.button_discord_link);
        mServerList = view.findViewById(R.id.select_auth_server_list);
        mOptions = new View[]{microsoft, local, elyBy};
        View[] tiles = {view.findViewById(R.id.auth_microsoft_tile), view.findViewById(R.id.auth_local_tile),
                view.findViewById(R.id.auth_elyby_tile)};

        for (View option : mOptions) {
            option.setClipToOutline(true);
            CSAnimationEngine.attachPressFeedback(option);
        }
        CSAnimationEngine.attachPressFeedback(addServer);
        CSAnimationEngine.attachPressFeedback(discord);
        CSAnimationEngine.attachPressFeedback(back);
        back.setOnClickListener(v -> Tools.removeCurrentFragment(requireActivity()));

        bind(microsoft, tiles[0], MicrosoftLoginFragment.class, MicrosoftLoginFragment.TAG, null);
        bind(local, tiles[1], LocalLoginFragment.class, LocalLoginFragment.TAG, null);
        bind(elyBy, tiles[2], ElyByLoginFragment.class, ElyByLoginFragment.TAG, null);
        addServer.setOnClickListener(v -> {
            Motion.bounce(view.findViewById(R.id.auth_add_tile));
            showAddServerSheet();
        });
        discord.setOnClickListener(v -> {
            Motion.bounce(view.findViewById(R.id.auth_discord_tile));
            com.craftstudio.cslauncher.discord.CSDiscordRpcSheet.show(requireActivity());
        });
        refreshDiscordRow();
        populateServers(false);
        view.post(() -> adaptToWidth(view));

        // Entrance: header, then the rows rise one after another with their icon popping
        Motion.riseIn(header, 30L, 10f);
        for (int i = 0; i < mOptions.length; i++) {
            Motion.riseIn(mOptions[i], 110L + i * 70L, 22f, 460L);
            Motion.popIn(tiles[i], 240L + i * 70L, 0.4f);
        }
        int n = mOptions.length;
        for (int i = 0; i < mServerList.getChildCount(); i++) {
            Motion.riseIn(mServerList.getChildAt(i), 110L + (n + i) * 70L, 22f, 460L);
        }
        Motion.riseIn(addServer, 110L + (n + mServerList.getChildCount()) * 70L, 18f, 460L);
        Motion.riseIn(discord, 520L, 16f);
        Motion.fadeIn(view.findViewById(R.id.select_auth_footnote), 640L, Motion.MEDIUM);
        tiles[0].postDelayed(() -> breathe(tiles[0]), 1400L);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshDiscordRow();
        for (View option : allRows()) {
            if (option.getAlpha() < 1f && option.getAlpha() > 0f) {
                option.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220L).start();
            }
        }
    }

    @Override
    public void onDestroyView() {
        if (mAddDialog != null && mAddDialog.isShowing()) mAddDialog.dismiss();
        mAddDialog = null;
        super.onDestroyView();
    }

    private View[] allRows() {
        View[] rows = new View[mOptions.length + (mServerList == null ? 0 : mServerList.getChildCount())];
        System.arraycopy(mOptions, 0, rows, 0, mOptions.length);
        if (mServerList != null) {
            for (int i = 0; i < mServerList.getChildCount(); i++) rows[mOptions.length + i] = mServerList.getChildAt(i);
        }
        return rows;
    }

    // ---- saved servers -------------------------------------------------------------------------

    /** Rebuilds the server rows from {@link AuthServers}; {@code animateLast} pops the newest in */
    private void populateServers(boolean animateLast) {
        if (mServerList == null) return;
        mServerList.removeAllViews();
        List<AuthServers.Entry> servers = AuthServers.list();
        LayoutInflater inflater = LayoutInflater.from(mServerList.getContext());
        for (AuthServers.Entry entry : servers) {
            View row = inflater.inflate(R.layout.item_auth_server, mServerList, false);
            ((TextView) row.findViewById(R.id.as_name)).setText(entry.name);
            ((TextView) row.findViewById(R.id.as_host)).setText(entry.host());
            ((TextView) row.findViewById(R.id.as_initial)).setText(entry.name.isEmpty() ? "?" : entry.name.substring(0, 1).toUpperCase());
            row.setClipToOutline(true);
            CSAnimationEngine.attachPressFeedback(row);
            bind(row, row.findViewById(R.id.as_tile), CustomLoginFragment.class, CustomLoginFragment.TAG,
                    CustomLoginFragment.argsFor(entry));
            View remove = row.findViewById(R.id.as_remove);
            CSAnimationEngine.attachPressFeedback(remove);
            remove.setOnClickListener(v -> confirmRemove(entry, row));
            mServerList.addView(row);
        }
        if (animateLast && mServerList.getChildCount() > 0) {
            View last = mServerList.getChildAt(mServerList.getChildCount() - 1);
            Motion.riseIn(last, 0L, 24f, 460L);
            Motion.popIn(last.findViewById(R.id.as_tile), 120L, 0.4f);
        }
    }

    private void confirmRemove(@NonNull AuthServers.Entry entry, @NonNull View row) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.auth_remove_server)
                .setMessage(getString(R.string.auth_remove_server_confirm, entry.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.auth_remove_server, (d, w) -> {
                    AuthServers.remove(entry.apiRoot);
                    row.animate().alpha(0f).translationX(row.getWidth() * 0.25f).setDuration(180L)
                            .withEndAction(() -> populateServers(false)).start();
                })
                .show();
    }

    // ---- add-server sheet ----------------------------------------------------------------------

    private void showAddServerSheet() {
        if (getActivity() == null) return;
        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_auth_server, null, false);
        EditText url = content.findViewById(R.id.aas_url);
        TextView status = content.findViewById(R.id.aas_status);
        TextView add = content.findViewById(R.id.aas_add);
        TextView cancel = content.findViewById(R.id.aas_cancel);
        ProgressBar spinner = content.findViewById(R.id.aas_spinner);
        LinearLayout presets = content.findViewById(R.id.aas_presets);

        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            window.setWindowAnimations(R.style.CSBottomSheetAnimation);
        }
        mAddDialog = dialog;

        final YggdrasilClient.Server[] resolved = {null};
        final int[] serial = {0};
        Runnable check = () -> {
            final String typed = url.getText().toString().trim();
            final int mine = ++serial[0];
            spinner.setVisibility(View.VISIBLE);
            status.setText(R.string.cl_server_checking);
            status.setTextColor(getResources().getColor(R.color.hm_text_dim));
            status.setVisibility(View.VISIBLE);
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
                    if (mine != serial[0] || !dialog.isShowing()) return;
                    spinner.setVisibility(View.GONE);
                    resolved[0] = result;
                    if (result != null) {
                        status.setText(getString(R.string.cl_server_ok, result.name));
                        status.setTextColor(getResources().getColor(R.color.hm_accent));
                    } else {
                        status.setText(getString(R.string.cl_server_bad, error == null ? "" : error));
                        status.setTextColor(getResources().getColor(R.color.vl_warn));
                    }
                    add.setEnabled(result != null);
                    add.animate().alpha(result != null ? 1f : 0.5f).setDuration(160L).start();
                });
            });
        };
        url.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                mHandler.removeCallbacks(check);
                resolved[0] = null;
                add.setEnabled(false);
                add.setAlpha(0.5f);
                if (s.toString().trim().length() >= 4) {
                    mHandler.postDelayed(check, 600L);
                } else {
                    status.setVisibility(View.GONE);
                    spinner.setVisibility(View.GONE);
                }
            }
        });
        url.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && add.isEnabled()) { add.performClick(); return true; }
            return false;
        });
        LayoutInflater inflater = LayoutInflater.from(content.getContext());
        for (String[] preset : PRESETS) {
            TextView chip = (TextView) inflater.inflate(R.layout.item_cl_preset, presets, false);
            chip.setText(preset[0]);
            chip.setOnClickListener(v -> {
                url.setText(preset[1]);
                url.setSelection(url.getText().length());
            });
            CSAnimationEngine.attachPressFeedback(chip);
            presets.addView(chip);
        }
        CSAnimationEngine.attachPressFeedback(add);
        CSAnimationEngine.attachPressFeedback(cancel);
        cancel.setOnClickListener(v -> dialog.dismiss());
        add.setOnClickListener(v -> {
            YggdrasilClient.Server server = resolved[0];
            if (server == null) { Motion.shake(content.findViewById(R.id.aas_field)); return; }
            AuthServers.Entry entry = AuthServers.add(server);
            dialog.dismiss();
            populateServers(true);
            Toast.makeText(requireContext(), getString(R.string.auth_server_added, entry.name), Toast.LENGTH_SHORT).show();
        });
        dialog.setOnDismissListener(d -> {
            mHandler.removeCallbacks(check);
            if (mAddDialog == d) mAddDialog = null;
        });
        dialog.show();
        url.requestFocus();
    }

    // ---- layout / motion -----------------------------------------------------------------------

    private static void breathe(@NonNull View tile) {
        if (!tile.isAttachedToWindow()) return;
        android.animation.ObjectAnimator x = android.animation.ObjectAnimator.ofFloat(tile, View.SCALE_X, 1f, 1.06f, 1f);
        android.animation.ObjectAnimator y = android.animation.ObjectAnimator.ofFloat(tile, View.SCALE_Y, 1f, 1.06f, 1f);
        for (android.animation.ObjectAnimator animator : new android.animation.ObjectAnimator[]{x, y}) {
            animator.setDuration(2400L);
            animator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            animator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            animator.start();
        }
    }

    /** Wide screens (landscape tablets / phones): the two groups sit side by side */
    private void adaptToWidth(@NonNull View root) {
        if (getView() == null) return;
        float density = getResources().getDisplayMetrics().density;
        float widthDp = root.getWidth() / density;
        if (widthDp < 640f) return;
        LinearLayout columns = root.findViewById(R.id.select_auth_columns);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < columns.getChildCount(); i++) {
            View child = columns.getChildAt(i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) params.setMarginStart(Math.round(12 * density));
            child.setLayoutParams(params);
        }
    }

    private void bind(@NonNull View option, @NonNull View tile, Class<? extends Fragment> fragmentClass, String tag,
                      @Nullable Bundle args) {
        option.setOnClickListener(v -> {
            Motion.bounce(tile);
            for (View other : allRows()) {
                if (other != option) other.animate().alpha(0.35f).scaleX(0.97f).scaleY(0.97f).setDuration(180L).start();
            }
            CreatePopupMotion.optionChosen(option, () -> launchAuthFragment(fragmentClass, tag, args));
        });
    }

    private void refreshDiscordRow() {
        View root = getView();
        if (root == null) return;
        TextView status = root.findViewById(R.id.auth_discord_status);
        Context context = root.getContext();
        status.setText(R.string.auth_desc_discord);
    }

    private void launchAuthFragment(Class<? extends  Fragment> fragmentClass, String fragmentTag, @Nullable Bundle args) {
        if (getActivity() == null) return;
        if(ProgressKeeper.hasProgressKey(ProgressLayout.AUTHENTICATE)) {
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_SHORT).show();
            return;
        }
        Tools.swapFragment(requireActivity(), fragmentClass, fragmentTag, args);
    }
}
