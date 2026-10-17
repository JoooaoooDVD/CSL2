package com.craftstudio.cslauncher.modding.modpacks;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.modding.modpacks.api.ApiHandler;
import com.craftstudio.cslauncher.modding.modpacks.api.CurseforgeApi;
import com.craftstudio.cslauncher.modding.modpacks.api.ModrinthApi;
import com.craftstudio.cslauncher.modding.modpacks.models.Constants;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.modding.modpacks.models.ModDetail;
import com.craftstudio.cslauncher.modding.modpacks.models.ModItem;
import com.craftstudio.cslauncher.utils.DownloadUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kdt.mcgui.ProgressLayout;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Downloads one version of a mod / resource pack / shader pack into the selected profile,
 * replaces the previously installed file of the same project, and pulls the dependencies the
 * user ticked. Progress goes through the shared ProgressLayout so the launcher's global
 * progress bar and task counter behave the same as every other download.
 */
public final class ContentInstaller {
    private static final String TAG = "ContentInstaller";

    private ContentInstaller() {}

    public interface Callback {
        void onFinished(@Nullable File installedFile, @Nullable Exception error);
    }

    /** A dependency the user chose to install, resolved later against its own source */
    public static final class Dependency {
        public final String projectId;
        public final int apiSource;

        public Dependency(String projectId, int apiSource) {
            this.projectId = projectId;
            this.apiSource = apiSource;
        }
    }

    public static void install(@NonNull ModDetail detail, int versionIndex, @NonNull ContentType type,
                               @NonNull List<Dependency> dependencies, @Nullable String oldFilePath,
                               @Nullable String mcVersion, @Nullable String loader,
                               @Nullable String curseforgeKey, @NonNull Callback callback) {
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.global_waiting);
        CSLauncherApplication.sExecutorService.execute(() -> {
            File target = null;
            Exception failure = null;
            try {
                String url = detail.versionUrls[versionIndex];
                if (url == null || url.isEmpty()) throw new IOException("No download URL available for this version");

                File dir = InstalledContentIndex.contentDir(type);
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Unable to create " + dir);

                String fileName = null;
                if (detail.versionFileNames != null && versionIndex < detail.versionFileNames.length) {
                    fileName = detail.versionFileNames[versionIndex];
                }
                if (fileName == null || fileName.isEmpty()) fileName = fileNameFromUrl(url);
                if (!fileName.endsWith(type.fileExtension)) fileName += type.fileExtension;
                target = new File(dir, sanitize(fileName));

                File temp = new File(dir, target.getName() + ".part");
                final String displayName = target.getName();
                long expectedSize = detail.versionFileSizes != null && versionIndex < detail.versionFileSizes.length
                        ? detail.versionFileSizes[versionIndex] : -1;
                try {
                    ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.mcl_launch_downloading, displayName);
                    final long[] lastReport = {0};
                    DownloadUtils.downloadFileMonitored(url, temp, null, (curr, max) -> {
                        long now = System.currentTimeMillis();
                        if (now - lastReport[0] < 100) return;
                        lastReport[0] = now;
                        if (max > 0) {
                            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, (int) (curr * 100L / max),
                                    R.string.mcl_launch_downloading_progress, displayName, curr / ONE_MB, max / ONE_MB);
                        } else {
                            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.mcl_launch_downloading, displayName);
                        }
                    });
                    // Validate before anything is marked installed: size (when known), then archive structure
                    if (!temp.isFile() || temp.length() == 0) throw new IOException("Download produced an empty file");
                    if (expectedSize > 0 && temp.length() != expectedSize) {
                        throw new IOException("Incomplete download: got " + temp.length() + " of " + expectedSize + " bytes");
                    }
                    String sha1 = detail.versionHashes != null && versionIndex < detail.versionHashes.length ? detail.versionHashes[versionIndex] : null;
                    if (sha1 != null && !sha1.isEmpty()) {
                        String actual = InstalledContentIndex.sha1(temp);
                        if (actual != null && !actual.equalsIgnoreCase(sha1)) throw new IOException("Checksum mismatch, the download is corrupted");
                    }
                    validateArchive(temp, type);
                } catch (Exception e) {
                    if (temp.exists() && !temp.delete()) Log.w(TAG, "Could not remove partial download " + temp);
                    throw e;
                }
                if (target.exists() && !target.delete()) Log.w(TAG, "Could not replace " + target);
                if (!temp.renameTo(target)) {
                    if (temp.exists() && !temp.delete()) Log.w(TAG, "Could not remove partial download " + temp);
                    throw new IOException("Unable to move the download into place");
                }

                if (type == ContentType.WORLD) {
                    // A world zip becomes a folder in saves/; the zip itself is not kept
                    ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 90, R.string.lib_installing);
                    File worldDir;
                    try {
                        worldDir = extractWorld(target, dir, detail.title);
                    } finally {
                        if (target.exists() && !target.delete()) Log.w(TAG, "Could not remove the world archive " + target);
                    }
                    target = worldDir;
                }

                if (oldFilePath != null) {
                    File old = new File(oldFilePath);
                    if (old.exists() && !old.getAbsolutePath().equals(target.getAbsolutePath()) && !old.delete()) {
                        Log.w(TAG, "Could not remove the previous file " + old);
                    }
                }

                for (Dependency dependency : dependencies) {
                    if (dependency == null || dependency.projectId == null) continue;
                    try {
                        if (dependency.apiSource == Constants.SOURCE_CURSEFORGE) {
                            installCurseforgeDependency(dependency.projectId, mcVersion, loader, curseforgeKey);
                        } else {
                            installModrinthDependency(dependency.projectId, type, mcVersion, loader);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Dependency " + dependency.projectId + " failed: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                failure = e;
                Log.w(TAG, "Install of " + detail.title + " (" + type + ") failed during "
                        + (target == null ? "setup" : "download/validation") + ": " + e.getMessage());
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            }
            final File installed = failure == null ? target : null;
            final Exception error = failure;
            Tools.runOnUiThread(() -> callback.onFinished(installed, error));
        });
    }

    private static final double ONE_MB = 1024d * 1024d;

    /**
     * Rejects downloads that are not what the content type expects, so a HTML error page or a
     * truncated file is never dropped into the profile as if it were installed.
     */
    static void validateArchive(@NonNull File file, @NonNull ContentType type) throws IOException {
        switch (type) {
            case MOD: {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
                    if (!zip.entries().hasMoreElements()) throw new IOException("The downloaded jar is empty");
                } catch (java.util.zip.ZipException e) {
                    throw new IOException("The downloaded file is not a valid mod jar", e);
                }
                return;
            }
            case RESOURCE_PACK:
            case SHADER_PACK:
            case DATAPACK:
            case WORLD: {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
                    boolean ok = false;
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                    int scanned = 0;
                    while (entries.hasMoreElements() && scanned++ < 50000) {
                        String name = entries.nextElement().getName().replace('\\', '/');
                        if (type == ContentType.WORLD) { if (name.endsWith("level.dat")) { ok = true; break; } }
                        else if (type == ContentType.SHADER_PACK) { if (name.startsWith("shaders/") || name.contains("/shaders/") || name.endsWith("pack.mcmeta")) { ok = true; break; } }
                        else if (name.endsWith("pack.mcmeta")) { ok = true; break; }
                    }
                    if (!ok) {
                        throw new IOException(type == ContentType.WORLD
                                ? "This archive does not contain a Minecraft world (no level.dat)"
                                : "This archive is missing pack.mcmeta / shaders, Minecraft would not recognise it");
                    }
                } catch (java.util.zip.ZipException e) {
                    throw new IOException("The downloaded file is not a valid zip archive", e);
                }
                return;
            }
            default:
                // modpacks are handled by ModpackInstaller
        }
    }

    /** Deletes an installed file (or world folder). Returns whether it is gone afterwards */
    public static boolean uninstall(@NonNull File file) {
        if (file.isDirectory()) {
            try {
                org.apache.commons.io.FileUtils.deleteDirectory(file);
            } catch (IOException e) {
                Log.w(TAG, "Could not delete " + file + ": " + e.getMessage());
            }
            return !file.exists();
        }
        return !file.exists() || file.delete();
    }

    /**
     * Unpacks a downloaded world archive into its own folder under saves/. Handles both
     * layouts CurseForge worlds come in: "WorldName/level.dat" and a bare "level.dat" at the root.
     * @return the created world directory
     */
    @NonNull
    static File extractWorld(@NonNull File zip, @NonNull File savesDir, @Nullable String fallbackName) throws IOException {
        String rootFolder = null;
        boolean levelAtRoot = false;
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(zip)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().replace('\\', '/');
                if (name.endsWith("level.dat")) {
                    String parent = name.substring(0, name.length() - "level.dat".length());
                    if (parent.isEmpty()) { levelAtRoot = true; break; }
                    // Prefer the shallowest level.dat as the world root
                    if (rootFolder == null || parent.length() < rootFolder.length()) rootFolder = parent;
                }
            }
            if (!levelAtRoot && rootFolder == null) throw new IOException("This archive does not contain a Minecraft world (no level.dat)");

            String baseName = levelAtRoot
                    ? (fallbackName == null || fallbackName.trim().isEmpty() ? stripExtension(zip.getName()) : fallbackName.trim())
                    : rootFolder.substring(0, rootFolder.length() - 1);
            int slash = baseName.lastIndexOf('/');
            if (slash >= 0) baseName = baseName.substring(slash + 1);
            File worldDir = uniqueDir(savesDir, sanitize(baseName));
            if (!worldDir.mkdirs()) throw new IOException("Unable to create " + worldDir);

            String prefix = levelAtRoot ? "" : rootFolder;
            String canonicalRoot = worldDir.getCanonicalPath() + File.separator;
            entries = zipFile.entries();
            byte[] buffer = new byte[64 * 1024];
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith(prefix)) continue;
                String relative = name.substring(prefix.length());
                if (relative.isEmpty()) continue;
                File out = new File(worldDir, relative);
                if (!out.getCanonicalPath().startsWith(canonicalRoot)) continue; // zip-slip guard
                if (entry.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Unable to create " + parent);
                try (java.io.InputStream in = zipFile.getInputStream(entry);
                     java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) os.write(buffer, 0, read);
                }
            }
            return worldDir;
        }
    }

    private static File uniqueDir(File parent, String name) {
        if (name.isEmpty()) name = "World";
        File candidate = new File(parent, name);
        int counter = 2;
        while (candidate.exists()) candidate = new File(parent, name + " (" + counter++ + ")");
        return candidate;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * A dependency goes into the folder of its own project type: shader packs usually depend
     * on a mod (Iris), so the mod has to land in the mods folder and keep its .jar name.
     */
    private static void installModrinthDependency(String projectId, ContentType parentType,
                                                  @Nullable String mcVersion, @Nullable String loader) throws IOException {
        ApiHandler handler = new ApiHandler("https://api.modrinth.com/v2");
        JsonObject project = handler.get("project/" + projectId, JsonObject.class);
        ContentType depType = parentType;
        if (project != null && project.has("project_type") && !project.get("project_type").isJsonNull()) {
            depType = ContentType.fromModrinthType(project.get("project_type").getAsString());
        }
        String filterLoader = depType == ContentType.MOD && loader != null && !loader.isEmpty() ? loader : null;
        ModItem item = new ModItem(Constants.SOURCE_MODRINTH, false, projectId, projectId, "", "");
        ModDetail detail = new ModrinthApi().getModDetails(item, mcVersion, filterLoader);
        if (detail == null && "quilt".equals(filterLoader)) {
            detail = new ModrinthApi().getModDetails(item, mcVersion, "fabric");
        }
        if (detail == null || detail.versionUrls == null || detail.versionUrls.length == 0) return;

        int index = 0;
        if (detail.versionTypes != null) {
            for (int i = 0; i < detail.versionTypes.length; i++) {
                if ("release".equals(detail.versionTypes[i])) {
                    index = i;
                    break;
                }
            }
        }
        String url = detail.versionUrls[index];
        if (url == null) return;
        String name = detail.versionFileNames != null && detail.versionFileNames[index] != null
                ? detail.versionFileNames[index] : fileNameFromUrl(url);
        if (!name.endsWith(depType.fileExtension)) name += depType.fileExtension;
        File dir = InstalledContentIndex.contentDir(depType);
        if (!dir.exists()) dir.mkdirs();
        DownloadUtils.downloadFile(url, new File(dir, sanitize(name)));
    }

    private static void installCurseforgeDependency(String modId, @Nullable String mcVersion,
                                                    @Nullable String loader, @Nullable String key) throws IOException {
        if (key == null || key.isEmpty()) return;
        ModItem item = new ModItem(Constants.SOURCE_CURSEFORGE, false, modId, null, null, null);
        CurseforgeApi api = new CurseforgeApi(key);
        String filterLoader = loader != null && !loader.isEmpty() ? loader : null;
        ModDetail detail = api.getModDetails(item, mcVersion, filterLoader);
        if (detail == null || detail.versionUrls == null) return;
        for (int i = 0; i < detail.versionUrls.length; i++) {
            String url = detail.versionUrls[i];
            if (url == null || url.isEmpty()) continue;
            String name = detail.versionFileNames != null && detail.versionFileNames[i] != null
                    ? detail.versionFileNames[i] : fileNameFromUrl(url);
            if (!name.endsWith(".jar")) name += ".jar";
            File dir = InstalledContentIndex.contentDir(ContentType.MOD);
            if (!dir.exists()) dir.mkdirs();
            DownloadUtils.downloadFile(url, new File(dir, sanitize(name)));
            return;
        }
    }

    /** Fetches a Modrinth project's title and icon, {title, iconUrl, slug, projectType} */
    @Nullable
    public static String[] fetchModrinthProject(String projectId) {
        try {
            JsonObject project = new ApiHandler("https://api.modrinth.com/v2").get("project/" + projectId, JsonObject.class);
            if (project == null) return null;
            return new String[]{string(project, "title"), string(project, "icon_url"),
                    string(project, "slug"), string(project, "project_type")};
        } catch (Exception e) {
            return null;
        }
    }

    /** Fetches a CurseForge mod's name and logo, {name, iconUrl, slug, null} */
    @Nullable
    public static String[] fetchCurseforgeProject(String modId, @Nullable String key) {
        if (key == null || key.isEmpty()) return null;
        try {
            JsonObject response = new ApiHandler("https://api.curseforge.com/v1", key).get("mods/" + modId, JsonObject.class);
            if (response == null || !response.has("data") || !response.get("data").isJsonObject()) return null;
            JsonObject data = response.getAsJsonObject("data");
            String icon = null;
            JsonElement logo = data.get("logo");
            if (logo != null && logo.isJsonObject()) icon = string(logo.getAsJsonObject(), "thumbnailUrl");
            return new String[]{string(data, "name"), icon, string(data, "slug"), null};
        } catch (Exception e) {
            return null;
        }
    }

    /** One screenshot of a project gallery */
    public static final class GalleryImage {
        @NonNull public final String url;
        @NonNull public final String thumbnailUrl;
        @Nullable public final String title;
        @Nullable public final String description;

        GalleryImage(@NonNull String url, @Nullable String thumbnailUrl, @Nullable String title, @Nullable String description) {
            this.url = url;
            this.thumbnailUrl = thumbnailUrl == null || thumbnailUrl.isEmpty() ? url : thumbnailUrl;
            this.title = title;
            this.description = description;
        }
    }

    /**
     * Fetches the screenshot gallery of a project (featured image first). Never throws,
     * returns an empty list when the project has no gallery or the request fails.
     * Blocking, call it off the UI thread.
     */
    @NonNull
    public static List<GalleryImage> fetchGallery(@NonNull ModItem item, @Nullable String curseforgeKey) {
        List<GalleryImage> images = new java.util.ArrayList<>();
        try {
            if (item.apiSource == Constants.SOURCE_CURSEFORGE) {
                if (curseforgeKey == null || curseforgeKey.isEmpty()) return images;
                JsonObject response = new ApiHandler("https://api.curseforge.com/v1", curseforgeKey)
                        .get("mods/" + item.id, JsonObject.class);
                if (response == null || !response.has("data") || !response.get("data").isJsonObject()) return images;
                JsonElement screenshots = response.getAsJsonObject("data").get("screenshots");
                if (screenshots == null || !screenshots.isJsonArray()) return images;
                for (JsonElement element : screenshots.getAsJsonArray()) {
                    if (!element.isJsonObject()) continue;
                    JsonObject shot = element.getAsJsonObject();
                    String url = string(shot, "url");
                    if (url == null || url.isEmpty()) continue;
                    images.add(new GalleryImage(url, string(shot, "thumbnailUrl"),
                            string(shot, "title"), string(shot, "description")));
                }
            } else {
                JsonObject project = new ApiHandler("https://api.modrinth.com/v2")
                        .get("project/" + item.id, JsonObject.class);
                if (project == null) return images;
                JsonElement gallery = project.get("gallery");
                if (gallery == null || !gallery.isJsonArray()) return images;
                List<JsonObject> entries = new java.util.ArrayList<>();
                for (JsonElement element : gallery.getAsJsonArray()) {
                    if (element.isJsonObject()) entries.add(element.getAsJsonObject());
                }
                java.util.Collections.sort(entries, (a, b) -> {
                    boolean fa = isTrue(a, "featured"), fb = isTrue(b, "featured");
                    if (fa != fb) return fa ? -1 : 1;
                    return Long.compare(number(a, "ordering"), number(b, "ordering"));
                });
                for (JsonObject entry : entries) {
                    String url = string(entry, "url");
                    if (url == null || url.isEmpty()) url = string(entry, "raw_url");
                    if (url == null || url.isEmpty()) continue;
                    images.add(new GalleryImage(url, url, string(entry, "title"), string(entry, "description")));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Gallery unavailable for " + item.id, e);
        }
        return images;
    }

    private static boolean isTrue(JsonObject object, String key) {
        JsonElement element = object.get(key);
        try {
            return element != null && element.isJsonPrimitive() && element.getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static long number(JsonObject object, String key) {
        JsonElement element = object.get(key);
        try {
            return element != null && element.isJsonPrimitive() ? element.getAsLong() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    @Nullable
    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        return element.getAsString();
    }

    @NonNull
    private static String fileNameFromUrl(@NonNull String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        int query = name.indexOf('?');
        if (query >= 0) name = name.substring(0, query);
        try {
            name = java.net.URLDecoder.decode(name, "UTF-8");
        } catch (Exception ignored) {}
        return name.isEmpty() ? "download" : name;
    }

    @NonNull
    private static String sanitize(@NonNull String name) {
        return name.replace('/', '_').replace('\\', '_');
    }
}
