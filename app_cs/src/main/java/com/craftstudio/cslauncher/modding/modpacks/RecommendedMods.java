package com.craftstudio.cslauncher.modding.modpacks;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.modding.modpacks.api.CurseforgeApi;
import com.craftstudio.cslauncher.modding.modpacks.models.Constants;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.modding.modpacks.models.ModItem;
import com.craftstudio.cslauncher.modding.modpacks.models.SearchFilters;
import com.craftstudio.cslauncher.modding.modpacks.models.SearchResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Pinned recommendations on top of the Mods tab: Thorium Optimization (CurseForge 1753836, "BASE
 * OPTIMISE MOD") and Pojav Tagger (CurseForge 1672821, the OPTL partner mod). Each card looks like any
 * other result plus a green badge, looking like any other result plus a green Recommended badge.
 * Install / details / compatibility go through the normal card code, so the version that gets
 * installed is picked for the selected profile exactly like for every other mod.
 */
public final class RecommendedMods {
    private static final String TAG = "RecommendedMods";
    /** Thorium Optimization on CurseForge (Fabric client performance mod) */
    public static final String THORIUM_CF_ID = "1753836";
    /** Pojav Tagger on CurseForge (OPTL's tagger mod, Fabric client) */
    public static final String POJAV_TAGGER_CF_ID = "1672821";

    /** One pinned card: CurseForge project id + the badge text resource */
    private static final class Pin {
        final String id; final int label;
        Pin(String id, int label) { this.id = id; this.label = label; }
    }
    private static final Pin[] PINS = {
            new Pin(THORIUM_CF_ID, com.craftstudio.cslauncher.R.string.lib_recommended_badge),
            new Pin(POJAV_TAGGER_CF_ID, com.craftstudio.cslauncher.R.string.lib_recommended_badge_optl),
    };

    private static final long CACHE_MS = 10 * 60 * 1000L;
    private static final long FAILURE_RETRY_MS = 60 * 1000L;

    private static final Object LOCK = new Object();
    private static final java.util.Map<String, ModItem> sCached = new java.util.HashMap<>();
    private static final java.util.Map<String, Long> sFetchedAt = new java.util.HashMap<>();
    private static final java.util.Map<String, Long> sFailedAt = new java.util.HashMap<>();

    private RecommendedMods() {}

    /** Whether the pinned card belongs on top of this search (Mods tab, first page, no text query) */
    public static boolean applies(@NonNull SearchFilters query, @Nullable SearchResult previousPage) {
        return previousPage == null
                && query.contentType == ContentType.MOD
                && (query.name == null || query.name.trim().isEmpty());
    }

    /**
     * Blocking (call from the search thread): fetches / reuses the recommended item and puts it
     * first in the result page, dropping the same project if the search also returned it.
     */
    @Nullable
    public static SearchResult decorate(@Nullable SearchResult page, @Nullable String curseforgeKey) {
        if (curseforgeKey == null) return page;
        List<ModItem> pinnedItems = new ArrayList<>();
        java.util.Set<String> pinnedIds = new java.util.HashSet<>();
        for (Pin pin : PINS) {
            ModItem item = fetch(curseforgeKey, pin.id);
            if (item == null) continue;
            ModItem copy = copy(item);
            copy.recommended = true;
            copy.recommendedLabel = pin.label;
            pinnedItems.add(copy);
            pinnedIds.add(pin.id);
        }
        if (pinnedItems.isEmpty()) return page;
        if (page == null) {
            // Search failed but the pinned cards are known: show them rather than an empty error
            page = new SearchResult();
            page.results = new ModItem[0];
        }
        List<ModItem> merged = new ArrayList<>(pinnedItems);
        if (page.results != null) {
            for (ModItem item : page.results) {
                if (item == null) continue;
                if (item.apiSource == Constants.SOURCE_CURSEFORGE && pinnedIds.contains(item.id)) continue;
                merged.add(item);
            }
        }
        page.results = merged.toArray(new ModItem[0]);
        return page;
    }

    @Nullable
    private static ModItem fetch(@NonNull String curseforgeKey, @NonNull String id) {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            ModItem cached = sCached.get(id);
            Long at = sFetchedAt.get(id);
            Long failed = sFailedAt.get(id);
            if (cached != null && at != null && now - at < CACHE_MS) return cached;
            if (cached == null && failed != null && now - failed < FAILURE_RETRY_MS) return null;
        }
        ModItem item = null;
        try {
            item = new CurseforgeApi(curseforgeKey).getMod(id);
        } catch (Exception e) {
            Log.w(TAG, "Recommended mod lookup failed: " + id, e);
        }
        synchronized (LOCK) {
            if (item != null && !item.isRestricted) {
                sCached.put(id, item);
                sFetchedAt.put(id, now);
                sFailedAt.remove(id);
            } else {
                sFailedAt.put(id, now);
            }
            return sCached.get(id);
        }
    }

    /** Fresh object per page so the badge flag never leaks into the cached item */
    private static ModItem copy(@NonNull ModItem src) {
        ModItem item = new ModItem(src.apiSource, src.isModpack, src.id, src.title, src.description, src.imageUrl);
        item.copyMetaFrom(src);
        return item;
    }
}
