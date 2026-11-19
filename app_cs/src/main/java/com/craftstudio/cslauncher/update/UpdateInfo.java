package com.craftstudio.cslauncher.update;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/** One published build of the launcher, parsed from a GitHub release. */
public final class UpdateInfo {
    public static final class Apk {
        public final String file, sha256, downloadUrl;
        public final long size;
        Apk(String file, String sha256, long size, String downloadUrl) {
            this.file = file; this.sha256 = sha256; this.size = size; this.downloadUrl = downloadUrl;
        }
    }

    public final String tag;
    public final String title;
    public final int versionCode;
    public final String versionName;
    public final boolean prerelease;
    public final long publishedAt;
    public final String htmlUrl;
    /** Release notes without the machine-readable block */
    public final String notes;
    public final List<Apk> apks = new ArrayList<>();

    UpdateInfo(String tag, String title, int versionCode, String versionName, boolean prerelease,
               long publishedAt, String htmlUrl, String notes) {
        this.tag = tag; this.title = title; this.versionCode = versionCode; this.versionName = versionName;
        this.prerelease = prerelease; this.publishedAt = publishedAt; this.htmlUrl = htmlUrl; this.notes = notes;
    }

    @Nullable
    public Apk apkNamed(@NonNull String name) {
        for (Apk a : apks) if (a.file.equalsIgnoreCase(name)) return a;
        return null;
    }

    /** "1.4 (build 130)" */
    @NonNull
    public String label() {
        return versionName + " (build " + versionCode + ")";
    }
}
