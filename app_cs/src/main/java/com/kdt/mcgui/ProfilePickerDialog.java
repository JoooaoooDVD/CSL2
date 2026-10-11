package com.kdt.mcgui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.fragments.InstanceVersionResolver;
import com.craftstudio.cslauncher.instances.DisplayInstance;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.InstanceAdapter;
import com.craftstudio.cslauncher.instances.InstanceIconProvider;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.ProfilePickerMotion;

import java.util.List;
import java.util.Locale;

/**
 * Popup listing every instance (replaces the old drop-down of {@link mcVersionSpinner}).
 * Left column: current profile + Create New Instance, right column: scrollable list where a
 * tap selects the profile and the pencil opens its editor.
 */
public class ProfilePickerDialog extends Dialog {

    public interface Callback {
        void onProfileSelected(int position);
        void onProfileEdit(int position);
        void onCreateInstance();
    }

    private final List<DisplayInstance> mInstances;
    private final Callback mCallback;
    private int mSelected;
    private boolean mClosing;

    private View mScrim, mCard, mSideContent, mCreate, mCurrent;
    private RecyclerView mList;
    private RowAdapter mAdapter;

    public ProfilePickerDialog(@NonNull Context context, @NonNull List<DisplayInstance> instances,
                               int selected, @NonNull Callback callback) {
        super(context, R.style.CS_ProfilePickerDialog);
        mInstances = instances;
        mSelected = selected;
        mCallback = callback;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_profile_picker);
        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        mScrim = findViewById(R.id.pp_scrim);
        mCard = findViewById(R.id.pp_card);
        mSideContent = findViewById(R.id.pp_side_content);
        mCreate = findViewById(R.id.pp_create);
        mCurrent = findViewById(R.id.pp_current);
        mList = findViewById(R.id.pp_list);

        // Rounded corners for the card content and the textured button on every API level
        mCard.setClipToOutline(true);
        mCreate.setClipToOutline(true);
        findViewById(R.id.pp_current_tile).setClipToOutline(true);
        sizeCard();

        mScrim.setOnClickListener(v -> close(null));
        findViewById(R.id.pp_close).setOnClickListener(v -> close(null));
        mCreate.setOnClickListener(v -> close(mCallback::onCreateInstance));
        CSAnimationEngine.attachPressFeedback(mCreate);
        CSAnimationEngine.attachPressFeedback(findViewById(R.id.pp_close));

        int count = mInstances.size();
        ((TextView) findViewById(R.id.pp_count)).setText(count == 1
                ? getContext().getString(R.string.pp_count_one)
                : String.format(Locale.getDefault(), getContext().getString(R.string.pp_count_many), count));
        findViewById(R.id.pp_empty).setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        bindCurrent(false);

        mAdapter = new RowAdapter();
        mList.setLayoutManager(new LinearLayoutManager(getContext()));
        mList.setAdapter(mAdapter);
        mList.setItemAnimator(null);
        if (mSelected > 2 && mSelected < count) mList.scrollToPosition(mSelected - 1);

        ProfilePickerMotion.enter(mScrim, mCard, (ViewGroup) mSideContent, mCreate, mList);
    }

    /** Card sized to the screen: wide landscape layout, never touching the edges */
    private void sizeCard() {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        int width = Math.min((int) (700 * d), metrics.widthPixels - (int) (40 * d));
        int height = Math.min((int) (380 * d), metrics.heightPixels - (int) (28 * d));
        ViewGroup.LayoutParams params = mCard.getLayoutParams();
        params.width = width;
        params.height = height;
        mCard.setLayoutParams(params);
        View side = findViewById(R.id.pp_side);
        ViewGroup.LayoutParams sideParams = side.getLayoutParams();
        sideParams.width = Math.max((int) (200 * d), (int) (width * 0.36f));
        side.setLayoutParams(sideParams);
    }

    @Override
    public void show() {
        // Keep the launcher's immersive / edge-to-edge state: show without focus first so the
        // system bars do not pop in, copy the activity state, then take focus back.
        Window window = getWindow();
        Activity activity = findActivity(getContext());
        if (window == null || activity == null) {
            super.show();
            return;
        }
        window.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.layoutInDisplayCutoutMode = activity.getWindow().getAttributes().layoutInDisplayCutoutMode;
            window.setAttributes(attrs);
        }
        super.show();
        applySystemBars(window, activity);
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    @SuppressWarnings("deprecation")
    private static void applySystemBars(@NonNull Window window, @NonNull Activity activity) {
        View activityDecor = activity.getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsets insets = activityDecor.getRootWindowInsets();
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null && insets != null && !insets.isVisible(WindowInsets.Type.statusBars())) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            window.getDecorView().setSystemUiVisibility(activityDecor.getSystemUiVisibility());
        }
    }

    @Nullable
    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        close(null);
    }

    /** Animated close, {@code then} runs once the popup is gone */
    private void close(@Nullable Runnable then) {
        if (mClosing) return;
        mClosing = true;
        ProfilePickerMotion.exit(mScrim, mCard, () -> {
            try {
                if (isShowing()) dismiss();
            } catch (IllegalArgumentException ignored) {
                // Window already detached (activity finishing)
            }
            if (then != null) then.run();
        });
    }

    // ─── Content ─────────────────────────────────────────────────────────────

    private void bindCurrent(boolean animate) {
        ImageView icon = findViewById(R.id.pp_current_icon);
        TextView name = findViewById(R.id.pp_current_name);
        TextView sub = findViewById(R.id.pp_current_sub);
        if (mSelected < 0 || mSelected >= mInstances.size()) {
            icon.setImageDrawable(null);
            name.setText(R.string.pp_none);
            sub.setText("");
            return;
        }
        DisplayInstance instance = mInstances.get(mSelected);
        icon.setImageDrawable(iconOf(instance));
        name.setText(nameOf(instance));
        sub.setText(subtitleOf(instance));
        if (animate) ProfilePickerMotion.currentChanged(mCurrent);
    }

    private Drawable iconOf(DisplayInstance instance) {
        Drawable shared = InstanceIconProvider.fetchIcon(getContext().getResources(), instance);
        // The cached drawable is also used by the home chip: never share its bounds/state
        Drawable icon = shared.getConstantState() != null
                ? shared.getConstantState().newDrawable(getContext().getResources()).mutate()
                : shared;
        if (icon instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) icon;
            // Pixel art icons stay crisp, photos keep smooth filtering
            if (bitmapDrawable.getBitmap() != null && bitmapDrawable.getBitmap().getWidth() <= 64) {
                bitmapDrawable.setFilterBitmap(false);
            }
        }
        return icon;
    }

    private String nameOf(DisplayInstance instance) {
        String name = InstanceAdapter.plainProfileName(Tools.validOrNullString(instance.name), instance.versionId);
        if (name != null) return name;
        String version = versionOf(instance);
        return version != null ? version : (instance.versionId != null ? instance.versionId : "");
    }

    @Nullable
    private String versionOf(DisplayInstance instance) {
        if (Instance.VERSION_LATEST_RELEASE.equalsIgnoreCase(instance.versionId))
            return getContext().getString(R.string.profiles_latest_release);
        if (Instance.VERSION_LATEST_SNAPSHOT.equalsIgnoreCase(instance.versionId))
            return getContext().getString(R.string.profiles_latest_snapshot);
        return Tools.validOrNullString(InstanceVersionResolver.shortVersion(instance.versionId));
    }

    private String subtitleOf(DisplayInstance instance) {
        String version = versionOf(instance);
        String loader = instance.versionId == null ? "" : InstanceVersionResolver.loaderFromVersionId(instance.versionId);
        String loaderLabel;
        switch (loader) {
            case "fabric": loaderLabel = "Fabric"; break;
            case "quilt": loaderLabel = "Quilt"; break;
            case "forge": loaderLabel = "Forge"; break;
            case "neoforge": loaderLabel = "NeoForge"; break;
            default: loaderLabel = "Vanilla"; break;
        }
        if (version == null) return loaderLabel;
        return getContext().getString(R.string.home_version_subtitle_fmt, version, loaderLabel);
    }

    private void select(int position, @NonNull View row) {
        if (mClosing) return;
        int previous = mSelected;
        mSelected = position;
        if (previous >= 0) mAdapter.notifyItemChanged(previous);
        mAdapter.notifyItemChanged(position);
        bindCurrent(true);
        ProfilePickerMotion.rowChosen(row);
        // Short beat so the user sees the choice land, then close and apply
        row.postDelayed(() -> close(() -> mCallback.onProfileSelected(position)), 170);
    }

    private final class RowAdapter extends RecyclerView.Adapter<RowHolder> {
        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_profile_picker, parent, false);
            return new RowHolder(view);
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onBindViewHolder(@NonNull RowHolder holder, int position) {
            DisplayInstance instance = mInstances.get(position);
            holder.icon.setImageDrawable(iconOf(instance));
            holder.name.setText(nameOf(instance));
            holder.sub.setText(subtitleOf(instance));
            boolean active = position == mSelected;
            holder.row.setActivated(active);
            holder.active.setVisibility(active ? View.VISIBLE : View.GONE);
            holder.row.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) select(pos, holder.row);
            });
            holder.edit.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) close(() -> mCallback.onProfileEdit(pos));
            });
        }

        @Override
        public int getItemCount() {
            return mInstances.size();
        }
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        final View row, active, edit;
        final ImageView icon;
        final TextView name, sub;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            row = itemView.findViewById(R.id.pp_row);
            active = itemView.findViewById(R.id.pp_row_active);
            edit = itemView.findViewById(R.id.pp_row_edit);
            icon = itemView.findViewById(R.id.pp_row_icon);
            name = itemView.findViewById(R.id.pp_row_name);
            sub = itemView.findViewById(R.id.pp_row_sub);
            itemView.findViewById(R.id.pp_row_tile).setClipToOutline(true);
            CSAnimationEngine.attachPressFeedback(row);
            CSAnimationEngine.attachPressFeedback(edit);
        }
    }
}
