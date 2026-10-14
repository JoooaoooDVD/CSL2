package com.craftstudio.cslauncher.worlds;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the world editor shows and changes, read from / written to level.dat.
 */
public final class WorldDetails {

    public final File dir;
    /** Display name stored in level.dat (falls back to the folder name) */
    public String levelName;
    /** "1.21.4" style version string, null for very old worlds */
    @Nullable public String versionName;
    public int dataVersion;
    public int gameType = -1;
    public boolean hardcore;
    public int difficulty = -1;
    public boolean allowCommands;
    public boolean hasSeed;
    public long seed;
    /** World age in ticks */
    public long timeTicks;
    public long dayTimeTicks;
    public long lastPlayedMs;
    public int spawnX, spawnY, spawnZ;
    public boolean hasSpawn;
    public boolean wasModded;
    public boolean generateFeatures = true;
    @Nullable public String levelType;
    public final List<String> enabledPacks = new ArrayList<>();
    public final List<String> disabledPacks = new ArrayList<>();
    public boolean readable;

    private WorldDetails(@NonNull File dir) {
        this.dir = dir;
        this.levelName = dir.getName();
    }

    @NonNull
    public static WorldDetails load(@NonNull File worldDir) {
        WorldDetails details = new WorldDetails(worldDir);
        File level = new File(worldDir, "level.dat");
        if (!level.isFile()) level = new File(worldDir, "level.dat_old");
        if (!level.isFile()) return details;
        try {
            Nbt.Root root = Nbt.read(level);
            Nbt.Tag data = Nbt.find(root.compound, "Data");
            if (data == null) return details;
            details.readable = true;
            String name = Nbt.getString(data, "LevelName");
            if (name != null && !name.trim().isEmpty()) details.levelName = name;
            details.versionName = Nbt.getString(data, "Version.Name");
            details.dataVersion = (int) Nbt.getNumber(data, "DataVersion", 0);
            details.gameType = (int) Nbt.getNumber(data, "GameType", -1);
            details.hardcore = Nbt.getNumber(data, "hardcore", 0) != 0;
            details.difficulty = (int) Nbt.getNumber(data, "Difficulty", -1);
            details.allowCommands = Nbt.getNumber(data, "allowCommands", 0) != 0;
            if (Nbt.has(data, "WorldGenSettings.seed")) {
                details.seed = Nbt.getNumber(data, "WorldGenSettings.seed", 0);
                details.hasSeed = true;
            } else if (Nbt.has(data, "RandomSeed")) {
                details.seed = Nbt.getNumber(data, "RandomSeed", 0);
                details.hasSeed = true;
            }
            if (Nbt.has(data, "WorldGenSettings.generate_features")) {
                details.generateFeatures = Nbt.getNumber(data, "WorldGenSettings.generate_features", 1) != 0;
            }
            details.levelType = Nbt.getString(data, "generatorName");
            details.timeTicks = Nbt.getNumber(data, "Time", 0);
            details.dayTimeTicks = Nbt.getNumber(data, "DayTime", 0);
            details.lastPlayedMs = Nbt.getNumber(data, "LastPlayed", 0);
            if (Nbt.has(data, "SpawnX")) {
                details.hasSpawn = true;
                details.spawnX = (int) Nbt.getNumber(data, "SpawnX", 0);
                details.spawnY = (int) Nbt.getNumber(data, "SpawnY", 0);
                details.spawnZ = (int) Nbt.getNumber(data, "SpawnZ", 0);
            } else if (Nbt.has(data, "spawn.pos")) {
                Nbt.Tag pos = Nbt.find(data, "spawn.pos");
                if (pos != null && pos.value instanceof int[] && ((int[]) pos.value).length >= 3) {
                    int[] xyz = (int[]) pos.value;
                    details.hasSpawn = true;
                    details.spawnX = xyz[0];
                    details.spawnY = xyz[1];
                    details.spawnZ = xyz[2];
                }
            }
            details.wasModded = Nbt.getNumber(data, "WasModded", 0) != 0;
            readPackList(Nbt.find(data, "DataPacks.Enabled"), details.enabledPacks);
            readPackList(Nbt.find(data, "DataPacks.Disabled"), details.disabledPacks);
        } catch (Exception ignored) {
            // Corrupt or foreign level.dat: the editor still shows the folder facts
        }
        return details;
    }

    private static void readPackList(@Nullable Nbt.Tag list, List<String> out) {
        if (list == null || list.type != Nbt.TAG_LIST) return;
        for (Nbt.Tag tag : list.list()) {
            if (tag.type == Nbt.TAG_STRING) out.add((String) tag.value);
        }
    }

    /** Writes a new LevelName into level.dat (what the game shows in its world list) */
    public static void rename(@NonNull File worldDir, @NonNull String newName) throws IOException {
        File level = new File(worldDir, "level.dat");
        if (!level.isFile()) throw new IOException("level.dat missing");
        Nbt.Root root = Nbt.read(level);
        Nbt.Tag data = Nbt.find(root.compound, "Data");
        if (data == null || data.type != Nbt.TAG_COMPOUND) throw new IOException("Data compound missing");
        data.compound().put("LevelName", new Nbt.Tag(Nbt.TAG_STRING, newName));
        Nbt.write(root, level);
    }

    /** Stores the given bitmap as the 64x64 world icon the game displays */
    public static void saveIcon(@NonNull File worldDir, @NonNull Bitmap source) throws IOException {
        int size = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - size) / 2, y = (source.getHeight() - size) / 2;
        Bitmap icon = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(icon);
        Matrix matrix = new Matrix();
        float scale = 64f / size;
        matrix.postTranslate(-x, -y);
        matrix.postScale(scale, scale);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(source, matrix, paint);
        File target = new File(worldDir, "icon.png");
        try (FileOutputStream out = new FileOutputStream(target)) {
            icon.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            icon.recycle();
        }
    }

    // ─── Presentation helpers ────────────────────────────────────────────────

    @NonNull
    public String gameModeLabel() {
        switch (gameType) {
            case 0: return hardcore ? "Hardcore" : "Survival";
            case 1: return "Creative";
            case 2: return "Adventure";
            case 3: return "Spectator";
            default: return "Unknown";
        }
    }

    @NonNull
    public String difficultyLabel() {
        if (hardcore) return "Hardcore";
        switch (difficulty) {
            case 0: return "Peaceful";
            case 1: return "Easy";
            case 2: return "Normal";
            case 3: return "Hard";
            default: return "Unknown";
        }
    }

    /** "12h 34m" from the tick count (20 ticks per second) */
    @NonNull
    public String playTimeLabel() {
        long seconds = timeTicks / 20;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours == 0 && minutes == 0) return "< 1m";
        if (hours == 0) return minutes + "m";
        return String.format(Locale.US, "%dh %02dm", hours, minutes);
    }

    /** In-game day number */
    public long dayNumber() {
        return dayTimeTicks / 24000L + 1;
    }

    @NonNull
    public String worldTypeLabel() {
        if (levelType == null) return "Default";
        switch (levelType.toLowerCase(Locale.ROOT)) {
            case "flat": return "Superflat";
            case "largebiomes": return "Large biomes";
            case "amplified": return "Amplified";
            case "debug_all_block_states": return "Debug";
            case "default": return "Default";
            default: return levelType;
        }
    }
}
