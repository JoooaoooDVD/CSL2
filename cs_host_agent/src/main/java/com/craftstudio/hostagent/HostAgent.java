package com.craftstudio.hostagent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;

/**
 * CS Launcher Plus host agent. The Java edition's integrated server builds its player list as
 * {@code new IntegratedPlayerList(server, registries, storage)} whose constructor calls
 * {@code super(server, registries, storage, 8)}: the LAN world takes 8 players, full stop. This
 * agent finds that class by shape (no mappings: it is the only class with a 3-object
 * constructor that pushes the literal 8 into a 4-argument super constructor and also declares a
 * {@code (SocketAddress, GameProfile)} login check) and replaces the 8 with
 * {@code Integer.getInteger("cs.host.maxPlayers", 8)}. Nothing else is touched; a class that
 * does not match is returned untransformed (null).
 */
public final class HostAgent {
    public static final String PROPERTY = "cs.host.maxPlayers";
    private static final byte[] NEEDLE = "java/net/SocketAddress".getBytes(StandardCharsets.US_ASCII);

    private HostAgent() {}

    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
                try {
                    if (name == null || name.startsWith("java/") || name.startsWith("sun/") || name.startsWith("jdk/")) return null;
                    if (!contains(bytes, NEEDLE)) return null;
                    return patch(name, bytes);
                } catch (Throwable t) {
                    System.err.println("[cs-host-agent] skipped " + name + ": " + t);
                    return null;
                }
            }
        }, false);
        System.out.println("[cs-host-agent] ready, " + PROPERTY + "=" + System.getProperty(PROPERTY, "(unset: 8)"));
    }

    /** The patched class, or null when {@code bytes} is not the integrated player list. */
    static byte[] patch(String name, byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean loginCheck = false;
        for (MethodNode m : cn.methods) {
            if (m.desc.startsWith("(Ljava/net/SocketAddress;L") && (m.access & Opcodes.ACC_STATIC) == 0) loginCheck = true;
        }
        if (!loginCheck) return null;
        int patched = 0;
        for (MethodNode m : cn.methods) {
            if (!"<init>".equals(m.name)) continue;
            InsnList insns = m.instructions;
            for (AbstractInsnNode n = insns.getFirst(); n != null; n = n.getNext()) {
                if (n.getOpcode() != Opcodes.BIPUSH || ((IntInsnNode) n).operand != 8) continue;
                AbstractInsnNode next = n.getNext();
                if (!(next instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) next;
                if (call.getOpcode() != Opcodes.INVOKESPECIAL || !"<init>".equals(call.name)) continue;
                if (!call.desc.endsWith("I)V") || cn.name.equals(call.owner) || !call.owner.equals(cn.superName)) continue;
                if (countArgs(call.desc) != 4) continue;
                InsnList repl = new InsnList();
                repl.add(new LdcInsnNode(PROPERTY));
                repl.add(new IntInsnNode(Opcodes.BIPUSH, 8));
                repl.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "getInteger", "(Ljava/lang/String;I)Ljava/lang/Integer;", false));
                repl.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
                repl.add(new InsnNode(Opcodes.NOP));
                AbstractInsnNode last = repl.getLast();
                insns.insertBefore(n, repl);
                insns.remove(n);
                n = last;
                patched++;
            }
        }
        if (patched == 0) return null;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        System.out.println("[cs-host-agent] player limit lifted in " + name + " (" + PROPERTY + "=" + System.getProperty(PROPERTY, "8") + ")");
        return cw.toByteArray();
    }

    /** Parameter count of a method descriptor. */
    static int countArgs(String desc) {
        int n = 0;
        int i = 1;
        while (desc.charAt(i) != ')') {
            char c = desc.charAt(i);
            if (c == 'L') { i = desc.indexOf(';', i) + 1; n++; }
            else if (c == '[') { i++; }
            else { i++; n++; }
        }
        return n;
    }

    static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }
}
