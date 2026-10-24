package com.craftstudio.cslauncher.game.crash.autofix.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Context handed to an {@link AiDiagnosticProvider}; never contains account data or absolute paths. */
public final class AiRequest {
    public final String loader;
    public final String minecraftVersion;
    public final int exitCode;
    public final boolean signal;
    /** Mod ids installed in the profile (enabled), used to whitelist DISABLE_MOD targets. */
    public final List<String> installedModIds;
    /** Resource pack entries currently enabled in options.txt. */
    public final List<String> enabledResourcePacks;
    /** Config file names (relative to config/). */
    public final List<String> configFiles;

    public AiRequest(String loader, String minecraftVersion, int exitCode, boolean signal,
                     List<String> installedModIds, List<String> enabledResourcePacks, List<String> configFiles) {
        this.loader = loader == null ? "" : loader;
        this.minecraftVersion = minecraftVersion == null ? "" : minecraftVersion;
        this.exitCode = exitCode;
        this.signal = signal;
        this.installedModIds = Collections.unmodifiableList(new ArrayList<>(installedModIds == null ? Collections.<String>emptyList() : installedModIds));
        this.enabledResourcePacks = Collections.unmodifiableList(new ArrayList<>(enabledResourcePacks == null ? Collections.<String>emptyList() : enabledResourcePacks));
        this.configFiles = Collections.unmodifiableList(new ArrayList<>(configFiles == null ? Collections.<String>emptyList() : configFiles));
    }

    /** The instruction block a provider should send along with the lines. */
    public String schemaPrompt() {
        return "Analyse the Minecraft crash log and answer with ONLY a JSON object of this exact shape: "
                + "{\"category\": one of " + java.util.Arrays.toString(com.craftstudio.cslauncher.game.crash.autofix.CrashCategory.values())
                + ", \"confidence\": number 0..1, \"explanation\": short string, \"component\": string or empty, "
                + "\"actions\": [{\"kind\": one of " + java.util.Arrays.toString(com.craftstudio.cslauncher.game.crash.autofix.RepairStep.Kind.values())
                + ", \"target\": string, \"detail\": string}]}. "
                + "Targets for DISABLE_MOD must be one of the installed mod ids " + installedModIds
                + "; for DISABLE_RESOURCE_PACK one of " + enabledResourcePacks
                + "; for RESET_CONFIG one of " + configFiles + ". Do not include anything else.";
    }
}
