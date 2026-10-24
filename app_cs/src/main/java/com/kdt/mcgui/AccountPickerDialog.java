package com.kdt.mcgui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.authenticator.AuthType;
import com.craftstudio.cslauncher.authenticator.accounts.Account;
import com.craftstudio.cslauncher.prefs.SettingsPopup;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.util.List;

/**
 * The account menu behind the top-bar pill: one card per saved account (skin head, name, type,
 * active state, skin editor and remove buttons) and an "Add account" button. Replaces the stock
 * spinner dropdown; the pill itself is still the spinner so nothing else changes.
 */
public class AccountPickerDialog extends CSPopupDialog {

    public interface Callback {
        void onAccountPicked(@NonNull Account account);
        void onSkinEditor(@NonNull Account account);
        void onDelete(@NonNull Account account);
        void onAddAccount();
    }

    private final List<Account> mAccounts;
    private final int mSelected;
    private final Callback mCallback;

    public AccountPickerDialog(@NonNull Context context, @NonNull List<Account> accounts, int selected, @NonNull Callback callback) {
        super(context);
        mAccounts = accounts;
        mSelected = selected;
        mCallback = callback;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_account_picker);
        View card = findViewById(R.id.ap_card);
        setupPopup(findViewById(R.id.ap_scrim), card, true);
        sizeCard(card);

        View close = findViewById(R.id.ap_close);
        close.setOnClickListener(v -> onDismissRequested());
        CSAnimationEngine.attachPressFeedback(close);

        Context ctx = getContext();
        ImageView headerHead = findViewById(R.id.ap_head);
        headerHead.setImageDrawable(headOf(mSelected >= 0 && mSelected < mAccounts.size() ? mAccounts.get(mSelected) : null));
        TextView subtitle = findViewById(R.id.ap_subtitle);
        int n = mAccounts.size();
        subtitle.setText((n == 1 ? ctx.getString(R.string.ap_count_one) : ctx.getString(R.string.ap_count_many, n))
                + "  ·  " + ctx.getString(R.string.ap_subtitle));

        ViewGroup list = findViewById(R.id.ap_list);
        LayoutInflater inflater = LayoutInflater.from(ctx);
        for (int i = 0; i < mAccounts.size(); i++) addCard(inflater, list, mAccounts.get(i), i == mSelected);
        findViewById(R.id.ap_empty).setVisibility(mAccounts.isEmpty() ? View.VISIBLE : View.GONE);

        View add = findViewById(R.id.ap_add);
        add.setOnClickListener(v -> { if (!isClosing()) close(mCallback::onAddAccount); });
        CSAnimationEngine.attachPressFeedback(add);

        CreatePopupMotion.chooserContent(findViewById(R.id.ap_header), new View[0], subtitle);
        Motion.cascadeChildren(list, 120L, 40L, 14f);
        Motion.fadeIn(add, 300L, 260L);
    }

    private void addCard(LayoutInflater inflater, ViewGroup list, final Account account, boolean selected) {
        final View row = inflater.inflate(R.layout.item_account_card, list, false);
        ((ImageView) row.findViewById(R.id.ac_head)).setImageDrawable(headOf(account));
        ((TextView) row.findViewById(R.id.ac_name)).setText(account.username);
        TextView type = row.findViewById(R.id.ac_type);
        ImageView typeIcon = row.findViewById(R.id.ac_type_icon);
        if (account.authType == AuthType.MICROSOFT) type.setText(R.string.ap_type_microsoft);
        else if (account.authType == AuthType.ELY_BY) type.setText(R.string.ap_type_elyby);
        else type.setText(R.string.ap_type_local);
        if (account.authType.iconResource != 0) {
            typeIcon.setImageResource(account.authType.iconResource);
            typeIcon.setVisibility(View.VISIBLE);
        }
        row.findViewById(R.id.ac_active).setVisibility(selected ? View.VISIBLE : View.GONE);
        row.findViewById(R.id.ac_check).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.setActivated(selected);

        View skin = row.findViewById(R.id.ac_skin);
        boolean canEditSkin = account.authType == AuthType.MICROSOFT || account.authType == AuthType.LOCAL;
        skin.setVisibility(canEditSkin ? View.VISIBLE : View.GONE);
        skin.setOnClickListener(v -> { if (!isClosing()) close(() -> mCallback.onSkinEditor(account)); });
        CSAnimationEngine.attachPressFeedback(skin);

        View delete = row.findViewById(R.id.ac_delete);
        delete.setOnClickListener(v -> {
            if (isClosing()) return;
            new SettingsPopup(getContext())
                    .title(getContext().getString(R.string.ap_delete_title, account.username))
                    .message(R.string.warning_remove_account)
                    .positive(R.string.global_delete, () -> close(() -> mCallback.onDelete(account)))
                    .negative(android.R.string.cancel, null)
                    .show();
        });
        CSAnimationEngine.attachPressFeedback(delete);

        row.setOnClickListener(v -> {
            if (isClosing()) return;
            for (int i = 0; i < list.getChildCount(); i++) {
                View other = list.getChildAt(i);
                other.setActivated(other == row);
                other.findViewById(R.id.ac_check).setVisibility(other == row ? View.VISIBLE : View.INVISIBLE);
            }
            Motion.bounce(row.findViewById(R.id.ac_head));
            Motion.popIn(row.findViewById(R.id.ac_check), 0L, 0.4f);
            row.postDelayed(() -> close(() -> mCallback.onAccountPicked(account)), 200L);
        });
        CSAnimationEngine.attachPressFeedback(row);
        list.addView(row);
    }

    private BitmapDrawable headOf(@Nullable Account account) {
        Bitmap face = account == null ? null : account.getSkinFace();
        if (face == null) face = BitmapFactory.decodeResource(getContext().getResources(), R.drawable.ic_default_head);
        BitmapDrawable d = new BitmapDrawable(getContext().getResources(), face);
        d.setFilterBitmap(false); // crisp pixels
        return d;
    }

    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = Math.min((int) (520 * d), metrics.widthPixels - (int) (40 * d));
        int maxHeight = metrics.heightPixels - (int) (24 * d);
        int wanted = (int) (Math.max(mAccounts.size(), 1) * 78 * d + 200 * d);
        params.height = wanted > maxHeight ? maxHeight : ViewGroup.LayoutParams.WRAP_CONTENT;
        card.setLayoutParams(params);
    }
}
