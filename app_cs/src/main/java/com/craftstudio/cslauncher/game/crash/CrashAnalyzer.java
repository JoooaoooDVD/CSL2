package com.craftstudio.cslauncher.game.crash;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a launcher log into a one-line diagnosis the player can act on. Rules are ordered by
 * how specific / actionable they are; the first rule that matches anywhere in the log wins and
 * the matched line is shown as evidence. Everything that doesn't match ends in "unknown" with
 * the advice to share the log.
 */
public final class CrashAnalyzer {
    public static final class Diagnosis {
        public final int titleRes;
        public final int adviceRes;
        @Nullable public final String detail;    // e.g. the mod / Java version pulled out of the line
        @Nullable public final String evidence;  // the matched log line
        public final boolean known;
        Diagnosis(int titleRes, int adviceRes, @Nullable String detail, @Nullable String evidence, boolean known) {
            this.titleRes = titleRes; this.adviceRes = adviceRes; this.detail = detail; this.evidence = evidence; this.known = known;
        }
    }

    private static final class Rule {
        final Pattern pattern; final int title; final int advice; @Nullable final Pattern detail;
        Rule(String regex, int title, int advice, @Nullable String detailRegex) {
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            this.title = title; this.advice = advice;
            this.detail = detailRegex == null ? null : Pattern.compile(detailRegex, Pattern.CASE_INSENSITIVE);
        }
    }

    private static final Rule[] RULES = {
            new Rule("java\\.lang\\.OutOfMemoryError|GC overhead limit exceeded|Could not reserve enough space|Cannot allocate memory|Native memory allocation .* failed",
                    R.string.gc_dx_oom_title, R.string.gc_dx_oom_advice, null),
            new Rule("Mixin apply failed|MixinApplyError|InvalidMixinException|Mixin transformation of .* failed|MixinTransformerError|Critical injection failure",
                    R.string.gc_dx_mixin_title, R.string.gc_dx_mixin_advice, "from mod ([a-z0-9_\\-]+)|mixins\\.([a-z0-9_\\-]+)\\.json|([a-z0-9_\\-]+)\\.mixins\\.json"),
            new Rule("Missing or unsupported mandatory dependencies|Incompatible mod set|ModResolutionException|Mod resolution failed|requires (?:any )?version .* of |Missing required dependency|Could not find required mod",
                    R.string.gc_dx_dependency_title, R.string.gc_dx_dependency_advice, "mod '([^']+)'|Mod ([A-Za-z0-9_\\-]+) requires|requires .* of ([a-z0-9_\\-]+)"),
            new Rule("UnsupportedClassVersionError|has been compiled by a more recent version of the Java Runtime|class file version (\\d+)",
                    R.string.gc_dx_java_title, R.string.gc_dx_java_advice, "class file version (\\d+)"),
            new Rule("GLFW error|Failed to create (?:the )?GLFW window|EGL_BAD_|eglCreateContext|Could not (?:initialize|create) (?:the )?(?:OpenGL|renderer|EGL)|GL_INVALID_|Unable to initialize OpenGL|libGL(?:ES)?.*(?:failed|error)|Renderer .* not supported",
                    R.string.gc_dx_renderer_title, R.string.gc_dx_renderer_advice, null),
            new Rule("Could not find or load main class|ClassNotFoundException: net\\.minecraft\\.client\\.main\\.Main|NoClassDefFoundError: net/minecraft/client/main/Main|Error: Unable to access jarfile|zip END header not found|Invalid or corrupt jarfile",
                    R.string.gc_dx_install_title, R.string.gc_dx_install_advice, null),
            new Rule("UnknownHostException|SocketTimeoutException|Failed to download|Connection reset|Network is unreachable|SSLHandshakeException",
                    R.string.gc_dx_network_title, R.string.gc_dx_network_advice, null),
            new Rule("Invalid session|Failed to verify username|authentication servers|Failed to login: Invalid session",
                    R.string.gc_dx_account_title, R.string.gc_dx_account_advice, null),
            new Rule("Exception in server tick loop|Ticking entity|Ticking block entity|Exception ticking world|Exception generating new chunk|Watching Server|Server tick timeout",
                    R.string.gc_dx_world_title, R.string.gc_dx_world_advice, "Entity Type: ([a-z0-9_:]+)|Block: Block\\{([a-z0-9_:]+)\\}"),
            new Rule("NoSuchMethodError|NoSuchFieldError|AbstractMethodError|IncompatibleClassChangeError|Unable to find Fabric Loader|Wrong (?:Fabric|Forge|NeoForge) version|Mod .* requires .* loader",
                    R.string.gc_dx_loader_title, R.string.gc_dx_loader_advice, "(?:at|in) (?:mod )?'?([a-z0-9_\\-]+)'?\\s*\\(|net\\.([a-z0-9_]+)\\.[a-z0-9_.]*(?:mixin|init)"),
            new Rule("Game terminated|Terminated by signal|SIGSEGV|SIGABRT|SIGKILL|fatal signal|Fatal error|hs_err_pid",
                    R.string.gc_dx_native_title, R.string.gc_dx_native_advice, null),
    };

    private CrashAnalyzer() {}

    @NonNull
    public static Diagnosis analyze(@NonNull List<String> lines, boolean isSignal, int exitCode) {
        for (Rule rule : RULES) {
            for (int i = lines.size() - 1; i >= 0; i--) { // most recent occurrence
                String line = lines.get(i);
                if (!rule.pattern.matcher(line).find()) continue;
                String detail = null;
                if (rule.detail != null) {
                    // look at the matched line and its neighbours for the detail
                    for (int j = Math.max(0, i - 3); j < Math.min(lines.size(), i + 12) && detail == null; j++) {
                        Matcher m = rule.detail.matcher(lines.get(j));
                        if (m.find()) {
                            for (int g = 1; g <= m.groupCount(); g++) {
                                if (m.group(g) != null) { detail = m.group(g); break; }
                            }
                        }
                    }
                    if (detail != null && rule.title == R.string.gc_dx_java_title) detail = javaFromClassVersion(detail);
                }
                return new Diagnosis(rule.title, rule.advice, detail, compact(line), true);
            }
        }
        if (isSignal || exitCode == 137 || exitCode == 9 || exitCode == 139 || exitCode == 134) {
            return new Diagnosis(R.string.gc_dx_native_title, R.string.gc_dx_native_advice, null, null, true);
        }
        return new Diagnosis(R.string.gc_dx_unknown_title, R.string.gc_dx_unknown_advice, null, lastErrorLine(lines), false);
    }

    /** true when the line is worth highlighting (errors, exceptions, stack frames) */
    public static boolean isErrorLine(@NonNull String line) {
        return line.contains("ERROR") || line.contains("FATAL") || line.contains("Exception") || line.contains("Error:")
                || line.contains("Caused by") || line.startsWith("\tat ") || line.contains("[STDERR]") || line.contains("crash");
    }

    @Nullable
    private static String lastErrorLine(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String l = lines.get(i);
            if ((l.contains("ERROR") || l.contains("Exception") || l.contains("FATAL")) && !l.startsWith("\tat ")) return compact(l);
        }
        return null;
    }

    private static String compact(String line) {
        String t = line.trim();
        return t.length() > 220 ? t.substring(0, 220) + "\u2026" : t;
    }

    private static String javaFromClassVersion(String v) {
        try {
            int major = Integer.parseInt(v);
            if (major >= 45) return "Java " + (major - 44);
        } catch (NumberFormatException ignored) {}
        return v;
    }
}
