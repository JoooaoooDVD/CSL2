package com.craftstudio.cslauncher.friends.ui;

import android.app.Activity;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.friends.CsAccount;
import com.craftstudio.cslauncher.friends.FriendsClient;
import com.craftstudio.cslauncher.utils.motion.Motion;

/** Sign in / create account / change password for the launcher's CS account. */
public final class AccountDialog {
    private AccountDialog() {}

    public static void show(@NonNull Activity activity, boolean register, @Nullable Runnable onDone) {
        LinearLayout box = box(activity);
        TextView hint = text(activity, activity.getString(R.string.friends_account_hint));
        box.addView(hint);
        EditText user = field(activity, R.string.friends_username, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText pass = field(activity, R.string.friends_password, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText name = field(activity, R.string.friends_display_name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.addView(user);
        box.addView(pass);
        if (register) {
            com.craftstudio.cslauncher.authenticator.accounts.Account current = com.craftstudio.cslauncher.authenticator.accounts.Accounts.getCurrent();
            if (current != null && current.username != null) name.setText(current.username);
            box.addView(name);
        }
        TextView error = text(activity, "");
        error.setTextColor(ContextCompat.getColor(activity, R.color.vl_bad));
        error.setVisibility(View.GONE);
        box.addView(error);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(register ? R.string.friends_create_account : R.string.friends_sign_in)
                .setView(box)
                .setPositiveButton(register ? R.string.friends_create_account : R.string.friends_sign_in, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String u = user.getText().toString().trim();
            String p = pass.getText().toString();
            if (!CsAccount.validUsername(u)) { fail(error, "Username: 3–16 letters, digits or _"); shake(user); return; }
            if (!CsAccount.validPassword(p)) { fail(error, "Password: at least 6 characters"); shake(pass); return; }
            error.setVisibility(View.GONE);
            v.setEnabled(false);
            ((TextView) v).setText(R.string.friends_working);
            FriendsClient.get().account(register, u, p, register ? name.getText().toString() : null, e -> {
                if (!dialog.isShowing()) return;
                v.setEnabled(true);
                ((TextView) v).setText(register ? R.string.friends_create_account : R.string.friends_sign_in);
                if (e != null) { fail(error, e); return; }
                dialog.dismiss();
                Toast.makeText(activity, "Signed in as " + CsAccount.username(), Toast.LENGTH_SHORT).show();
                if (onDone != null) onDone.run();
            });
        }));
        dialog.show();
    }

    public static void showChangePassword(@NonNull Activity activity) {
        LinearLayout box = box(activity);
        EditText oldPw = field(activity, R.string.friends_old_password, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText newPw = field(activity, R.string.friends_new_password, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView error = text(activity, "");
        error.setTextColor(ContextCompat.getColor(activity, R.color.vl_bad));
        error.setVisibility(View.GONE);
        box.addView(oldPw);
        box.addView(newPw);
        box.addView(error);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.friends_change_password)
                .setView(box)
                .setPositiveButton(R.string.friends_change_password, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = newPw.getText().toString();
            if (!CsAccount.validPassword(n)) { fail(error, "New password: at least 6 characters"); shake(newPw); return; }
            v.setEnabled(false);
            FriendsClient.get().changePassword(oldPw.getText().toString(), n, e -> {
                if (!dialog.isShowing()) return;
                v.setEnabled(true);
                if (e != null) { fail(error, e); return; }
                dialog.dismiss();
                Toast.makeText(activity, "Password changed", Toast.LENGTH_SHORT).show();
            });
        }));
        dialog.show();
    }

    private static void fail(TextView error, String msg) {
        error.setText(msg);
        error.setVisibility(View.VISIBLE);
    }

    private static void shake(View v) {
        if (!Motion.content(v)) return;
        v.animate().translationX(10f).setDuration(50).withEndAction(() ->
                v.animate().translationX(-10f).setDuration(50).withEndAction(() ->
                        v.animate().translationX(0f).setDuration(50).start()).start()).start();
    }

    private static LinearLayout box(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 20);
        l.setPadding(pad, dp(a, 8), pad, 0);
        return l;
    }

    private static TextView text(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setTextColor(ContextCompat.getColor(a, R.color.vl_text_dim));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private static EditText field(Activity a, int hint, int inputType) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setBackgroundResource(R.drawable.bg_vl_field);
        e.setTextColor(ContextCompat.getColor(a, R.color.vl_text));
        e.setHintTextColor(ContextCompat.getColor(a, R.color.vl_text_faint));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setGravity(Gravity.CENTER_VERTICAL);
        e.setPadding(dp(a, 14), 0, dp(a, 14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 44));
        lp.bottomMargin = dp(a, 8);
        e.setLayoutParams(lp);
        return e;
    }

    private static int dp(Activity a, int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }
}
