package com.craftstudio.cslauncher.worlds;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Tiny, dependency-free NBT reader/writer. Enough to open level.dat, read a few values,
 * change the world name and write the file back byte-for-byte compatible with the game.
 */
public final class Nbt {

    private Nbt() {}

    public static final int TAG_END = 0, TAG_BYTE = 1, TAG_SHORT = 2, TAG_INT = 3, TAG_LONG = 4,
            TAG_FLOAT = 5, TAG_DOUBLE = 6, TAG_BYTE_ARRAY = 7, TAG_STRING = 8, TAG_LIST = 9,
            TAG_COMPOUND = 10, TAG_INT_ARRAY = 11, TAG_LONG_ARRAY = 12;

    /** A typed value. Compounds are {@code Map<String, Tag>}, lists are {@code List<Tag>}. */
    public static final class Tag {
        public final int type;
        public Object value;

        public Tag(int type, Object value) {
            this.type = type;
            this.value = value;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Tag> compound() {
            return (Map<String, Tag>) value;
        }

        @SuppressWarnings("unchecked")
        public List<Tag> list() {
            return (List<Tag>) value;
        }

        public int listType() {
            return mListType;
        }

        int mListType = TAG_END;
    }

    /** Root compound with its (usually empty) name */
    public static final class Root {
        public final String name;
        public final Tag compound;

        public Root(String name, Tag compound) {
            this.name = name;
            this.compound = compound;
        }
    }

    // ─── Reading ─────────────────────────────────────────────────────────────

    @NonNull
    public static Root read(@NonNull File file) throws IOException {
        try (InputStream raw = new BufferedInputStream(new FileInputStream(file))) {
            raw.mark(2);
            int b1 = raw.read(), b2 = raw.read();
            raw.reset();
            InputStream in = (b1 == 0x1f && b2 == 0x8b) ? new GZIPInputStream(raw) : raw;
            DataInputStream data = new DataInputStream(in);
            int type = data.readUnsignedByte();
            if (type != TAG_COMPOUND) throw new IOException("Root tag is not a compound: " + type);
            String name = data.readUTF();
            Tag tag = readPayload(data, TAG_COMPOUND, 0);
            return new Root(name, tag);
        }
    }

    private static Tag readPayload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT too deep");
        switch (type) {
            case TAG_BYTE: return new Tag(type, in.readByte());
            case TAG_SHORT: return new Tag(type, in.readShort());
            case TAG_INT: return new Tag(type, in.readInt());
            case TAG_LONG: return new Tag(type, in.readLong());
            case TAG_FLOAT: return new Tag(type, in.readFloat());
            case TAG_DOUBLE: return new Tag(type, in.readDouble());
            case TAG_BYTE_ARRAY: {
                int length = in.readInt();
                if (length < 0 || length > (1 << 28)) throw new IOException("Bad byte array length");
                byte[] array = new byte[length];
                in.readFully(array);
                return new Tag(type, array);
            }
            case TAG_STRING: return new Tag(type, in.readUTF());
            case TAG_LIST: {
                int listType = in.readUnsignedByte();
                int length = in.readInt();
                if (length < 0 || length > (1 << 24)) throw new IOException("Bad list length");
                List<Tag> list = new ArrayList<>(length);
                for (int i = 0; i < length; i++) list.add(readPayload(in, listType, depth + 1));
                Tag tag = new Tag(type, list);
                tag.mListType = listType;
                return tag;
            }
            case TAG_COMPOUND: {
                Map<String, Tag> map = new LinkedHashMap<>();
                while (true) {
                    int childType = in.readUnsignedByte();
                    if (childType == TAG_END) break;
                    String name = in.readUTF();
                    map.put(name, readPayload(in, childType, depth + 1));
                }
                return new Tag(type, map);
            }
            case TAG_INT_ARRAY: {
                int length = in.readInt();
                if (length < 0 || length > (1 << 26)) throw new IOException("Bad int array length");
                int[] array = new int[length];
                for (int i = 0; i < length; i++) array[i] = in.readInt();
                return new Tag(type, array);
            }
            case TAG_LONG_ARRAY: {
                int length = in.readInt();
                if (length < 0 || length > (1 << 25)) throw new IOException("Bad long array length");
                long[] array = new long[length];
                for (int i = 0; i < length; i++) array[i] = in.readLong();
                return new Tag(type, array);
            }
            default:
                throw new IOException("Unknown NBT tag " + type);
        }
    }

    // ─── Writing ─────────────────────────────────────────────────────────────

    /** Writes the root compound gzip-compressed, the way the game stores level.dat */
    public static void write(@NonNull Root root, @NonNull File file) throws IOException {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (OutputStream raw = new BufferedOutputStream(new FileOutputStream(temp));
             GZIPOutputStream gzip = new GZIPOutputStream(raw)) {
            DataOutputStream out = new DataOutputStream(gzip);
            out.writeByte(TAG_COMPOUND);
            out.writeUTF(root.name == null ? "" : root.name);
            writePayload(out, root.compound);
            out.flush();
            gzip.finish();
        }
        if (!temp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (!temp.renameTo(file)) throw new IOException("Could not replace " + file.getName());
        }
    }

    /** Writes the root compound uncompressed, the way the game stores servers.dat */
    public static void writeUncompressed(@NonNull Root root, @NonNull File file) throws IOException {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (OutputStream raw = new BufferedOutputStream(new FileOutputStream(temp))) {
            DataOutputStream out = new DataOutputStream(raw);
            out.writeByte(TAG_COMPOUND);
            out.writeUTF(root.name == null ? "" : root.name);
            writePayload(out, root.compound);
            out.flush();
        }
        if (!temp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (!temp.renameTo(file)) throw new IOException("Could not replace " + file.getName());
        }
    }

    /** Creates a tag; convenience for builders of small structures */
    public static Tag newCompound() { return new Tag(TAG_COMPOUND, new java.util.LinkedHashMap<String, Tag>()); }

    public static Tag newList(int elementType) {
        Tag t = new Tag(TAG_LIST, new java.util.ArrayList<Tag>());
        t.mListType = elementType;
        return t;
    }

    private static void writePayload(DataOutputStream out, Tag tag) throws IOException {
        switch (tag.type) {
            case TAG_BYTE: out.writeByte((Byte) tag.value); break;
            case TAG_SHORT: out.writeShort((Short) tag.value); break;
            case TAG_INT: out.writeInt((Integer) tag.value); break;
            case TAG_LONG: out.writeLong((Long) tag.value); break;
            case TAG_FLOAT: out.writeFloat((Float) tag.value); break;
            case TAG_DOUBLE: out.writeDouble((Double) tag.value); break;
            case TAG_BYTE_ARRAY: {
                byte[] array = (byte[]) tag.value;
                out.writeInt(array.length);
                out.write(array);
                break;
            }
            case TAG_STRING: out.writeUTF((String) tag.value); break;
            case TAG_LIST: {
                List<Tag> list = tag.list();
                int listType = list.isEmpty() ? tag.mListType : list.get(0).type;
                out.writeByte(listType);
                out.writeInt(list.size());
                for (Tag child : list) writePayload(out, child);
                break;
            }
            case TAG_COMPOUND: {
                for (Map.Entry<String, Tag> entry : tag.compound().entrySet()) {
                    out.writeByte(entry.getValue().type);
                    out.writeUTF(entry.getKey());
                    writePayload(out, entry.getValue());
                }
                out.writeByte(TAG_END);
                break;
            }
            case TAG_INT_ARRAY: {
                int[] array = (int[]) tag.value;
                out.writeInt(array.length);
                for (int v : array) out.writeInt(v);
                break;
            }
            case TAG_LONG_ARRAY: {
                long[] array = (long[]) tag.value;
                out.writeInt(array.length);
                for (long v : array) out.writeLong(v);
                break;
            }
            default:
                throw new IOException("Unknown NBT tag " + tag.type);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Walks a dotted path through compounds ("Data.Version.Name"), null when absent */
    @Nullable
    public static Tag find(@Nullable Tag compound, @NonNull String path) {
        Tag current = compound;
        for (String part : path.split("\\.")) {
            if (current == null || current.type != TAG_COMPOUND) return null;
            current = current.compound().get(part);
        }
        return current;
    }

    @Nullable
    public static String getString(@Nullable Tag compound, @NonNull String path) {
        Tag tag = find(compound, path);
        return tag != null && tag.type == TAG_STRING ? (String) tag.value : null;
    }

    public static long getNumber(@Nullable Tag compound, @NonNull String path, long fallback) {
        Tag tag = find(compound, path);
        if (tag == null) return fallback;
        if (tag.value instanceof Number) return ((Number) tag.value).longValue();
        return fallback;
    }

    public static boolean has(@Nullable Tag compound, @NonNull String path) {
        return find(compound, path) != null;
    }
}
