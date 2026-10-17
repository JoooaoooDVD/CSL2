package com.craftstudio.cslauncher.multiplayer;

import androidx.annotation.NonNull;

import com.craftstudio.cslauncher.worlds.Nbt;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adds / refreshes a server entry in the game's {@code servers.dat} so the guest can open
 * Multiplayer and tap the host's world instead of typing an address. Only the entry with our
 * marker name is touched; everything else is preserved byte for byte in content.
 */
public final class ServersDat {
    private ServersDat() {}

    /** Entry name prefix; the session code is appended so old entries can be recognised */
    public static final String NAME_PREFIX = "\u00a77[Virtual LAN]\u00a7r ";

    public static synchronized void upsert(@NonNull File gameDir, @NonNull String displayName, @NonNull String address) throws IOException {
        File file = new File(gameDir, "servers.dat");
        Nbt.Tag root;
        if (file.isFile() && file.length() > 0) {
            try {
                root = Nbt.read(file).compound;
            } catch (IOException e) {
                root = Nbt.newCompound();
            }
        } else {
            root = Nbt.newCompound();
        }
        Map<String, Nbt.Tag> rootMap = root.compound();
        Nbt.Tag servers = rootMap.get("servers");
        if (servers == null || servers.type != Nbt.TAG_LIST) {
            servers = Nbt.newList(Nbt.TAG_COMPOUND);
            rootMap.put("servers", servers);
        }
        List<Nbt.Tag> list = servers.list();
        String fullName = NAME_PREFIX + displayName;
        Nbt.Tag existing = null;
        List<Nbt.Tag> stale = new ArrayList<>();
        for (Nbt.Tag entry : list) {
            if (entry.type != Nbt.TAG_COMPOUND) continue;
            String name = Nbt.getString(entry, "name");
            if (name == null || !name.startsWith(NAME_PREFIX)) continue;
            if (existing == null) existing = entry; else stale.add(entry);
        }
        list.removeAll(stale);
        if (existing == null) {
            existing = Nbt.newCompound();
            list.add(0, existing);
        }
        Map<String, Nbt.Tag> m = existing.compound();
        m.put("name", new Nbt.Tag(Nbt.TAG_STRING, fullName));
        m.put("ip", new Nbt.Tag(Nbt.TAG_STRING, address));
        m.put("hidden", new Nbt.Tag(Nbt.TAG_BYTE, (byte) 0));
        if (!gameDir.isDirectory()) //noinspection ResultOfMethodCallIgnored
            gameDir.mkdirs();
        Nbt.writeUncompressed(new Nbt.Root("", root), file);
    }

    /** Removes our entry (called when the guest session ends) */
    public static synchronized void remove(@NonNull File gameDir) {
        File file = new File(gameDir, "servers.dat");
        if (!file.isFile()) return;
        try {
            Nbt.Tag root = Nbt.read(file).compound;
            Nbt.Tag servers = root.compound().get("servers");
            if (servers == null || servers.type != Nbt.TAG_LIST) return;
            List<Nbt.Tag> list = servers.list();
            boolean changed = false;
            for (int i = list.size() - 1; i >= 0; i--) {
                String name = Nbt.getString(list.get(i), "name");
                if (name != null && name.startsWith(NAME_PREFIX)) {
                    list.remove(i);
                    changed = true;
                }
            }
            if (changed) Nbt.writeUncompressed(new Nbt.Root("", root), file);
        } catch (IOException ignored) {
        }
    }
}
