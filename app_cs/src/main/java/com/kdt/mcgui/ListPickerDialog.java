package com.kdt.mcgui;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Big, searchable single-choice picker used by the create-instance form (Minecraft version,
 * loader version, Java, renderer). Replaces the tiny anchored dropdown that was hard to use on
 * phones. Picked index refers to the original list.
 */
public class ListPickerDialog extends CSPopupDialog {

    public interface OnPicked {
        void onPicked(int index);
    }

    private final CharSequence mEyebrow, mTitle;
    private final List<String> mItems;
    @Nullable private final List<String> mBadges;
    private final int mSelected;
    private final OnPicked mOnPicked;
    private final List<Integer> mVisible = new ArrayList<>();
    private Adapter mAdapter;
    private TextView mCount, mEmpty;

    public ListPickerDialog(@NonNull Context context, @Nullable CharSequence eyebrow, @NonNull CharSequence title,
                            @NonNull List<String> items, @Nullable List<String> badges, int selected, @NonNull OnPicked onPicked) {
        super(context);
        mEyebrow = eyebrow;
        mTitle = title;
        mItems = items;
        mBadges = badges;
        mSelected = selected;
        mOnPicked = onPicked;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_list_picker);
        View card = findViewById(R.id.lp_card);
        setupPopup(findViewById(R.id.lp_scrim), card, true);
        sizeCard(card);

        TextView eyebrow = findViewById(R.id.lp_eyebrow);
        if (mEyebrow == null) eyebrow.setVisibility(View.GONE); else eyebrow.setText(mEyebrow);
        ((TextView) findViewById(R.id.lp_title)).setText(mTitle);
        mCount = findViewById(R.id.lp_count);
        mEmpty = findViewById(R.id.lp_empty);

        View close = findViewById(R.id.lp_close);
        close.setOnClickListener(v -> onDismissRequested());
        CSAnimationEngine.attachPressFeedback(close);

        RecyclerView list = findViewById(R.id.lp_list);
        list.setLayoutManager(new LinearLayoutManager(getContext()));
        mAdapter = new Adapter();
        list.setAdapter(mAdapter);

        View searchBox = findViewById(R.id.lp_search_box);
        EditText search = findViewById(R.id.lp_search);
        if (mItems.size() <= 8) {
            searchBox.setVisibility(View.GONE);
        } else {
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) { filter(s.toString()); }
            });
        }
        filter("");
        int position = mVisible.indexOf(mSelected);
        if (position > 2) list.scrollToPosition(position);

        CreatePopupMotion.chooserContent(findViewById(R.id.lp_header), new View[0], searchBox);
        Motion.cascadeChildren(list, 100L, 30L, 12f);
    }

    private void filter(@NonNull String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        mVisible.clear();
        for (int i = 0; i < mItems.size(); i++) {
            if (q.isEmpty() || mItems.get(i).toLowerCase(Locale.ROOT).contains(q)) mVisible.add(i);
        }
        mAdapter.notifyDataSetChanged();
        mCount.setText(String.valueOf(mVisible.size()));
        mEmpty.setVisibility(mVisible.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = Math.min((int) (420 * d), metrics.widthPixels - (int) (40 * d));
        card.setLayoutParams(params);
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_list_picker, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            int index = mVisible.get(position);
            holder.text.setText(mItems.get(index));
            boolean chosen = index == mSelected;
            holder.itemView.setActivated(chosen);
            holder.text.setTypeface(null, chosen ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            holder.check.setVisibility(chosen ? View.VISIBLE : View.INVISIBLE);
            String badge = mBadges != null && index < mBadges.size() ? mBadges.get(index) : null;
            if (badge == null || badge.isEmpty()) holder.badge.setVisibility(View.GONE);
            else { holder.badge.setVisibility(View.VISIBLE); holder.badge.setText(badge); }
            holder.itemView.setOnClickListener(v -> {
                if (isClosing()) return;
                holder.itemView.setActivated(true);
                holder.check.setVisibility(View.VISIBLE);
                Motion.popIn(holder.check, 0L, 0.4f);
                v.postDelayed(() -> close(() -> { if (index != mSelected) mOnPicked.onPicked(index); }), 160L);
            });
        }

        @Override
        public int getItemCount() {
            return mVisible.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final TextView text, badge;
        final View check;
        Holder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.lpi_text);
            badge = itemView.findViewById(R.id.lpi_badge);
            check = itemView.findViewById(R.id.lpi_check);
        }
    }
}
