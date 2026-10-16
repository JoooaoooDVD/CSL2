package com.kdt.mcgui;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListPopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.Architecture;
import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.JVersionList;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.extra.ExtraConstants;
import com.craftstudio.cslauncher.extra.ExtraCore;
import com.craftstudio.cslauncher.game.renderer.RendererCache;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.InstanceInstaller;
import com.craftstudio.cslauncher.instances.InstanceSetter;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.modding.bta.BTADownloadTask;
import com.craftstudio.cslauncher.modding.bta.BTAUtils;
import com.craftstudio.cslauncher.modding.fabric.FabricVersion;
import com.craftstudio.cslauncher.modding.fabric.FabriclikeUtils;
import com.craftstudio.cslauncher.modding.forge.ForgelikeUtils;
import com.craftstudio.cslauncher.modding.optifine.OptiFineDownloadTask;
import com.craftstudio.cslauncher.modding.optifine.OptiFineUtils;
import com.craftstudio.cslauncher.multirt.MultiRTUtils;
import com.craftstudio.cslauncher.multirt.Runtime;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.prefs.RamInputDialog;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Normal" create instance popup: a two column landscape form.
 * <ul>
 *   <li>left: name, loader (Vanilla / Fabric / Forge / NeoForge / Quilt / OptiFine /
 *       Legacy Fabric / BTA), Minecraft version, loader version ("Latest" by default);</li>
 *   <li>right: RAM (own value or launcher default), Java runtime, renderer, shared folder.</li>
 * </ul>
 * Create runs the install in the background (busy overlay inside the card), selects the new
 * instance and closes. Forge-like installers keep running through the usual install progress.
 */
public class CreateInstanceDialog extends CSPopupDialog {

    private static final int L_VANILLA = 0, L_FABRIC = 1, L_FORGE = 2, L_QUILT = 3, L_NEOFORGE = 4,
            L_OPTIFINE = 5, L_LEGACY_FABRIC = 6, L_BTA = 7;
    private static final String[] LOADER_NAMES = {"Vanilla", "Fabric", "Forge", "Quilt", "NeoForge",
            "OptiFine", "Legacy Fabric", "BTA"};
    private static final int[] LOADER_ICONS = {R.drawable.ic_pe_px_grass, R.drawable.ic_fabric,
            R.drawable.ic_forge, R.drawable.ic_quilt, R.drawable.ic_neoforge,
            R.drawable.ic_loader_optifine, R.drawable.ic_fabric, R.drawable.ic_pe_px_sword};
    /** Better than Adventure! is always built on Beta 1.7.3 */
    private static final String BTA_GAME_VERSION = "b1.7.3";
    private static final String DEFAULT_RUNTIME = "<Default>";

    @Nullable private final Runnable mOnBack;

    // Views
    private EditText mName;
    private final View[] mChips = new View[8];
    private View mMcField, mLoaderField, mLoaderBlock, mMcProgress, mLoaderProgress, mBusy, mCreate;
    private TextView mMcValue, mLoaderValue, mJavaValue, mRendererValue, mRamValue, mSummary, mSubtitle;
    private ImageView mHeaderIcon;
    private SeekBar mRamSlider;
    private MaterialSwitch mRamGlobalSwitch, mSharedSwitch;

    // Loader / versions
    private int mLoader = L_FABRIC;
    private int mGameToken, mLoaderToken;
    private boolean mGameLoading, mLoaderLoading, mGameFailed, mLoaderFailed;
    private final List<String> mGameVersions = new ArrayList<>();
    private int mGameIndex = -1;
    @Nullable private String mPreferredGame;
    /** Values passed to the installer, index 0 = latest */
    private final List<String> mLoaderValues = new ArrayList<>();
    private final List<String> mLoaderDisplay = new ArrayList<>();
    private int mLoaderIndex = 0;
    private Map<String, List<String>> mForgeVersions;
    /** OptiFine: game version -> builds (newest first); builds of the selected game, aligned with mLoaderValues */
    private Map<String, List<OptiFineUtils.OptiFineVersion>> mOptiFineVersions;
    private final List<OptiFineUtils.OptiFineVersion> mOptiFineBuilds = new ArrayList<>();
    /** BTA: every version (tested, untested, nightly), aligned with mLoaderValues */
    private final List<BTAUtils.BTAVersion> mBtaBuilds = new ArrayList<>();
    private final List<String> mBtaLabels = new ArrayList<>();

    // Run settings
    private int mRamMin, mRamMax, mRamStep, mRam;
    private boolean mUseGlobalRam = true;
    private final List<Runtime> mRuntimes = new ArrayList<>();
    private int mRuntimeIndex;
    private List<String> mRendererIds = new ArrayList<>();
    private final List<String> mRendererDisplay = new ArrayList<>();
    private int mRendererIndex;
    private boolean mShared, mSharedTouched;

    private boolean mBusyRunning;
    @Nullable private ListPopupWindow mDropdown;

    public CreateInstanceDialog(@NonNull Context context, @Nullable Runnable onBack) {
        super(context);
        mOnBack = onBack;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_create_instance);
        View card = findViewById(R.id.ci_card);
        setupPopup(findViewById(R.id.ci_scrim), card, true);
        Window window = getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        sizeCard(card);
        bindViews();
        initRunSettings();
        selectLoader(L_FABRIC, false);

        CreatePopupMotion.enter(mScrim, mCard);
        CreatePopupMotion.formContent(findViewById(R.id.ci_header), findViewById(R.id.ci_left),
                findViewById(R.id.ci_right), findViewById(R.id.ci_footer));
    }

    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = Math.min((int) (780 * d), metrics.widthPixels - (int) (40 * d));
        // Phones: full height minus margins (the body scrolls); tablets: a fixed comfortable height
        params.height = metrics.heightPixels > 560 * d ? (int) (500 * d) : ViewGroup.LayoutParams.MATCH_PARENT;
        card.setLayoutParams(params);
    }

    private void bindViews() {
        mName = findViewById(R.id.ci_name);
        mChips[L_VANILLA] = findViewById(R.id.ci_chip_vanilla);
        mChips[L_FABRIC] = findViewById(R.id.ci_chip_fabric);
        mChips[L_FORGE] = findViewById(R.id.ci_chip_forge);
        mChips[L_QUILT] = findViewById(R.id.ci_chip_quilt);
        mChips[L_NEOFORGE] = findViewById(R.id.ci_chip_neoforge);
        mChips[L_OPTIFINE] = findViewById(R.id.ci_chip_optifine);
        mChips[L_LEGACY_FABRIC] = findViewById(R.id.ci_chip_legacy_fabric);
        mChips[L_BTA] = findViewById(R.id.ci_chip_bta);
        mMcField = findViewById(R.id.ci_mc_field);
        mMcValue = findViewById(R.id.ci_mc_value);
        mMcProgress = findViewById(R.id.ci_mc_progress);
        mLoaderBlock = findViewById(R.id.ci_loader_block);
        mLoaderField = findViewById(R.id.ci_loader_field);
        mLoaderValue = findViewById(R.id.ci_loader_value);
        mLoaderProgress = findViewById(R.id.ci_loader_progress);
        mJavaValue = findViewById(R.id.ci_java_value);
        mRendererValue = findViewById(R.id.ci_renderer_value);
        mRamValue = findViewById(R.id.ci_ram_value);
        mRamSlider = findViewById(R.id.ci_ram_slider);
        mRamGlobalSwitch = findViewById(R.id.ci_ram_global_switch);
        mSharedSwitch = findViewById(R.id.ci_shared_switch);
        mSummary = findViewById(R.id.ci_summary);
        mSubtitle = findViewById(R.id.ci_subtitle);
        mHeaderIcon = findViewById(R.id.ci_header_icon);
        mBusy = findViewById(R.id.ci_busy);
        mCreate = findViewById(R.id.ci_create);
        mCreate.setClipToOutline(true);
        findViewById(R.id.ci_header_tile).setClipToOutline(true);
        mRamValue.setSelected(true);

        View back = findViewById(R.id.ci_back);
        View close = findViewById(R.id.ci_close);
        View cancel = findViewById(R.id.ci_cancel);
        back.setOnClickListener(v -> {
            if (mBusyRunning) return;
            hideKeyboard();
            close(mOnBack);
        });
        close.setOnClickListener(v -> onDismissRequested());
        cancel.setOnClickListener(v -> onDismissRequested());
        mCreate.setOnClickListener(v -> onCreateClicked());
        for (View view : new View[]{back, close, cancel, mCreate}) CSAnimationEngine.attachPressFeedback(view);

        for (int i = 0; i < mChips.length; i++) {
            final int loader = i;
            mChips[i].setOnClickListener(v -> selectLoader(loader, true));
        }

        mName.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard();
                mName.clearFocus();
                return true;
            }
            return false;
        });

        mMcField.setOnClickListener(v -> {
            if (mGameFailed) {
                loadGameVersions();
                return;
            }
            if (mGameVersions.isEmpty()) return;
            showDropdown(mMcField, mGameVersions, mGameIndex, index -> {
                mGameIndex = index;
                mPreferredGame = mGameVersions.get(index);
                Motion.swapText(mMcValue, mPreferredGame);
                onGameVersionChanged();
            });
        });
        mLoaderField.setOnClickListener(v -> {
            if (mLoaderFailed) {
                loadLoaderVersions();
                return;
            }
            if (mLoaderDisplay.isEmpty()) return;
            showDropdown(mLoaderField, mLoaderDisplay, mLoaderIndex, index -> {
                mLoaderIndex = index;
                Motion.swapText(mLoaderValue, mLoaderDisplay.get(index));
                updateSummary();
            });
        });
    }

    // ─── Run settings (right column) ─────────────────────────────────────────

    private void initRunSettings() {
        Context context = getContext();
        // RAM: same bounds as the launcher's Java settings
        mRamMin = context.getResources().getInteger(R.integer.memory_seekbar_min);
        // 64 MB steps keep the slider values round (256, 320 ... 4096)
        mRamStep = Math.max(64, context.getResources().getInteger(R.integer.memory_seekbar_increment));
        int deviceRam = Tools.getTotalDeviceMemory(context);
        if (Architecture.is32BitsDevice() || deviceRam < 2048) mRamMax = Math.min(1024, deviceRam);
        else mRamMax = deviceRam - (deviceRam < 3064 ? 800 : 1024);
        if (mRamMax < mRamMin) mRamMax = mRamMin;
        mRam = clampRam(LauncherPreferences.PREF_RAM_ALLOCATION);
        mRamSlider.setMax((mRamMax - mRamMin) / mRamStep);
        mRamSlider.setProgress((mRam - mRamMin) / mRamStep);
        mRamSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                mRam = clampRam(mRamMin + progress * mRamStep);
                updateRam(false);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        mRamValue.setOnClickListener(v -> RamInputDialog.show(getContext(), mRam, mRamMin, mRamMax, megabytes -> {
            mRam = clampRam(megabytes);
            mUseGlobalRam = false;
            mRamSlider.setProgress((mRam - mRamMin) / mRamStep);
            updateRam(true);
        }));
        ((TextView) findViewById(R.id.ci_ram_global_title)).setText(String.format(Locale.getDefault(),
                context.getString(R.string.ci_memory_global), LauncherPreferences.PREF_RAM_ALLOCATION));
        findViewById(R.id.ci_ram_global_desc).setVisibility(View.GONE);
        findViewById(R.id.ci_ram_global_row).setOnClickListener(v -> {
            mUseGlobalRam = !mUseGlobalRam;
            updateRam(true);
        });
        updateRam(false);

        // Java runtimes (same semantics as the profile editor)
        try {
            mRuntimes.addAll(MultiRTUtils.getRuntimes());
        } catch (Exception ignored) {
            // No runtime folder yet: only the automatic entry is offered
        }
        mRuntimes.add(new Runtime(DEFAULT_RUNTIME));
        mRuntimeIndex = mRuntimes.size() - 1;
        mJavaValue.setText(runtimeLabel(mRuntimes.get(mRuntimeIndex)));
        findViewById(R.id.ci_java_field).setOnClickListener(v -> {
            List<String> labels = new ArrayList<>();
            for (Runtime runtime : mRuntimes) labels.add(runtimeLabel(runtime));
            showDropdown(findViewById(R.id.ci_java_field), labels, mRuntimeIndex, index -> {
                mRuntimeIndex = index;
                Motion.swapText(mJavaValue, labels.get(index));
            });
        });

        // Renderers: compatible ones + "launcher default" (null)
        try {
            RendererCache renderers = RendererCache.getCompatibleRenderers(context);
            mRendererIds = new ArrayList<>(renderers.rendererIds);
            mRendererDisplay.addAll(Arrays.asList(renderers.rendererDisplayNames));
        } catch (Exception ignored) {
            mRendererIds = new ArrayList<>();
        }
        while (mRendererDisplay.size() > mRendererIds.size()) mRendererDisplay.remove(mRendererDisplay.size() - 1);
        mRendererDisplay.add(context.getString(R.string.ci_renderer_default));
        mRendererIndex = mRendererDisplay.size() - 1;
        mRendererValue.setText(mRendererDisplay.get(mRendererIndex));
        findViewById(R.id.ci_renderer_field).setOnClickListener(v -> {
            List<String> names = new ArrayList<>(mRendererDisplay);
            String defaultLabel = names.remove(names.size() - 1);
            new RendererPickerDialog(context, mRendererIds, names, mRendererIndex, defaultLabel, index -> {
                mRendererIndex = index;
                Motion.swapText(mRendererValue, mRendererDisplay.get(index));
            }).show();
        });

        // Shared game folder
        ((TextView) findViewById(R.id.ci_shared_desc)).setText(R.string.ci_shared_desc);
        findViewById(R.id.ci_shared_row).setOnClickListener(v -> {
            mSharedTouched = true;
            mShared = !mShared;
            mSharedSwitch.setChecked(mShared);
        });
    }

    private int clampRam(int value) {
        return Math.max(mRamMin, Math.min(mRamMax, value));
    }

    private void updateRam(boolean animate) {
        mRamGlobalSwitch.setChecked(mUseGlobalRam);
        int shown = mUseGlobalRam ? LauncherPreferences.PREF_RAM_ALLOCATION : mRam;
        String text = String.format(Locale.getDefault(), getContext().getString(R.string.ci_memory_value), shown);
        if (animate) Motion.swapText(mRamValue, text);
        else mRamValue.setText(text);
        float alpha = mUseGlobalRam ? 0.45f : 1f;
        if (animate && Motion.content(mRamSlider)) {
            mRamSlider.animate().alpha(alpha).setStartDelay(0).setDuration(Motion.SHORT)
                    .setInterpolator(Motion.STANDARD).start();
        } else {
            mRamSlider.setAlpha(alpha);
        }
        updateSummary();
    }

    @NonNull
    private String runtimeLabel(@NonNull Runtime runtime) {
        if (DEFAULT_RUNTIME.equals(runtime.name)) return getContext().getString(R.string.ci_java_auto);
        String name = runtime.name.replace(".tar.xz", "");
        return runtime.versionString == null ? name : name + " - " + runtime.versionString;
    }

    // ─── Loader / versions (left column) ─────────────────────────────────────

    private void selectLoader(int loader, boolean animate) {
        if (mBusyRunning) return;
        boolean changed = loader != mLoader || !animate;
        mLoader = loader;
        for (int i = 0; i < mChips.length; i++) mChips[i].setSelected(i == loader);
        mHeaderIcon.setImageResource(LOADER_ICONS[loader]);
        mSubtitle.setText(getContext().getString(R.string.ci_subtitle) + " • " + LOADER_NAMES[loader]);
        if (!mSharedTouched) {
            // Vanilla / OptiFine share the common folder by default, modded profiles keep their own mods
            mShared = loader == L_VANILLA || loader == L_OPTIFINE;
            mSharedSwitch.setChecked(mShared);
        }
        if (animate) CreatePopupMotion.loaderChanged(mChips[loader], findViewById(R.id.ci_header_tile));
        if (changed) loadGameVersions();
    }

    private boolean isFabricLike() {
        return mLoader == L_FABRIC || mLoader == L_QUILT || mLoader == L_LEGACY_FABRIC;
    }

    private boolean isForgeLike() {
        return mLoader == L_FORGE || mLoader == L_NEOFORGE;
    }

    private FabriclikeUtils fabricUtils() {
        if (mLoader == L_QUILT) return FabriclikeUtils.QUILT_UTILS;
        if (mLoader == L_LEGACY_FABRIC) return FabriclikeUtils.LEGACY_FABRIC_UTILS;
        return FabriclikeUtils.FABRIC_UTILS;
    }

    private ForgelikeUtils forgeUtils() {
        return mLoader == L_NEOFORGE ? ForgelikeUtils.NEOFORGE_UTILS : ForgelikeUtils.FORGE_UTILS;
    }

    private void loadGameVersions() {
        final int token = ++mGameToken;
        mLoaderToken++;
        mGameVersions.clear();
        mGameIndex = -1;
        mLoaderValues.clear();
        mLoaderDisplay.clear();
        mGameFailed = false;
        setGameLoading(true);

        if (mLoader == L_VANILLA) {
            setLoaderBlockVisible(false);
            List<String> releases = new ArrayList<>();
            Object table = ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
            if (table instanceof JVersionList && ((JVersionList) table).versions != null) {
                for (JVersionList.Version version : ((JVersionList) table).versions) {
                    if (version != null && version.id != null && "release".equals(version.type)) releases.add(version.id);
                }
            }
            onGameVersions(token, releases.isEmpty() ? null : releases);
            return;
        }

        setLoaderBlockVisible(true);
        setLoaderLoading(true);
        final int loader = mLoader;
        final boolean fabricLike = isFabricLike();
        final FabriclikeUtils fabric = fabricUtils();
        final ForgelikeUtils forge = forgeUtils();
        CSLauncherApplication.sExecutorService.execute(() -> {
            List<String> result = null;
            Map<String, List<String>> forgeMap = null;
            Map<String, List<OptiFineUtils.OptiFineVersion>> optiFineMap = null;
            List<BTAUtils.BTAVersion> btaBuilds = null;
            List<String> btaLabels = null;
            try {
                if (loader == L_OPTIFINE) {
                    optiFineMap = groupOptiFineVersions(OptiFineUtils.downloadOptiFineVersions());
                    if (optiFineMap != null) result = new ArrayList<>(optiFineMap.keySet());
                } else if (loader == L_BTA) {
                    BTAUtils.BTAVersionList list = BTAUtils.downloadVersionList();
                    if (list != null) {
                        btaBuilds = new ArrayList<>();
                        btaLabels = new ArrayList<>();
                        addBtaVersions(list.testedVersions, null, btaBuilds, btaLabels);
                        addBtaVersions(list.untestedVersions, "untested", btaBuilds, btaLabels);
                        addBtaVersions(list.nightlyVersions, "nightly", btaBuilds, btaLabels);
                        result = new ArrayList<>();
                        result.add(BTA_GAME_VERSION);
                    }
                } else if (fabricLike) {
                    FabricVersion[] versions = fabric.downloadGameVersions();
                    if (versions != null) {
                        result = new ArrayList<>();
                        for (FabricVersion version : versions) {
                            if (version != null && version.stable && version.version != null) result.add(version.version);
                        }
                    }
                } else {
                    List<String> all = forge.downloadVersions();
                    if (all != null) {
                        forgeMap = groupForgeVersions(all, forge);
                        result = new ArrayList<>(forgeMap.keySet());
                    }
                }
            } catch (Exception e) {
                result = null;
            }
            final List<String> finalResult = result;
            final Map<String, List<String>> finalMap = forgeMap;
            final Map<String, List<OptiFineUtils.OptiFineVersion>> finalOptiFine = optiFineMap;
            final List<BTAUtils.BTAVersion> finalBta = btaBuilds;
            final List<String> finalBtaLabels = btaLabels;
            Tools.runOnUiThread(() -> {
                if (token != mGameToken) return;
                mForgeVersions = finalMap;
                mOptiFineVersions = finalOptiFine;
                mBtaBuilds.clear();
                mBtaLabels.clear();
                if (finalBta != null && finalBtaLabels != null) {
                    mBtaBuilds.addAll(finalBta);
                    mBtaLabels.addAll(finalBtaLabels);
                }
                onGameVersions(token, finalResult);
            });
        });
    }

    /** Same grouping as the Forge version list: game version -> loader builds, newest first */
    @NonNull
    private static Map<String, List<String>> groupForgeVersions(@NonNull List<String> all, @NonNull ForgelikeUtils utils) {
        LinkedHashMap<String, List<String>> map = new LinkedHashMap<>();
        for (String version : all) {
            if (version == null || utils.shouldSkipVersion(version)) continue;
            String game;
            try {
                game = utils.processVersionString(version);
            } catch (RuntimeException e) {
                continue;
            }
            List<String> list = map.get(game);
            if (list == null) {
                list = new ArrayList<>();
                map.put(game, list);
            }
            list.add(version);
        }
        if (!utils.isVersionOrderInversed()) return map;
        List<String> keys = new ArrayList<>(map.keySet());
        Collections.reverse(keys);
        LinkedHashMap<String, List<String>> reversed = new LinkedHashMap<>();
        for (String key : keys) {
            List<String> list = map.get(key);
            if (list == null) continue;
            Collections.reverse(list);
            reversed.put(key, list);
        }
        return reversed;
    }

    /** OptiFine page order (newest first); "Minecraft 1.20.1" headers become "1.20.1" */
    @Nullable
    private static Map<String, List<OptiFineUtils.OptiFineVersion>> groupOptiFineVersions(@Nullable OptiFineUtils.OptiFineVersions versions) {
        if (versions == null || versions.gameVersions == null || versions.optifineVersions == null) return null;
        LinkedHashMap<String, List<OptiFineUtils.OptiFineVersion>> map = new LinkedHashMap<>();
        int count = Math.min(versions.gameVersions.size(), versions.optifineVersions.size());
        for (int i = 0; i < count; i++) {
            String header = versions.gameVersions.get(i);
            List<OptiFineUtils.OptiFineVersion> builds = versions.optifineVersions.get(i);
            if (header == null || builds == null) continue;
            String game = header.startsWith("Minecraft ") ? header.substring("Minecraft ".length()).trim() : header.trim();
            List<OptiFineUtils.OptiFineVersion> valid = new ArrayList<>();
            for (OptiFineUtils.OptiFineVersion build : builds) {
                if (build != null && build.versionName != null && build.downloadUrl != null) valid.add(build);
            }
            if (valid.isEmpty()) continue;
            List<OptiFineUtils.OptiFineVersion> existing = map.get(game);
            if (existing == null) map.put(game, valid);
            else existing.addAll(valid);
        }
        return map;
    }

    private static void addBtaVersions(@Nullable List<BTAUtils.BTAVersion> source, @Nullable String tag,
                                       @NonNull List<BTAUtils.BTAVersion> builds, @NonNull List<String> labels) {
        if (source == null) return;
        for (BTAUtils.BTAVersion version : source) {
            if (version == null || version.versionName == null) continue;
            builds.add(version);
            labels.add(tag == null ? version.versionName : version.versionName + " (" + tag + ")");
        }
    }

    @NonNull
    private static String optiFineLabel(@NonNull String versionName) {
        // "OptiFine HD U I6" -> "HD U I6"
        String label = versionName.trim();
        return label.startsWith("OptiFine ") ? label.substring("OptiFine ".length()) : label;
    }

    private void onGameVersions(int token, @Nullable List<String> versions) {
        if (token != mGameToken) return;
        setGameLoading(false);
        if (versions == null) {
            mGameFailed = true;
            mMcValue.setText(R.string.ci_load_failed);
            if (mLoader != L_VANILLA) {
                setLoaderLoading(false);
                mLoaderValue.setText("");
            }
            updateSummary();
            return;
        }
        mGameVersions.addAll(versions);
        if (mGameVersions.isEmpty()) {
            mMcValue.setText(R.string.ci_no_versions);
            setLoaderLoading(false);
            mLoaderValue.setText("");
            updateSummary();
            return;
        }
        // Keep the version the user picked when switching loader, if this loader has it
        int preferred = mPreferredGame != null ? mGameVersions.indexOf(mPreferredGame) : -1;
        mGameIndex = Math.max(preferred, 0);
        mMcValue.setText(mGameVersions.get(mGameIndex));
        CreatePopupMotion.valueLoaded(mMcValue);
        onGameVersionChanged();
    }

    private void onGameVersionChanged() {
        updateNameHint();
        if (mLoader == L_VANILLA) {
            updateSummary();
            return;
        }
        loadLoaderVersions();
    }

    private void loadLoaderVersions() {
        if (mGameIndex < 0 || mGameIndex >= mGameVersions.size()) return;
        final int token = ++mLoaderToken;
        final String game = mGameVersions.get(mGameIndex);
        mLoaderValues.clear();
        mLoaderDisplay.clear();
        mOptiFineBuilds.clear();
        mLoaderIndex = 0;
        mLoaderFailed = false;
        setLoaderLoading(true);

        if (mLoader == L_OPTIFINE) {
            List<OptiFineUtils.OptiFineVersion> builds = mOptiFineVersions != null ? mOptiFineVersions.get(game) : null;
            List<String> labels = null;
            if (builds != null) {
                mOptiFineBuilds.addAll(builds);
                labels = new ArrayList<>();
                for (OptiFineUtils.OptiFineVersion build : builds) labels.add(optiFineLabel(build.versionName));
            }
            onLoaderVersions(token, labels, labels);
            return;
        }
        if (mLoader == L_BTA) {
            List<String> names = new ArrayList<>();
            for (BTAUtils.BTAVersion build : mBtaBuilds) names.add(build.versionName);
            onLoaderVersions(token, names, new ArrayList<>(mBtaLabels));
            return;
        }

        if (isForgeLike()) {
            List<String> builds = mForgeVersions != null ? mForgeVersions.get(game) : null;
            List<String> labels = new ArrayList<>();
            if (builds != null) {
                for (String build : builds) labels.add(forgeLabel(build, game));
            }
            onLoaderVersions(token, builds, labels);
            return;
        }

        final FabriclikeUtils fabric = fabricUtils();
        CSLauncherApplication.sExecutorService.execute(() -> {
            List<String> values = null;
            try {
                FabricVersion[] versions = fabric.downloadLoaderVersions(game);
                if (versions != null) {
                    values = new ArrayList<>();
                    for (FabricVersion version : versions) {
                        if (version != null && version.stable && version.version != null) values.add(version.version);
                    }
                    // Only betas for this game version: offer them rather than nothing
                    if (values.isEmpty()) {
                        for (FabricVersion version : versions) {
                            if (version != null && version.version != null) values.add(version.version);
                        }
                    }
                }
            } catch (Exception e) {
                values = null;
            }
            final List<String> finalValues = values;
            Tools.runOnUiThread(() -> onLoaderVersions(token, finalValues, finalValues));
        });
    }

    @NonNull
    private static String forgeLabel(@NonNull String build, @NonNull String game) {
        // Forge builds are "1.20.1-47.2.0": show only the loader part
        return build.startsWith(game + "-") ? build.substring(game.length() + 1) : build;
    }

    private void onLoaderVersions(int token, @Nullable List<String> values, @Nullable List<String> labels) {
        if (token != mLoaderToken) return;
        setLoaderLoading(false);
        if (values == null || labels == null) {
            mLoaderFailed = true;
            mLoaderValue.setText(R.string.ci_load_failed);
            updateSummary();
            return;
        }
        if (values.isEmpty()) {
            mLoaderValue.setText(R.string.ci_no_versions);
            updateSummary();
            return;
        }
        mLoaderValues.addAll(values);
        for (int i = 0; i < labels.size(); i++) {
            mLoaderDisplay.add(i == 0 ? getContext().getString(R.string.ci_latest, labels.get(0)) : labels.get(i));
        }
        mLoaderIndex = 0;
        mLoaderValue.setText(mLoaderDisplay.get(0));
        CreatePopupMotion.valueLoaded(mLoaderValue);
        updateSummary();
    }

    private void setGameLoading(boolean loading) {
        mGameLoading = loading;
        mMcProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) mMcValue.setText(R.string.ci_loading);
        updateCreateEnabled();
    }

    private void setLoaderLoading(boolean loading) {
        mLoaderLoading = loading;
        mLoaderProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) mLoaderValue.setText(R.string.ci_loading);
        updateCreateEnabled();
    }

    private void setLoaderBlockVisible(boolean visible) {
        if (visible) Motion.reveal(mLoaderBlock);
        else Motion.hide(mLoaderBlock, true);
    }

    private boolean isReady() {
        if (mGameLoading || mGameIndex < 0 || mGameIndex >= mGameVersions.size()) return false;
        if (mLoader == L_VANILLA) return true;
        return !mLoaderLoading && mLoaderIndex >= 0 && mLoaderIndex < mLoaderValues.size();
    }

    private void updateCreateEnabled() {
        float alpha = isReady() ? 1f : 0.55f;
        if (mCreate.getAlpha() != alpha) mCreate.animate().alpha(alpha).setStartDelay(0).setDuration(Motion.SHORT).start();
    }

    private void updateNameHint() {
        mName.setHint(defaultName());
    }

    @NonNull
    private String defaultName() {
        String game = mGameIndex >= 0 && mGameIndex < mGameVersions.size() ? mGameVersions.get(mGameIndex) : null;
        return game == null ? getContext().getString(R.string.ci_name_hint) : LOADER_NAMES[mLoader] + " " + game;
    }

    private void updateSummary() {
        updateCreateEnabled();
        StringBuilder builder = new StringBuilder(LOADER_NAMES[mLoader]);
        if (mLoader != L_VANILLA && mLoaderIndex >= 0 && mLoaderIndex < mLoaderValues.size()) {
            String value = mLoaderValues.get(mLoaderIndex);
            builder.append(' ').append(isForgeLike() && mGameIndex >= 0 && mGameIndex < mGameVersions.size()
                    ? forgeLabel(value, mGameVersions.get(mGameIndex)) : value);
        }
        if (mGameIndex >= 0 && mGameIndex < mGameVersions.size()) {
            builder.append("  •  Minecraft ").append(mGameVersions.get(mGameIndex));
        }
        builder.append("  •  ");
        if (mUseGlobalRam) builder.append(getContext().getString(R.string.ci_summary_ram_global));
        else builder.append(String.format(Locale.getDefault(), getContext().getString(R.string.ci_memory_value), mRam));
        mSummary.setText(builder.toString());
    }

    // ─── Dropdowns ───────────────────────────────────────────────────────────

    private interface OnPicked {
        void onPicked(int index);
    }

    private void showDropdown(@NonNull View anchor, @NonNull List<String> items, int selected, @NonNull OnPicked onPicked) {
        if (mBusyRunning || items.isEmpty()) return;
        hideKeyboard();
        if (mDropdown != null) mDropdown.dismiss();
        // Title from the field's label so the picker says what it is for
        CharSequence title = anchor == mMcField ? getContext().getText(R.string.ci_mc_version)
                : anchor == mLoaderField ? getContext().getText(R.string.ci_loader_version)
                : anchor.getId() == R.id.ci_java_field ? getContext().getText(R.string.ci_java)
                : getContext().getText(R.string.ci_renderer);
        new ListPickerDialog(getContext(), getContext().getText(R.string.ci_title), titleCase(title), items, null, selected,
                onPicked::onPicked).show();
    }

    /** "MINECRAFT VERSION" → "Minecraft Version" */
    private static String titleCase(CharSequence upper) {
        StringBuilder sb = new StringBuilder();
        boolean start = true;
        for (char c : upper.toString().toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            sb.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ';
        }
        return sb.toString();
    }

    private final class DropdownAdapter extends BaseAdapter {
        private final List<String> mItems;
        private final int mSelected;

        DropdownAdapter(List<String> items, int selected) {
            mItems = items;
            mSelected = selected;
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public Object getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ci_dropdown, parent, false);
            TextView text = row.findViewById(R.id.ci_row_text);
            text.setText(mItems.get(position));
            boolean chosen = position == mSelected;
            row.setSelected(chosen);
            text.setTypeface(null, chosen ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            row.findViewById(R.id.ci_row_check).setVisibility(chosen ? View.VISIBLE : View.INVISIBLE);
            return row;
        }
    }

    // ─── Create ──────────────────────────────────────────────────────────────

    private void onCreateClicked() {
        if (mBusyRunning) return;
        if (!isReady()) {
            Motion.shake(mCreate);
            if (!mGameLoading && !mLoaderLoading) {
                Toast.makeText(getContext(), R.string.ci_pick_version, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (ProgressKeeper.hasOngoingTasks()) {
            Toast.makeText(getContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }
        hideKeyboard();

        final int loader = mLoader;
        final String game = mGameVersions.get(mGameIndex);
        final String loaderValue = loader == L_VANILLA ? null : mLoaderValues.get(mLoaderIndex);
        String typed = mName.getText() != null ? mName.getText().toString().trim() : "";
        final String name = typed.isEmpty() ? defaultName() : typed;
        final int ram = mUseGlobalRam ? 0 : mRam;
        Runtime runtime = mRuntimeIndex >= 0 && mRuntimeIndex < mRuntimes.size() ? mRuntimes.get(mRuntimeIndex) : null;
        final String runtimeName = runtime == null || DEFAULT_RUNTIME.equals(runtime.name) || runtime.versionString == null
                ? null : runtime.name;
        final String renderer = mRendererIndex >= 0 && mRendererIndex < mRendererIds.size() ? mRendererIds.get(mRendererIndex) : null;
        final boolean shared = mShared;
        final FabriclikeUtils fabric = fabricUtils();
        final ForgelikeUtils forge = forgeUtils();
        final OptiFineUtils.OptiFineVersion optiFine = loader == L_OPTIFINE && mLoaderIndex < mOptiFineBuilds.size()
                ? mOptiFineBuilds.get(mLoaderIndex) : null;
        final BTAUtils.BTAVersion bta = loader == L_BTA && mLoaderIndex < mBtaBuilds.size()
                ? mBtaBuilds.get(mLoaderIndex) : null;
        if ((loader == L_OPTIFINE && optiFine == null) || (loader == L_BTA && bta == null)) {
            Motion.shake(mCreate);
            return;
        }
        final Context context = getContext();

        mBusyRunning = true;
        ((TextView) findViewById(R.id.ci_busy_text)).setText(loader == L_VANILLA
                ? context.getString(R.string.ci_busy_vanilla, game)
                : context.getString(R.string.ci_busy_loader, LOADER_NAMES[loader], game));
        CreatePopupMotion.showBusy(mBusy);

        final InstanceSetter common = instance -> {
            instance.name = name;
            instance.ramAllocation = ram;
            instance.selectedRuntime = runtimeName;
            instance.renderer = renderer;
            instance.sharedData = shared;
        };

        CSLauncherApplication.sExecutorService.execute(() -> {
            try {
                Instance created;
                boolean installing = false;
                if (loader == L_VANILLA) {
                    created = Instances.createInstance(instance -> {
                        common.setInstanceProperties(instance);
                        instance.versionId = game;
                    }, game);
                } else if (loader == L_FABRIC || loader == L_QUILT || loader == L_LEGACY_FABRIC) {
                    final String versionId = fabric.install(game, loaderValue);
                    if (versionId == null) {
                        throw new java.io.IOException(context.getString(R.string.fabric_dl_cant_read_meta, fabric.getName()));
                    }
                    created = Instances.createInstance(instance -> {
                        common.setInstanceProperties(instance);
                        instance.icon = fabric.getIconName();
                        instance.versionId = versionId;
                    }, versionId);
                } else if (loader == L_OPTIFINE) {
                    // Same steps as the old OptiFine page: vanilla game first, then the OptiFine installer
                    ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0);
                    final InstanceInstaller installer;
                    try {
                        new OptiFineDownloadTask(optiFine).prepareForInstall();
                        installer = OptiFineUtils.createInstaller(optiFine);
                    } finally {
                        ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                    }
                    created = Instances.createInstance(instance -> {
                        common.setInstanceProperties(instance);
                        instance.installer = installer;
                    }, "OptiFine-" + game);
                    installer.start();
                    installing = true;
                } else if (loader == L_BTA) {
                    ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.fabric_dl_progress, "BTA");
                    try {
                        created = new BTADownloadTask(null, bta).install(common);
                    } finally {
                        ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                    }
                } else {
                    ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0);
                    final InstanceInstaller installer;
                    try {
                        installer = forge.createInstaller(loaderValue);
                    } finally {
                        ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                    }
                    created = Instances.createInstance(instance -> {
                        common.setInstanceProperties(instance);
                        instance.icon = forge.getIconName();
                        instance.installer = installer;
                    }, loaderValue);
                    installer.start();
                    installing = true;
                }
                final Instance result = created;
                final boolean finalInstalling = installing;
                Tools.runOnUiThread(() -> onCreated(context, result, name, loader, finalInstalling));
            } catch (Exception e) {
                Tools.runOnUiThread(() -> onCreateFailed(context, e));
            }
        });
    }

    private void onCreated(@NonNull Context context, @NonNull Instance instance, @NonNull String name,
                           int loader, boolean installing) {
        // Also refreshes the profile chip / spinner (REFRESH_VERSION_SPINNER)
        Instances.setSelectedInstance(instance);
        String message = installing
                ? context.getString(R.string.ci_done_installing, name, LOADER_NAMES[loader])
                : context.getString(R.string.ci_done, name);
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        mBusyRunning = false;
        if (isShowing()) close(null);
    }

    private void onCreateFailed(@NonNull Context context, @NonNull Exception e) {
        mBusyRunning = false;
        if (isShowing()) {
            CreatePopupMotion.hideBusy(mBusy);
            Tools.showError(context, e);
        } else {
            Tools.showErrorRemote(e);
        }
    }

    // ─── Misc ────────────────────────────────────────────────────────────────

    @Override
    protected void onDismissRequested() {
        if (mBusyRunning) {
            CreatePopupMotion.nudge(mCard);
            return;
        }
        hideKeyboard();
        close(null);
    }

    @Override
    public void dismiss() {
        if (mDropdown != null) {
            mDropdown.dismiss();
            mDropdown = null;
        }
        super.dismiss();
    }

    private void hideKeyboard() {
        if (mName == null) return;
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mName.getWindowToken(), 0);
    }
}
