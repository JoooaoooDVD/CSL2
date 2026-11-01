package com.craftstudio.cslauncher.engine.horizon;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.authenticator.accounts.Account;
import com.craftstudio.cslauncher.skins.LocalSkinStore;
import com.craftstudio.cslauncher.skins.SkinLibrary;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/**
 * Skin &amp; Cape bridge between the CS Launcher skin system ({@link SkinLibrary}, {@link LocalSkinStore})
 * and CS Engine. Nothing new is stored here: a CS Engine profile remembers a skin id and a cape id under
 * its own selection key, falling back to the account's selection; the engine gets the PNG files at launch
 * ({@code cs_launch}) and on every change in game ({@link HorizonNative#nativeHostSkin}).
 *
 * Account rules: offline/local accounts always render the selection. Microsoft accounts keep their
 * official profile skin/cape (authentication and the launcher's official upload flow are untouched); the
 * engine only fills a missing cape with the local one.
 */
public final class HorizonSkins {
    /** Cape selection meaning "no cape, even if the account has one selected". */
    public static final String CAPE_NONE = "none";

    private HorizonSkins() {}

    public static final class Selection {
        @Nullable public SkinLibrary.Entry skin;
        @Nullable public LocalSkinStore.Cape cape;
        /** The profile explicitly disabled the cape. */
        public boolean capeDisabled;

        @NonNull
        public String skinPath() {
            return skin == null ? "" : SkinLibrary.fileOf(skin).getAbsolutePath();
        }

        @NonNull
        public String model() {
            return skin != null && skin.slim ? "slim" : "classic";
        }

        @NonNull
        public String capePath() {
            return cape == null || capeDisabled ? "" : LocalSkinStore.capeFile(cape).getAbsolutePath();
        }
    }

    @NonNull
    public static String profileKey(@NonNull String profileKey) {
        return "csengine:" + profileKey;
    }

    /** The account's own selection key (null for no account). */
    @Nullable
    public static String accountKey(@Nullable Account account) {
        return account == null ? null : LocalSkinStore.keyOf(account);
    }

    /** The profile's selection, else the account's. */
    @NonNull
    public static Selection resolve(@NonNull String profileKey, @Nullable String accountKey) {
        Selection s = new Selection();
        String key = profileKey(profileKey);
        String skinId = SkinLibrary.getSelected(key);
        if (skinId == null && accountKey != null) skinId = SkinLibrary.getSelected(accountKey);
        s.skin = SkinLibrary.find(skinId);
        String capeId = LocalSkinStore.getSelectedCapeByKey(key);
        if (capeId == null && accountKey != null) capeId = LocalSkinStore.getSelectedCapeByKey(accountKey);
        if (CAPE_NONE.equals(capeId)) {
            s.capeDisabled = true;
        } else {
            s.cape = LocalSkinStore.findCape(capeId);
        }
        if (s.skin != null && !SkinLibrary.fileOf(s.skin).isFile()) s.skin = null;
        if (s.cape != null && !LocalSkinStore.capeFile(s.cape).isFile()) s.cape = null;
        return s;
    }

    /** Remember the profile's choice (persisted by the skin library's index files). */
    public static void save(@NonNull String profileKey, @Nullable String skinId, @Nullable String capeIdOrNone) throws IOException {
        String key = profileKey(profileKey);
        SkinLibrary.setSelected(key, skinId);
        LocalSkinStore.setSelectedCapeByKey(key, capeIdOrNone);
    }

    /** The {@code cs_launch} fields the engine reads at start. */
    public static void putLaunch(@NonNull JSONObject json, @NonNull Selection s) throws JSONException {
        json.put("skin", s.skinPath());
        json.put("skin_model", s.model());
        json.put("cape", s.capePath());
    }

    /** Hot switch in the running engine (only valid in the game process). */
    public static void pushToEngine(@NonNull Selection s) {
        HorizonNative.nativeHostSkin(s.skinPath(), s.model(), s.capePath());
    }

    @Nullable
    public static File skinFile(@Nullable SkinLibrary.Entry entry) {
        return entry == null ? null : SkinLibrary.fileOf(entry);
    }
}
