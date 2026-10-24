package com.craftstudio.cslauncher.game.crash.autofix;

import java.util.Locale;

/**
 * One planned repair, produced by the analyzer and executed by the {@link RepairEngine}.
 * The step only names <em>what</em> to do and on which component; it never contains code or
 * file paths picked by an external source, so the executor can validate every target against
 * the profile before touching anything.
 */
public final class RepairStep {
    public enum Kind {
        /** Rename a mod jar to .jar.disabled (never deletes). Target: mod id or jar file name. */
        DISABLE_MOD,
        /** Re-enable a disabled jar that provides the missing dependency. Target: mod id. */
        ENABLE_MOD,
        /** Download a missing library mod from Modrinth, checksum verified. Target: mod id, detail: version requirement. */
        INSTALL_DEPENDENCY,
        /** Verify every library / client jar of the version against its checksum and quarantine corrupt ones. */
        VERIFY_LIBRARIES,
        /** Remove only the offending JVM option tokens. Target: option token. */
        PRUNE_JVM_ARGS,
        /** Set the profile memory allocation to a device-safe value. Detail: "oom" or "native". */
        ADJUST_MEMORY,
        /** Switch the profile renderer to the most compatible one (gl4es). */
        FALLBACK_RENDERER,
        /** Switch the profile runtime to an installed Java matching the required major version. Target: major version. */
        SELECT_RUNTIME,
        /** Turn shaders off in Iris / OptiFine configuration (packs stay on disk). */
        DISABLE_SHADERS,
        /** Remove a resource pack from options.txt (pack stays on disk). Target: pack entry or empty for all. */
        DISABLE_RESOURCE_PACK,
        /** Move a broken config file to the backup folder so the mod regenerates it. Target: relative path under config/. */
        RESET_CONFIG,
        /** Probe connectivity with bounded retries; no file change, success means a relaunch will re-download. */
        CHECK_NETWORK
    }

    public final Kind kind;
    /** Component the step applies to (mod id, token, file name...). Never null, may be empty. */
    public final String target;
    /** Extra information for the executor (version range, reason). Never null, may be empty. */
    public final String detail;

    public RepairStep(Kind kind, String target, String detail) {
        this.kind = kind;
        this.target = target == null ? "" : target.trim();
        this.detail = detail == null ? "" : detail.trim();
    }

    public RepairStep(Kind kind, String target) {
        this(kind, target, "");
    }

    public RepairStep(Kind kind) {
        this(kind, "", "");
    }

    /** Stable key used to make sure the same fix is not retried after it already failed. */
    public String key() {
        return kind.name() + ":" + target.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return key() + (detail.isEmpty() ? "" : " (" + detail + ")");
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RepairStep)) return false;
        return key().equals(((RepairStep) o).key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }
}
