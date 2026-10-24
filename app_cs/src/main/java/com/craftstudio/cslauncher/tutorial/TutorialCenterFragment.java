package com.craftstudio.cslauncher.tutorial;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.craftstudio.cslauncher.LauncherActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.prefs.SettingsPopup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tutorial Center (Settings → Miscellaneous → Tutorials, or the "?" button → All tutorials).
 * Lists every module with its status, search, filters, and the resume / restart / reset actions.
 * Resetting here only touches the tutorial preference file, never launcher settings.
 */
public class TutorialCenterFragment extends Fragment {
    public static final String TAG = "TutorialCenterFragment";

    private enum Filter { ALL, RECOMMENDED, BEGINNER, DONE, TODO, BASICS, PLAY, CONTENT, SETTINGS, TOOLS }

    private final List<TutorialDefinition> mVisible = new ArrayList<>();
    private Filter mFilter = Filter.ALL;
    private String mQuery = "";
    private Adapter mAdapter;
    private TextView mStats;
    private TextView mResume;
    private TextView mEmpty;
    private ViewGroup mFilters;

    public TutorialCenterFragment() {
        super(R.layout.fragment_tutorial_center);
    }

    /** Opens the center full screen (it is a settings-level screen, not a home pane) */
    public static void open(@NonNull FragmentActivity activity) {
        Fragment top = activity.getSupportFragmentManager().findFragmentById(R.id.container_fragment);
        if (top instanceof TutorialCenterFragment) return;
        Tools.swapFragmentFullScreen(activity, TutorialCenterFragment.class, TAG, null);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mStats = view.findViewById(R.id.tc_stats);
        mResume = view.findViewById(R.id.tc_resume);
        mEmpty = view.findViewById(R.id.tc_empty);
        mFilters = view.findViewById(R.id.tc_filters);
        RecyclerView list = view.findViewById(R.id.tc_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        mAdapter = new Adapter();
        list.setAdapter(mAdapter);

        view.findViewById(R.id.tc_back).setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        view.findViewById(R.id.tc_more).setOnClickListener(this::showMoreMenu);
        mResume.setOnClickListener(v -> {
            closeAndRun(() -> {
                if (!TutorialManager.getInstance().resumeSaved()) {
                    Toast.makeText(v.getContext(), R.string.tut_fallback_cannot_start, Toast.LENGTH_SHORT).show();
                }
            });
        });

        EditText search = view.findViewById(R.id.tc_search);
        View clear = view.findViewById(R.id.tc_search_clear);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                mQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                clear.setVisibility(mQuery.isEmpty() ? View.GONE : View.VISIBLE);
                refresh();
            }
        });
        clear.setOnClickListener(v -> search.setText(""));

        buildFilters();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    // ───────────────────────────── filters ─────────────────────────────

    private void buildFilters() {
        mFilters.removeAllViews();
        addFilter(Filter.ALL, R.string.tut_center_filter_all);
        addFilter(Filter.RECOMMENDED, R.string.tut_center_filter_recommended);
        addFilter(Filter.BEGINNER, R.string.tut_center_filter_beginner);
        addFilter(Filter.TODO, R.string.tut_center_filter_todo);
        addFilter(Filter.DONE, R.string.tut_center_filter_done);
        addFilter(Filter.BASICS, R.string.tut_category_basics);
        addFilter(Filter.PLAY, R.string.tut_category_play);
        addFilter(Filter.CONTENT, R.string.tut_category_content);
        addFilter(Filter.SETTINGS, R.string.tut_category_settings);
        addFilter(Filter.TOOLS, R.string.tut_category_tools);
        updateFilterChips();
    }

    private void addFilter(@NonNull Filter filter, @StringRes int label) {
        TextView chip = (TextView) LayoutInflater.from(requireContext()).inflate(R.layout.tutorial_filter_chip, mFilters, false);
        chip.setText(label);
        chip.setTag(filter);
        chip.setOnClickListener(v -> {
            mFilter = filter;
            updateFilterChips();
            refresh();
        });
        mFilters.addView(chip);
    }

    private void updateFilterChips() {
        for (int i = 0; i < mFilters.getChildCount(); i++) {
            View chip = mFilters.getChildAt(i);
            chip.setSelected(chip.getTag() == mFilter);
        }
    }

    private boolean matches(@NonNull TutorialDefinition def, @NonNull Context context) {
        boolean done = TutorialState.isCompleted(context, def.id);
        switch (mFilter) {
            case RECOMMENDED: if (!def.recommended) return false; break;
            case BEGINNER: if (!def.beginner) return false; break;
            case DONE: if (!done) return false; break;
            case TODO: if (done) return false; break;
            case BASICS: if (def.category != TutorialDefinition.Category.BASICS) return false; break;
            case PLAY: if (def.category != TutorialDefinition.Category.PLAY) return false; break;
            case CONTENT: if (def.category != TutorialDefinition.Category.CONTENT) return false; break;
            case SETTINGS: if (def.category != TutorialDefinition.Category.SETTINGS) return false; break;
            case TOOLS: if (def.category != TutorialDefinition.Category.TOOLS) return false; break;
            default: break;
        }
        if (mQuery.isEmpty()) return true;
        String hay = (context.getString(def.title) + " " + context.getString(def.summary) + " "
                + context.getString(def.description)).toLowerCase(Locale.ROOT);
        return hay.contains(mQuery);
    }

    // ───────────────────────────── data ─────────────────────────────

    private void refresh() {
        Context context = getContext();
        if (context == null || mAdapter == null) return;
        mVisible.clear();
        int total = 0, done = 0;
        for (TutorialDefinition def : TutorialRegistry.all()) {
            if (!def.isAvailable(context)) continue;
            total++;
            if (TutorialState.isCompleted(context, def.id)) done++;
            if (matches(def, context)) mVisible.add(def);
        }
        // Recommended first, then in registry order
        List<TutorialDefinition> ordered = new ArrayList<>(mVisible.size());
        for (TutorialDefinition def : mVisible) if (def.recommended && !TutorialState.isCompleted(context, def.id)) ordered.add(def);
        for (TutorialDefinition def : mVisible) if (!ordered.contains(def)) ordered.add(def);
        mVisible.clear();
        mVisible.addAll(ordered);
        mAdapter.notifyDataSetChanged();
        mStats.setText(getString(R.string.tut_center_stats, done, total));
        mEmpty.setVisibility(mVisible.isEmpty() ? View.VISIBLE : View.GONE);
        boolean resumable = TutorialManager.getInstance().hasResumableRun(context) && !TutorialManager.getInstance().isRunning();
        mResume.setVisibility(resumable ? View.VISIBLE : View.GONE);
    }

    // ───────────────────────────── actions ─────────────────────────────

    private void startTutorial(@NonNull TutorialDefinition def) {
        Context context = requireContext();
        TutorialManager manager = TutorialManager.getInstance();
        TutorialState.ActiveRun run = TutorialState.getActiveRun(context);
        if (run != null && run.tutorialId.equals(def.id)) {
            closeAndRun(manager::resumeSaved);
            return;
        }
        TutorialState.clearTutorial(context, def.id);
        closeAndRun(() -> {
            if (!manager.start(def.id)) {
                LauncherActivity activity = manager.activity();
                if (activity != null) Toast.makeText(activity, R.string.tut_fallback_cannot_start, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void replayAll() {
        closeAndRun(() -> TutorialManager.getInstance().startSequence(TutorialRegistry.allIds(), false));
    }

    /** Leaves this screen, then runs the action once the launcher home is back on top */
    private void closeAndRun(@NonNull Runnable action) {
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        Tools.backToMainMenu(activity);
        activity.getWindow().getDecorView().postDelayed(action, 320L);
    }

    private void showMoreMenu(@NonNull View anchor) {
        Context context = anchor.getContext();
        CharSequence[] entries = {
                getString(R.string.tut_center_replay_all),
                getString(R.string.tut_center_restart_setup),
                getString(R.string.tut_center_reset),
                getString(TutorialState.showTips(context) ? R.string.tut_center_tips_off : R.string.tut_center_tips_on),
                getString(TutorialState.isInteractive(context) ? R.string.tut_center_mode_guided : R.string.tut_center_mode_interactive)
        };
        new SettingsPopup(context)
                .title(R.string.tut_center_more)
                .singleChoice(entries, -1, which -> {
                    switch (which) {
                        case 0: replayAll(); break;
                        case 1: confirmRestartSetup(); break;
                        case 2: confirmReset(); break;
                        case 3:
                            TutorialState.setShowTips(context, !TutorialState.showTips(context));
                            break;
                        case 4:
                            TutorialState.setInteractive(context, !TutorialState.isInteractive(context));
                            Toast.makeText(context, TutorialState.isInteractive(context)
                                    ? R.string.tut_opt_interactive_t : R.string.tut_opt_guided_t, Toast.LENGTH_SHORT).show();
                            break;
                        default: break;
                    }
                })
                .show();
    }

    private void confirmReset() {
        Context context = requireContext();
        new SettingsPopup(context)
                .title(R.string.tut_center_reset)
                .message(R.string.tut_center_reset_message)
                .positive(R.string.tut_center_reset_confirm, () -> {
                    TutorialManager.getInstance().cancel();
                    TutorialState.resetProgress(context, TutorialRegistry.allIds());
                    Toast.makeText(context, R.string.tut_center_reset_done, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .negative(R.string.pe_cancel, null)
                .show();
    }

    private void confirmRestartSetup() {
        Context context = requireContext();
        new SettingsPopup(context)
                .title(R.string.tut_center_restart_setup)
                .message(R.string.tut_center_restart_message)
                .positive(R.string.tut_center_restart_confirm, () -> {
                    FragmentActivity activity = getActivity();
                    if (!(activity instanceof LauncherActivity)) return;
                    LauncherActivity launcher = (LauncherActivity) activity;
                    Tools.backToMainMenu(launcher);
                    launcher.getWindow().getDecorView().postDelayed(() -> FirstLaunchSetup.restart(launcher), 320L);
                })
                .negative(R.string.pe_cancel, null)
                .show();
    }

    // ───────────────────────────── list ─────────────────────────────

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tutorial, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(mVisible.get(position));
        }

        @Override
        public int getItemCount() {
            return mVisible.size();
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title, badge, summary, status, action;
        final View dot;
        final ProgressBar progress;

        Holder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.it_icon);
            title = itemView.findViewById(R.id.it_title);
            badge = itemView.findViewById(R.id.it_badge);
            summary = itemView.findViewById(R.id.it_summary);
            status = itemView.findViewById(R.id.it_status);
            action = itemView.findViewById(R.id.it_action);
            dot = itemView.findViewById(R.id.it_status_dot);
            progress = itemView.findViewById(R.id.it_progress);
        }

        void bind(@NonNull TutorialDefinition def) {
            Context context = itemView.getContext();
            icon.setImageResource(def.icon);
            title.setText(def.title);
            summary.setText(def.summary);
            boolean done = TutorialState.isCompleted(context, def.id);
            int steps = def.steps().size();
            int at = TutorialState.getProgress(context, def.id);
            TutorialState.ActiveRun run = TutorialState.getActiveRun(context);
            boolean paused = run != null && run.tutorialId.equals(def.id);
            if (paused) at = Math.max(at, run.step);
            boolean partial = !done && at > 0;
            int minutes = Math.max(1, Math.round(steps * 0.5f));

            if (def.recommended && !done) {
                badge.setVisibility(View.VISIBLE);
                badge.setText(R.string.tut_center_badge_recommended);
            } else if (def.beginner && !done) {
                badge.setVisibility(View.VISIBLE);
                badge.setText(R.string.tut_center_badge_beginner);
            } else {
                badge.setVisibility(View.GONE);
            }

            String meta = context.getResources().getQuantityString(R.plurals.tut_center_steps, steps, steps)
                    + " · " + context.getString(R.string.tut_center_minutes, minutes);
            if (done) {
                dot.setBackgroundResource(R.drawable.tut_status_done);
                status.setText(context.getString(R.string.tut_center_status_done) + " · " + meta);
                progress.setProgress(100);
                action.setText(R.string.tut_center_replay);
            } else if (partial) {
                dot.setBackgroundResource(R.drawable.tut_status_partial);
                status.setText(context.getString(R.string.tut_center_status_partial, at + 1, steps) + " · " + meta);
                progress.setProgress(steps == 0 ? 0 : Math.round(100f * at / steps));
                action.setText(paused ? R.string.tut_center_resume : R.string.tut_center_continue);
            } else {
                dot.setBackgroundResource(R.drawable.tut_status_new);
                status.setText(context.getString(R.string.tut_center_status_new) + " · " + meta);
                progress.setProgress(0);
                action.setText(R.string.tut_center_start);
            }
            action.setOnClickListener(v -> startTutorial(def));
            itemView.setOnClickListener(v -> {
                FragmentActivity activity = getActivity();
                if (activity instanceof LauncherActivity) showDetails((LauncherActivity) activity, def, done, partial);
            });
        }
    }

    private void showDetails(@NonNull LauncherActivity activity, @NonNull TutorialDefinition def, boolean done, boolean partial) {
        StringBuilder message = new StringBuilder(getString(def.description));
        if (!def.prerequisites.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (String id : def.prerequisites) {
                TutorialDefinition pre = TutorialRegistry.get(id);
                if (pre == null || TutorialState.isCompleted(activity, id)) continue;
                if (names.length() > 0) names.append(", ");
                names.append(getString(pre.title));
            }
            if (names.length() > 0) message.append("\n\n").append(getString(R.string.tut_center_prereq, names));
        }
        new SettingsPopup(activity)
                .title(def.title)
                .subtitle(getString(R.string.tut_help_subtitle, def.steps().size()))
                .message(message)
                .positive(done ? R.string.tut_center_replay : partial ? R.string.tut_center_continue : R.string.tut_center_start,
                        () -> startTutorial(def))
                .negative(R.string.pe_cancel, null)
                .show();
    }
}
