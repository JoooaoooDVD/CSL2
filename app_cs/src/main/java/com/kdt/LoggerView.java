package com.kdt;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.craftstudio.cslauncher.Logger;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The game log page.
 * <ul>
 *     <li>Turning "Live" on first loads the end of latestlog.txt (so the page is never empty),
 *     then follows new lines from {@link Logger}.</li>
 *     <li>Lines are coloured by level: errors red, warnings yellow, success green, the rest
 *     default; with a dim line number gutter and a small monospace font.</li>
 *     <li>Filters (all / errors / warnings / success), auto-scroll that pauses when the user
 *     scrolls up, "Latest" pill, long press to copy a line.</li>
 * </ul>
 * Shown in the game and in the AWT (installer) activity.
 */
public class LoggerView extends ConstraintLayout {
    private static final String TAG = "LoggerView";
    /** Lines kept in memory; when exceeded, the oldest TRIM_CHUNK lines are dropped at once */
    private static final int MAX_LINES = 5000;
    private static final int TRIM_CHUNK = 500;
    /** Bytes of latestlog.txt loaded when the page opens */
    private static final int HISTORY_BYTES = 256 * 1024;
    /** Incoming lines are batched: one UI update per interval instead of one per line */
    private static final long BATCH_INTERVAL_MS = 120L;

    static final byte LEVEL_NORMAL = 0, LEVEL_ERROR = 1, LEVEL_WARN = 2, LEVEL_OK = 3;
    private static final int FILTER_ALL = 0;

    private static final int COLOR_NORMAL = 0xFFD9D9DE;
    private static final int COLOR_ERROR = 0xFFFF6B6B;
    private static final int COLOR_WARN = 0xFFFFD166;
    private static final int COLOR_OK = 0xFF5BE49B;
    private static final int COLOR_GUTTER = 0xFF7C7C84;
    private static final int COLOR_COUNT = 0xFF9A9AA2;
    private static final int ROW_ERROR_BG = 0x14FF6B6B;
    private static final int ROW_WARN_BG = 0x0FFFD166;

    // --- Level detection
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");
    private static final Pattern TAG_ERROR = Pattern.compile("\\[(?:[^\\]\\[]*/)?(?:ERROR|FATAL|SEVERE)\\]|\\b(?:ERROR|FATAL)\\b|^E/");
    private static final Pattern TAG_WARN = Pattern.compile("\\[(?:[^\\]\\[]*/)?WARN(?:ING)?\\]|\\bWARN(?:ING)?\\b|^W/");
    private static final Pattern TAG_INFO = Pattern.compile("\\[(?:[^\\]\\[]*/)?(?:INFO|DEBUG|TRACE)\\]");
    private static final Pattern STACK = Pattern.compile("^\\s+at |^\\s*\\.\\.\\. \\d+ (?:more|common frames omitted)|^Caused by: |^\\s+Suppressed: ");
    private static final Pattern WORD_ERROR = Pattern.compile("[A-Za-z]+(?:Exception|Error)\\b|(?i)\\b(?:fatal|crash(?:ed)?|failed to|failure)\\b");
    private static final Pattern WORD_WARN = Pattern.compile("(?i)\\b(?:warn(?:ing)?|deprecated)\\b");
    private static final Pattern WORD_OK = Pattern.compile("(?i)\\b(?:success(?:ful(?:ly)?)?|succeeded|done|completed?|ready|loaded|started|connected|enabled)\\b");

    /** One log line */
    static final class Line {
        final int number;
        final byte level;
        final String text;
        CharSequence styled; // built lazily by the adapter

        Line(int number, byte level, String text) {
            this.number = number;
            this.level = level;
            this.text = text;
        }
    }

    /** Keeps the state needed to give stack trace lines the level of their exception */
    static final class Classifier {
        private byte mLast = LEVEL_NORMAL;

        byte classify(@NonNull String line) {
            byte level;
            if (TAG_ERROR.matcher(line).find()) level = LEVEL_ERROR;
            else if (TAG_WARN.matcher(line).find()) level = LEVEL_WARN;
            else if (STACK.matcher(line).find() && (mLast == LEVEL_ERROR || mLast == LEVEL_WARN)) level = mLast;
            else if (TAG_INFO.matcher(line).find()) level = WORD_OK.matcher(line).find() ? LEVEL_OK : LEVEL_NORMAL;
            else if (STACK.matcher(line).find() || WORD_ERROR.matcher(line).find()) level = LEVEL_ERROR;
            else if (WORD_WARN.matcher(line).find()) level = LEVEL_WARN;
            else if (WORD_OK.matcher(line).find()) level = LEVEL_OK;
            else level = LEVEL_NORMAL;
            mLast = level;
            return level;
        }
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder mPending = new StringBuilder();
    private final Object mPendingLock = new Object();
    private final Runnable mFlushRunnable = this::flushPending;
    private boolean mFlushScheduled;

    private final ArrayList<Line> mAll = new ArrayList<>();
    private final ArrayList<Line> mShown = new ArrayList<>();
    private final Classifier mClassifier = new Classifier();
    private final int[] mCounts = new int[4]; // per level, lines in mAll
    private int mNextNumber = 1;
    private int mFilter = FILTER_ALL;

    private boolean mLive;
    private boolean mAutoScroll = true;
    private boolean mHistoryLoading;
    private int mGeneration;
    private boolean mUserDragging;
    private boolean mClosing;

    private Logger.eventLogListener mLogListener;
    private View mScrim, mCard, mHeader, mFilters, mEmpty, mLiveDot;
    private TextView mStats, mLiveToggle, mAutoScrollToggle, mJump, mEmptyTitle, mEmptyText;
    private TextView[] mFilterChips;
    private RecyclerView mList;
    private LinearLayoutManager mLayoutManager;
    private LogAdapter mAdapter;
    private Typeface mMono;
    private ObjectAnimator mLivePulse;
    private final NumberFormat mNumberFormat = NumberFormat.getIntegerInstance();

    public LoggerView(@NonNull Context context) {
        this(context, null);
    }

    public LoggerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @Override
    public void setVisibility(int visibility) {
        if (mCard == null) { // during inflation
            super.setVisibility(visibility);
            return;
        }
        if (visibility == VISIBLE) {
            boolean wasShown = getVisibility() == VISIBLE;
            super.setVisibility(VISIBLE);
            if (!wasShown || mClosing) {
                mClosing = false;
                setAutoScroll(true);
                setLive(true);
                animateIn();
            }
        } else if (getVisibility() == VISIBLE) {
            if (mClosing) return;
            setLive(false); // the JNI side can skip the callbacks while hidden
            animateOut(visibility);
        } else {
            super.setVisibility(visibility);
        }
    }

    private void init() {
        inflate(getContext(), R.layout.view_logger, this);
        try {
            mMono = ResourcesCompat.getFont(getContext(), R.font.jetbrains_mono);
        } catch (Throwable t) {
            mMono = null;
        }
        if (mMono == null) mMono = Typeface.MONOSPACE;

        mScrim = findViewById(R.id.log_scrim);
        mCard = findViewById(R.id.log_card);
        mHeader = findViewById(R.id.log_header);
        mFilters = findViewById(R.id.log_filters_scroll);
        mStats = findViewById(R.id.log_stats);
        mStats.setTypeface(mMono);
        mLiveDot = findViewById(R.id.log_live_dot);
        mLiveToggle = findViewById(R.id.content_log_toggle_log);
        mAutoScrollToggle = findViewById(R.id.content_log_toggle_autoscroll);
        mJump = findViewById(R.id.log_jump);
        mEmpty = findViewById(R.id.content_log_empty);
        mEmptyTitle = findViewById(R.id.content_log_empty_title);
        mEmptyText = findViewById(R.id.content_log_empty_text);
        mList = findViewById(R.id.content_log_list);

        mLayoutManager = new LinearLayoutManager(getContext());
        mLayoutManager.setStackFromEnd(true);
        mList.setLayoutManager(mLayoutManager);
        mList.setItemAnimator(null); // lines arrive in batches, item animations would only cost frames
        mList.setHasFixedSize(true);
        mAdapter = new LogAdapter();
        mList.setAdapter(mAdapter);
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                mUserDragging = newState == RecyclerView.SCROLL_STATE_DRAGGING;
                if (newState == RecyclerView.SCROLL_STATE_IDLE && !mAutoScroll && !recyclerView.canScrollVertically(1)
                        && !mShown.isEmpty()) {
                    setAutoScroll(true); // back at the bottom: follow again
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (mUserDragging && dy < 0 && mAutoScroll) setAutoScroll(false);
                updateJumpPill();
            }
        });

        mLiveToggle.setOnClickListener(v -> {
            Motion.bounce(v);
            setLive(!mLive);
        });
        mAutoScrollToggle.setOnClickListener(v -> {
            Motion.bounce(v);
            setAutoScroll(!mAutoScroll);
        });
        mJump.setOnClickListener(v -> setAutoScroll(true));
        findViewById(R.id.log_clear).setOnClickListener(v -> {
            Motion.pulse(v);
            clearLines();
        });
        findViewById(R.id.content_log_share).setOnClickListener(v -> Tools.shareLog(getContext()));
        findViewById(R.id.log_view_cancel).setOnClickListener(v -> LoggerView.this.setVisibility(GONE));

        mFilterChips = new TextView[]{
                findViewById(R.id.log_filter_all), findViewById(R.id.log_filter_error),
                findViewById(R.id.log_filter_warn), findViewById(R.id.log_filter_ok)};
        for (int i = 0; i < mFilterChips.length; i++) {
            final int filter = i;
            mFilterChips[i].setOnClickListener(v -> setFilter(filter));
        }

        // Called from the native logger thread: only cheap buffering here
        mLogListener = text -> {
            if (text == null) return;
            synchronized (mPendingLock) {
                mPending.append(text);
                if (text.isEmpty() || text.charAt(text.length() - 1) != '\n') mPending.append('\n');
                if (mFlushScheduled) return;
                mFlushScheduled = true;
            }
            mHandler.postDelayed(mFlushRunnable, BATCH_INTERVAL_MS);
        };

        setAutoScroll(true);
        applyLiveState(false);
        updateFilterChips();
        updateStats();
    }

    // ------------------------------------------------------------------ live / history

    private void setLive(boolean live) {
        if (live == mLive) return;
        mLive = live;
        mGeneration++;
        if (live) {
            synchronized (mPendingLock) {
                mPending.setLength(0);
            }
            mHistoryLoading = true;
            // Listen first, then read the file only up to its current size: the lines logged
            // while the history loads come through the listener and are added after it.
            Logger.setLogListener(mLogListener);
            loadHistory(mGeneration);
        } else {
            Logger.setLogListener(null);
            mHistoryLoading = false;
            synchronized (mPendingLock) {
                mPending.setLength(0);
                mFlushScheduled = false;
            }
            mHandler.removeCallbacks(mFlushRunnable);
        }
        applyLiveState(live);
    }

    private void loadHistory(final int generation) {
        final File file = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        final long limit = file.length();
        updateStats();
        Thread loader = new Thread(() -> {
            ArrayList<Line> lines = new ArrayList<>();
            Classifier classifier = new Classifier();
            try {
                if (limit > 0) {
                    long start = Math.max(0, limit - HISTORY_BYTES);
                    byte[] data = new byte[(int) (limit - start)];
                    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                        raf.seek(start);
                        raf.readFully(data);
                    }
                    String text = new String(data, StandardCharsets.UTF_8);
                    if (start > 0) { // drop the cut first line
                        int newline = text.indexOf('\n');
                        text = newline >= 0 ? text.substring(newline + 1) : "";
                    }
                    int number = 1;
                    for (String raw : text.split("\n", -1)) {
                        String line = clean(raw);
                        if (line == null) continue;
                        lines.add(new Line(number++, classifier.classify(line), line));
                    }
                    if (lines.size() > MAX_LINES) lines = new ArrayList<>(lines.subList(lines.size() - MAX_LINES, lines.size()));
                }
            } catch (Throwable t) {
                Log.w(TAG, "Could not read the log history", t);
            }
            final List<Line> result = lines;
            final byte lastLevel = classifier.mLast;
            mHandler.post(() -> {
                if (generation != mGeneration || !mLive) return;
                mAll.clear();
                java.util.Arrays.fill(mCounts, 0);
                for (Line line : result) mCounts[line.level]++;
                mAll.addAll(result);
                mNextNumber = result.isEmpty() ? 1 : result.get(result.size() - 1).number + 1;
                mClassifier.mLast = lastLevel;
                mHistoryLoading = false;
                rebuildShown(false);
                flushPending();
            });
        }, "LogHistory");
        loader.setDaemon(true);
        loader.start();
    }

    /** Main thread: adds everything buffered since the last flush in one go */
    private void flushPending() {
        String chunk;
        synchronized (mPendingLock) {
            mFlushScheduled = false;
            if (mHistoryLoading || mPending.length() == 0) {
                if (mHistoryLoading && mPending.length() > 0) {
                    mFlushScheduled = true;
                    mHandler.postDelayed(mFlushRunnable, BATCH_INTERVAL_MS);
                }
                return;
            }
            chunk = mPending.toString();
            mPending.setLength(0);
        }
        if (!mLive) return;

        int shownBefore = mShown.size();
        int added = 0;
        for (String raw : chunk.split("\n", -1)) {
            String text = clean(raw);
            if (text == null) continue;
            Line line = new Line(mNextNumber++, mClassifier.classify(text), text);
            mAll.add(line);
            mCounts[line.level]++;
            if (matchesFilter(line)) {
                mShown.add(line);
                added++;
            }
        }
        if (added > 0) mAdapter.notifyItemRangeInserted(shownBefore, added);
        trimIfNeeded();
        if (added > 0 && mAutoScroll) scrollToEnd();
        updateStats();
        updateFilterChips();
        updateEmptyState();
        updateJumpPill();
    }

    /** Drops ANSI colour codes and line endings; null for lines that are empty */
    @Nullable
    private static String clean(@NonNull String raw) {
        String text = raw;
        if (text.indexOf('\u001B') >= 0) text = ANSI.matcher(text).replaceAll("");
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\r' || text.charAt(end - 1) == ' ')) end--;
        if (end == 0) return null;
        return end == text.length() ? text : text.substring(0, end);
    }

    private void trimIfNeeded() {
        if (mAll.size() <= MAX_LINES) return;
        int drop = Math.min(mAll.size(), mAll.size() - MAX_LINES + TRIM_CHUNK);
        for (int i = 0; i < drop; i++) mCounts[mAll.get(i).level]--;
        int firstKept = mAll.get(drop).number;
        mAll.subList(0, drop).clear();
        int removed = 0;
        while (removed < mShown.size() && mShown.get(removed).number < firstKept) removed++;
        if (removed > 0) {
            mShown.subList(0, removed).clear();
            mAdapter.notifyItemRangeRemoved(0, removed);
        }
    }

    private void clearLines() {
        mAll.clear();
        mShown.clear();
        java.util.Arrays.fill(mCounts, 0);
        mAdapter.notifyDataSetChanged();
        updateStats();
        updateFilterChips();
        updateEmptyState();
        updateJumpPill();
    }

    // ------------------------------------------------------------------ filters / scrolling

    private boolean matchesFilter(@NonNull Line line) {
        return mFilter == FILTER_ALL || line.level == mFilter;
    }

    private void setFilter(int filter) {
        if (filter == mFilter) return;
        mFilter = filter;
        updateFilterChips();
        rebuildShown(true);
        if (Motion.buttons(mFilterChips[filter])) Motion.bounce(mFilterChips[filter]);
    }

    private void rebuildShown(boolean animate) {
        mShown.clear();
        for (Line line : mAll) if (matchesFilter(line)) mShown.add(line);
        mAdapter.notifyDataSetChanged();
        scrollToEnd();
        updateStats();
        updateFilterChips();
        updateEmptyState();
        updateJumpPill();
        if (animate && Motion.content(mList)) {
            mList.animate().cancel();
            mList.setAlpha(0.25f);
            mList.setTranslationY(Motion.dp(mList, 10));
            mList.animate().alpha(1f).translationY(0f).setStartDelay(0)
                    .setDuration(Motion.MEDIUM).setInterpolator(Motion.ENTER).start();
        }
    }

    private void scrollToEnd() {
        if (!mShown.isEmpty()) mList.scrollToPosition(mShown.size() - 1);
    }

    private void setAutoScroll(boolean autoScroll) {
        mAutoScroll = autoScroll;
        mAutoScrollToggle.setSelected(autoScroll);
        mAutoScrollToggle.setAlpha(autoScroll ? 1f : 0.7f);
        if (autoScroll) scrollToEnd();
        updateJumpPill();
    }

    private void updateJumpPill() {
        boolean show = !mAutoScroll && !mShown.isEmpty() && mList.canScrollVertically(1);
        boolean shown = mJump.getVisibility() == VISIBLE && mJump.getTag() == null;
        if (show == shown) return;
        mJump.animate().cancel();
        if (show) {
            mJump.setTag(null);
            mJump.setVisibility(VISIBLE);
            if (Motion.content(mJump)) {
                mJump.setAlpha(0f);
                mJump.setTranslationY(Motion.dp(mJump, 16));
                mJump.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(Motion.MEDIUM)
                        .setInterpolator(Motion.SPRING).start();
            } else {
                mJump.setAlpha(1f);
                mJump.setTranslationY(0f);
            }
        } else if (Motion.content(mJump) && mJump.getVisibility() == VISIBLE) {
            mJump.setTag("hiding");
            mJump.animate().alpha(0f).translationY(Motion.dp(mJump, 16)).setStartDelay(0)
                    .setDuration(Motion.SHORT).setInterpolator(Motion.EXIT)
                    .withEndAction(() -> {
                        mJump.setVisibility(GONE);
                        mJump.setTag(null);
                    }).start();
        } else {
            mJump.setVisibility(GONE);
            mJump.setTag(null);
        }
    }

    // ------------------------------------------------------------------ header state

    private void applyLiveState(boolean live) {
        mLiveToggle.setSelected(live);
        mLiveToggle.setAlpha(live ? 1f : 0.7f);
        mLiveDot.setVisibility(live ? VISIBLE : INVISIBLE);
        if (mLivePulse != null) {
            mLivePulse.cancel();
            mLivePulse = null;
        }
        mLiveDot.setAlpha(1f);
        if (live && Motion.content(mLiveDot)) {
            mLivePulse = ObjectAnimator.ofFloat(mLiveDot, View.ALPHA, 1f, 0.25f);
            mLivePulse.setDuration(900);
            mLivePulse.setRepeatMode(ValueAnimator.REVERSE);
            mLivePulse.setRepeatCount(ValueAnimator.INFINITE);
            mLivePulse.start();
        }
        updateStats();
        updateEmptyState();
    }

    private void updateStats() {
        if (mHistoryLoading) {
            mStats.setText(R.string.log_page_loading);
            return;
        }
        int total = mAll.size();
        StringBuilder text = new StringBuilder();
        if (!mLive) text.append(getContext().getString(R.string.log_page_paused_stats)).append("  \u00B7  ");
        text.append(getResources().getQuantityString(R.plurals.log_page_lines, total, mNumberFormat.format(total)));
        int errors = mCounts[LEVEL_ERROR];
        int warnings = mCounts[LEVEL_WARN];
        if (errors > 0) text.append("  \u00B7  ").append(getResources().getQuantityString(R.plurals.log_page_errors, errors, mNumberFormat.format(errors)));
        if (warnings > 0) text.append("  \u00B7  ").append(getResources().getQuantityString(R.plurals.log_page_warnings, warnings, mNumberFormat.format(warnings)));
        mStats.setText(text);
    }

    private void updateFilterChips() {
        int[] labels = {R.string.log_filter_all, R.string.log_filter_errors, R.string.log_filter_warnings, R.string.log_filter_success};
        for (int i = 0; i < mFilterChips.length; i++) {
            int count = i == FILTER_ALL ? mAll.size() : mCounts[i];
            String label = getContext().getString(labels[i]);
            SpannableStringBuilder text = new SpannableStringBuilder(label).append("  ");
            int start = text.length();
            text.append(mNumberFormat.format(count));
            text.setSpan(new ForegroundColorSpan(COLOR_COUNT), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            mFilterChips[i].setText(text);
            mFilterChips[i].setSelected(i == mFilter);
        }
    }

    private void updateEmptyState() {
        boolean empty = mShown.isEmpty() && !mHistoryLoading;
        if (empty) {
            ImageViewHelper.setIcon(mEmpty, !mLive ? R.drawable.ic_gm_eye_off : R.drawable.ic_gm_terminal);
            if (!mLive && mAll.isEmpty()) {
                mEmptyTitle.setText(R.string.log_page_paused_title);
                mEmptyText.setText(R.string.log_page_paused_text);
            } else if (mAll.isEmpty()) {
                mEmptyTitle.setText(R.string.log_page_waiting_title);
                mEmptyText.setText(R.string.log_page_waiting_text);
            } else {
                mEmptyTitle.setText(R.string.log_page_nothing_title);
                mEmptyText.setText(R.string.log_page_nothing_text);
            }
        }
        if (empty == (mEmpty.getVisibility() == VISIBLE)) return;
        if (empty) {
            mEmpty.setVisibility(VISIBLE);
            if (Motion.content(mEmpty)) {
                mEmpty.setAlpha(0f);
                mEmpty.setTranslationY(Motion.dp(mEmpty, 12));
                mEmpty.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(Motion.MEDIUM)
                        .setInterpolator(Motion.ENTER).start();
            }
        } else {
            mEmpty.animate().cancel();
            mEmpty.setAlpha(1f);
            mEmpty.setTranslationY(0f);
            mEmpty.setVisibility(GONE);
        }
    }

    /** Small helper so the empty state icon can change without another field */
    private static final class ImageViewHelper {
        static void setIcon(@NonNull View emptyRoot, int icon) {
            View view = emptyRoot.findViewById(R.id.content_log_empty_icon);
            if (view instanceof android.widget.ImageView) ((android.widget.ImageView) view).setImageResource(icon);
        }
    }

    // ------------------------------------------------------------------ enter / exit

    private void animateIn() {
        if (!Motion.content(mCard)) {
            restViews();
            return;
        }
        restViews();
        mScrim.setAlpha(0f);
        mScrim.animate().alpha(1f).setStartDelay(0).setDuration(Motion.SHORT + 60).setInterpolator(Motion.STANDARD).start();
        mCard.setAlpha(0f);
        mCard.setTranslationY(Motion.dp(mCard, 28));
        mCard.setScaleX(0.97f);
        mCard.setScaleY(0.97f);
        mCard.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(30)
                .setDuration(Motion.MEDIUM + 60).setInterpolator(Motion.ENTER).start();
        mHeader.setAlpha(0f);
        mHeader.setTranslationY(-Motion.dp(mHeader, 10));
        mHeader.animate().alpha(1f).translationY(0f).setStartDelay(110).setDuration(Motion.MEDIUM)
                .setInterpolator(Motion.ENTER).start();
        mFilters.setAlpha(0f);
        mFilters.setTranslationX(Motion.dp(mFilters, 18));
        mFilters.animate().alpha(1f).translationX(0f).setStartDelay(170).setDuration(Motion.MEDIUM)
                .setInterpolator(Motion.ENTER).start();
        mList.setAlpha(0f);
        mList.animate().alpha(1f).setStartDelay(200).setDuration(Motion.MEDIUM).setInterpolator(Motion.STANDARD).start();
    }

    private void animateOut(int finalVisibility) {
        if (!Motion.content(mCard)) {
            super.setVisibility(finalVisibility);
            restViews();
            return;
        }
        mClosing = true;
        mScrim.animate().cancel();
        mCard.animate().cancel();
        mScrim.animate().alpha(0f).setStartDelay(40).setDuration(Motion.SHORT + 40).setInterpolator(Motion.STANDARD).start();
        mCard.animate().alpha(0f).translationY(Motion.dp(mCard, 20)).scaleX(0.98f).scaleY(0.98f).setStartDelay(0)
                .setDuration(Motion.SHORT + 20).setInterpolator(Motion.EXIT)
                .withEndAction(() -> {
                    if (!mClosing) return; // reopened meanwhile
                    mClosing = false;
                    LoggerView.super.setVisibility(finalVisibility);
                    restViews();
                }).start();
    }

    private void restViews() {
        for (View view : new View[]{mScrim, mCard, mHeader, mFilters, mList}) {
            view.animate().cancel();
            view.setAlpha(1f);
            view.setTranslationX(0f);
            view.setTranslationY(0f);
            view.setScaleX(1f);
            view.setScaleY(1f);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mLivePulse != null) mLivePulse.cancel();
        if (mLive) {
            mLive = false;
            Logger.setLogListener(null);
        }
        mHandler.removeCallbacks(mFlushRunnable);
    }

    // ------------------------------------------------------------------ adapter

    private final class LogAdapter extends RecyclerView.Adapter<LogAdapter.Holder> {
        final class Holder extends RecyclerView.ViewHolder {
            final TextView text;

            Holder(@NonNull TextView view) {
                super(view);
                text = view;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView view = new TextView(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            view.setTypeface(mMono);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            view.setLineSpacing(0f, 1.12f);
            view.setIncludeFontPadding(false);
            int horizontal = (int) Motion.dp(parent, 12);
            int vertical = (int) Motion.dp(parent, 2);
            view.setPadding(horizontal, vertical, horizontal, vertical);
            view.setTextColor(COLOR_NORMAL);
            Holder holder = new Holder(view);
            view.setOnLongClickListener(v -> {
                @SuppressWarnings("deprecation") int position = holder.getAdapterPosition();
                if (position == RecyclerView.NO_POSITION || position >= mShown.size()) return false;
                copyLine(mShown.get(position).text);
                Motion.pulse(v);
                return true;
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Line line = mShown.get(position);
            if (line.styled == null) line.styled = style(line);
            holder.text.setText(line.styled);
            holder.text.setBackgroundColor(line.level == LEVEL_ERROR ? ROW_ERROR_BG
                    : line.level == LEVEL_WARN ? ROW_WARN_BG : 0);
        }

        @Override
        public int getItemCount() {
            return mShown.size();
        }
    }

    @NonNull
    private static CharSequence style(@NonNull Line line) {
        String number = String.valueOf(line.number);
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (int i = number.length(); i < 5; i++) text.append(' ');
        text.append(number).append("  ");
        text.setSpan(new ForegroundColorSpan(COLOR_GUTTER), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start = text.length();
        text.append(line.text);
        int color = line.level == LEVEL_ERROR ? COLOR_ERROR
                : line.level == LEVEL_WARN ? COLOR_WARN
                : line.level == LEVEL_OK ? COLOR_OK : COLOR_NORMAL;
        text.setSpan(new ForegroundColorSpan(color), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private void copyLine(@NonNull String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("log", text));
            Toast.makeText(getContext(), R.string.log_page_copied, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "Copy failed", t);
        }
    }
}
