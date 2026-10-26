package com.craftstudio.cslauncher.modding.modpacks.models;

import androidx.annotation.NonNull;

public class ModItem extends ModSource {

    public String id;
    public String title;
    public String description;
    public String imageUrl;
    /** True if the mod author blocked third-party distribution (CF allowModDistribution=false) */
    public boolean isRestricted;
    /** Direct website URL for the mod page (used for CF restricted mods) */
    public String websiteUrl;
    /** Pinned to the top of the Mods tab with a green "Recommended" badge (not serialized) */
    public transient boolean recommended;

    /** Project slug, used to match manually installed jars by their mod id */
    public String slug;
    /** Author or team name shown on the library card */
    public String author;
    /** Category slugs without the loader entries, e.g. "optimization" */
    public String[] categories;
    /** Loaders the project supports, lowercase, e.g. "fabric" */
    public String[] loaders;
    /** Minecraft versions the project supports, in the order the API returned them */
    public String[] gameVersions;
    public long downloads;
    public long follows;
    /** ISO-8601 date of the last update, may be null */
    public String dateModified;
    /** CurseForge latestFilesIndexes, parallel arrays. Null for Modrinth results */
    public long[] cfIndexFileIds;
    public String[] cfIndexGameVersions;
    public String[] cfIndexLoaders;

    public ModItem(int apiSource, boolean isModpack, String id, String title, String description, String imageUrl) {
        this.apiSource = apiSource;
        this.isModpack = isModpack;
        this.id = id;
        this.title = title;
        this.description = description;
        this.imageUrl = imageUrl;
    }

    @NonNull
    @Override
    public String toString() {
        return "ModItem{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", imageUrl='" + imageUrl + '\'' +
                ", apiSource=" + apiSource +
                ", isModpack=" + isModpack +
                '}';
    }

    /** Copies the library metadata from another item, used when a detail object replaces a search hit */
    public void copyMetaFrom(ModItem other) {
        if (other == null) return;
        this.isRestricted = other.isRestricted;
        this.websiteUrl = other.websiteUrl;
        this.slug = other.slug;
        this.author = other.author;
        this.categories = other.categories;
        this.loaders = other.loaders;
        this.gameVersions = other.gameVersions;
        this.downloads = other.downloads;
        this.follows = other.follows;
        this.dateModified = other.dateModified;
        this.cfIndexFileIds = other.cfIndexFileIds;
        this.cfIndexGameVersions = other.cfIndexGameVersions;
        this.cfIndexLoaders = other.cfIndexLoaders;
    }

    public String getIconCacheTag() {
        return apiSource+"_"+id+"_v2";
    }
}