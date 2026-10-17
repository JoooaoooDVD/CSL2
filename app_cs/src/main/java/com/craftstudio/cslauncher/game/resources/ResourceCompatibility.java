package com.craftstudio.cslauncher.game.resources;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.modding.modpacks.models.ModItem;

/**
 * Compatibility verdict for one search hit against the running profile. Computed from the
 * search metadata (supported game versions + Modrinth loader categories) so the list renders
 * without an extra request per card; the install path re-checks against the real version list.
 */
public final class ResourceCompatibility {
    public enum Level { COMPATIBLE, POSSIBLE, MISMATCH, MISSING_DEPENDENCY, UNKNOWN }

    @NonNull public final Level level;
    /** Optional explanation shown under the chips (string resource id, 0 for none). */
    public final int noteRes;
    /** Format argument for noteRes, or null. */
    @Nullable public final String noteArg;

    private ResourceCompatibility(@NonNull Level level, int noteRes, @Nullable String noteArg) {
        this.level = level;
        this.noteRes = noteRes;
        this.noteArg = noteArg;
    }

    public int labelRes() {
        switch (level) {
            case COMPATIBLE: return R.string.rb_compat_ok;
            case POSSIBLE: return R.string.rb_compat_possible;
            case MISMATCH: return R.string.rb_compat_mismatch;
            case MISSING_DEPENDENCY: return R.string.rb_compat_missing_dep;
            default: return R.string.rb_compat_unknown;
        }
    }

    public int colorRes() {
        switch (level) {
            case COMPATIBLE: return R.color.rb_ok;
            case POSSIBLE: return R.color.rb_info;
            case MISMATCH: return R.color.rb_bad;
            case MISSING_DEPENDENCY: return R.color.rb_warn;
            default: return R.color.rb_neutral;
        }
    }

    public int backgroundColorRes() {
        switch (level) {
            case COMPATIBLE: return R.color.rb_ok_bg;
            case POSSIBLE: return R.color.rb_info_bg;
            case MISMATCH: return R.color.rb_bad_bg;
            case MISSING_DEPENDENCY: return R.color.rb_warn_bg;
            default: return R.color.rb_neutral_bg;
        }
    }

    @NonNull
    public static ResourceCompatibility evaluate(@NonNull ModItem item, @NonNull ContentType type,
                                                 @Nullable ResourceProfileContext profile) {
        // 1. Shader packs need a shader loader. Missing dependency outranks version checks.
        if (type == ContentType.SHADER_PACK && profile != null && profile.isUsable()) {
            boolean wantsIris = false, wantsOptiFine = false, wantsVanilla = false, anyLoaderInfo = false;
            if (item.loaders != null) {
                for (String l : item.loaders) {
                    if (l == null) continue;
                    anyLoaderInfo = true;
                    switch (l) {
                        case "iris": wantsIris = true; break;
                        case "optifine": wantsOptiFine = true; break;
                        case "vanilla": case "canvas": wantsVanilla = true; break;
                        default: break;
                    }
                }
            }
            boolean satisfied = wantsVanilla
                    || (wantsIris && profile.hasIris)
                    || (wantsOptiFine && profile.hasOptiFine)
                    || (!anyLoaderInfo && (profile.hasIris || profile.hasOptiFine));
            if (!satisfied) {
                int note;
                if (wantsIris && !wantsOptiFine) note = R.string.rb_note_shader_needs_iris;
                else if (wantsOptiFine && !wantsIris) note = R.string.rb_note_shader_needs_optifine;
                else note = R.string.rb_note_shader_needs_any;
                return new ResourceCompatibility(Level.MISSING_DEPENDENCY, note, null);
            }
        }

        // 2. Game version match
        String mc = profile == null ? null : profile.mcVersion;
        if (mc == null || mc.isEmpty()) {
            return new ResourceCompatibility(Level.UNKNOWN, R.string.rb_note_unknown_version, null);
        }
        if (item.gameVersions == null || item.gameVersions.length == 0) {
            return new ResourceCompatibility(Level.UNKNOWN, 0, null);
        }
        String family = ResourceProfileContext.majorMinor(mc);
        boolean sameFamily = false;
        for (String v : item.gameVersions) {
            if (v == null) continue;
            if (v.equals(mc)) return new ResourceCompatibility(Level.COMPATIBLE, 0, null);
            if (family != null && family.equals(ResourceProfileContext.majorMinor(v))) sameFamily = true;
        }
        if (sameFamily) return new ResourceCompatibility(Level.POSSIBLE, R.string.rb_note_possible, family);
        return new ResourceCompatibility(Level.MISMATCH, R.string.rb_note_version_mismatch, mc);
    }
}
