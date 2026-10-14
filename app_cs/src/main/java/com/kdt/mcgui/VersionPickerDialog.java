package com.kdt.mcgui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.modding.modpacks.LibraryCatalog;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Choose a version" of the install screen: full-height popup with search, Minecraft version
 * chips, Compatible / Releases toggles and rich rows (channel tile, loaders, file size, date).
 * The data stays in the fragment; it is read through {@link Source}.
 */
public class VersionPickerDialog extends CSPopupDialog {

    public static final int MARK_NONE = 0, MARK_INSTALLED = 1, MARK_RECOMMENDED = 2;

    public interface Source {
        @NonNull List<Integer> orderedIndices();
        @NonNull String title(int index);
        @NonNull String meta(int index);
        @NonNull String extra(int index);
        @NonNull String[] gameVersions(int index);
        /** "release" / "beta" / "alpha" (anything else is shown as-is) */
        @Nullable String channel(int index);
        boolean compatible(int index);
        boolean downloadable(int index);
        int mark(int index);
    }

    public interface OnPicked {
        void onPicked(int index);
    }

    private final Source mSource;
    private final OnPicked mOnPicked;
    private final CharSequence mProjectTitle, mProfileLabel;
    @Nullable private final Drawable mIcon;
    private final int mSelected;
    private final boolean mHasProfile, mAnyCompatible;

    private final List<Integer> mVisible = new ArrayList<>();
    private Adapter mAdapter;
    private RecyclerView mList;
    private EditText mSearch;
    private TextView mCount, mCompat, mRelease, mJump;
    private View mEmpty;
    private LinearLayout mChips;
    private String mChip = "";
    private boolean mOnlyCompatible, mOnlyRelease;

    public VersionPickerDialog(@NonNull Context context, @NonNull Source source, @NonNull CharSequence projectTitle,
                               @NonNull CharSequence profileLabel, @Nullable Drawable icon, int selected,
                               boolean hasProfile, boolean anyCompatible, @NonNull OnPicked onPicked) {
        super(context);
        mSource = source;
        mProjectTitle = projectTitle;
        mProfileLabel = profileLabel;
        mIcon = icon;
        mSelected = selected;
        mHasProfile = hasProfile;
        mAnyCompatible = anyCompatible;
        mOnPicked = onPicked;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_version_picker);
        View card = findViewById(R.id.vp_card);
        setupPopup(findViewById(R.id.vp_scrim), card, true);
        sizeCard(card);

        ImageView icon = findViewById(R.id.vp_icon);
        if (mIcon != null) icon.setImageDrawable(mIcon); else icon.setImageResource(R.drawable.ic_lib_download);
        icon.setClipToOutline(true);
        ((TextView) findViewById(R.id.vp_eyebrow)).setText(getContext().getString(R.string.vp_versions_for,
                mProfileLabel.toString().toUpperCase(Locale.ROOT)));
        ((TextView) findViewById(R.id.vp_subtitle)).setText(mProjectTitle);
        mCount = findViewById(R.id.vp_count);
        mEmpty = findViewById(R.id.vp_empty);
        mCompat = findViewById(R.id.vp_filter_compat);
        mRelease = findViewById(R.id.vp_filter_release);
        mJump = findViewById(R.id.vp_jump_selected);
        mChips = findViewById(R.id.vp_chips);
        mSearch = findViewById(R.id.vp_search);

        View close = findViewById(R.id.vp_close);
        close.setOnClickListener(v -> onDismissRequested());
        CSAnimationEngine.attachPressFeedback(close);

        mList = findViewById(R.id.vp_list);
        mList.setLayoutManager(new LinearLayoutManager(getContext()));
        mAdapter = new Adapter();
        mList.setAdapter(mAdapter);

        // Compatible filter: on by default when the profile has compatible versions and the
        // current selection is one of them; hidden entirely when there is no profile to compare to
        if (!mHasProfile) {
            mCompat.setVisibility(View.GONE);
        } else {
            mOnlyCompatible = mAnyCompatible && (mSelected < 0 || mSource.compatible(mSelected));
            mCompat.setSelected(mOnlyCompatible);
            mCompat.setOnClickListener(v -> {
                mOnlyCompatible = !mOnlyCompatible;
                mCompat.setSelected(mOnlyCompatible);
                Motion.bounce(v);
                refresh(true);
            });
        }
        mRelease.setOnClickListener(v -> {
            mOnlyRelease = !mOnlyRelease;
            mRelease.setSelected(mOnlyRelease);
            Motion.bounce(v);
            refresh(true);
        });
        mJump.setOnClickListener(v -> {
            int position = mVisible.indexOf(mSelected);
            if (position >= 0) mList.smoothScrollToPosition(position);
            else Motion.shake(v);
        });
        findViewById(R.id.vp_clear_filters).setOnClickListener(v -> {
            mOnlyCompatible = false; mCompat.setSelected(false);
            mOnlyRelease = false; mRelease.setSelected(false);
            mSearch.setText("");
            selectChip("");
            refresh(true);
        });

        buildChips();
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refresh(false); }
        });
        refresh(false);
        int position = mVisible.indexOf(mSelected);
        if (position > 2) mList.scrollToPosition(Math.max(0, position - 1));

        CreatePopupMotion.chooserContent(findViewById(R.id.vp_header), new View[0], findViewById(R.id.vp_tools));
        Motion.riseIn(findViewById(R.id.vp_chips_scroll), 120L, 10f);
        Motion.cascadeChildren(mList, 140L, 28L, 12f);
    }

    private void buildChips() {
        List<String> versions = new ArrayList<>();
        for (int index : mSource.orderedIndices()) {
            for (String version : mSource.gameVersions(index)) {
                if (version != null && !versions.contains(version)) versions.add(version);
            }
        }
        versions.sort((a, b) -> LibraryCatalog.compareVersions(b, a));
        View scroll = findViewById(R.id.vp_chips_scroll);
        if (versions.size() < 2) { scroll.setVisibility(View.GONE); return; }
        List<String> labels = new ArrayList<>();
        labels.add("");
        labels.addAll(versions.subList(0, Math.min(versions.size(), 40)));
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (String label : labels) {
            TextView chip = (TextView) inflater.inflate(R.layout.item_library_chip, mChips, false);
            chip.setBackgroundResource(R.drawable.bg_vp_chip);
            chip.setTextColor(ContextCompat.getColor(getContext(), R.color.hm_text));
            chip.setTextSize(13f);
            ViewGroup.LayoutParams params = chip.getLayoutParams();
            params.height = (int) (34 * dp());
            chip.setLayoutParams(params);
            chip.setText(label.isEmpty() ? getContext().getString(R.string.lib_picker_all) : label);
            chip.setTag(label);
            chip.setSelected(label.isEmpty());
            chip.setOnClickListener(v -> {
                selectChip(label);
                Motion.bounce(v);
                refresh(true);
            });
            mChips.addView(chip);
        }
    }

    private void selectChip(@NonNull String label) {
        mChip = label;
        for (int i = 0; i < mChips.getChildCount(); i++) {
            View child = mChips.getChildAt(i);
            child.setSelected(label.equals(child.getTag()));
        }
    }

    private void refresh(boolean animate) {
        String query = mSearch.getText() == null ? "" : mSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Integer> all = mSource.orderedIndices();
        mVisible.clear();
        for (int index : all) {
            if (mOnlyCompatible && !mSource.compatible(index)) continue;
            if (mOnlyRelease && !isRelease(index)) continue;
            if (!mChip.isEmpty() && !LibraryCatalog.containsVersion(mSource.gameVersions(index), mChip)) continue;
            if (!query.isEmpty()) {
                String hay = (mSource.title(index) + " " + mSource.meta(index) + " " + mSource.extra(index)).toLowerCase(Locale.ROOT);
                if (!hay.contains(query)) continue;
            }
            mVisible.add(index);
        }
        mAdapter.notifyDataSetChanged();
        mCount.setText(getContext().getString(R.string.vp_count_fmt, mVisible.size(), all.size()).toUpperCase(Locale.ROOT));
        boolean empty = mVisible.isEmpty();
        if (empty && mEmpty.getVisibility() != View.VISIBLE) { mEmpty.setVisibility(View.VISIBLE); Motion.popIn(mEmpty, 0L, 0.9f); }
        else if (!empty) mEmpty.setVisibility(View.GONE);
        mJump.setVisibility(mSelected >= 0 && mVisible.contains(mSelected) ? View.VISIBLE : View.INVISIBLE);
        if (animate && !empty) Motion.cascadeChildren(mList, 0L, 18L, 8f);
    }

    private boolean isRelease(int index) {
        String channel = mSource.channel(index);
        return channel == null || channel.isEmpty() || "release".equalsIgnoreCase(channel);
    }

    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = Math.min((int) (640 * d), metrics.widthPixels - (int) (28 * d));
        card.setLayoutParams(params);
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_vp_version, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Context context = holder.itemView.getContext();
            int index = mVisible.get(position);
            holder.name.setText(mSource.title(index));
            String meta = mSource.meta(index);
            holder.meta.setText(meta);
            holder.meta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
            String extra = mSource.extra(index);
            holder.extra.setText(extra);
            holder.extra.setVisibility(extra.isEmpty() ? View.GONE : View.VISIBLE);

            // Channel tile: R / B / A in green / amber / red
            String channel = mSource.channel(index);
            String letter; int color;
            if (isRelease(index)) { letter = "R"; color = 0xFF1E9E45; }
            else if ("beta".equalsIgnoreCase(channel)) { letter = "B"; color = 0xFFD98C1F; }
            else if ("alpha".equalsIgnoreCase(channel)) { letter = "A"; color = 0xFFD93036; }
            else { letter = channel.substring(0, 1).toUpperCase(Locale.ROOT); color = 0xFF5A6474; }
            holder.channelText.setText(letter);
            holder.channel.getBackground().mutate().setTint(color);

            int mark = mSource.mark(index);
            boolean compatible = mSource.compatible(index);
            if (mark == MARK_INSTALLED) badge(holder.badge, R.string.lib_badge_installed, R.drawable.bg_lib_badge_green, R.color.lib_badge_green_text);
            else if (mark == MARK_RECOMMENDED) badge(holder.badge, R.string.lib_badge_recommended, R.drawable.bg_lib_badge_green, R.color.lib_badge_green_text);
            else if (mHasProfile && !compatible) badge(holder.badge, R.string.lib_badge_incompatible, R.drawable.bg_lib_badge_warning, R.color.lib_warning);
            else holder.badge.setVisibility(View.GONE);

            boolean chosen = index == mSelected;
            holder.itemView.setActivated(chosen);
            holder.check.setVisibility(chosen ? View.VISIBLE : View.INVISIBLE);
            holder.checkWrap.getBackground().mutate().setTint(chosen
                    ? ContextCompat.getColor(context, R.color.hm_accent) : 0xFF2E3440);
            holder.itemView.setAlpha(mSource.downloadable(index) ? 1f : 0.45f);
            holder.itemView.setOnClickListener(v -> {
                if (isClosing()) return;
                if (!mSource.downloadable(index)) {
                    Motion.shake(v);
                    Toast.makeText(context, R.string.lib_restricted, Toast.LENGTH_SHORT).show();
                    return;
                }
                holder.itemView.setActivated(true);
                holder.checkWrap.getBackground().mutate().setTint(ContextCompat.getColor(context, R.color.hm_accent));
                holder.check.setVisibility(View.VISIBLE);
                Motion.popIn(holder.check, 0L, 0.3f);
                v.postDelayed(() -> close(() -> mOnPicked.onPicked(index)), 170L);
            });
        }

        private void badge(TextView badge, int text, int bg, int color) {
            badge.setVisibility(View.VISIBLE);
            badge.setText(text);
            badge.setBackgroundResource(bg);
            badge.setTextColor(ContextCompat.getColor(badge.getContext(), color));
        }

        @Override
        public int getItemCount() {
            return mVisible.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final TextView name, meta, extra, badge, channelText;
        final View channel, checkWrap, check;
        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.vpi_name);
            meta = itemView.findViewById(R.id.vpi_meta);
            extra = itemView.findViewById(R.id.vpi_extra);
            badge = itemView.findViewById(R.id.vpi_badge);
            channel = itemView.findViewById(R.id.vpi_channel);
            channelText = itemView.findViewById(R.id.vpi_channel_text);
            checkWrap = itemView.findViewById(R.id.vpi_check_wrap);
            check = itemView.findViewById(R.id.vpi_check);
        }
    }
}
