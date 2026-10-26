package com.craftstudio.cslauncher.fragments;

import com.craftstudio.cslauncher.utils.TopNavDock;
import com.craftstudio.cslauncher.utils.AnimationManager;
import static com.craftstudio.cslauncher.Tools.openPath;
import static com.craftstudio.cslauncher.Tools.shareLog;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.constraintlayout.widget.Guideline;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.craftstudio.cslauncher.R;

import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.contracts.OpenDocumentWithExtension;
import com.craftstudio.cslauncher.extra.ExtraConstants;
import com.craftstudio.cslauncher.extra.ExtraCore;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.utils.FileUtils;

import java.io.File;

/**
 * The main menu: left navigation sidebar + right content pane.
 * The home content of the pane (banner, version, PLAY, cards, quick settings) lives in
 * {@link RightPaneHomeFragment}; every screen opened from the sidebar shows up in the right pane,
 * which has its own back stack.
 */
public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    /* The two-pane views, null in portrait */
    private FrameLayout mRightPane;
    /* The pane that hosts the content picker, null in portrait */
    private FrameLayout mLeftPaneContainer;
    private Guideline mPaneGuideline;
    /* Intercepts back when the right pane shows something above the home screen */
    private OnBackPressedCallback mRightPaneBackCallback;


    private final ActivityResultLauncher<Object> mModInstallerLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("jar"), (data)->{
                if(data != null) Tools.launchModInstaller(requireContext(), data);
            });

    private final FragmentManager.OnBackStackChangedListener mBackStackListener = () -> {
        mRightPaneBackCallback.setEnabled(isRightPaneActive());
        updateLeftPaneVisibility();
        syncActiveNav();
    };

    public MainMenuFragment(){
        super(R.layout.fragment_launcher);
    }

    // ─── Two-pane helpers ────────────────────────────────────────────────────

    /** @return whether the two-pane landscape layout is active */
    public boolean isTwoPane() {
        return mRightPane != null;
    }

    /** @return whether the right pane shows a screen above its home screen */
    public boolean isRightPaneActive() {
        return isTwoPane() && getChildFragmentManager().getBackStackEntryCount() > 0;
    }

    /**
     * Opens a screen in the right pane. Does nothing in portrait.
     * @return whether the screen was opened, false means the caller has to show it another way
     */
    public boolean openInPane(@NonNull Class<? extends Fragment> fragmentClass,
                              @Nullable String tag, @Nullable Bundle args) {
        if (!isTwoPane()) return false;
        String entryName = tag != null ? tag : fragmentClass.getName();
        FragmentManager manager = getChildFragmentManager();
        int count = manager.getBackStackEntryCount();
        // Already showing it, ignore the double tap
        if (count > 0 && entryName.equals(manager.getBackStackEntryAt(count - 1).getName())) return true;
        manager.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.right_pane_container, fragmentClass, args, tag)
                .addToBackStack(entryName)
                .commit();
        return true;
    }

    /** Opens a screen in the right pane, or full screen in portrait */
    public void openPane(@NonNull Class<? extends Fragment> fragmentClass, @NonNull String tag,
                         @Nullable Bundle args) {
        if (!openInPane(fragmentClass, tag, args)) {
            Tools.swapFragment(requireActivity(), fragmentClass, tag, args);
        }
    }

    /** Pops one screen off the right pane */
    public void popRightPane() {
        if (isRightPaneActive()) getChildFragmentManager().popBackStack();
    }

    /** Pops every screen off the right pane, so its home screen shows again */
    public void clearRightPane() {
        FragmentManager manager = getChildFragmentManager();
        if (manager.getBackStackEntryCount() == 0) return;
        manager.popBackStack(manager.getBackStackEntryAt(0).getId(), FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    // ─── Browse / Manage Content ─────────────────────────────────────────────

    /**
     * Opens the Browse Content / Manage Content picker: mods, resource packs or shader packs.
     * The manage variant also exposes the per-instance version/loader filter.
     * The picker docks in the left pane and its default section loads in the right pane.
     */
    public void showContentPicker(boolean manage) {
        if (!manage) {
            // Browsing has its own Mods / Resource Packs / Shaders tabs in the library header
            openLibrary(ContentType.MOD);
            return;
        }
        showManageChooser();
    }

    /**
     * Manager button: a popup asks which content to manage (mods, resource packs or shader
     * packs), then the full screen manager opens on that type.
     */
    private void showManageChooser() {
        if (getContext() == null) return;
        com.kdt.mcgui.ManageChooserDialog dialog = new com.kdt.mcgui.ManageChooserDialog(requireContext(), new com.kdt.mcgui.ManageChooserDialog.Callback() {
            @Override
            public void onTypeChosen(@NonNull ContentType type) {
                if (getView() != null) onContentTypeChosen(true, type);
            }

            @Override
            public void onWorldsChosen() {
                if (getView() != null) openPane(ManageWorldsFragment.class, ManageWorldsFragment.TAG, null);
            }

            @Override
            public void onFilterRequested() {
                if (getContext() == null) return;
                ContentFilterDialog.show(requireContext(), Instances.getSelectedInstanceKey(),
                        (version, loader) -> {
                            if (getContext() == null) return;
                            Toast.makeText(requireContext(),
                                    getString(R.string.manage_mods_filter_active, version, loader),
                                    Toast.LENGTH_SHORT).show();
                        });
            }
        });
        dialog.setOnDismissListener(d -> com.craftstudio.cslauncher.tutorial.TutorialEvents.emit(com.craftstudio.cslauncher.tutorial.TutorialEvents.MANAGE_CHOOSER_CLOSED));
        dialog.show();
        com.craftstudio.cslauncher.tutorial.TutorialEvents.emit(com.craftstudio.cslauncher.tutorial.TutorialEvents.MANAGE_CHOOSER_OPENED);
    }

    /**
     * Opens the content library. If it is already somewhere in the right pane (e.g. under an
     * install page), it is brought back instead of stacking a second copy.
     */
    public void openLibrary(@NonNull ContentType contentType) {
        if (isTwoPane()) {
            FragmentManager manager = getChildFragmentManager();
            for (int i = manager.getBackStackEntryCount() - 1; i >= 0; i--) {
                if (ModsSearchFragment.TAG.equals(manager.getBackStackEntryAt(i).getName())) {
                    if (i != manager.getBackStackEntryCount() - 1) {
                        manager.popBackStack(ModsSearchFragment.TAG, 0);
                    }
                    return;
                }
            }
        }
        Bundle args = new Bundle();
        populateModStoreArgs(args, contentType);
        openPane(ModsSearchFragment.class, ModsSearchFragment.TAG, args);
    }

    /**
     * Called by {@link ContentPickerFragment} when one of its mods / resource packs / shader packs
     * buttons is tapped. The picker itself stays in the left pane.
     */
    public void selectContentType(boolean manage, ContentType contentType) {
        onContentTypeChosen(manage, contentType);
    }

    /**
     * The left column only exists while the Manage Content picker is docked there; otherwise
     * the right pane takes the full width (navigation is in the top bar dock).
     */
    private void updateLeftPaneVisibility() {
        if (!isTwoPane() || mLeftPaneContainer == null) return;
        Fragment leftFragment = getChildFragmentManager().findFragmentById(R.id.left_pane_container);
        boolean pickerActive = leftFragment instanceof ContentPickerFragment && !leftFragment.isRemoving()
                && isTagInBackStack(ContentPickerFragment.TAG);
        mLeftPaneContainer.setVisibility(pickerActive ? View.VISIBLE : View.GONE);
        if (mPaneGuideline != null) {
            // Set the params directly: setGuidelineBegin() alone keeps an earlier percent and
            // the percent wins, which left an empty black column after closing the Manager
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams params =
                    (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) mPaneGuideline.getLayoutParams();
            if (pickerActive) {
                params.guidePercent = 0.18f;
                params.guideBegin = -1;
            } else {
                params.guidePercent = -1f;
                params.guideBegin = 0;
            }
            params.guideEnd = -1;
            mPaneGuideline.setLayoutParams(params);
        }
    }

    private boolean isTagInBackStack(@NonNull String tag) {
        FragmentManager manager = getChildFragmentManager();
        for (int i = manager.getBackStackEntryCount() - 1; i >= 0; i--) {
            if (tag.equals(manager.getBackStackEntryAt(i).getName())) return true;
        }
        return false;
    }

    private void onContentTypeChosen(boolean manage, ContentType contentType) {
        if (manage) {
            Bundle args = new Bundle();
            args.putString(ManageModsFragment.ARG_CONTENT_TYPE, contentType.name());
            openPane(ManageModsFragment.class, ManageModsFragment.TAG + ":" + contentType.name(), args);
        } else {
            openLibrary(contentType);
        }
    }

    /**
     * Builds the arguments for ModsSearchFragment with the saved per-instance filter pre-seeded.
     * The loader filter is only passed for MOD, resource packs and shader packs aren't loader specific.
     */
    private void populateModStoreArgs(Bundle args, ContentType contentType) {
        String instanceKey = Instances.getSelectedInstanceKey();
        SharedPreferences prefs = requireContext()
                .getSharedPreferences("mod_filters", Context.MODE_PRIVATE);

        String version = prefs.getString("mc_version_" + instanceKey, "");
        String loader  = prefs.getString("loader_" + instanceKey, "");

        // Nothing saved for this instance yet: default to the version/loader it runs, so the
        // results are already relevant without a filter-then-apply step
        if (version.isEmpty() && loader.isEmpty()) {
            InstanceVersionResolver.Info info = InstanceVersionResolver.resolve(instanceKey);
            if (info.mcVersion != null) version = info.mcVersion;
            loader = info.loader;
        }

        args.putString(ModsSearchFragment.ARG_CONTENT_TYPE, contentType.name());
        if (!version.isEmpty()) args.putString(ModsSearchFragment.ARG_PRESET_MC_VERSION, version);
        if (contentType == ContentType.MOD && !loader.isEmpty()) {
            args.putString(ModsSearchFragment.ARG_PRESET_LOADER, loader);
        }
    }

    /** @return whether the tag is the top entry of the right pane back stack, ie already showing */
    private boolean isTagAlreadyOnTop(String tag) {
        if (!isTwoPane()) return false;
        FragmentManager manager = getChildFragmentManager();
        int count = manager.getBackStackEntryCount();
        if (count == 0) return false;
        return tag.equals(manager.getBackStackEntryAt(count - 1).getName());
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The owner is this fragment, so the callback is removed with it
        mRightPaneBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (isRightPaneActive()) getChildFragmentManager().popBackStackImmediate();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, mRightPaneBackCallback);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mRightPane = view.findViewById(R.id.right_pane_container);
        mLeftPaneContainer = view.findViewById(R.id.left_pane_container);
        mPaneGuideline = view.findViewById(R.id.pane_guideline);
        getChildFragmentManager().addOnBackStackChangedListener(mBackStackListener);

        FragmentManager childManager = getChildFragmentManager();
        if (isTwoPane()) {
            // Checked by presence and not by savedInstanceState, so a rotation keeps working
            if (childManager.findFragmentById(R.id.right_pane_container) == null) {
                childManager.beginTransaction()
                        .setReorderingAllowed(true)
                        // Not on the back stack, the home screen is the base and not a destination
                        .replace(R.id.right_pane_container, RightPaneHomeFragment.class, null,
                                RightPaneHomeFragment.TAG)
                        .commit();
            }
            mBackStackListener.onBackStackChanged();
        } else if (childManager.getBackStackEntryCount() > 0) {
            // The pane the screens were in is gone after rotating to portrait
            childManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        // Navigation lives in the top bar dock (TopNavDock, bound by LauncherActivity) and
        // calls navigate(); there is no left sidebar any more.
        // Reflect the real destination (restored stack after rotation, or plain home)
        syncActiveNav();
    }

    /**
     * Opens the destination of a top bar dock button.
     * @return whether the id belongs to the main menu navigation
     */
    public boolean navigate(int navId) {
        if (getView() == null) return false;
        if (navId == R.id.nav_home) {
            clearRightPane();
        } else if (navId == R.id.mod_store_button) {
            showContentPicker(false);
        } else if (navId == R.id.open_files_button) {
            // Only a popup for now: the dock is marked once the manager really opens
            showContentPicker(true);
            return true;
        } else if (navId == R.id.nav_controls) {
            // Touch controls customization: its own activity, the dock keeps the current tab
            startActivity(new android.content.Intent(requireContext(), com.craftstudio.cslauncher.CustomControlsActivity.class));
            return true;
        } else if (navId == R.id.nav_multiplayer) {
            openPane(com.craftstudio.cslauncher.multiplayer.ui.VirtualLanFragment.class,
                    com.craftstudio.cslauncher.multiplayer.ui.VirtualLanFragment.TAG, null);
        } else if (navId == R.id.nav_logs) {
            openPane(LogsFragment.class, LogsFragment.TAG, null);
        } else if (navId == R.id.nav_playtime) {
            openPane(PlayTimeFragment.class, PlayTimeFragment.TAG, null);
        } else if (navId == R.id.nav_skins) {
            // Skin manager: full screen editor of the current account, the dock keeps its tab
            openSkinManager();
            return true;
        } else {
            return false;
        }
        setActiveNav(navId);
        return true;
    }

    /** Skins + capes of the selected account (local or Microsoft) */
    private void openSkinManager() {
        if (getActivity() == null) return;
        com.craftstudio.cslauncher.authenticator.accounts.Account account =
                com.craftstudio.cslauncher.authenticator.accounts.Accounts.getCurrent();
        if (account == null || account.mSaveLocation == null) {
            android.widget.Toast.makeText(requireContext(), R.string.pt_no_account, android.widget.Toast.LENGTH_SHORT).show();
            com.craftstudio.cslauncher.extra.ExtraCore.setValue(com.craftstudio.cslauncher.extra.ExtraConstants.SELECT_AUTH_METHOD, true);
            return;
        }
        if (com.craftstudio.cslauncher.progresskeeper.ProgressKeeper.hasProgressKey(com.kdt.mcgui.ProgressLayout.AUTHENTICATE)) {
            android.widget.Toast.makeText(requireContext(), R.string.tasks_ongoing, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (getParentFragmentManager().isStateSaved()) return;
        Bundle args = new Bundle();
        args.putString(com.craftstudio.cslauncher.skins.SkinEditorFragment.ARG_ACCOUNT_FILE, account.mSaveLocation.getName());
        Tools.swapFragmentFullScreen(requireActivity(), com.craftstudio.cslauncher.skins.SkinEditorFragment.class,
                com.craftstudio.cslauncher.skins.SkinEditorFragment.TAG, args);
    }

    /** Opens the game directory of the selected instance (overflow menu) */
    public void openGameDirectory() {
        if (getContext() != null) openGameDirectory(requireContext());
    }

    /** Marks one dock button as active; {@link View#NO_ID} clears the selection */
    private void setActiveNav(int navId) {
        if (getView() == null || getActivity() == null) return;
        TopNavDock.setActive(getActivity(), navId);
    }

    /** Follows the right-pane back stack: home when empty, the opened screen otherwise */
    private void syncActiveNav() {
        FragmentManager manager = getChildFragmentManager();
        int count = manager.getBackStackEntryCount();
        if (count == 0) {
            setActiveNav(R.id.nav_home);
            return;
        }
        String top = manager.getBackStackEntryAt(count - 1).getName();
        if (LogsFragment.TAG.equals(top)) setActiveNav(R.id.nav_logs);
        else if (PlayTimeFragment.TAG.equals(top)) setActiveNav(R.id.nav_playtime);
        else if (com.craftstudio.cslauncher.multiplayer.ui.VirtualLanFragment.TAG.equals(top)) setActiveNav(R.id.nav_multiplayer);
        else if (top != null && (top.startsWith(ModsSearchFragment.TAG) || ModInstallFragment.TAG.equals(top)))
            setActiveNav(R.id.mod_store_button);
        else if (top != null && top.startsWith(ManageModsFragment.TAG))
            setActiveNav(R.id.open_files_button);
        else setActiveNav(android.view.View.NO_ID);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        getChildFragmentManager().removeOnBackStackChangedListener(mBackStackListener);
        mRightPane = null;
        mLeftPaneContainer = null;
        mPaneGuideline = null;
    }

    private void openGameDirectory(Context context) {
        Instance instance = Instances.loadSelectedInstance();
        if(instance == null) {
            Toast.makeText(context, R.string.no_instance, Toast.LENGTH_LONG).show();
            return;
        }
        File gameDirectory = instance.getGameDirectory();
        if(FileUtils.ensureDirectorySilently(gameDirectory)) {
            openPath(context, gameDirectory, false);
        }else {
            Toast.makeText(context, R.string.gamedir_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** Shared log file for the whole launcher, used by the overflow menu */
    public void shareLogs() {
        shareLog(requireContext());
    }

    /** Starts the "install a jar into the selected instance" flow, guarded against running tasks */
    public void openJarInstaller() {
        if (ProgressKeeper.getTaskCount() == 0) {
            mModInstallerLauncher.launch(null);
        } else Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onResume() {
        super.onResume();
        ExtraCore.setValue(ExtraConstants.REFRESH_ACCOUNT_SPINNER, true);
    }
}
