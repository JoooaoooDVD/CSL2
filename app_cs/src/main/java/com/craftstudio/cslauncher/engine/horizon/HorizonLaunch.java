package com.craftstudio.cslauncher.engine.horizon;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.authenticator.accounts.Account;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@code cs_launch} request for {@link HorizonGameActivity}.
 *
 * Account hand-off follows Horizon's Launch API (docs/api/launch.md): a session file
 * {@code {name, uuid, access_token, user_type:"msa", xuid}} with mode 0600 that the game reads once and
 * deletes. Only a Microsoft account with a real Minecraft profile is handed over; local accounts play
 * offline with {@code --name}. Nothing here bypasses Microsoft authentication.
 */
public final class HorizonLaunch {
    public static final String EXTRA = "cs_launch";
    public static final String RENDERER_VULKAN = "vulkan";
    public static final String RENDERER_VULKAN_COMPAT = "vulkan-compatibility";
    public static final String RENDERER_OPENGL = "opengl";

    public final String profileKey;
    /** The profile's own game folder (saves, options.txt, packs, shaders, .hmod mods). */
    public final File gameHome;
    /** Resolution scale 25–100, 0 = engine default. */
    public int renderScale;
    @Nullable public String server;
    public String renderer = "";
    public final List<String> extraArguments = new ArrayList<>();
    /** Control layout file for the CS overlay (null = launcher default). */
    @Nullable public String controlsPath;

    public HorizonLaunch(@NonNull String profileKey, @NonNull File gameHome) {
        this.profileKey = profileKey;
        this.gameHome = gameHome;
    }

    /** The intent that starts the engine for this request. */
    @NonNull
    public Intent intent(@NonNull Context context, @Nullable Account account) throws IOException, JSONException {
        File engineData = HorizonEngine.stageEngineData(context);
        File instance = gameHome;
        if (!instance.isDirectory() && !instance.mkdirs()) throw new IOException("Cannot create " + instance);
        JSONObject json = new JSONObject();
        json.put("home", HorizonEngine.home().getAbsolutePath());
        json.put("cache", HorizonEngine.cacheDir(context).getAbsolutePath());
        json.put("game_home", instance.getAbsolutePath());
        json.put("resources", HorizonEngine.resourcesDir().getAbsolutePath());
        json.put("engine_data", engineData.getAbsolutePath());
        if (account != null && account.isMicrosoft && hasProfile(account)) {
            json.put("session_file", writeSessionFile(account).getAbsolutePath());
        } else if (account != null) {
            json.put("offline_name", account.username);
        }
        if (server != null && !server.isEmpty()) json.put("server", server);
        if (!renderer.isEmpty()) json.put("renderer", renderer);
        json.put("extra_arguments", new JSONArray(extraArguments));
        // CS control system drives the game; the engine draws no touch controls of its own.
        json.put("host_input", true);
        if (renderScale >= 25 && renderScale <= 100) json.put("render_scale", renderScale);
        // Skin & Cape: the profile's (else the account's) selection from the CS skin library.
        String accountKey = HorizonSkins.accountKey(account);
        HorizonSkins.putLaunch(json, HorizonSkins.resolve(profileKey, accountKey));
        Intent intent = new Intent(context, HorizonGameActivity.class);
        intent.putExtra(EXTRA, json.toString());
        intent.putExtra(HorizonGameActivity.EXTRA_PROFILE, profileKey);
        if (account != null) {
            intent.putExtra(HorizonGameActivity.EXTRA_ACCOUNT_NAME, account.username);
            intent.putExtra(HorizonGameActivity.EXTRA_ACCOUNT_MICROSOFT, account.isMicrosoft && hasProfile(account));
            intent.putExtra(HorizonGameActivity.EXTRA_ACCOUNT_KEY, accountKey);
        }
        if (controlsPath != null) intent.putExtra(HorizonGameActivity.EXTRA_CONTROLS, controlsPath);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return intent;
    }

    /** A Microsoft account that owns the game: CS kept a real profile id and token for it. */
    public static boolean hasProfile(@NonNull Account account) {
        return account.isMicrosoft
                && account.accessToken != null && !"0".equals(account.accessToken) && account.accessToken.length() > 20
                && account.profileId != null && !account.profileId.startsWith("00000000-0000-0000-0000");
    }

    @NonNull
    static File writeSessionFile(@NonNull Account account) throws IOException, JSONException {
        File dir = HorizonEngine.sessionsDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        // Leftovers of games that never started (the engine refuses files older than two minutes anyway).
        File[] old = dir.listFiles();
        if (old != null) {
            long now = System.currentTimeMillis();
            for (File f : old) if (now - f.lastModified() > 120_000L) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
        byte[] random = new byte[12];
        new SecureRandom().nextBytes(random);
        StringBuilder name = new StringBuilder("session-");
        for (byte b : random) name.append(String.format("%02x", b));
        File file = new File(dir, name + ".json");
        JSONObject body = new JSONObject();
        body.put("name", account.username);
        body.put("uuid", account.profileId.replace("-", ""));
        body.put("access_token", account.accessToken);
        body.put("user_type", "msa");
        if (account.xuid != null && !account.xuid.isEmpty()) body.put("xuid", account.xuid);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        // Mode 0600 as the Launch API requires.
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(false, false);
        //noinspection ResultOfMethodCallIgnored
        file.setWritable(false, false);
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(true, true);
        //noinspection ResultOfMethodCallIgnored
        file.setWritable(true, true);
        return file;
    }
}
