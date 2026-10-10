package com.craftstudio.cslauncher.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.extra.ExtraConstants;
import com.craftstudio.cslauncher.extra.ExtraCore;
import com.craftstudio.cslauncher.game.renderer.RendererCache;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.multirt.MultiRTUtils;
import com.craftstudio.cslauncher.multirt.RTSpinnerAdapter;
import com.craftstudio.cslauncher.multirt.Runtime;
import com.craftstudio.cslauncher.instances.InstanceIconProvider;
import com.craftstudio.cslauncher.utils.profiles.VersionSelectorDialog;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.CropperUtils;
import com.craftstudio.cslauncher.utils.LauncherBackground;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The profile editor: identity (name + icon), appearance (per-profile banner artwork)
 * and the profile's game configuration. Every edit is held in memory and written only
 * when Save is pressed; Cancel (or closing the screen) discards all pending changes and
 * keeps the saved profile intact. The profile directory itself is never renamed - it is
 * the profile's stable identity and game directory - so a rename can't duplicate or
 * overwrite any other profile.
 */
public class InstanceEditorFragment extends Fragment implements CropperUtils.CropperReceiver {
    public static final String TAG = "InstanceEditorFragment";

    /** Longest side stored for a profile banner crop (kept far above on-screen size) */
    private static final int BACKGROUND_MAX_SIDE = 1600;
    /** Hard cap for profile names so one long name can never break the home layout */
    private static final int MAX_NAME_LENGTH = 64;

    private Instance mInstance;
    private String mSelectedControlLayout;
    private Button mSaveButton, mDeleteButton, mCancelButton, mControlSelectButton, mVersionSelectButton;
    private Spinner mDefaultRuntime, mDefaultRenderer;
    private EditText mDefaultName, mDefaultJvmArgument;
    private TextView mDefaultVersion, mDefaultControl;
    private ImageView mInstanceIcon, mBackgroundPreview;
    private CheckBox mSharedDataCheckbox;
    private ViewGroup mIconChoices;
    private int mRecommendedIconSize;

    // ─── Pending changes: memory only until Save ─────────────────────────────
    private Bitmap mPendingIcon;           // custom icon cropped from storage
    private String mPendingStaticIcon;     // built-in icon picked from the strip
    private Bitmap mPendingBackground;     // banner artwork cropped from storage
    private boolean mClearBackground;      // reset banner artwork to the global default
    private int mBackgroundLoadId;         // cancels stale preview decodes

    private final ActivityResultLauncher<?> mCropperLauncher = CropperUtils.registerCropper(this, this);

    /** Second picker for the profile banner: wide crop box, larger output */
    private final ActivityResultLauncher<?> mBackgroundCropperLauncher = CropperUtils.registerCropper(this,
            new CropperUtils.CropperReceiver() {
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
                    mPendingBackground = contentBitmap;
                    mClearBackground = false;
                    showBackgroundPreview(contentBitmap);
                }

                @Override
                public void onFailed(Exception exception) {
                    Tools.showErrorRemote(exception);
                }
            });

    private List<String> mRenderNames;

    public InstanceEditorFragment() {
        super(R.layout.fragment_instance_editor);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Paths, which can be changed
        String value = (String) ExtraCore.consumeValue(ExtraConstants.FILE_SELECTOR);
        if (value != null) {
            mSelectedControlLayout = value;
        }
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        CSAnimationEngine.animateDialogContentView(view.findViewById(R.id.instance_editor_banner));
        bindViews(view);
        bindIconChoices(view);
        CSAnimationEngine.attachPressFeedback(mSaveButton);
        CSAnimationEngine.attachPressFeedback(mCancelButton);
        CSAnimationEngine.attachPressFeedback(mDeleteButton);
        CSAnimationEngine.attachPressFeedback(mControlSelectButton);
        CSAnimationEngine.attachPressFeedback(mVersionSelectButton);

        RendererCache list = RendererCache.getCompatibleRenderers(view.getContext());
        mRenderNames = list.rendererIds;
        List<String> renderList = new ArrayList<>(list.rendererDisplayNames.length + 1);
        renderList.addAll(Arrays.asList(list.rendererDisplayNames));
        renderList.add(view.getContext().getString(R.string.global_default));
        mDefaultRenderer.setAdapter(new ArrayAdapter<>(view.getContext(), R.layout.item_simple_list_1, renderList));

        // Set up behaviors
        mSaveButton.setOnClickListener(v -> {
            if (!save()) return;
            Toast.makeText(view.getContext(), R.string.profile_editor_saved, Toast.LENGTH_SHORT).show();
            // Listening screens (home banner, version pill, icon strip) re-read the saved state
            ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, null);
            Tools.backToMainMenu(requireActivity());
        });

        mDeleteButton.setOnClickListener(v -> {
            DeleteConfirmDialogFragment dialogFragment = new DeleteConfirmDialogFragment();
            dialogFragment.show(getChildFragmentManager(), "delete_dialog_confirm");
        });

        // Cancel discards everything pending - the saved profile is never touched
        mCancelButton.setOnClickListener(v -> Tools.removeCurrentFragment(requireActivity()));

        View.OnClickListener controlSelectListener = getControlSelectListener();
        mControlSelectButton.setOnClickListener(controlSelectListener);
        mDefaultControl.setOnClickListener(controlSelectListener);

        // Setup the expendable list behavior
        View.OnClickListener versionSelectListener = getVersionSelectListener();
        mVersionSelectButton.setOnClickListener(versionSelectListener);
        mDefaultVersion.setOnClickListener(versionSelectListener);

        // Set up the icon change click listeners (system picker → cropper)
        View.OnClickListener pickIconListener = v -> {
            // Fill recommended size on click to get the most up to date data
            mRecommendedIconSize = Math.max(mInstanceIcon.getWidth(), mInstanceIcon.getHeight());
            CropperUtils.startCropper(mCropperLauncher);
        };
        mInstanceIcon.setOnClickListener(pickIconListener);
        View galleryButton = view.findViewById(R.id.vprof_editor_icon_gallery);
        if (galleryButton != null) {
            galleryButton.setOnClickListener(pickIconListener);
            CSAnimationEngine.attachPressFeedback(galleryButton);
        }

        // Profile banner artwork: pick from storage or restore the default
        View backgroundChoose = view.findViewById(R.id.vprof_editor_bg_choose);
        if (backgroundChoose != null) {
            backgroundChoose.setOnClickListener(v -> CropperUtils.startCropper(mBackgroundCropperLauncher));
        }
        View backgroundReset = view.findViewById(R.id.vprof_editor_bg_reset);
        if (backgroundReset != null) {
            backgroundReset.setOnClickListener(v -> resetBackground());
        }
        View backgroundPreview = view.findViewById(R.id.vprof_editor_bg_preview);
        if (backgroundPreview instanceof ImageView) {
            mBackgroundPreview = (ImageView) backgroundPreview;
        }
        View cancelButton = view.findViewById(R.id.vprof_editor_cancel_button);
        CSAnimationEngine.attachPressFeedback(backgroundChoose);
        CSAnimationEngine.attachPressFeedback(backgroundReset);
        CSAnimationEngine.attachPressFeedback(cancelButton);

        mSharedDataCheckbox.setOnCheckedChangeListener((v, checked) -> {
            mInstance.sharedData = checked;
            int text = R.string.instance_shared_data_off;
            if (checked) text = R.string.instance_shared_data_on;
            mSharedDataCheckbox.setText(text);
        });

        Instance selectedInstance = Instances.loadSelectedInstance();
        Context context = view.getContext();
        if (selectedInstance == null) {
            Toast.makeText(context, R.string.no_instance, Toast.LENGTH_LONG).show();
            getParentFragmentManager().popBackStack();
        } else {
            loadValues(selectedInstance, context);
        }
    }

    private View.OnClickListener getControlSelectListener() {
        return v -> {
            Bundle bundle = new Bundle(3);
            bundle.putBoolean(FileSelectorFragment.BUNDLE_SELECT_FOLDER, false);
            bundle.putString(FileSelectorFragment.BUNDLE_ROOT_PATH, Tools.CTRLMAP_PATH);

            Tools.swapFragment(requireActivity(),
                    FileSelectorFragment.class, FileSelectorFragment.TAG, bundle);
        };
    }

    private View.OnClickListener getVersionSelectListener() {
        return v -> VersionSelectorDialog.open(v.getContext(), false, (id, snapshot) -> mDefaultVersion.setText(id));
    }

    private static String nullToEmpty(String in) {
        if (in == null) return "";
        return in;
    }

    private void loadValues(@NonNull Instance instance, @NonNull Context context) {
        mInstance = instance;
        // Reset every pending edit for this editing session
        mPendingIcon = null;
        mPendingStaticIcon = null;
        mPendingBackground = null;
        mClearBackground = false;
        mBackgroundLoadId++;

        mInstanceIcon.setImageDrawable(
                InstanceIconProvider.fetchIcon(getResources(), instance)
        );
        updateStaticIconSelection(instance.hasCustomIconFile() ? null : instance.icon);
        refreshBackgroundPreview();

        // Runtime spinner
        List<Runtime> runtimes = MultiRTUtils.getRuntimes();
        int jvmIndex = -1;
        if (instance.selectedRuntime != null) {
            jvmIndex = runtimes.indexOf(new Runtime(instance.selectedRuntime));
        }
        mDefaultRuntime.setAdapter(new RTSpinnerAdapter(context, runtimes));
        if (jvmIndex == -1) jvmIndex = runtimes.size() - 1;
        mDefaultRuntime.setSelection(jvmIndex);

        // Renderer spinner
        int rendererIndex = mRenderNames.indexOf(instance.getLaunchRenderer());
        if (rendererIndex == -1) {
            rendererIndex = mDefaultRenderer.getAdapter().getCount() - 1;
        }
        mDefaultRenderer.setSelection(rendererIndex);

        mDefaultVersion.setText(instance.versionId);
        mDefaultJvmArgument.setText(nullToEmpty(instance.jvmArgs));
        mDefaultName.setText(nullToEmpty(instance.name));
        mDefaultControl.setText(mSelectedControlLayout == null ? nullToEmpty(instance.controlLayout) : mSelectedControlLayout);
        mSharedDataCheckbox.setChecked(instance.sharedData);
    }

    private void bindViews(@NonNull View view) {
        mDefaultControl = view.findViewById(R.id.vprof_editor_ctrl_spinner);
        mDefaultRuntime = view.findViewById(R.id.vprof_editor_spinner_runtime);
        mDefaultRenderer = view.findViewById(R.id.vprof_editor_instance_renderer);
        mDefaultVersion = view.findViewById(R.id.vprof_editor_version_spinner);

        mDefaultName = view.findViewById(R.id.vprof_editor_instance_name);
        mDefaultJvmArgument = view.findViewById(R.id.vprof_editor_jre_args);

        mSaveButton = view.findViewById(R.id.vprof_editor_save_button);
        mDeleteButton = view.findViewById(R.id.vprof_editor_delete_button);
        mCancelButton = view.findViewById(R.id.vprof_editor_cancel_button);
        mControlSelectButton = view.findViewById(R.id.vprof_editor_ctrl_button);
        mVersionSelectButton = view.findViewById(R.id.vprof_editor_version_button);
        mInstanceIcon = view.findViewById(R.id.vprof_editor_instance_icon);
        mSharedDataCheckbox = view.findViewById(R.id.vprof_editor_data_checkbox_container);
    }

    // ─── Built-in icon strip ────────────────────────────────────────────────

    /** Wires the launcher-icon chips (tags carry the InstanceIconProvider icon names) */
    private void bindIconChoices(@NonNull View view) {
        View choices = view.findViewById(R.id.vprof_editor_icon_choices);
        if (!(choices instanceof ViewGroup)) return;
        mIconChoices = (ViewGroup) choices;
        for (int i = 0; i < mIconChoices.getChildCount(); i++) {
            View chip = mIconChoices.getChildAt(i);
            if (!(chip.getTag() instanceof String)) continue;
            chip.setOnClickListener(v -> selectStaticIcon(String.valueOf(v.getTag())));
            CSAnimationEngine.attachPressFeedback(chip);
        }
    }

    private void selectStaticIcon(@Nullable String iconName) {
        if (iconName == null || mInstance == null || !InstanceIconProvider.hasStaticIcon(iconName)) return;
        mPendingStaticIcon = iconName;
        mPendingIcon = null;
        mInstanceIcon.setImageDrawable(InstanceIconProvider.fetchStaticIconByName(getResources(), iconName));
        updateStaticIconSelection(iconName);
        CSAnimationEngine.animateImageSwap(mInstanceIcon);
    }

    private void updateStaticIconSelection(@Nullable String selectedTag) {
        if (mIconChoices == null) return;
        for (int i = 0; i < mIconChoices.getChildCount(); i++) {
            View chip = mIconChoices.getChildAt(i);
            chip.setSelected(selectedTag != null && selectedTag.equals(String.valueOf(chip.getTag())));
        }
    }

    // ─── Profile banner artwork ─────────────────────────────────────────────

    /** Shows the stored artwork, the pending crop, or the default banner in the preview */
    private void refreshBackgroundPreview() {
        if (mBackgroundPreview == null) return;
        if (mPendingBackground != null) {
            showBackgroundPreview(mPendingBackground);
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
                CSAnimationEngine.animateImageSwap(mBackgroundPreview);
            });
        });
    }

    private void showBackgroundPreview(@Nullable Bitmap bitmap) {
        if (mBackgroundPreview == null || bitmap == null) return;
        mBackgroundPreview.setImageBitmap(bitmap);
        CSAnimationEngine.animateImageSwap(mBackgroundPreview);
    }

    private void resetBackground() {
        mClearBackground = true;
        mPendingBackground = null;
        if (mBackgroundPreview != null) {
            mBackgroundPreview.setImageResource(R.drawable.banner_home);
            CSAnimationEngine.animateImageSwap(mBackgroundPreview);
        }
    }

    // ─── Save / validation ──────────────────────────────────────────────────

    /**
     * Validates the inputs and persists every section of the editor in one write.
     * @return true when the profile was saved; false when validation or IO failed
     *         (the editor stays open and shows the reason)
     */
    private boolean save() {
        if (mInstance == null) return false;

        String newName = mDefaultName.getText().toString().trim();
        if (newName.isEmpty()) {
            mDefaultName.setError(getString(R.string.profile_name_empty_error));
            mDefaultName.requestFocus();
            return false;
        }
        if (newName.length() > MAX_NAME_LENGTH) {
            mDefaultName.setError(getString(R.string.profile_name_too_long));
            mDefaultName.requestFocus();
            return false;
        }

        // Existing profile configuration fields, unchanged save semantics
        mInstance.name = newName;
        mInstance.versionId = mDefaultVersion.getText().toString();
        mInstance.controlLayout = mDefaultControl.getText().toString();
        mInstance.jvmArgs = mDefaultJvmArgument.getText().toString();
        if (mInstance.controlLayout.isEmpty()) mInstance.controlLayout = null;
        if (mInstance.jvmArgs.isEmpty()) mInstance.jvmArgs = null;

        Runtime selectedRuntime = (Runtime) mDefaultRuntime.getSelectedItem();
        mInstance.selectedRuntime = (selectedRuntime.name.equals("<Default>") || selectedRuntime.versionString == null)
                ? null : selectedRuntime.name;

        if (mDefaultRenderer.getSelectedItemPosition() == mRenderNames.size()) mInstance.renderer = null;
        else mInstance.renderer = mRenderNames.get(mDefaultRenderer.getSelectedItemPosition());

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

            if (mPendingBackground != null) {
                mInstance.encodeNewBackground(mPendingBackground);
            } else if (mClearBackground) {
                mInstance.clearCustomBackground();
            }

            // One write for the whole profile; the directory (identity + game dir) is untouched,
            // so renaming can neither duplicate this profile nor overwrite another one.
            mInstance.write();

            if (iconChanged) InstanceIconProvider.dropIcon(mInstance);
            return true;
        } catch (Exception e) {
            Tools.showErrorRemote(e);
            return false;
        }
    }

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
        mInstanceIcon.setImageBitmap(contentBitmap);
        CSAnimationEngine.animateImageSwap(mInstanceIcon);
    }

    @Override
    public void onFailed(Exception exception) {
        Tools.showErrorRemote(exception);
    }
}
