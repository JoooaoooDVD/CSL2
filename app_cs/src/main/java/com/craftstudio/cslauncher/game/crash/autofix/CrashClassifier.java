package com.craftstudio.cslauncher.game.crash.autofix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule based crash classifier. Works on the launcher log tail (plus the optional crash report /
 * hs_err summary) and produces one {@link CrashAnalysis}.
 *
 * <p>Design: every rule scans all lines, collects evidence and a score. Scores are not purely
 * "first match wins" because a crash usually leaves several symptoms (e.g. an OOM followed by a
 * generic exception). Rules with a concrete component (a named mod, a named option) outrank
 * generic ones. The class is Android free so it can be unit tested with fixture logs.</p>
 */
public final class CrashClassifier {

    /** Inputs that are not in the log but matter for classification. */
    public static final class Input {
        public final List<String> lines;
        public final int exitCode;
        public final boolean signal;
        /** Optional one-line summary from hs_err_pid*.log, may be null. */
        public final String hsErrSummary;
        /** Loader of the profile ("fabric", "forge", "neoforge", "quilt", "optifine" or ""). */
        public final String loader;

        public Input(List<String> lines, int exitCode, boolean signal, String hsErrSummary, String loader) {
            this.lines = lines == null ? Collections.<String>emptyList() : lines;
            this.exitCode = exitCode;
            this.signal = signal;
            this.hsErrSummary = hsErrSummary;
            this.loader = loader == null ? "" : loader.toLowerCase(Locale.ROOT);
        }
    }

    private static final int MAX_EVIDENCE = 4;

    // ---- patterns -------------------------------------------------------------------------
    private static final Pattern P_OOM_HEAP = Pattern.compile("java\\.lang\\.OutOfMemoryError(?::\\s*(.*))?");
    private static final Pattern P_OOM_NATIVE = Pattern.compile("(?i)(Cannot allocate memory|Native memory allocation|Out of Memory Error|unable to create (?:new )?native thread|GC overhead limit exceeded|There is insufficient memory for the Java Runtime)");

    private static final Pattern P_JVM_UNRECOGNIZED_VM = Pattern.compile("Unrecognized VM option '([^']+)'");
    private static final Pattern P_JVM_UNRECOGNIZED_OPT = Pattern.compile("Unrecognized option: (\\S+)");
    private static final Pattern P_JVM_IMPROPER = Pattern.compile("Improperly specified VM option '([^']+)'");
    private static final Pattern P_JVM_HEAP = Pattern.compile("(Invalid (?:maximum|initial) heap size: (\\S+)|Initial heap size set to a larger value than the maximum heap size|Too small (?:maximum|initial) heap)");
    private static final Pattern P_JVM_CREATE = Pattern.compile("(Could not create the Java Virtual Machine|Error: A fatal exception has occurred\\. Program will exit)");
    private static final Pattern P_JVM_CONFLICT = Pattern.compile("Conflicting collector combinations in option list|Multiple garbage collectors selected");

    private static final Pattern P_FABRIC_MISSING = Pattern.compile("Mod '([^']*)' \\(([\\w.\\-]+)\\)(?: [^ ]+)? requires (any version|version ([^ ]+)(?: or later)?|any [^ ]+ version) of ([\\w.\\-]+), which is missing");
    private static final Pattern P_FABRIC_MISSING2 = Pattern.compile("requires (?:any version|version ([^ ,]+)(?: or later)?) of ([\\w.\\-]+), which is missing");
    private static final Pattern P_FORGE_MISSING = Pattern.compile("Mod ID: '([\\w.\\-]+)', Requested by: '([\\w.\\-]+)', Expected range: '([^']*)', Actual version: '\\[MISSING\\]'");
    private static final Pattern P_FORGE_MISSING2 = Pattern.compile("Missing or unsupported mandatory dependencies:");
    private static final Pattern P_QUILT_MISSING = Pattern.compile("Depends on ([\\w.\\-]+)(?: versions? ([^ ,]+))?,? which is missing");

    private static final Pattern P_FABRIC_WRONG_VER = Pattern.compile("Mod '([^']*)' \\(([\\w.\\-]+)\\)(?: [^ ]+)? requires (?:any version|version ([^ ]+)(?: or later)?|any [^ ]+ version) of ([\\w.\\-]+), but only the wrong version is present: ([^!]+)!");
    private static final Pattern P_FORGE_WRONG_VER = Pattern.compile("Mod ID: '([\\w.\\-]+)', Requested by: '([\\w.\\-]+)', Expected range: '([^']*)', Actual version: '([^'\\[]+)'");
    private static final Pattern P_FABRIC_INCOMPAT = Pattern.compile("Mod '([^']*)' \\(([\\w.\\-]+)\\)(?: [^ ]+)? is incompatible with");
    private static final Pattern P_CLASS_VERSION = Pattern.compile("UnsupportedClassVersionError: (\\S+) has been compiled by a more recent version of the Java Runtime \\(class file version (\\d+)(?:\\.\\d+)?\\)");
    private static final Pattern P_CLASS_VERSION2 = Pattern.compile("UnsupportedClassVersionError: ([\\w/$.]+)");
    private static final Pattern P_JAVA_REQUIRED = Pattern.compile("requires (?:Java|java) (\\d+)(?: or (?:newer|later|higher))?");

    private static final Pattern P_MIXIN_FAIL = Pattern.compile("Mixin apply(?: for mod ([\\w.\\-]+))? failed ([\\w.\\-]+)\\.mixins\\.json|Mixin apply failed ([\\w.\\-]+)(?:\\.mixins)?\\.json|MixinApplyError|MixinTransformerError|InvalidMixinException|MixinTargetAlreadyLoadedException");
    private static final Pattern P_MIXIN_FROM_MOD = Pattern.compile("from mod ([\\w.\\-]+)");
    private static final Pattern P_MIXIN_CONFIG = Pattern.compile("([\\w.\\-]+?)(?:\\.mixins)?\\.json");
    private static final Pattern P_FORGE_MODFILE = Pattern.compile("Mod File: .*[/\\\\]mods[/\\\\]([^/\\\\\\s]+\\.jar)");
    private static final Pattern P_FAILED_LOAD_MOD = Pattern.compile("(?:Failed to load mod|Could not load mod|Failure loading mod|Error loading mod|Mod loading error) ?(?:'|\\()?([\\w.\\-]+)?");
    private static final Pattern P_DUPLICATE_MOD = Pattern.compile("Duplicate mod(?:s)? (?:found|id)[:!]? ?'?([\\w.\\-]+)?|Found duplicate mod id ([\\w.\\-]+)|has the same id as");
    private static final Pattern P_MOD_RESOLUTION = Pattern.compile("ModResolutionException|Incompatible mods? found|Mod resolution failed|Some of your mods are incompatible|net\\.fabricmc\\.loader\\.impl\\.FormattedException|ModLoadingException|LoadingFailedException");

    private static final Pattern P_CONFIG = Pattern.compile("(?i)(?:config[/\\\\]([\\w.\\- /\\\\]+?\\.(?:json5?|toml|cfg|properties|yaml|yml)))");
    private static final Pattern P_CONFIG_ERR = Pattern.compile("(?i)(JsonSyntaxException|MalformedJsonException|JsonParseException|ParsingException|ConfigurationException|Failed to (?:load|read|parse) config|Could not (?:load|read|parse) config|Error (?:loading|reading|parsing) config|Invalid config|Config(?:uration)? file .* (?:is )?(?:corrupt|invalid|malformed)|com\\.electronwill\\.nightconfig)");

    private static final Pattern P_LIB_ZIP = Pattern.compile("(zip END header not found|invalid LOC header|invalid CEN header|java\\.util\\.zip\\.ZipException|java\\.util\\.jar\\.JarException|Error: Could not find or load main class|ClassNotFoundException: net\\.minecraft\\.client\\.main\\.Main|Invalid or corrupt jarfile|error in opening zip file|Unable to (?:read|open) (?:jar|library)|SecurityException: Invalid signature file digest)");
    private static final Pattern P_LIB_PATH = Pattern.compile("((?:/[^/\\s:]+)*/(?:libraries|versions)/[^\\s:'\"]+\\.jar)");
    private static final Pattern P_LWJGL_CLASS = Pattern.compile("NoClassDefFoundError: org/lwjgl/");

    private static final Pattern P_GFX = Pattern.compile("(GLFW error|Failed to create (?:an )?(?:OpenGL|EGL|GL) context|eglCreateContext|eglMakeCurrent failed|EGL_BAD_|Could not create GL context|Unsupported OpenGL version|OpenGL (?:3\\.2|4\\.\\d|ES 3\\.\\d) (?:is )?(?:not supported|required)|GL_INVALID_|Failed to initialize (?:renderer|OpenGL|graphics)|libGL\\.so|libgl4es|libOSMesa|zink|virgl|vulkan.*(?:failed|unsupported|not available)|VK_ERROR_|No suitable (?:GPU|device)|Pixel format not accelerated|Failed to load renderer|renderer .* (?:failed|could not)|GLFWError|GlStateManager|glfwInit)");
    private static final Pattern P_GFX_WEAK = Pattern.compile("(RenderSystem|Rendering (?:overlay|screen)|Unexpected error rendering|Tesselating block model|Rendering entity in world|GameRenderer|Rendering Block Entity|LevelRenderer|Shader)");

    private static final Pattern P_SHADER = Pattern.compile("(?i)(Failed to (?:load|compile|link) shader|shader (?:compilation|compile|link) (?:error|failed)|net\\.irisshaders|net\\.coderbot\\.iris|iris\\.(?:pipeline|gl)|ShaderPack|shaderpack|optifine\\.shaders|Shaders\\.(?:java|init|load)|Could not reload shader|Error compiling shader|GLSL (?:compile|link) error|ShaderInstance|Failed to apply shader program|invalid shader program|compileShaderInternal)");
    private static final Pattern P_SHADER_PACK = Pattern.compile("(?i)(?:shader ?pack|loading shaderpack|shaderpack=|pack=)[\\s:\"']*([^\\s\"',]+\\.zip|[\\w.\\- +()]+?)(?:[\"',\\s]|$)");

    private static final Pattern P_RP = Pattern.compile("(?i)(Failed to load (?:resource|data) ?pack|Unable to load pack|Failed to (?:open|read) (?:resource)?pack|PackResources|Could not load (?:resource)?pack|Invalid (?:resource)?pack|Exception (?:loading|reading) (?:resource)?pack|Resource pack .* (?:failed|invalid|corrupt)|ResourceLoadStateTracker|Realms?ResourcePack|ResourcePackRepository|Failed to reload resources|Missing or invalid pack)");
    private static final Pattern P_RP_NAME = Pattern.compile("(?i)(?:pack|resource pack|file)[\\s:\"']*((?:file/)?[^\\s\"',]+\\.zip)|Pack \"([^\"]+)\"|pack '([^']+)'|file/([^\\s\"',]+)");

    private static final Pattern P_NATIVE = Pattern.compile("(UnsatisfiedLinkError|dlopen failed|library \"[^\"]+\" not found|cannot locate symbol|has text relocations|SIGSEGV|SIGBUS|SIGILL|SIGABRT|SIGFPE|Fatal signal|A fatal error has been detected by the Java Runtime Environment|Problematic frame|EXCEPTION_ACCESS_VIOLATION|libc\\.so .* abort|libjli|libjvm\\.so)");
    private static final Pattern P_NATIVE_LIB = Pattern.compile("(lib[\\w.\\-+]+\\.so(?:\\.[\\d.]+)?)");

    private static final Pattern P_DISK = Pattern.compile("(No space left on device|ENOSPC|Read-only file system|EROFS|Disk quota exceeded|EDQUOT|Not enough (?:disk )?space|Insufficient (?:disk )?space|java\\.io\\.IOException: write failed|EACCES \\(Permission denied\\)|Permission denied\\)|Operation not permitted\\)|FileSystemException: .*: Input/output error|Structure needs cleaning)");
    private static final Pattern P_NET = Pattern.compile("(UnknownHostException|SocketTimeoutException|ConnectException|SSLHandshakeException|SSLException|Connection reset|Connection refused|Network is unreachable|No address associated with hostname|Unable to resolve host|Failed to download|Download failed|Could not download|Failed to fetch|HTTP (?:4\\d\\d|5\\d\\d)|Server returned HTTP response code|Software caused connection abort|timed out|ETIMEDOUT|ENETUNREACH|ECONNRESET|resources\\.download\\.minecraft\\.net|piston-(?:data|meta)\\.mojang\\.com|libraries\\.minecraft\\.net|maven\\.fabricmc\\.net|maven\\.minecraftforge\\.net|maven\\.neoforged\\.net)");

    private static final Pattern P_JAVA_GENERIC = Pattern.compile("(java\\.lang\\.(?:NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|AbstractMethodError|IncompatibleClassChangeError|IllegalAccessError|VerifyError|ClassCastException|ExceptionInInitializerError|BootstrapMethodError|LinkageError))(?::\\s*(\\S+))?");

    private CrashClassifier() {}

    // ---- public entry -------------------------------------------------------------------

    public static CrashAnalysis analyze(Input in) {
        List<String> lines = in.lines;
        // Only the last part of the log matters, but the loader prints its dependency summary
        // early; keep everything that fits and strip timestamps for matching.
        List<String> clean = new ArrayList<>(lines.size());
        for (String l : lines) clean.add(stripPrefix(l));

        // --- 1. Launcher-level failures that shadow everything else -----------------------
        CrashAnalysis r;
        if ((r = jvmArgument(clean)) != null) return r;
        if ((r = missingDependency(clean, in.loader)) != null) return r;
        if ((r = incompatibleModVersion(clean, in.loader)) != null) return r;
        if ((r = javaRuntime(clean)) != null) return r;
        if ((r = outOfMemory(clean, in)) != null) return r;
        if ((r = modConflict(clean, in.loader)) != null) return r;
        if ((r = corruptedConfig(clean)) != null) return r;
        if ((r = shader(clean)) != null) return r;
        if ((r = resourcePack(clean)) != null) return r;
        if ((r = corruptedLibrary(clean)) != null) return r;
        if ((r = nativeLibrary(clean, in)) != null) return r;
        if ((r = graphics(clean)) != null) return r;
        if ((r = disk(clean)) != null) return r;
        if ((r = network(clean)) != null) return r;
        if ((r = genericJava(clean)) != null) return r;
        if ((r = signalFallback(clean, in)) != null) return r;
        return CrashAnalysis.unknown(lastErrorLines(clean));
    }

    // ---- rules ---------------------------------------------------------------------------

    private static CrashAnalysis jvmArgument(List<String> lines) {
        Set<String> tokens = new LinkedHashSet<>();
        List<String> ev = new ArrayList<>();
        boolean create = false, heap = false, conflict = false;
        for (String l : lines) {
            Matcher m;
            if ((m = P_JVM_UNRECOGNIZED_VM.matcher(l)).find()) { tokens.add("-XX:" + stripXx(m.group(1))); add(ev, l); }
            else if ((m = P_JVM_IMPROPER.matcher(l)).find()) { tokens.add("-XX:" + stripXx(m.group(1))); add(ev, l); }
            else if ((m = P_JVM_UNRECOGNIZED_OPT.matcher(l)).find()) { tokens.add(m.group(1)); add(ev, l); }
            else if ((m = P_JVM_HEAP.matcher(l)).find()) { heap = true; if (m.group(2) != null) tokens.add(m.group(2)); add(ev, l); }
            else if (P_JVM_CONFLICT.matcher(l).find()) { conflict = true; add(ev, l); }
            else if (P_JVM_CREATE.matcher(l).find()) { create = true; add(ev, l); }
        }
        if (tokens.isEmpty() && !heap && !conflict) {
            // "Could not create the JVM" alone, without a named option, is ambiguous (can be memory)
            return null;
        }
        List<RepairStep> plan = new ArrayList<>();
        String component;
        if (!tokens.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String t : tokens) {
                plan.add(new RepairStep(RepairStep.Kind.PRUNE_JVM_ARGS, t));
                if (sb.length() > 0) sb.append(' ');
                sb.append(t);
            }
            component = sb.toString();
        } else if (heap) {
            plan.add(new RepairStep(RepairStep.Kind.PRUNE_JVM_ARGS, "-Xmx", "heap"));
            plan.add(new RepairStep(RepairStep.Kind.PRUNE_JVM_ARGS, "-Xms", "heap"));
            component = "-Xmx/-Xms";
        } else {
            plan.add(new RepairStep(RepairStep.Kind.PRUNE_JVM_ARGS, "-XX:+Use", "gc-conflict"));
            component = "garbage collector flags";
        }
        float conf = tokens.isEmpty() ? 0.8f : 0.97f;
        if (create) conf = Math.min(1f, conf + 0.02f);
        return new CrashAnalysis(CrashCategory.INVALID_JVM_ARGUMENT, conf,
                "The JVM rejected a custom argument before the game started", ev, component, plan, true, false, true);
    }

    private static CrashAnalysis missingDependency(List<String> lines, String loader) {
        List<String> ev = new ArrayList<>();
        List<RepairStep> plan = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String firstDep = null;
        boolean forgeHeader = false;
        for (String l : lines) {
            Matcher m;
            if ((m = P_FABRIC_MISSING.matcher(l)).find()) {
                String dep = m.group(5), req = m.group(4) == null ? "" : m.group(4);
                if (seen.add(dep)) { plan.add(new RepairStep(RepairStep.Kind.INSTALL_DEPENDENCY, dep, req)); add(ev, l); if (firstDep == null) firstDep = dep; }
            } else if ((m = P_FABRIC_MISSING2.matcher(l)).find()) {
                String dep = m.group(2), req = m.group(1) == null ? "" : m.group(1);
                if (seen.add(dep)) { plan.add(new RepairStep(RepairStep.Kind.INSTALL_DEPENDENCY, dep, req)); add(ev, l); if (firstDep == null) firstDep = dep; }
            } else if ((m = P_FORGE_MISSING.matcher(l)).find()) {
                String dep = m.group(1), req = m.group(3);
                if (seen.add(dep)) { plan.add(new RepairStep(RepairStep.Kind.INSTALL_DEPENDENCY, dep, req)); add(ev, l); if (firstDep == null) firstDep = dep; }
            } else if ((m = P_QUILT_MISSING.matcher(l)).find()) {
                String dep = m.group(1), req = m.group(2) == null ? "" : m.group(2);
                if (seen.add(dep)) { plan.add(new RepairStep(RepairStep.Kind.INSTALL_DEPENDENCY, dep, req)); add(ev, l); if (firstDep == null) firstDep = dep; }
            } else if (P_FORGE_MISSING2.matcher(l).find()) {
                forgeHeader = true; add(ev, l);
            }
        }
        if (plan.isEmpty()) {
            if (forgeHeader) {
                return new CrashAnalysis(CrashCategory.MISSING_DEPENDENCY, 0.7f, "The loader reported missing mandatory dependencies but did not name them in the log tail",
                        ev, "", Collections.<RepairStep>emptyList(), false, false, false);
            }
            return null;
        }
        // Things that are not mods cannot be downloaded; they stay in the plan only if resolvable.
        List<RepairStep> filtered = new ArrayList<>();
        boolean loaderMissing = false;
        for (RepairStep s : plan) {
            String id = s.target.toLowerCase(Locale.ROOT);
            if (id.equals("minecraft") || id.equals("java") || id.equals("fabricloader") || id.equals("quilt_loader")
                    || id.equals("forge") || id.equals("neoforge")) { loaderMissing = true; continue; }
            filtered.add(s);
        }
        String component = firstDep == null ? "" : firstDep;
        if (filtered.isEmpty()) {
            return new CrashAnalysis(CrashCategory.INCOMPATIBLE_MOD_VERSION, 0.85f,
                    "A mod requires a different Minecraft / loader version than this profile uses", ev, component,
                    Collections.<RepairStep>emptyList(), false, false, false);
        }
        return new CrashAnalysis(CrashCategory.MISSING_DEPENDENCY, 0.96f,
                "A mod depends on another mod that is not installed" + (loaderMissing ? " (plus a loader/game version requirement)" : ""),
                ev, component, filtered, true, false, true);
    }

    private static CrashAnalysis incompatibleModVersion(List<String> lines, String loader) {
        List<String> ev = new ArrayList<>();
        String modId = null, modName = null;
        boolean forgeWrong = false;
        for (String l : lines) {
            Matcher m;
            if ((m = P_FABRIC_WRONG_VER.matcher(l)).find()) {
                add(ev, l);
                String dep = m.group(4);
                if (modId == null) {
                    // if the wrong version is the game or loader itself, the mod is the odd one out
                    modId = m.group(2); modName = m.group(1);
                    if (!(dep.equals("minecraft") || dep.equals("fabricloader") || dep.equals("java"))) {
                        // dependency on another mod with wrong version: still the requesting mod is what we disable (safer)
                    }
                }
            } else if ((m = P_FORGE_WRONG_VER.matcher(l)).find()) {
                add(ev, l); forgeWrong = true;
                if (modId == null) modId = m.group(2);
            } else if ((m = P_FABRIC_INCOMPAT.matcher(l)).find()) {
                add(ev, l);
                if (modId == null) { modId = m.group(2); modName = m.group(1); }
            }
        }
        if (ev.isEmpty()) return null;
        List<RepairStep> plan = new ArrayList<>();
        if (modId != null) plan.add(new RepairStep(RepairStep.Kind.DISABLE_MOD, modId, modName == null ? "" : modName));
        return new CrashAnalysis(CrashCategory.INCOMPATIBLE_MOD_VERSION, modId == null ? 0.7f : 0.92f,
                "A mod was built for another game or loader version", ev, modId == null ? "" : modId, plan,
                modId != null, true, true);
    }

    private static CrashAnalysis javaRuntime(List<String> lines) {
        List<String> ev = new ArrayList<>();
        int required = 0;
        String cls = null;
        for (String l : lines) {
            Matcher m;
            if ((m = P_CLASS_VERSION.matcher(l)).find()) {
                add(ev, l);
                int cf = parseInt(m.group(2));
                if (cf >= 45) required = Math.max(required, cf - 44);
                if (cls == null) cls = m.group(1);
            } else if ((m = P_CLASS_VERSION2.matcher(l)).find()) {
                add(ev, l);
                if (cls == null) cls = m.group(1);
            } else if ((m = P_JAVA_REQUIRED.matcher(l)).find() && l.toLowerCase(Locale.ROOT).contains("java")) {
                add(ev, l);
                required = Math.max(required, parseInt(m.group(1)));
            }
        }
        if (ev.isEmpty()) return null;
        List<RepairStep> plan = new ArrayList<>();
        if (required > 0) plan.add(new RepairStep(RepairStep.Kind.SELECT_RUNTIME, String.valueOf(required)));
        return new CrashAnalysis(CrashCategory.JAVA_RUNTIME_ERROR, required > 0 ? 0.95f : 0.8f,
                required > 0 ? "The game or a mod needs Java " + required + " or newer" : "A class was compiled for a newer Java than the selected runtime",
                ev, cls == null ? (required > 0 ? "Java " + required : "") : cls, plan, required > 0, false, true);
    }

    private static CrashAnalysis outOfMemory(List<String> lines, Input in) {
        List<String> ev = new ArrayList<>();
        boolean heap = false, nativeOom = false, metaspace = false;
        for (String l : lines) {
            Matcher m = P_OOM_HEAP.matcher(l);
            if (m.find()) {
                add(ev, l);
                String what = m.group(1) == null ? "" : m.group(1).toLowerCase(Locale.ROOT);
                if (what.contains("metaspace")) metaspace = true;
                else if (what.contains("native") || what.contains("thread") || what.contains("direct buffer")) nativeOom = true;
                else heap = true;
            } else if (P_OOM_NATIVE.matcher(l).find()) {
                add(ev, l); nativeOom = true;
            }
        }
        if (in.hsErrSummary != null) {
            String h = in.hsErrSummary.toLowerCase(Locale.ROOT);
            if (h.contains("insufficient memory") || h.contains("out of memory") || h.contains("cannot allocate")) { nativeOom = true; add(ev, in.hsErrSummary); }
        }
        if (ev.isEmpty()) {
            // Android low-memory killer: SIGKILL with no Java error at all. Only claim it when the
            // tail is quiet (no other exception) so real crashes keep their own category.
            if (in.signal && in.exitCode == 9 && !containsException(lines)) {
                List<RepairStep> plan = new ArrayList<>();
                plan.add(new RepairStep(RepairStep.Kind.ADJUST_MEMORY, "", "native"));
                return new CrashAnalysis(CrashCategory.OUT_OF_MEMORY, 0.6f,
                        "The process was killed by the system (SIGKILL) without a Java error - typically Android reclaiming memory",
                        lastErrorLines(lines), "", plan, true, false, true);
            }
            return null;
        }
        List<RepairStep> plan = new ArrayList<>();
        String detail = nativeOom && !heap ? "native" : (metaspace && !heap ? "metaspace" : "heap");
        plan.add(new RepairStep(RepairStep.Kind.ADJUST_MEMORY, "", detail));
        return new CrashAnalysis(CrashCategory.OUT_OF_MEMORY, 0.95f,
                "heap".equals(detail) ? "The Java heap ran out of memory" : "native".equals(detail) ? "The process ran out of native memory (allocation too close to the device limit)" : "Metaspace exhausted (too many mod classes)",
                ev, detail, plan, true, false, true);
    }

    private static CrashAnalysis modConflict(List<String> lines, String loader) {
        List<String> ev = new ArrayList<>();
        String modId = null, jar = null;
        boolean resolution = false, mixin = false, duplicate = false;
        for (String l : lines) {
            Matcher m;
            if ((m = P_MIXIN_FAIL.matcher(l)).find()) {
                mixin = true; add(ev, l);
                if (modId == null) {
                    if (m.group(1) != null) modId = m.group(1);
                    else if (m.group(2) != null) modId = m.group(2);
                    else if (m.group(3) != null) modId = m.group(3);
                }
                Matcher fm = P_MIXIN_FROM_MOD.matcher(l);
                if (fm.find()) modId = fm.group(1);
            } else if ((m = P_FORGE_MODFILE.matcher(l)).find()) {
                if (jar == null) jar = m.group(1); add(ev, l);
            } else if ((m = P_DUPLICATE_MOD.matcher(l)).find()) {
                duplicate = true; add(ev, l);
                if (modId == null) modId = m.group(1) != null ? m.group(1) : m.group(2);
            } else if (P_MOD_RESOLUTION.matcher(l).find()) {
                resolution = true; add(ev, l);
            } else if ((m = P_FAILED_LOAD_MOD.matcher(l)).find()) {
                resolution = true; add(ev, l);
                if (modId == null && m.group(1) != null && !m.group(1).equalsIgnoreCase("file")) modId = m.group(1);
            }
        }
        if (!(mixin || duplicate || resolution)) return null;
        // "from mod X" anywhere in the tail (Mixin reports it on a separate line)
        if (modId == null) {
            for (String l : lines) {
                Matcher fm = P_MIXIN_FROM_MOD.matcher(l);
                if (fm.find()) { modId = fm.group(1); break; }
            }
        }
        if (modId != null && (modId.equals("minecraft") || modId.equals("fabricloader") || modId.equals("fabric") || modId.equals("java") || modId.equals("forge") || modId.equals("neoforge") || modId.equals("mixinextras"))) modId = null;
        List<RepairStep> plan = new ArrayList<>();
        String component = "";
        if (modId != null) { plan.add(new RepairStep(RepairStep.Kind.DISABLE_MOD, modId, "mixin")); component = modId; }
        else if (jar != null) { plan.add(new RepairStep(RepairStep.Kind.DISABLE_MOD, jar, "jar")); component = jar; }
        float conf = !plan.isEmpty() ? 0.9f : (mixin ? 0.75f : 0.6f);
        return new CrashAnalysis(CrashCategory.MOD_CONFLICT, conf,
                mixin ? "A mod's Mixin could not be applied (conflicts with the game version or another mod)" :
                        duplicate ? "Two copies of the same mod are installed" : "The mod loader could not resolve the installed mods",
                ev, component, plan, !plan.isEmpty(), true, true);
    }

    private static CrashAnalysis corruptedLibrary(List<String> lines) {
        List<String> ev = new ArrayList<>();
        String path = null;
        boolean strong = false;
        for (String l : lines) {
            Matcher m;
            if (P_LIB_ZIP.matcher(l).find()) {
                strong = true; add(ev, l);
                if ((m = P_LIB_PATH.matcher(l)).find() && path == null) path = m.group(1);
            } else if (P_LWJGL_CLASS.matcher(l).find()) {
                add(ev, l);
            }
        }
        if (ev.isEmpty()) return null;
        if (path == null) {
            // the launcher logs the jar it was opening on its own line
            for (String l : lines) {
                Matcher m = P_LIB_PATH.matcher(l);
                if (m.find()) { path = m.group(1); add(ev, l); break; }
            }
        }
        List<RepairStep> plan = new ArrayList<>();
        plan.add(new RepairStep(RepairStep.Kind.VERIFY_LIBRARIES, path == null ? "" : path));
        return new CrashAnalysis(CrashCategory.CORRUPTED_LIBRARY, strong ? 0.9f : 0.6f,
                "A game library or the client jar is corrupt or incomplete", ev, path == null ? "" : shortName(path), plan, true, false, true);
    }

    private static CrashAnalysis corruptedConfig(List<String> lines) {
        List<String> ev = new ArrayList<>();
        String file = null;
        boolean err = false;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            Matcher m;
            boolean e = P_CONFIG_ERR.matcher(l).find();
            if (e) { err = true; add(ev, l); }
            if ((m = P_CONFIG.matcher(l)).find()) {
                // the file name may sit on the same line or on one of the next few lines
                if (e || err && file == null && isNear(lines, i)) {
                    if (file == null) { file = m.group(1).replace('\\', '/'); add(ev, l); }
                }
            }
        }
        if (!err) return null;
        if (file == null) {
            // JSON syntax errors without a config path are more likely a mod / world problem
            boolean nightConfig = false;
            for (String e : ev) if (e.contains("nightconfig") || e.toLowerCase(Locale.ROOT).contains("config")) nightConfig = true;
            if (!nightConfig) return null;
            return new CrashAnalysis(CrashCategory.CORRUPTED_CONFIGURATION, 0.55f,
                    "A configuration file could not be parsed, but the log does not name it", ev, "",
                    Collections.<RepairStep>emptyList(), false, false, false);
        }
        List<RepairStep> plan = new ArrayList<>();
        plan.add(new RepairStep(RepairStep.Kind.RESET_CONFIG, file));
        return new CrashAnalysis(CrashCategory.CORRUPTED_CONFIGURATION, 0.9f,
                "A mod configuration file is malformed", ev, "config/" + file, plan, true, true, true);
    }

    private static boolean isNear(List<String> lines, int i) {
        for (int k = Math.max(0, i - 3); k < i; k++) if (P_CONFIG_ERR.matcher(lines.get(k)).find()) return true;
        return false;
    }

    private static CrashAnalysis shader(List<String> lines) {
        List<String> ev = new ArrayList<>();
        String pack = null;
        for (String l : lines) {
            if (P_SHADER.matcher(l).find()) {
                add(ev, l);
                Matcher m = P_SHADER_PACK.matcher(l);
                if (pack == null && m.find()) { String p = m.group(1); if (p != null && !p.equalsIgnoreCase("none") && !p.equalsIgnoreCase("off") && !p.equalsIgnoreCase("(internal)")) pack = p; }
            }
        }
        if (ev.size() < 1) return null;
        // One mention of "Shader" is weak (vanilla core shaders log too); require an error-ish context.
        boolean errorish = false;
        for (String e : ev) {
            String low = e.toLowerCase(Locale.ROOT);
            if (low.contains("error") || low.contains("exception") || low.contains("failed") || low.contains("caused by") || low.contains("\tat ")) { errorish = true; break; }
        }
        if (!errorish) return null;
        List<RepairStep> plan = new ArrayList<>();
        plan.add(new RepairStep(RepairStep.Kind.DISABLE_SHADERS, pack == null ? "" : pack));
        return new CrashAnalysis(CrashCategory.SHADER_ERROR, 0.85f,
                "A shader pack failed to compile or crashed the renderer", ev, pack == null ? "" : pack, plan, true, false, true);
    }

    private static CrashAnalysis resourcePack(List<String> lines) {
        List<String> ev = new ArrayList<>();
        String pack = null;
        for (String l : lines) {
            if (P_RP.matcher(l).find()) {
                add(ev, l);
                if (pack == null) {
                    Matcher m = P_RP_NAME.matcher(l);
                    if (m.find()) {
                        for (int g = 1; g <= m.groupCount(); g++) if (m.group(g) != null) { pack = m.group(g); break; }
                    }
                }
            }
        }
        if (ev.isEmpty()) return null;
        if (pack != null && pack.startsWith("file/")) pack = pack.substring(5);
        List<RepairStep> plan = new ArrayList<>();
        plan.add(new RepairStep(RepairStep.Kind.DISABLE_RESOURCE_PACK, pack == null ? "" : pack));
        return new CrashAnalysis(CrashCategory.RESOURCE_PACK_ERROR, pack == null ? 0.7f : 0.88f,
                "A resource pack could not be loaded", ev, pack == null ? "" : pack, plan, true, pack == null, true);
    }

    private static CrashAnalysis graphics(List<String> lines) {
        List<String> ev = new ArrayList<>();
        int strong = 0, weak = 0;
        for (String l : lines) {
            if (P_GFX.matcher(l).find()) { strong++; add(ev, l); }
            else if (P_GFX_WEAK.matcher(l).find() && (l.contains("Exception") || l.contains("error") || l.contains("Error"))) { weak++; add(ev, l); }
        }
        if (strong == 0 && weak < 2) return null;
        List<RepairStep> plan = new ArrayList<>();
        plan.add(new RepairStep(RepairStep.Kind.FALLBACK_RENDERER));
        return new CrashAnalysis(CrashCategory.GRAPHICS_INITIALIZATION_ERROR, strong > 0 ? 0.85f : 0.55f,
                strong > 0 ? "The graphics renderer failed to initialise on this device" : "The game crashed inside the renderer",
                ev, "renderer", plan, true, strong == 0, true);
    }

    private static CrashAnalysis disk(List<String> lines) {
        List<String> ev = new ArrayList<>();
        for (String l : lines) if (P_DISK.matcher(l).find()) add(ev, l);
        if (ev.isEmpty()) return null;
        return new CrashAnalysis(CrashCategory.DISK_OR_STORAGE_ERROR, 0.9f,
                "The game could not write to storage (full disk or missing permission)", ev, "storage",
                Collections.<RepairStep>emptyList(), false, false, false);
    }

    private static CrashAnalysis network(List<String> lines) {
        List<String> ev = new ArrayList<>();
        for (String l : lines) if (P_NET.matcher(l).find()) add(ev, l);
        if (ev.isEmpty()) return null;
        // Network errors while playing (server disconnect) are not launch failures.
        boolean launchPhase = false;
        for (String e : ev) {
            String low = e.toLowerCase(Locale.ROOT);
            if (low.contains("download") || low.contains("mojang") || low.contains("minecraft.net") || low.contains("maven") || low.contains("fetch") || low.contains("unknownhost") || low.contains("resolve host")) launchPhase = true;
        }
        if (!launchPhase) return null;
        List<RepairStep> plan = new ArrayList<>();
        plan.add(new RepairStep(RepairStep.Kind.CHECK_NETWORK));
        return new CrashAnalysis(CrashCategory.NETWORK_DOWNLOAD_ERROR, 0.85f,
                "Game files could not be downloaded", ev, "network", plan, true, false, true);
    }

    private static CrashAnalysis nativeLibrary(List<String> lines, Input in) {
        List<String> ev = new ArrayList<>();
        String lib = null;
        for (String l : lines) {
            if (P_NATIVE.matcher(l).find()) {
                add(ev, l);
                Matcher m = P_NATIVE_LIB.matcher(l);
                if (lib == null && m.find()) lib = m.group(1);
            }
        }
        if (in.hsErrSummary != null) {
            add(ev, in.hsErrSummary);
            Matcher m = P_NATIVE_LIB.matcher(in.hsErrSummary);
            if (lib == null && m.find()) lib = m.group(1);
        }
        if (ev.isEmpty()) return null;
        boolean rendererLib = lib != null && (lib.contains("gl4es") || lib.contains("OSMesa") || lib.contains("libGL") || lib.contains("zink") || lib.contains("vulkan") || lib.contains("EGL") || lib.contains("ltw") || lib.contains("angle") || lib.contains("virgl") || lib.contains("lwjgl_opengl"));
        List<RepairStep> plan = new ArrayList<>();
        if (rendererLib) plan.add(new RepairStep(RepairStep.Kind.FALLBACK_RENDERER, lib));
        return new CrashAnalysis(CrashCategory.NATIVE_LIBRARY_ERROR, lib == null ? 0.65f : 0.8f,
                "A native library crashed or could not be loaded",
                ev, lib == null ? "" : lib, plan, rendererLib, rendererLib, true);
    }

    /** Signal death with no recognisable marker at all: report it honestly, propose nothing. */
    private static CrashAnalysis signalFallback(List<String> lines, Input in) {
        if (!in.signal) return null;
        return new CrashAnalysis(CrashCategory.NATIVE_LIBRARY_ERROR, 0.4f,
                "The process died from signal " + in.exitCode + " without a Java stack trace",
                lastErrorLines(lines), "", Collections.<RepairStep>emptyList(), false, false, true);
    }

    private static CrashAnalysis genericJava(List<String> lines) {
        List<String> ev = new ArrayList<>();
        String cls = null;
        for (String l : lines) {
            Matcher m = P_JAVA_GENERIC.matcher(l);
            if (m.find()) { add(ev, l); if (cls == null && m.group(2) != null) cls = m.group(2); }
        }
        if (ev.isEmpty()) return null;
        // Linkage errors almost always come from a mod built against another version. Without a
        // mod id we cannot pick one safely, so no plan.
        return new CrashAnalysis(CrashCategory.JAVA_RUNTIME_ERROR, 0.6f,
                "A Java linkage error happened (a mod calls code that does not exist in this game version)",
                ev, cls == null ? "" : cls, Collections.<RepairStep>emptyList(), false, false, false);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static final Pattern P_PREFIX = Pattern.compile("^\\[[^\\]]*\\] \\[[^\\]]*\\]:? ?(?:\\[[^\\]]*\\]:? ?)?");

    /** Removes the "[12:00:00] [Render thread/ERROR]:" style prefix so patterns see the message. */
    static String stripPrefix(String line) {
        if (line == null) return "";
        String l = line;
        if (l.startsWith("[")) {
            Matcher m = P_PREFIX.matcher(l);
            if (m.find()) l = l.substring(m.end());
        }
        return l;
    }

    private static String stripXx(String opt) {
        if (opt == null) return "";
        String o = opt.trim();
        if (o.startsWith("-XX:")) o = o.substring(4);
        return o;
    }

    private static void add(List<String> ev, String line) {
        if (line == null) return;
        String t = line.trim();
        if (t.isEmpty() || ev.contains(t)) return;
        if (ev.size() >= MAX_EVIDENCE) return;
        ev.add(t.length() > 300 ? t.substring(0, 300) : t);
    }

    private static boolean containsException(List<String> lines) {
        for (String l : lines) if (l.contains("Exception") || l.contains("Error:") || l.contains("\tat ")) return true;
        return false;
    }

    private static List<String> lastErrorLines(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && out.size() < MAX_EVIDENCE; i--) {
            String l = lines.get(i).trim();
            if (l.isEmpty() || l.startsWith("at ")) continue;
            if (l.contains("Exception") || l.contains("Error") || l.contains("ERROR") || l.contains("FATAL") || l.contains("Caused by")) out.add(0, l.length() > 300 ? l.substring(0, 300) : l);
        }
        return out;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String shortName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }
}
