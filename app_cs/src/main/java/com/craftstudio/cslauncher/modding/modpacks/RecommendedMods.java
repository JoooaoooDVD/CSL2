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
 * The "BASE OPTIMISE MOD" recommendation: Thorium Optimization (CurseForge 1753836) is pinned as
 * the first card of the Mods tab, looking like any other result plus a green Recommended badge.
 * Install / details / compatibility go through the normal card code, so the version that gets
 * installed is picked for the selected profile exactly like for every other mod.
 */
public final class RecommendedMods {
    private static final String TAG = "RecommendedMods";
    /** Thorium Optimization on CurseForge (Fabric client performance mod) */
    public static final String THORIUM_CF_ID = "1753836";

    private static final long CACHE_MS = 10 * 60 * 1000L;
    private static final long FAILURE_RETRY_MS = 60 * 1000L;

    private static final Object LOCK = new Object();
    @Nullable private static ModItem sCached;
    private static long sFetchedAt;
    private static long sFailedAt;

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
        ModItem recommended = fetch(curseforgeKey);
        if (recommended == null) return page;
        if (page == null) {
            // Search failed but the pinned card is known: show it alone rather than an empty error
            page = new SearchResult();
            page.results = new ModItem[0];
        }
        List<ModItem> merged = new ArrayList<>();
        ModItem pinned = copy(recommended);
        pinned.recommended = true;
        merged.add(pinned);
        if (page.results != null) {
            for (ModItem item : page.results) {
                if (item == null) continue;
                if (item.apiSource == Constants.SOURCE_CURSEFORGE && THORIUM_CF_ID.equals(item.id)) continue;
                merged.add(item);
            }
        }
        page.results = merged.toArray(new ModItem[0]);
        return page;
    }

    @Nullable
    private static ModItem fetch(@NonNull String curseforgeKey) {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (sCached != null && now - sFetchedAt < CACHE_MS) return sCached;
            if (sCached == null && sFailedAt != 0 && now - sFailedAt < FAILURE_RETRY_MS) return null;
        }
        ModItem item = null;
        try {
            item = new CurseforgeApi(curseforgeKey).getMod(THORIUM_CF_ID);
        } catch (Exception e) {
            Log.w(TAG, "Recommended mod lookup failed", e);
        }
        synchronized (LOCK) {
            if (item != null && !item.isRestricted) {
                sCached = item;
                sFetchedAt = now;
                sFailedAt = 0;
            } else {
                sFailedAt = now;
            }
            return sCached;
        }
    }

    /** Fresh object per page so the badge flag never leaks into the cached item */
    private static ModItem copy(@NonNull ModItem src) {
        ModItem item = new ModItem(src.apiSource, src.isModpack, src.id, src.title, src.description, src.imageUrl);
        item.copyMetaFrom(src);
        return item;
    }
}
