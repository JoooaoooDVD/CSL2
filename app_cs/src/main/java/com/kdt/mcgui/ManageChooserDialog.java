package com.kdt.mcgui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.io.File;

/**
 * "Manage content" popup (Manager button): pick Mods, Resource packs, Shader packs or
 * Worlds, then the matching manager opens. Each option shows how many items are installed
 * in the selected profile (counted off the main thread). The card art is the manager
 * banner on top, gradient icon tiles below.
 */
public class ManageChooserDialog extends CSPopupDialog {

    public interface Callback {
        void onTypeChosen(@NonNull ContentType type);
        void onWorldsChosen();
        void onFilterRequested();
    }

    private static final ContentType[] TYPES = {ContentType.MOD, ContentType.RESOURCE_PACK, ContentType.SHADER_PACK};

    private final Callback mCallback;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public ManageChooserDialog(@NonNull Context context, @NonNull Callback callback) {
        super(context);
        mCallback = callback;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_manage_chooser);
        View card = findViewById(R.id.mc_card);
        setupPopup(findViewById(R.id.mc_scrim), card, true);
        sizeCard(card);

        View[] options = {findViewById(R.id.mc_opt_mods), findViewById(R.id.mc_opt_rp), findViewById(R.id.mc_opt_sh)};
        View[] tiles = {findViewById(R.id.mc_mods_tile), findViewById(R.id.mc_rp_tile), findViewById(R.id.mc_sh_tile)};
        View[] glows = {findViewById(R.id.mc_mods_glow), findViewById(R.id.mc_rp_glow), findViewById(R.id.mc_sh_glow)};
        View[] bars = {findViewById(R.id.mc_mods_bar), findViewById(R.id.mc_rp_bar), findViewById(R.id.mc_sh_bar)};
        TextView[] counts = {findViewById(R.id.mc_mods_count), findViewById(R.id.mc_rp_count), findViewById(R.id.mc_sh_count)};

        for (int i = 0; i < options.length; i++) {
            final ContentType type = TYPES[i];
            final View option = options[i];
            final View tile = tiles[i];
            option.setClipToOutline(true);
            option.setOnClickListener(v -> {
                if (isClosing()) return;
                Motion.bounce(tile);
                CreatePopupMotion.optionChosen(option, () -> close(() -> mCallback.onTypeChosen(type)));
            });
        }

        View worldOption = findViewById(R.id.mc_opt_world);
        View worldTile = findViewById(R.id.mc_world_tile);
        View worldGlow = findViewById(R.id.mc_world_glow);
        View worldBar = findViewById(R.id.mc_world_bar);
        TextView worldCount = findViewById(R.id.mc_world_count);
        worldOption.setClipToOutline(true);
        worldOption.setOnClickListener(v -> {
            if (isClosing()) return;
            Motion.bounce(worldTile);
            CreatePopupMotion.optionChosen(worldOption, () -> close(mCallback::onWorldsChosen));
        });

        View close = findViewById(R.id.mc_close);
        View filter = findViewById(R.id.mc_filter);
        close.setOnClickListener(v -> close(null));
        filter.setOnClickListener(v -> mCallback.onFilterRequested());
        CSAnimationEngine.attachPressFeedback(close);
        CSAnimationEngine.attachPressFeedback(filter);

        TextView subtitle = findViewById(R.id.mc_subtitle);
        String profileName = profileName();
        if (profileName.isEmpty()) subtitle.setVisibility(View.GONE);
        else subtitle.setText(getContext().getString(R.string.mc_subtitle, profileName));

        CreatePopupMotion.enter(mScrim, mCard);
        CreatePopupMotion.chooserContent(findViewById(R.id.mc_header), options, null);
        for (int i = 0; i < tiles.length; i++) {
            long delay = 200 + i * 70L;
            Motion.popIn(tiles[i], delay, 0.4f);
            Motion.fadeIn(counts[i], delay + 120, Motion.MEDIUM);
            breathe(glows[i], delay + 60);
            growBar(bars[i], delay + 160);
        }
        Motion.popIn(worldTile, 200 + tiles.length * 70L, 0.4f);
        Motion.fadeIn(worldCount, 200 + tiles.length * 70L + 120, Motion.MEDIUM);
        breathe(worldGlow, 200 + tiles.length * 70L + 60);
        growBar(worldBar, 200 + tiles.length * 70L + 160);
        loadCounts(counts, worldCount);
    }

    /** The glow behind a tile fades to its resting alpha, then slowly breathes */
    private void breathe(@NonNull View glow, long delay) {
        final float rest = 0.3f;
        glow.animate().cancel();
        if (!Motion.content(glow)) {
            glow.setAlpha(rest);
            return;
        }
        glow.setAlpha(0f);
        glow.setScaleX(0.5f);
        glow.setScaleY(0.5f);
        glow.animate().alpha(rest).scaleX(1f).scaleY(1f).setStartDelay(delay)
                .setDuration(Motion.LONG + 180).setInterpolator(Motion.ENTER).start();
    }

    /** The colour bar at the bottom of an option grows from the left */
    private void growBar(@NonNull View bar, long delay) {
        bar.animate().cancel();
        if (!Motion.content(bar)) {
            bar.setScaleX(1f);
            return;
        }
        bar.setPivotX(0f);
        bar.setScaleX(0f);
        bar.animate().scaleX(1f).setStartDelay(delay).setDuration(Motion.LONG + 120)
                .setInterpolator(Motion.STANDARD).start();
    }

    /** Counts the files of each content folder in the background */
    private void loadCounts(@NonNull TextView[] counts, @NonNull TextView worldCount) {
        final File gameDir = gameDirectory();
        CSLauncherApplication.sExecutorService.execute(() -> {
            final int[] values = new int[TYPES.length];
            for (int i = 0; i < TYPES.length; i++) values[i] = countFiles(new File(gameDir, TYPES[i].folderName), TYPES[i].fileExtension);
            final int worlds = countWorlds(gameDir);
            mHandler.post(() -> {
                if (!isShowing()) return;
                for (int i = 0; i < counts.length; i++) {
                    Motion.swapText(counts[i], getContext().getString(R.string.mc_count, values[i]));
                    counts[i].setTextColor(ContextCompat.getColor(getContext(),
                            values[i] > 0 ? R.color.hm_text : R.color.hm_text_dim));
                }
                Motion.swapText(worldCount, getContext().getString(R.string.mc_count, worlds));
                worldCount.setTextColor(ContextCompat.getColor(getContext(),
                        worlds > 0 ? R.color.hm_text : R.color.hm_text_dim));
            });
        });
    }

    /** Worlds are the save folders of the profile (a real world always has level.dat) */
    private static int countWorlds(@NonNull File gameDir) {
        File saves = new File(gameDir, "saves");
        File[] dirs = saves.listFiles(File::isDirectory);
        if (dirs == null) return 0;
        int withLevel = 0;
        for (File dir : dirs) {
            if (new File(dir, "level.dat").isFile() || new File(dir, "level.dat_old").isFile()) withLevel++;
        }
        return withLevel > 0 ? withLevel : dirs.length;
    }

    private static int countFiles(@NonNull File dir, @NonNull String extension) {
        File[] files = dir.listFiles(f -> f.isFile()
                && (f.getName().endsWith(extension) || f.getName().endsWith(extension + ".disabled")));
        return files == null ? 0 : files.length;
    }

    @NonNull
    private static File gameDirectory() {
        try {
            Instance instance = Instances.loadInstance(Instances.getSelectedInstanceKey());
            if (instance != null) return instance.getGameDirectory();
        } catch (Exception ignored) {}
        return new File(Tools.DIR_GAME_NEW);
    }

    @NonNull
    private static String profileName() {
        try {
            String key = Instances.getSelectedInstanceKey();
            Instance instance = Instances.loadInstance(key);
            if (instance == null) return "";
            return instance.name != null && !instance.name.isEmpty() ? instance.name : key;
        } catch (Exception e) {
            return "";
        }
    }

    /** Wide on tablets, never touching the edges; capped in height so the option rows scroll */
    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = Math.min((int) (660 * d), metrics.widthPixels - (int) (48 * d));
        card.setLayoutParams(params);
        final int maxHeight = (int) (metrics.heightPixels * 0.86);
        card.post(() -> {
            if (card.getHeight() > maxHeight) {
                ViewGroup.LayoutParams capped = card.getLayoutParams();
                capped.height = maxHeight;
                card.setLayoutParams(capped);
            }
        });
    }
}
