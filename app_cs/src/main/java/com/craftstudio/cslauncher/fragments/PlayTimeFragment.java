package com.craftstudio.cslauncher.fragments;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.instances.DisplayInstance;
import com.craftstudio.cslauncher.instances.InstanceIconProvider;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.stats.PlayTimeTracker;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Play Time screen (top bar dock): lifetime total, today / last 7 days, session stats,
 * time per profile and the recent session list. Everything comes from
 * {@link PlayTimeTracker}; the screen only reads, on a background thread, and refreshes itself
 * while a game is running so the live numbers tick.
 */
public class PlayTimeFragment extends Fragment {
    public static final String TAG = "PlayTimeFragment";

    private static final int SESSIONS_PAGE = 12;

    private TextView mTotal, mTotalSub, mSince, mLiveText, mShowMore;
    private View mLive, mEmpty, mHero, mProfilesCard, mSessionsCard;
    private LinearLayout mProfiles, mSessions;
    private final Map<Integer, TextView> mStatValues = new HashMap<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = () -> load(false);
    private int mSessionLimit = SESSIONS_PAGE;
    private boolean mFirstRender = true;
    private int mGeneration;

    public PlayTimeFragment() {
        super(R.layout.fragment_play_time);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mTotal = view.findViewById(R.id.pt_total);
        mTotalSub = view.findViewById(R.id.pt_total_sub);
        mSince = view.findViewById(R.id.pt_since);
        mLive = view.findViewById(R.id.pt_live);
        mLiveText = view.findViewById(R.id.pt_live_text);
        mEmpty = view.findViewById(R.id.pt_empty);
        mHero = view.findViewById(R.id.pt_hero);
        mProfilesCard = view.findViewById(R.id.pt_profiles_card);
        mSessionsCard = view.findViewById(R.id.pt_sessions_card);
        mProfiles = view.findViewById(R.id.pt_profiles);
        mSessions = view.findViewById(R.id.pt_sessions);
        mShowMore = view.findViewById(R.id.pt_show_more);

        bindStat(view, R.id.pt_stat_today, R.string.pt_stat_today);
        bindStat(view, R.id.pt_stat_week, R.string.pt_stat_week);
        bindStat(view, R.id.pt_stat_sessions, R.string.pt_stat_sessions);
        bindStat(view, R.id.pt_stat_average, R.string.pt_stat_average);
        bindStat(view, R.id.pt_stat_longest, R.string.pt_stat_longest);
        bindStat(view, R.id.pt_stat_last, R.string.pt_stat_last);

        View refresh = view.findViewById(R.id.pt_refresh_button);
        refresh.setOnClickListener(v -> {
            Motion.rotateTo(v, v.getRotation() + 360f);
            load(true);
        });
        mShowMore.setOnClickListener(v -> {
            mSessionLimit += SESSIONS_PAGE * 2;
            load(false);
        });
        CSAnimationEngine.attachPressFeedback(mShowMore);

        Motion.riseIn(view.findViewById(R.id.pt_banner), 0L, 8f);
        Motion.riseIn(mHero, 60L, 12f);
    }

    private void bindStat(View root, int includeId, int labelRes) {
        View tile = root.findViewById(includeId);
        if (tile == null) return;
        ((TextView) tile.findViewById(R.id.pt_stat_label)).setText(labelRes);
        mStatValues.put(includeId, (TextView) tile.findViewById(R.id.pt_stat_value));
    }

    @Override
    public void onResume() {
        super.onResume();
        load(false);
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mHandler.removeCallbacks(mTick);
        mStatValues.clear();
        mTotal = mTotalSub = mSince = mLiveText = mShowMore = null;
        mLive = mEmpty = mHero = mProfilesCard = mSessionsCard = null;
        mProfiles = mSessions = null;
    }

    // ─── Loading ─────────────────────────────────────────────────────────────

    private void load(boolean animateNumbers) {
        mHandler.removeCallbacks(mTick);
        if (getView() == null) return;
        final int generation = ++mGeneration;
        CSLauncherApplication.sExecutorService.execute(() -> {
            PlayTimeTracker.Summary summary = PlayTimeTracker.summary();
            // Icons + current names of the profiles that still exist
            Map<String, DisplayInstance> instances = new HashMap<>();
            try {
                for (DisplayInstance instance : Instances.loadDisplay().list) {
                    if (instance.getKey() != null) instances.put(instance.getKey(), instance);
                }
            } catch (IOException | RuntimeException ignored) {
                // profile folder unreadable: the names stored with the sessions are used
            }
            Tools.runOnUiThread(() -> {
                if (generation != mGeneration || getView() == null || !isAdded()) return;
                render(summary, instances, animateNumbers);
                // Live game: tick every second so the running session counts up; otherwise keep
                // the "ago" labels fresh
                long next = summary.liveSession != null ? 1_000L
                        : summary.lastPlayed > 0 ? PlayTimeTracker.refreshIntervalFor(summary.lastPlayed) : 60_000L;
                if (isResumed()) mHandler.postDelayed(mTick, next);
            });
        });
    }

    // ─── Rendering ───────────────────────────────────────────────────────────

    private void render(@NonNull PlayTimeTracker.Summary summary, @NonNull Map<String, DisplayInstance> instances, boolean animateNumbers) {
        Context context = requireContext();
        long now = System.currentTimeMillis();
        boolean empty = summary.sessionCount == 0 && summary.liveSession == null;

        // Live session banner
        PlayTimeTracker.Session live = summary.liveSession;
        long liveMs = live != null ? Math.max(0L, now - live.start) : 0L;
        if (live != null) {
            mLiveText.setText(getString(R.string.pt_live_banner, nameOf(live.profileKey, live.profileName, instances),
                    PlayTimeTracker.formatDuration(liveMs)));
            if (mLive.getVisibility() != View.VISIBLE) {
                mLive.setVisibility(View.VISIBLE);
                Motion.riseIn(mLive, 0L, 8f);
                Motion.pulse(mLive.findViewById(R.id.pt_live_dot));
            }
        } else if (mLive.getVisibility() == View.VISIBLE) {
            mLive.setVisibility(View.GONE);
        }

        // Hero numbers (the live session is already folded into the totals by the tracker)
        long total = summary.totalMs;
        String totalShort = PlayTimeTracker.formatDuration(total);
        if (!totalShort.contentEquals(mTotal.getText())) {
            if (animateNumbers && !mFirstRender) Motion.swapText(mTotal, totalShort);
            else mTotal.setText(totalShort);
        }
        String sessions = getResources().getQuantityString(R.plurals.pt_sessions_count, summary.sessionCount, summary.sessionCount);
        mTotalSub.setText(total > 0 ? PlayTimeTracker.formatDurationLong(context, total) + " · " + sessions : sessions);

        setStat(R.id.pt_stat_today, PlayTimeTracker.formatDuration(summary.todayMs));
        setStat(R.id.pt_stat_week, PlayTimeTracker.formatDuration(summary.weekMs));
        setStat(R.id.pt_stat_sessions, String.valueOf(summary.sessionCount));
        setStat(R.id.pt_stat_average, PlayTimeTracker.formatDuration(summary.averageMs));
        setStat(R.id.pt_stat_longest, PlayTimeTracker.formatDuration(Math.max(summary.longestMs, liveMs)));
        setStat(R.id.pt_stat_last, live != null ? getString(R.string.pt_home_playing_now)
                : summary.lastPlayed > 0 ? PlayTimeTracker.formatAgo(context, summary.lastPlayed) : getString(R.string.pt_never));
        if (summary.firstPlayed > 0) {
            mSince.setVisibility(View.VISIBLE);
            mSince.setText(getString(R.string.pt_stat_since) + " " + formatDate(context, summary.firstPlayed, false));
        } else {
            mSince.setVisibility(View.GONE);
        }

        mEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        mProfilesCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        mSessionsCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            if (mFirstRender) Motion.riseIn(mEmpty, 120L, 12f);
            mFirstRender = false;
            return;
        }

        renderProfiles(summary, instances, total);
        renderSessions(summary, instances, now);

        if (mFirstRender) {
            Motion.riseIn(mProfilesCard, 120L, 12f);
            Motion.riseIn(mSessionsCard, 180L, 12f);
            Motion.cascadeChildren(mProfiles, 200L, 40L, 8f);
        }
        mFirstRender = false;
    }

    private void setStat(int id, @NonNull String value) {
        TextView view = mStatValues.get(id);
        if (view != null && !value.contentEquals(view.getText())) view.setText(value);
    }

    private void renderProfiles(@NonNull PlayTimeTracker.Summary summary, @NonNull Map<String, DisplayInstance> instances, long total) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        List<PlayTimeTracker.ProfileStats> list = summary.profiles;
        // Reuse rows: same count most of the time, only the text changes on a tick
        while (mProfiles.getChildCount() > list.size()) mProfiles.removeViewAt(mProfiles.getChildCount() - 1);
        for (int i = 0; i < list.size(); i++) {
            PlayTimeTracker.ProfileStats p = list.get(i);
            View row;
            if (i < mProfiles.getChildCount()) {
                row = mProfiles.getChildAt(i);
            } else {
                row = inflater.inflate(R.layout.item_play_time_profile, mProfiles, false);
                mProfiles.addView(row);
            }
            bindProfile(row, p, instances, total, summary.liveSession != null && p.profileKey != null
                    && p.profileKey.equals(summary.liveSession.profileKey));
        }
    }

    private void bindProfile(@NonNull View row, @NonNull PlayTimeTracker.ProfileStats p,
                             @NonNull Map<String, DisplayInstance> instances, long total, boolean live) {
        Context context = row.getContext();
        DisplayInstance instance = p.profileKey != null ? instances.get(p.profileKey) : null;
        ImageView icon = row.findViewById(R.id.pt_profile_icon);
        Drawable drawable = null;
        if (instance != null) {
            try {
                drawable = InstanceIconProvider.fetchIcon(getResources(), instance);
            } catch (RuntimeException ignored) {
                // fallback below
            }
        }
        if (drawable == null) drawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_cs_full);
        if (icon.getTag() != drawable) {
            icon.setImageDrawable(drawable);
            icon.setTag(drawable);
        }
        ((TextView) row.findViewById(R.id.pt_profile_name)).setText(nameOf(p.profileKey, p.profileName, instances));
        ((TextView) row.findViewById(R.id.pt_profile_total)).setText(PlayTimeTracker.formatDuration(p.totalMs));

        int percent = total <= 0 ? 0 : (int) Math.round(p.totalMs * 100.0 / total);
        String version = InstanceVersionResolver.shortVersion(instance != null ? instance.versionId : p.versionId);
        String sessions = getResources().getQuantityString(R.plurals.pt_sessions_count, p.sessions, p.sessions);
        ((TextView) row.findViewById(R.id.pt_profile_line)).setText(getString(R.string.pt_profile_line,
                version == null || version.isEmpty() ? "–" : version, sessions, percent + "%"));
        ((TextView) row.findViewById(R.id.pt_profile_last)).setText(live
                ? getString(R.string.pt_home_playing_now)
                : getString(R.string.pt_profile_last, PlayTimeTracker.formatAgo(context, p.lastPlayed),
                PlayTimeTracker.formatDuration(p.longestMs)));

        // Share bar: width as a fraction of the track, animated on change
        final View bar = row.findViewById(R.id.pt_profile_bar);
        final View track = (View) bar.getParent();
        final float fraction = total <= 0 ? 0f : Math.max(0.02f, p.totalMs / (float) total);
        track.post(() -> {
            int width = Math.max(1, Math.round(track.getWidth() * fraction));
            ViewGroup.LayoutParams lp = bar.getLayoutParams();
            if (lp.width == width) return;
            if (!Motion.content(bar) || lp.width <= 1) {
                lp.width = width;
                bar.setLayoutParams(lp);
                return;
            }
            android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofInt(lp.width, width);
            animator.setDuration(Motion.MEDIUM);
            animator.setInterpolator(Motion.STANDARD);
            animator.addUpdateListener(a -> {
                ViewGroup.LayoutParams params = bar.getLayoutParams();
                params.width = (Integer) a.getAnimatedValue();
                bar.setLayoutParams(params);
            });
            animator.start();
        });
    }

    private void renderSessions(@NonNull PlayTimeTracker.Summary summary, @NonNull Map<String, DisplayInstance> instances, long now) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        List<PlayTimeTracker.Session> list = summary.recent;
        int count = Math.min(list.size(), mSessionLimit);
        while (mSessions.getChildCount() > count) mSessions.removeViewAt(mSessions.getChildCount() - 1);
        Context context = requireContext();
        for (int i = 0; i < count; i++) {
            PlayTimeTracker.Session s = list.get(i);
            View row;
            if (i < mSessions.getChildCount()) {
                row = mSessions.getChildAt(i);
            } else {
                row = inflater.inflate(R.layout.item_play_time_session, mSessions, false);
                mSessions.addView(row);
            }
            boolean live = s.isLive(now) && !s.closed;
            ((TextView) row.findViewById(R.id.pt_session_name)).setText(nameOf(s.profileKey, s.profileName, instances));
            String version = InstanceVersionResolver.shortVersion(s.versionId);
            ((TextView) row.findViewById(R.id.pt_session_line)).setText(getString(R.string.pt_session_line,
                    formatDate(context, s.start, true), version == null || version.isEmpty() ? "–" : version));
            TextView duration = row.findViewById(R.id.pt_session_duration);
            duration.setText(live ? PlayTimeTracker.formatDuration(now - s.start) + " · " + getString(R.string.pt_session_live)
                    : PlayTimeTracker.formatDuration(s.duration()));
            View dot = row.findViewById(R.id.pt_session_dot);
            dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(context, live ? R.color.vl_ok : R.color.vl_text_faint)));
        }
        mShowMore.setVisibility(list.size() > count ? View.VISIBLE : View.GONE);
    }

    /** Current profile name if the profile still exists, otherwise the one recorded with the session */
    @NonNull
    private static String nameOf(@Nullable String key, @Nullable String recorded, @NonNull Map<String, DisplayInstance> instances) {
        DisplayInstance instance = key != null ? instances.get(key) : null;
        if (instance != null && instance.name != null && !instance.name.isEmpty()) return instance.name;
        return recorded != null && !recorded.isEmpty() ? recorded : "?";
    }

    /** "26 Oct 2026" or "26 Oct, 7:12 PM" following the device's 12/24 h setting */
    @NonNull
    private static String formatDate(@NonNull Context context, long timestamp, boolean withTime) {
        Date date = new Date(timestamp);
        if (!withTime) return DateFormat.format("d MMM yyyy", date).toString();
        String day = DateFormat.format("d MMM", date).toString();
        String time = DateFormat.getTimeFormat(context).format(date);
        return String.format(Locale.getDefault(), "%s, %s", day, time);
    }
}
