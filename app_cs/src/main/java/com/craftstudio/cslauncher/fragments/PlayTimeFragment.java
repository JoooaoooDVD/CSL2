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

    private TextView mTotal, mTotalSub, mLiveText, mShowMore;
    private TextView mChipStreak, mChipDays, mChipSince, mTodayValue, mTodayDelta;
    private TextView mWeekTotal, mWeekDayName, mWeekDayValue;
    private com.craftstudio.cslauncher.stats.WeekChartView mWeekChart;
    private View mLive, mEmpty, mHero, mWeekCard, mStatsCard, mProfilesCard, mSessionsCard, mTodayTile;
    @Nullable private PlayTimeTracker.Summary mLastSummary;
    private long mShownTotal = -1;
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
        mChipStreak = view.findViewById(R.id.pt_chip_streak);
        mChipDays = view.findViewById(R.id.pt_chip_days);
        mChipSince = view.findViewById(R.id.pt_chip_since);
        mTodayTile = view.findViewById(R.id.pt_today_tile);
        mTodayValue = view.findViewById(R.id.pt_today_value);
        mTodayDelta = view.findViewById(R.id.pt_today_delta);
        mWeekCard = view.findViewById(R.id.pt_week_card);
        mWeekTotal = view.findViewById(R.id.pt_week_total);
        mWeekChart = view.findViewById(R.id.pt_week_chart);
        mWeekDayName = view.findViewById(R.id.pt_week_day_name);
        mWeekDayValue = view.findViewById(R.id.pt_week_day_value);
        mStatsCard = view.findViewById(R.id.pt_stats_card);
        mWeekChart.setOnDaySelectedListener(index -> {
            if (mLastSummary != null) bindSelectedDay(mLastSummary, index, true);
        });
        mLive = view.findViewById(R.id.pt_live);
        mLiveText = view.findViewById(R.id.pt_live_text);
        mEmpty = view.findViewById(R.id.pt_empty);
        mHero = view.findViewById(R.id.pt_hero);
        mProfilesCard = view.findViewById(R.id.pt_profiles_card);
        mSessionsCard = view.findViewById(R.id.pt_sessions_card);
        mProfiles = view.findViewById(R.id.pt_profiles);
        mSessions = view.findViewById(R.id.pt_sessions);
        mShowMore = view.findViewById(R.id.pt_show_more);

        bindStat(view, R.id.pt_stat_sessions, R.string.pt_stat_sessions);
        bindStat(view, R.id.pt_stat_average, R.string.pt_stat_average);
        bindStat(view, R.id.pt_stat_longest, R.string.pt_stat_longest);
        bindStat(view, R.id.pt_stat_shortest, R.string.pt_stat_shortest);
        bindStat(view, R.id.pt_stat_yesterday, R.string.pt_stat_yesterday);
        bindStat(view, R.id.pt_stat_lastweek, R.string.pt_stat_lastweek);
        bindStat(view, R.id.pt_stat_month, R.string.pt_stat_month);
        bindStat(view, R.id.pt_stat_beststreak, R.string.pt_stat_beststreak);
        bindStat(view, R.id.pt_stat_topday, R.string.pt_stat_topday);
        bindStat(view, R.id.pt_stat_favtime, R.string.pt_stat_favtime);
        bindStat(view, R.id.pt_stat_last, R.string.pt_stat_last);
        bindStat(view, R.id.pt_stat_topprofile, R.string.pt_stat_topprofile);

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
        Motion.riseIn(mHero, 60L, 14f);
        Motion.popIn(mTodayTile, 260L, 0.8f);
        Motion.riseIn(mWeekCard, 140L, 14f);
        Motion.riseIn(mStatsCard, 220L, 14f);
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
        mTotal = mTotalSub = mLiveText = mShowMore = null;
        mChipStreak = mChipDays = mChipSince = mTodayValue = mTodayDelta = null;
        mWeekTotal = mWeekDayName = mWeekDayValue = null;
        mWeekChart = null;
        mLastSummary = null;
        mLive = mEmpty = mHero = mWeekCard = mStatsCard = mProfilesCard = mSessionsCard = mTodayTile = null;
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

        mLastSummary = summary;
        long total = summary.totalMs;

        // Hero: total counts up on first show, morphs afterwards
        if (mShownTotal < 0 && total > 0 && Motion.content(mTotal)) {
            Motion.countUp(mTotal, total, 150L, PlayTimeTracker::formatDuration);
        } else if (mShownTotal != total) {
            String totalShort = PlayTimeTracker.formatDuration(total);
            if (animateNumbers && mShownTotal >= 0) Motion.swapText(mTotal, totalShort);
            else mTotal.setText(totalShort);
        }
        mShownTotal = total;
        String sessions = getResources().getQuantityString(R.plurals.pt_sessions_count, summary.sessionCount, summary.sessionCount);
        mTotalSub.setText(total > 0 ? PlayTimeTracker.formatDurationLong(context, total) + " · " + sessions : sessions);

        // Chips
        setChip(mChipStreak, summary.streakDays > 0
                ? getResources().getQuantityString(R.plurals.pt_streak_chip, summary.streakDays, summary.streakDays) : null);
        setChip(mChipDays, summary.daysPlayed > 0
                ? getResources().getQuantityString(R.plurals.pt_days_played_chip, summary.daysPlayed, summary.daysPlayed) : null);
        setChip(mChipSince, summary.firstPlayed > 0
                ? getString(R.string.pt_since_chip, formatDate(context, summary.firstPlayed, false)) : null);

        // Today tile
        String today = PlayTimeTracker.formatDuration(summary.todayMs);
        if (!today.contentEquals(mTodayValue.getText())) {
            if (animateNumbers && mTodayValue.length() > 0) Motion.swapText(mTodayValue, today);
            else mTodayValue.setText(today);
        }
        long delta = summary.todayMs - summary.yesterdayMs;
        if (Math.abs(delta) < 60_000L) mTodayDelta.setText(R.string.pt_delta_same);
        else if (delta > 0) mTodayDelta.setText(getString(R.string.pt_delta_up, PlayTimeTracker.formatDuration(delta)));
        else mTodayDelta.setText(getString(R.string.pt_delta_down, PlayTimeTracker.formatDuration(-delta)));

        // Week chart
        mWeekTotal.setText(PlayTimeTracker.formatDuration(summary.weekMs));
        String[] labels = new String[7];
        for (int i = 0; i < 7; i++) labels[i] = DateFormat.format("EEE", new Date(summary.weekDayStart[i])).toString();
        mWeekChart.setData(summary.week, labels, true);
        mWeekChart.setLiveToday(live != null);
        bindSelectedDay(summary, mWeekChart.getSelected(), false);

        // Stat tiles
        setStat(R.id.pt_stat_sessions, String.valueOf(summary.sessionCount));
        setStat(R.id.pt_stat_average, PlayTimeTracker.formatDuration(summary.averageMs));
        setStat(R.id.pt_stat_longest, PlayTimeTracker.formatDuration(Math.max(summary.longestMs, liveMs)));
        setStat(R.id.pt_stat_shortest, PlayTimeTracker.formatDuration(summary.shortestMs));
        setStat(R.id.pt_stat_yesterday, PlayTimeTracker.formatDuration(summary.yesterdayMs));
        setStat(R.id.pt_stat_lastweek, PlayTimeTracker.formatDuration(summary.lastWeekMs));
        setStat(R.id.pt_stat_month, PlayTimeTracker.formatDuration(summary.monthMs));
        setStat(R.id.pt_stat_beststreak, summary.bestStreakDays > 0
                ? getResources().getQuantityString(R.plurals.pt_days_short, summary.bestStreakDays, summary.bestStreakDays) : "–");
        setStat(R.id.pt_stat_topday, topWeekday(summary));
        setStat(R.id.pt_stat_favtime, favouriteSlot(summary));
        setStat(R.id.pt_stat_last, live != null ? getString(R.string.pt_home_playing_now)
                : summary.lastPlayed > 0 ? PlayTimeTracker.formatAgo(context, summary.lastPlayed) : getString(R.string.pt_never));
        setStat(R.id.pt_stat_topprofile, summary.profiles.isEmpty() ? "–"
                : nameOf(summary.profiles.get(0).profileKey, summary.profiles.get(0).profileName, instances));

        mEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        mProfilesCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        mSessionsCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (mFirstRender) {
            Motion.popChildren((ViewGroup) requireView().findViewById(R.id.pt_stats_row1), 260L, 40L, 0.85f);
            Motion.popChildren((ViewGroup) requireView().findViewById(R.id.pt_stats_row2), 380L, 40L, 0.85f);
            Motion.popChildren((ViewGroup) requireView().findViewById(R.id.pt_stats_row3), 500L, 40L, 0.85f);
        }
        if (empty) {
            if (mFirstRender) Motion.riseIn(mEmpty, 200L, 12f);
            mFirstRender = false;
            return;
        }

        renderProfiles(summary, instances, total);
        renderSessions(summary, instances, now);

        if (mFirstRender) {
            Motion.riseIn(mProfilesCard, 320L, 14f);
            Motion.riseIn(mSessionsCard, 400L, 14f);
            Motion.cascadeChildren(mProfiles, 420L, 50L, 10f);
            Motion.cascadeChildren(mSessions, 520L, 30L, 6f);
        }
        mFirstRender = false;
    }

    private void setChip(@Nullable TextView chip, @Nullable String text) {
        if (chip == null) return;
        if (text == null) {
            chip.setVisibility(View.GONE);
            return;
        }
        boolean appear = chip.getVisibility() != View.VISIBLE;
        chip.setVisibility(View.VISIBLE);
        if (!text.contentEquals(chip.getText())) chip.setText(text);
        if (appear && mFirstRender) Motion.popIn(chip, 350L, 0.7f);
    }

    /** Caption under the chart: "Today · Wed 29 Oct" + "1h 12m · 3 sessions" */
    private void bindSelectedDay(@NonNull PlayTimeTracker.Summary summary, int index, boolean animate) {
        if (mWeekDayName == null || index < 0 || index > 6) return;
        Context context = requireContext();
        long dayStart = summary.weekDayStart[index];
        String date = DateFormat.format("EEE d MMM", new Date(dayStart)).toString();
        String name = index == 6 ? getString(R.string.pt_day_caption, getString(R.string.pt_day_today), date)
                : index == 5 ? getString(R.string.pt_day_caption, getString(R.string.pt_day_yesterday), date) : date;
        long ms = summary.week[index];
        int count = summary.weekSessions[index];
        String value = ms <= 0 ? getString(R.string.pt_day_nothing)
                : getString(R.string.pt_day_value, PlayTimeTracker.formatDuration(ms),
                getResources().getQuantityString(R.plurals.pt_sessions_count, count, count));
        if (animate) {
            Motion.swapText(mWeekDayName, name);
            Motion.swapText(mWeekDayValue, value);
        } else {
            if (!name.contentEquals(mWeekDayName.getText())) mWeekDayName.setText(name);
            if (!value.contentEquals(mWeekDayValue.getText())) mWeekDayValue.setText(value);
        }
    }

    /** Weekday with the most play time, all time: "Sunday" */
    @NonNull
    private String topWeekday(@NonNull PlayTimeTracker.Summary summary) {
        int best = -1;
        long bestMs = 0;
        for (int d = 1; d <= 7; d++) {
            if (summary.byWeekday[d] > bestMs) { bestMs = summary.byWeekday[d]; best = d; }
        }
        if (best < 0) return "–";
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.DAY_OF_WEEK, best);
        return DateFormat.format("EEEE", c).toString();
    }

    /** Time of day the player is usually on: "Evening" */
    @NonNull
    private String favouriteSlot(@NonNull PlayTimeTracker.Summary summary) {
        int best = -1;
        long bestMs = 0;
        for (int i = 0; i < 8; i++) {
            if (summary.byDaySlot[i] > bestMs) { bestMs = summary.byDaySlot[i]; best = i; }
        }
        switch (best) {
            case 0: return getString(R.string.pt_slot_night);
            case 1: return getString(R.string.pt_slot_early);
            case 2: return getString(R.string.pt_slot_morning);
            case 3: return getString(R.string.pt_slot_noon);
            case 4: return getString(R.string.pt_slot_afternoon);
            case 5: return getString(R.string.pt_slot_evening);
            case 6: return getString(R.string.pt_slot_evening);
            case 7: return getString(R.string.pt_slot_late);
            default: return "–";
        }
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
        Context context = requireContext();
        long todayStart = PlayTimeTracker.startOfDay(now);
        // Rows are rebuilt as a flat list of [header | session] views; headers are reused by position too
        int viewIndex = 0;
        long lastDay = Long.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            PlayTimeTracker.Session s = list.get(i);
            long day = PlayTimeTracker.startOfDay(s.start);
            if (day != lastDay) {
                lastDay = day;
                TextView header = (TextView) childOfType(mSessions, viewIndex, R.layout.view_play_time_day_header, inflater, true);
                String label = day == todayStart ? getString(R.string.pt_day_today)
                        : day == todayStart - 24L * 3600 * 1000 ? getString(R.string.pt_day_yesterday)
                        : DateFormat.format("EEE d MMM", new Date(day)).toString();
                if (!label.contentEquals(header.getText())) header.setText(label);
                viewIndex++;
            }
            View row = childOfType(mSessions, viewIndex, R.layout.item_play_time_session, inflater, false);
            viewIndex++;
            boolean live = s.isLive(now) && !s.closed;
            ((TextView) row.findViewById(R.id.pt_session_name)).setText(nameOf(s.profileKey, s.profileName, instances));
            String version = InstanceVersionResolver.shortVersion(s.versionId);
            ((TextView) row.findViewById(R.id.pt_session_line)).setText(getString(R.string.pt_session_line,
                    DateFormat.getTimeFormat(context).format(new Date(s.start)), version == null || version.isEmpty() ? "–" : version));
            TextView duration = row.findViewById(R.id.pt_session_duration);
            duration.setText(live ? PlayTimeTracker.formatDuration(now - s.start) + " · " + getString(R.string.pt_session_live)
                    : PlayTimeTracker.formatDuration(s.duration()));
            View dot = row.findViewById(R.id.pt_session_dot);
            dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(context, live ? R.color.vl_ok : R.color.vl_text_faint)));
            if (live) Motion.pulse(dot);
        }
        while (mSessions.getChildCount() > viewIndex) mSessions.removeViewAt(mSessions.getChildCount() - 1);
        mShowMore.setVisibility(list.size() > count ? View.VISIBLE : View.GONE);
    }

    /** Child at {@code index} if it was inflated from {@code layout}, otherwise a fresh one replaces it */
    @NonNull
    private static View childOfType(@NonNull LinearLayout parent, int index, int layout, @NonNull LayoutInflater inflater, boolean header) {
        if (index < parent.getChildCount()) {
            View existing = parent.getChildAt(index);
            if (existing.getTag(R.id.pt_row_type) != null && (Integer) existing.getTag(R.id.pt_row_type) == layout) return existing;
            parent.removeViewAt(index);
        }
        View view = inflater.inflate(layout, parent, false);
        view.setTag(R.id.pt_row_type, layout);
        parent.addView(view, index);
        return view;
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
