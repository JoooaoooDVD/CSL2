package com.craftstudio.cslauncher.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.extra.ExtraConstants;
import com.craftstudio.cslauncher.extra.ExtraCore;
import com.craftstudio.cslauncher.game.renderer.RendererCache;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.InstanceIconProvider;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.multirt.MultiRTUtils;
import com.craftstudio.cslauncher.multirt.Runtime;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.CropperUtils;
import com.craftstudio.cslauncher.utils.LauncherBackground;
import com.craftstudio.cslauncher.utils.motion.MainMenuMotion;
import com.craftstudio.cslauncher.utils.motion.ProfileEditorMotion;
import com.craftstudio.cslauncher.utils.profiles.VersionSelectorDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The profile editor: identity (name + icon), the per-profile banner artwork and the
 * profile's game configuration (version, Java runtime, controlmap, JVM arguments,
 * renderer, shared data). Every edit is held in memory and written only when Save is
 * pressed; Cancel (or closing the screen) discards all pending changes and keeps the saved
 * profile intact. Pending edits survive the trip to the controlmap file picker. The
 * profile directory itself is never renamed - it is the profile's stable identity and game
 * directory - so a rename can't duplicate or overwrite any other profile.
 */
public class InstanceEditorFragment extends Fragment implements CropperUtils.CropperReceiver {
    public static final String TAG = "InstanceEditorFragment";

    /** Longest side stored for a profile banner crop (kept far above on-screen size) */
    private static final int BACKGROUND_MAX_SIDE = 1600;
    /** Hard cap for profile names so one long name can never break the home layout */
    private static final int MAX_NAME_LENGTH = 64;
    /** Built-in icons drawn as pixel art: scaled without smoothing to stay crisp */
    private static final List<String> PIXEL_ICONS = Arrays.asList("fox", "grass", "creeper", "sword", "enderman");

    // ─── Editing session (kept while the fragment lives, e.g. across the file picker) ──
    private Instance mInstance;
    private boolean mStateLoaded;
    private boolean mEntrancePlayed;
    private String mEditName;
    private String mVersionId;
    @Nullable private String mControlLayout;
    @Nullable private String mControlFromSelector;
    @Nullable private String mJvmArgs;
    private boolean mSharedData;
    private com.craftstudio.cslauncher.game.branding.BrandingMode mAppearance =
            com.craftstudio.cslauncher.game.branding.BrandingMode.VANILLA;
    private com.craftstudio.cslauncher.game.branding.BrandingMode mSavedAppearance = mAppearance;
    private boolean mAppearancePackEnabled;
    private java.util.EnumSet<com.craftstudio.cslauncher.game.branding.BrandingPart> mAppearanceParts = com.craftstudio.cslauncher.game.branding.BrandingPart.all();
    private String mSavedAppearanceParts;
    private final List<Runtime> mRuntimes = new ArrayList<>();
    private int mRuntimeIndex;
    private List<String> mRenderNames = new ArrayList<>();
    private final List<String> mRenderDisplayNames = new ArrayList<>();
    private int mRendererIndex;

    // ─── Pending artwork: memory only until Save ─────────────────────────────
    private Bitmap mPendingIcon;           // custom icon cropped from storage
    private String mPendingStaticIcon;     // built-in icon picked from the strip
    private Bitmap mPendingBackground;     // banner artwork cropped from storage
    private File mPendingRawBackground;    // picked GIF kept byte-for-byte so it stays animated
    private boolean mClearBackground;      // reset banner artwork to the global default
    private int mBackgroundLoadId;         // cancels stale preview decodes
    private int mRecommendedIconSize;

    // ─── Views ───────────────────────────────────────────────────────────────
    private EditText mName;
    private View mNameField;
    private ImageView mInstanceIcon, mBackgroundPreview;
    private TextView mVersionValue, mRuntimeValue, mControlValue, mJvmValue, mRendererValue, mSharedValue, mAppearanceValue;
    private MaterialSwitch mSharedSwitch;
    private ViewGroup mIconChoices;

    private final ActivityResultLauncher<?> mCropperLauncher = CropperUtils.registerCropper(this, this);

    /** Shared crop handling for the profile banner: a crop freezes animation. */
    private final CropperUtils.CropperReceiver mBackgroundCropReceiver = new CropperUtils.CropperReceiver() {
        @Override
        public float getAspectRatio() {
            return 16f / 6f;
        }

        @Override
        public int getTargetMaxSide() {
            return BACKGROUND_MAX_SIDE;
        }

        @Override
        public void onCropped(Bitmap contentBitmap) {
            discardPendingRawBackground();
            mPendingBackground = contentBitmap;
            mClearBackground = false;
            showBackgroundPreview(contentBitmap);
        }

        @Override
        public void onFailed(Exception exception) {
            Tools.showErrorRemote(exception);
        }
    };

    /** Banner entry point: GIFs bypass the cropper so the animation survives. */
    private final ActivityResultLauncher<String[]> mBackgroundPickLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onBackgroundPicked);

    public InstanceEditorFragment() {
        super(R.layout.fragment_instance_editor);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Coming back from the controlmap file picker
        String value = (String) ExtraCore.consumeValue(ExtraConstants.FILE_SELECTOR);
        if (value != null) mControlFromSelector = value;
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Context context = view.getContext();
        if (!mStateLoaded) {
            Instance selected = Instances.loadSelectedInstance();
            if (selected == null) {
                Toast.makeText(context, R.string.no_instance, Toast.LENGTH_LONG).show();
                getParentFragmentManager().popBackStack();
                return;
            }
            loadState(selected, context);
        }
        if (mControlFromSelector != null) {
            mControlLayout = mControlFromSelector;
            mControlFromSelector = null;
        }

        bindViews(view);
        bindBehaviors(view);
        applyStateToViews();
        applyCompactChrome(view);

        if (!mEntrancePlayed) {
            mEntrancePlayed = true;
            ProfileEditorMotion.playEntrance(
                    view.findViewById(R.id.vprof_editor_back),
                    view.findViewById(R.id.pe_title),
                    view.findViewById(R.id.pe_subtitle),
                    new View[]{view.findViewById(R.id.pe_card_identity), view.findViewById(R.id.pe_card_icon),
                            view.findViewById(R.id.pe_card_banner), view.findViewById(R.id.pe_card_config)},
                    view.findViewById(R.id.pe_icon_preview_box),
                    mIconChoices,
                    mBackgroundPreview,
                    view.findViewById(R.id.pe_config_rows),
                    view.findViewById(R.id.pe_action_bar));
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // Full screen editor: the launcher top bar steps aside while it is visible
        MainMenuMotion.topBar(getActivity(), false);
    }

    @Override
    public void onStop() {
        MainMenuMotion.topBar(getActivity(), true);
        super.onStop();
    }

    /**
     * Short screens (landscape phones): tighter header and action bar, so the cards get
     * most of the height. Tall screens keep the full-size mockup proportions.
     */
    private void applyCompactChrome(@NonNull View view) {
        if (getResources().getConfiguration().screenHeightDp >= 480) return;
        float density = getResources().getDisplayMetrics().density;
        View header = view.findViewById(R.id.pe_header);
        if (header != null) {
            header.setPadding(header.getPaddingLeft(), Math.round(4 * density),
                    header.getPaddingRight(), Math.round(6 * density));
        }
        View back = view.findViewById(R.id.vprof_editor_back);
        if (back != null) {
            ViewGroup.LayoutParams params = back.getLayoutParams();
            params.width = params.height = Math.round(44 * density);
            back.setLayoutParams(params);
        }
        TextView title = view.findViewById(R.id.pe_title);
        if (title != null) title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24);
        TextView subtitle = view.findViewById(R.id.pe_subtitle);
        if (subtitle != null) subtitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        View actionBar = view.findViewById(R.id.pe_action_bar);
        if (actionBar != null) {
            actionBar.setPadding(actionBar.getPaddingLeft(), Math.round(6 * density),
                    actionBar.getPaddingRight(), Math.round(8 * density));
        }
        int[] buttons = {R.id.vprof_editor_delete_button, R.id.vprof_editor_cancel_button, R.id.vprof_editor_save_button};
        for (int id : buttons) {
            View button = view.findViewById(id);
            if (button == null) continue;
            ViewGroup.LayoutParams params = button.getLayoutParams();
            params.height = Math.round(46 * density);
            button.setLayoutParams(params);
            if (button instanceof TextView) ((TextView) button).setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        }
    }

    @Override
    public void onDestroyView() {
        if (mName != null) mEditName = mName.getText().toString();
        stopPreviewAnimation();
        super.onDestroyView();
        mName = null;
        mNameField = null;
        mInstanceIcon = mBackgroundPreview = null;
        mVersionValue = mRuntimeValue = mControlValue = mJvmValue = mRendererValue = mSharedValue = mAppearanceValue = null;
        mSharedSwitch = null;
        mIconChoices = null;
    }

    // ─── State ───────────────────────────────────────────────────────────────

    private void loadState(@NonNull Instance instance, @NonNull Context context) {
        mInstance = instance;
        mStateLoaded = true;
        mPendingIcon = null;
        mPendingStaticIcon = null;
        mPendingBackground = null;
        discardPendingRawBackground();
        mClearBackground = false;

        mEditName = nullToEmpty(instance.name);
        mVersionId = instance.versionId;
        mControlLayout = instance.controlLayout;
        mJvmArgs = instance.jvmArgs;
        mSharedData = instance.sharedData;
        mAppearance = com.craftstudio.cslauncher.game.branding.BrandingManager.modeOf(instance);
        mSavedAppearance = mAppearance;
        mAppearanceParts = com.craftstudio.cslauncher.game.branding.BrandingManager.partsOf(instance);
        mSavedAppearanceParts = com.craftstudio.cslauncher.game.branding.BrandingPart.serialize(mAppearanceParts);
        mAppearancePackEnabled = false;
        // Cheap options.txt peek for the row status; off the UI thread
        final Instance peeked = instance;
        CSLauncherApplication.sExecutorService.execute(() -> {
            boolean enabled = com.craftstudio.cslauncher.game.branding.BrandingManager.isPackEnabled(peeked);
            Tools.runOnUiThread(() -> {
                if (mInstance != peeked) return;
                mAppearancePackEnabled = enabled;
                if (mAppearanceValue != null) mAppearanceValue.setText(appearanceLabel());
            });
        });

        mRuntimes.clear();
        try {
            mRuntimes.addAll(MultiRTUtils.getRuntimes());
        } catch (Exception e) {
            // No runtime folder yet: only the default entry is offered
        }
        mRuntimes.add(new Runtime("<Default>"));
        mRuntimeIndex = instance.selectedRuntime != null ? mRuntimes.indexOf(new Runtime(instance.selectedRuntime)) : -1;
        if (mRuntimeIndex < 0) mRuntimeIndex = mRuntimes.size() - 1;

        RendererCache renderers = RendererCache.getCompatibleRenderers(context);
        mRenderNames = renderers.rendererIds;
        mRenderDisplayNames.clear();
        mRenderDisplayNames.addAll(Arrays.asList(renderers.rendererDisplayNames));
        mRenderDisplayNames.add(context.getString(R.string.global_default));
        mRendererIndex = mRenderNames.indexOf(instance.getLaunchRenderer());
        if (mRendererIndex < 0) mRendererIndex = mRenderNames.size();
    }

    private void bindViews(@NonNull View view) {
        mName = view.findViewById(R.id.vprof_editor_instance_name);
        mNameField = view.findViewById(R.id.pe_name_field);
        mInstanceIcon = view.findViewById(R.id.vprof_editor_instance_icon);
        mBackgroundPreview = view.findViewById(R.id.vprof_editor_bg_preview);
        mVersionValue = view.findViewById(R.id.vprof_editor_version_spinner);
        mRuntimeValue = view.findViewById(R.id.pe_runtime_value);
        mControlValue = view.findViewById(R.id.vprof_editor_ctrl_spinner);
        mJvmValue = view.findViewById(R.id.pe_jvm_value);
        mRendererValue = view.findViewById(R.id.pe_renderer_value);
        mSharedValue = view.findViewById(R.id.pe_shared_value);
        mAppearanceValue = view.findViewById(R.id.pe_appearance_value);
        mSharedSwitch = view.findViewById(R.id.vprof_editor_shared_switch);
        mIconChoices = view.findViewById(R.id.vprof_editor_icon_choices);
        // Rounded clipping of the artwork previews
        mInstanceIcon.setClipToOutline(true);
        mBackgroundPreview.setClipToOutline(true);
    }

    private void bindBehaviors(@NonNull View view) {
        View back = view.findViewById(R.id.vprof_editor_back);
        View save = view.findViewById(R.id.vprof_editor_save_button);
        View delete = view.findViewById(R.id.vprof_editor_delete_button);
        View cancel = view.findViewById(R.id.vprof_editor_cancel_button);
        View controlSelect = view.findViewById(R.id.vprof_editor_ctrl_button);
        View iconGallery = view.findViewById(R.id.vprof_editor_icon_gallery);
        View iconAdd = view.findViewById(R.id.pe_icon_add);
        View iconBadge = view.findViewById(R.id.pe_icon_edit_badge);
        View bannerChoose = view.findViewById(R.id.vprof_editor_bg_choose);
        View bannerReset = view.findViewById(R.id.vprof_editor_bg_reset);
        View bannerBadge = view.findViewById(R.id.pe_banner_edit_badge);
        View rowVersion = view.findViewById(R.id.pe_row_version);
        View rowRuntime = view.findViewById(R.id.pe_row_runtime);
        View rowControl = view.findViewById(R.id.pe_row_control);
        View rowJvm = view.findViewById(R.id.pe_row_jvm);
        View rowRenderer = view.findViewById(R.id.pe_row_renderer);
        View rowShared = view.findViewById(R.id.pe_row_shared);
        View rowAppearance = view.findViewById(R.id.pe_row_appearance);
        if (rowAppearance != null) {
            rowAppearance.setOnClickListener(v -> showAppearanceDialog());
            CSAnimationEngine.attachPressFeedback(rowAppearance);
        }
        View rowFiles = view.findViewById(R.id.pe_row_files);
        if (rowFiles != null) {
            rowFiles.setOnClickListener(v -> openFileManager());
            CSAnimationEngine.attachPressFeedback(rowFiles);
        }

        for (View v : new View[]{back, save, delete, cancel, controlSelect, iconGallery, iconAdd, iconBadge,
                bannerChoose, bannerReset, bannerBadge, rowVersion, rowRuntime, rowControl, rowJvm, rowRenderer, rowShared}) {
            CSAnimationEngine.attachPressFeedback(v);
        }

        back.setOnClickListener(v -> Tools.removeCurrentFragment(requireActivity()));
        // Cancel discards everything pending - the saved profile is never touched
        cancel.setOnClickListener(v -> Tools.removeCurrentFragment(requireActivity()));
        delete.setOnClickListener(v -> {
            DeleteConfirmDialogFragment dialogFragment = new DeleteConfirmDialogFragment();
            dialogFragment.show(getChildFragmentManager(), "delete_dialog_confirm");
        });
        save.setOnClickListener(v -> {
            if (!save()) return;
            Toast.makeText(v.getContext(), R.string.profile_editor_saved, Toast.LENGTH_SHORT).show();
            // Listening screens (home banner, version pill, icon strip) re-read the saved state
            ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, null);
            Tools.backToMainMenu(requireActivity());
        });

        // Name field lifts while focused
        mName.setOnFocusChangeListener((v, focused) -> ProfileEditorMotion.fieldFocus(mNameField, focused));
        view.findViewById(R.id.pe_name_edit).setOnClickListener(v -> {
            mName.requestFocus();
            mName.setSelection(mName.getText().length());
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(mName, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });

        // Icon: built-in strip, or storage → cropper
        bindIconChoices();
        View.OnClickListener pickIcon = v -> {
            // Fill recommended size on click to get the most up to date data
            mRecommendedIconSize = Math.max(256, Math.max(mInstanceIcon.getWidth(), mInstanceIcon.getHeight()));
            CropperUtils.startCropper(mCropperLauncher);
        };
        mInstanceIcon.setOnClickListener(pickIcon);
        iconGallery.setOnClickListener(pickIcon);
        iconAdd.setOnClickListener(pickIcon);
        iconBadge.setOnClickListener(pickIcon);

        // Banner artwork: pick from storage (GIFs stay animated) or restore the default
        View.OnClickListener pickBanner = v -> mBackgroundPickLauncher.launch(new String[]{"image/*"});
        bannerChoose.setOnClickListener(pickBanner);
        bannerBadge.setOnClickListener(pickBanner);
        mBackgroundPreview.setOnClickListener(pickBanner);
        bannerReset.setOnClickListener(v -> resetBackground());

        // Game configuration rows
        rowVersion.setOnClickListener(v -> VersionSelectorDialog.open(v.getContext(), false, (id, snapshot) -> {
            mVersionId = id;
            if (mVersionValue != null) ProfileEditorMotion.valueChanged(mVersionValue, versionLabel());
        }));
        rowRuntime.setOnClickListener(v -> showRuntimeDialog());
        View.OnClickListener pickControl = v -> {
            Bundle bundle = new Bundle(3);
            bundle.putBoolean(FileSelectorFragment.BUNDLE_SELECT_FOLDER, false);
            bundle.putString(FileSelectorFragment.BUNDLE_ROOT_PATH, Tools.CTRLMAP_PATH);
            Tools.swapFragment(requireActivity(), FileSelectorFragment.class, FileSelectorFragment.TAG, bundle);
        };
        rowControl.setOnClickListener(pickControl);
        controlSelect.setOnClickListener(pickControl);
        // Long press goes back to the global controlmap
        rowControl.setOnLongClickListener(v -> {
            if (mControlLayout == null) return false;
            mControlLayout = null;
            ProfileEditorMotion.valueChanged(mControlValue, controlLabel());
            return true;
        });
        rowJvm.setOnClickListener(v -> showJvmDialog());
        rowRenderer.setOnClickListener(v -> showRendererDialog());
        rowShared.setOnClickListener(v -> mSharedSwitch.toggle());
        mSharedSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked == mSharedData) return;
            mSharedData = checked;
            ProfileEditorMotion.valueChanged(mSharedValue, getString(checked
                    ? R.string.instance_shared_data_on : R.string.instance_shared_data_off));
        });
    }

    private void applyStateToViews() {
        mName.setText(mEditName);
        mVersionValue.setText(versionLabel());
        mRuntimeValue.setText(runtimeLabel());
        mControlValue.setText(controlLabel());
        mJvmValue.setText(jvmLabel());
        mRendererValue.setText(rendererLabel());
        mSharedSwitch.setChecked(mSharedData);
        mSharedValue.setText(mSharedData ? R.string.instance_shared_data_on : R.string.instance_shared_data_off);
        if (mAppearanceValue != null) mAppearanceValue.setText(appearanceLabel());

        if (mPendingIcon != null) {
            mInstanceIcon.setImageBitmap(mPendingIcon);
            updateStaticIconSelection(null);
        } else if (mPendingStaticIcon != null) {
            setStaticPreview(mPendingStaticIcon);
            updateStaticIconSelection(mPendingStaticIcon);
        } else {
            mInstanceIcon.setImageDrawable(InstanceIconProvider.fetchIcon(getResources(), mInstance));
            String icon = mInstance.hasCustomIconFile() ? null : mInstance.icon;
            if (icon != null && PIXEL_ICONS.contains(icon)) crisp(mInstanceIcon.getDrawable());
            updateStaticIconSelection(icon);
        }
        refreshBackgroundPreview();
    }

    // ─── Value labels ────────────────────────────────────────────────────────

    private String versionLabel() {
        return mVersionId == null || mVersionId.isEmpty() ? getString(R.string.pe_no_version) : mVersionId;
    }

    private String runtimeLabel() {
        if (mRuntimeIndex < 0 || mRuntimeIndex >= mRuntimes.size() - 1) return getString(R.string.pe_default_runtime);
        return runtimeDisplay(mRuntimes.get(mRuntimeIndex));
    }

    private String runtimeDisplay(Runtime runtime) {
        return runtime.name.replace(".tar.xz", "") + " - " + (runtime.versionString == null
                ? getString(R.string.multirt_runtime_corrupt) : runtime.versionString);
    }

    private String controlLabel() {
        if (mControlLayout == null || mControlLayout.isEmpty()) return getString(R.string.pe_use_global);
        return new File(mControlLayout).getName();
    }

    private String jvmLabel() {
        return mJvmArgs == null || mJvmArgs.trim().isEmpty() ? getString(R.string.pe_use_global) : mJvmArgs.trim();
    }

    private String rendererLabel() {
        if (mRendererIndex < 0 || mRendererIndex >= mRenderDisplayNames.size()) return getString(R.string.global_default);
        return mRenderDisplayNames.get(mRendererIndex);
    }

    // ─── Dialogs ─────────────────────────────────────────────────────────────

    private void showRuntimeDialog() {
        new com.kdt.mcgui.RuntimePickerDialog(requireContext(), mRuntimes, mRuntimeIndex, which -> {
            mRuntimeIndex = which;
            if (mRuntimeValue != null) ProfileEditorMotion.valueChanged(mRuntimeValue, runtimeLabel());
        }).show();
    }

    private void showRendererDialog() {
        java.util.List<String> names = new java.util.ArrayList<>(mRenderDisplayNames);
        String defaultLabel = names.remove(names.size() - 1);
        new com.kdt.mcgui.RendererPickerDialog(requireContext(), mRenderNames, names, mRendererIndex, defaultLabel, which -> {
            mRendererIndex = which;
            if (mRendererValue != null) ProfileEditorMotion.valueChanged(mRendererValue, rendererLabel());
        }).show();
    }

    private void showAppearanceDialog() {
        new com.kdt.mcgui.AppearancePickerDialog(requireContext(), mAppearance, mAppearanceParts, (mode, parts) -> {
            if (mode == mAppearance && parts.equals(mAppearanceParts)) return;
            mAppearance = mode;
            mAppearanceParts = parts;
            if (mAppearanceValue != null) ProfileEditorMotion.valueChanged(mAppearanceValue, appearanceLabel());
        }).show();
    }

    private String appearanceLabel() {
        if (mAppearance == com.craftstudio.cslauncher.game.branding.BrandingMode.VANILLA) {
            return getString(R.string.branding_status_vanilla);
        }
        boolean unchanged = mSavedAppearance == mAppearance
                && java.util.Objects.equals(mSavedAppearanceParts, com.craftstudio.cslauncher.game.branding.BrandingPart.serialize(mAppearanceParts));
        boolean full = mAppearanceParts.containsAll(com.craftstudio.cslauncher.game.branding.BrandingPart.all());
        if (!unchanged) return getString(R.string.branding_status_custom_pending);
        if (full) return getString(mAppearancePackEnabled ? R.string.branding_status_custom_enabled : R.string.branding_status_custom_pending);
        return getString(R.string.branding_status_custom_parts,
                com.kdt.mcgui.AppearancePickerDialog.partNames(requireContext(), mAppearanceParts));
    }

    /**
     * After Save: apply the appearance to the profile folder right away (worker thread) so the
     * user gets immediate feedback instead of waiting for the next launch. The launch pipeline
     * runs the same idempotent step again, so a failure here is informational only.
     */
    private void applyAppearanceAfterSave(@NonNull Instance instance, boolean modeChanged) {
        com.craftstudio.cslauncher.game.branding.BrandingMode mode =
                com.craftstudio.cslauncher.game.branding.BrandingManager.modeOf(instance);
        boolean worthRunning = modeChanged || mode == com.craftstudio.cslauncher.game.branding.BrandingMode.CUSTOM;
        if (!worthRunning) return;
        final Context app = requireContext().getApplicationContext();
        final String versionId = instance.versionId;
        CSLauncherApplication.sExecutorService.execute(() -> {
            String resolved = versionId;
            try {
                if (versionId != null && (Instance.VERSION_LATEST_RELEASE.equals(versionId)
                        || Instance.VERSION_LATEST_SNAPSHOT.equals(versionId))) {
                    resolved = null; // not downloaded/resolved here: the launch pipeline handles it
                }
            } catch (Exception ignored) {
            }
            com.craftstudio.cslauncher.game.branding.BrandingManager.Outcome outcome =
                    com.craftstudio.cslauncher.game.branding.BrandingManager.apply(app, instance, resolved);
            // JAR_MISSING is the expected "first launch will do it" case: no toast noise for that
            if (outcome.applied && mode == com.craftstudio.cslauncher.game.branding.BrandingMode.CUSTOM
                    || outcome.reason != null && outcome.reason != com.craftstudio.cslauncher.game.branding.BrandingException.Reason.JAR_MISSING) {
                final String msg = outcome.message(app);
                Tools.runOnUiThread(() -> Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showJvmDialog() {
        Context context = requireContext();
        float density = getResources().getDisplayMetrics().density;
        EditText input = new EditText(context);
        input.setText(mJvmArgs == null ? "" : mJvmArgs);
        input.setHint(R.string.pe_jvm_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(2);
        input.setMaxLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setTextSize(14);
        input.setSelection(input.getText().length());
        FrameLayout box = new FrameLayout(context);
        int pad = Math.round(20 * density);
        box.setPadding(pad, Math.round(8 * density), pad, 0);
        box.addView(input, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.pe_jvm_args)
                .setView(box)
                .setPositiveButton(R.string.global_save, (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    mJvmArgs = value.isEmpty() ? null : value;
                    if (mJvmValue != null) ProfileEditorMotion.valueChanged(mJvmValue, jvmLabel());
                })
                .setNeutralButton(R.string.global_default, (dialog, which) -> {
                    mJvmArgs = null;
                    if (mJvmValue != null) ProfileEditorMotion.valueChanged(mJvmValue, jvmLabel());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ─── Built-in icon strip ────────────────────────────────────────────────

    /** Wires the launcher-icon chips (tags carry the InstanceIconProvider icon names) */
    private void bindIconChoices() {
        if (mIconChoices == null) return;
        for (int i = 0; i < mIconChoices.getChildCount(); i++) {
            View chip = mIconChoices.getChildAt(i);
            if (!(chip.getTag() instanceof String)) continue;
            String name = (String) chip.getTag();
            if (PIXEL_ICONS.contains(name) && chip instanceof ImageView) crisp(((ImageView) chip).getDrawable());
            chip.setOnClickListener(v -> {
                ProfileEditorMotion.chipSelected(v);
                selectStaticIcon(String.valueOf(v.getTag()));
            });
            CSAnimationEngine.attachPressFeedback(chip);
        }
    }

    private void selectStaticIcon(@Nullable String iconName) {
        if (iconName == null || mInstance == null || !InstanceIconProvider.hasStaticIcon(iconName)) return;
        mPendingStaticIcon = iconName;
        mPendingIcon = null;
        setStaticPreview(iconName);
        updateStaticIconSelection(iconName);
        ProfileEditorMotion.iconChanged(mInstanceIcon);
    }

    private void setStaticPreview(@NonNull String iconName) {
        Drawable drawable = InstanceIconProvider.fetchStaticIconByName(getResources(), iconName);
        if (PIXEL_ICONS.contains(iconName)) crisp(drawable);
        mInstanceIcon.setImageDrawable(drawable);
    }

    private void updateStaticIconSelection(@Nullable String selectedTag) {
        if (mIconChoices == null) return;
        for (int i = 0; i < mIconChoices.getChildCount(); i++) {
            View chip = mIconChoices.getChildAt(i);
            boolean selected = selectedTag != null && selectedTag.equals(String.valueOf(chip.getTag()));
            chip.setSelected(selected);
            if (selected) {
                // Keep the chosen chip in view
                View scroll = (View) mIconChoices.getParent();
                if (scroll instanceof android.widget.HorizontalScrollView) {
                    scroll.post(() -> ((android.widget.HorizontalScrollView) scroll)
                            .smoothScrollTo(Math.max(0, chip.getLeft() - chip.getWidth()), 0));
                }
            }
        }
    }

    private static void crisp(@Nullable Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            ((BitmapDrawable) drawable).setFilterBitmap(false);
            ((BitmapDrawable) drawable).setAntiAlias(false);
        }
    }

    // ─── Profile banner artwork ─────────────────────────────────────────────

    /** Shows the stored artwork, the pending crop, or the default banner in the preview */
    private void refreshBackgroundPreview() {
        if (mBackgroundPreview == null) return;
        if (mPendingBackground != null) {
            mBackgroundPreview.setImageBitmap(mPendingBackground);
            return;
        }
        if (mClearBackground || mInstance == null || !mInstance.hasCustomBackground()) {
            mBackgroundPreview.setImageResource(R.drawable.banner_home);
            return;
        }
        final File backgroundFile = mInstance.getBackgroundFile();
        final int loadId = ++mBackgroundLoadId;
        // Decode off the UI thread; only the newest request may publish its result
        CSLauncherApplication.sExecutorService.execute(() -> {
            Drawable drawable = LauncherBackground.decodeImage(backgroundFile);
            Tools.runOnUiThread(() -> {
                if (loadId != mBackgroundLoadId || mBackgroundPreview == null) return;
                if (drawable == null) {
                    // Missing/unreadable artwork: show the default and allow a re-pick
                    mBackgroundPreview.setImageResource(R.drawable.banner_home);
                    return;
                }
                mBackgroundPreview.setImageDrawable(drawable);
                playPreviewAnimation();
                ProfileEditorMotion.bannerChanged(mBackgroundPreview);
            });
        });
    }

    private void showBackgroundPreview(@Nullable Bitmap bitmap) {
        if (mBackgroundPreview == null || bitmap == null) return;
        mBackgroundLoadId++;
        stopPreviewAnimation();
        mBackgroundPreview.setImageBitmap(bitmap);
        ProfileEditorMotion.bannerChanged(mBackgroundPreview);
    }

    /** A picked GIF is staged byte-for-byte; anything else goes through the cropper. */
    private void onBackgroundPicked(@Nullable Uri uri) {
        if (uri == null || getContext() == null) return;
        String mime = getContext().getContentResolver().getType(uri);
        if (!"image/gif".equalsIgnoreCase(mime)) {
            CropperUtils.openCropper(requireContext(), uri, mBackgroundCropReceiver);
            return;
        }
        final android.content.ContentResolver resolver = getContext().getContentResolver();
        CSLauncherApplication.sExecutorService.execute(() -> {
            File staged = new File(Tools.DIR_CACHE, "pending_profile_bg");
            try (InputStream in = resolver.openInputStream(uri);
                 OutputStream out = new FileOutputStream(staged)) {
                if (in == null) throw new java.io.IOException("Can't open " + uri);
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                out.flush();
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                staged.delete();
                Tools.showErrorRemote(e);
                return;
            }
            Drawable drawable = LauncherBackground.decodeImage(staged);
            if (getActivity() == null) {
                //noinspection ResultOfMethodCallIgnored
                staged.delete();
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (mBackgroundPreview == null) return;
                if (drawable == null) {
                    staged.delete();
                    Tools.showErrorRemote(new java.io.IOException("Unusable background"));
                    return;
                }
                discardPendingRawBackground();
                mPendingRawBackground = staged;
                mPendingBackground = null;
                mClearBackground = false;
                stopPreviewAnimation();
                mBackgroundPreview.setImageDrawable(drawable);
                playPreviewAnimation();
                ProfileEditorMotion.bannerChanged(mBackgroundPreview);
            });
        });
    }

    /** Drops a staged GIF without touching the saved profile. */
    private void discardPendingRawBackground() {
        if (mPendingRawBackground != null) {
            //noinspection ResultOfMethodCallIgnored
            mPendingRawBackground.delete();
            mPendingRawBackground = null;
        }
    }

    /** Plays the preview when it is an animated image (GIF, animated WebP). */
    private void playPreviewAnimation() {
        if (mBackgroundPreview == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        Drawable drawable = mBackgroundPreview.getDrawable();
        if (!(drawable instanceof AnimatedImageDrawable)) return;
        AnimatedImageDrawable animated = (AnimatedImageDrawable) drawable;
        animated.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
        animated.start();
    }

    private void stopPreviewAnimation() {
        if (mBackgroundPreview == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        Drawable drawable = mBackgroundPreview.getDrawable();
        if (drawable instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) drawable).stop();
    }

    private void resetBackground() {
        mClearBackground = true;
        mPendingBackground = null;
        discardPendingRawBackground();
        mBackgroundLoadId++;
        if (mBackgroundPreview != null) {
            stopPreviewAnimation();
            mBackgroundPreview.setImageResource(R.drawable.banner_home);
            ProfileEditorMotion.bannerChanged(mBackgroundPreview);
        }
    }

    // ─── Save / validation ──────────────────────────────────────────────────

    /**
     * Validates the inputs and persists every section of the editor in one write.
     * @return true when the profile was saved; false when validation or IO failed
     *         (the editor stays open and shows the reason)
     */
    private boolean save() {
        if (mInstance == null || mName == null) return false;

        String newName = mName.getText().toString().trim();
        if (newName.isEmpty() || newName.length() > MAX_NAME_LENGTH) {
            mName.setError(getString(newName.isEmpty() ? R.string.profile_name_empty_error : R.string.profile_name_too_long));
            mName.requestFocus();
            ProfileEditorMotion.invalid(mNameField);
            return false;
        }

        mInstance.name = newName;
        mInstance.versionId = mVersionId;
        mInstance.controlLayout = mControlLayout == null || mControlLayout.isEmpty() ? null : mControlLayout;
        mInstance.jvmArgs = mJvmArgs == null || mJvmArgs.trim().isEmpty() ? null : mJvmArgs.trim();
        mInstance.sharedData = mSharedData;
        String newParts = com.craftstudio.cslauncher.game.branding.BrandingPart.serialize(mAppearanceParts);
        boolean appearanceChanged = mAppearance != com.craftstudio.cslauncher.game.branding.BrandingManager.modeOf(mInstance)
                || !java.util.Objects.equals(newParts, mInstance.appearanceParts);
        mInstance.appearance = mAppearance == com.craftstudio.cslauncher.game.branding.BrandingMode.VANILLA ? null : mAppearance.key;
        mInstance.appearanceParts = newParts;

        Runtime runtime = mRuntimeIndex >= 0 && mRuntimeIndex < mRuntimes.size() ? mRuntimes.get(mRuntimeIndex) : null;
        mInstance.selectedRuntime = runtime == null || runtime.name.equals("<Default>") || runtime.versionString == null
                ? null : runtime.name;

        if (mRendererIndex < 0 || mRendererIndex >= mRenderNames.size()) mInstance.renderer = null;
        else mInstance.renderer = mRenderNames.get(mRendererIndex);

        try {
            boolean iconChanged = false;
            if (mPendingIcon != null) {
                mInstance.encodeNewIcon(mPendingIcon);
                iconChanged = true;
            } else if (mPendingStaticIcon != null) {
                mInstance.deleteCustomIcon();
                mInstance.icon = mPendingStaticIcon;
                iconChanged = true;
            }

            if (mPendingRawBackground != null) {
                mInstance.storeRawBackground(mPendingRawBackground);
                discardPendingRawBackground();
            } else if (mPendingBackground != null) {
                mInstance.encodeNewBackground(mPendingBackground);
            } else if (mClearBackground) {
                mInstance.clearCustomBackground();
            }

            // One write for the whole profile; the directory (identity + game dir) is untouched,
            // so renaming can neither duplicate this profile nor overwrite another one.
            mInstance.write();

            if (iconChanged) InstanceIconProvider.dropIcon(mInstance);
            mSavedAppearance = mAppearance;
            mSavedAppearanceParts = newParts;
            applyAppearanceAfterSave(mInstance, appearanceChanged);
            return true;
        } catch (Exception e) {
            Tools.showErrorRemote(e);
            return false;
        }
    }

    private static String nullToEmpty(String in) {
        return in == null ? "" : in;
    }

    // ─── Icon cropper ───────────────────────────────────────────────────────

    @Override
    public float getAspectRatio() {
        return 1f;
    }

    @Override
    public int getTargetMaxSide() {
        return mRecommendedIconSize;
    }

    @Override
    public void onCropped(Bitmap contentBitmap) {
        // Held in memory: the file is only written when Save is pressed
        mPendingIcon = contentBitmap;
        mPendingStaticIcon = null;
        updateStaticIconSelection(null);
        if (mInstanceIcon == null) return;
        mInstanceIcon.setImageBitmap(contentBitmap);
        ProfileEditorMotion.iconChanged(mInstanceIcon);
    }

    @Override
    public void onFailed(Exception exception) {
        Tools.showErrorRemote(exception);
    }

    /** Opens the sandboxed file manager rooted at this profile's game directory */
    private void openFileManager() {
        if (mInstance == null) return;
        java.io.File root;
        try {
            root = mInstance.getGameDirectory();
        } catch (Exception e) {
            root = new java.io.File(Tools.DIR_GAME_NEW);
        }
        String title = mName != null && mName.getText() != null && mName.getText().length() > 0
                ? mName.getText().toString() : (mInstance.name != null ? mInstance.name : "Profile");
        Tools.swapFragment(requireActivity(), FileManagerFragment.class, FileManagerFragment.TAG,
                FileManagerFragment.args(root, title, null));
    }
}
