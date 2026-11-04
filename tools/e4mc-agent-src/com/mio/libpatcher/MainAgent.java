package com.mio.libpatcher;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

import javassist.ByteArrayClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;

public final class MainAgent {

    private static final String TAG = "[MasterixE4mc] ";

    private static final String[] TARGETS = {
        "io/netty/incubator/codec/quic/Quiche",
        "dev/xhyrom/e4mc/shadow/io/netty/incubator/codec/quic/Quiche"
    };

    private static final String PATCH =
            "{ String mxPath = System.getProperty(\"link.e4mc.native_path\");"
          + "  if (mxPath != null && mxPath.trim().length() > 0) {"
          + "    java.io.File mxFile = new java.io.File(mxPath.trim());"
          + "    if (mxFile.isFile() && mxFile.length() > 0L) {"
          + "      System.out.println(\"" + TAG + "loading native \" + mxFile.getAbsolutePath());"
          + "      System.load(mxFile.getAbsolutePath());"
          + "      System.out.println(\"" + TAG + "native loaded\");"
          + "      return;"
          + "    }"
          + "    System.out.println(\"" + TAG + "native_path set but file missing: \" + mxPath);"
          + "  } }";

    private MainAgent() {}

    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer(new QuicheTransformer());
        System.out.println(TAG + "agent attached");
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    private static final class QuicheTransformer
            implements ClassFileTransformer {

        @Override
        public byte[] transform(
                ClassLoader loader,
                String className,
                Class<?> redefined,
                ProtectionDomain domain,
                byte[] classfile) {

            if (className == null) return null;

            boolean match = false;

            for (String t : TARGETS) {
                if (t.equals(className)) {
                    match = true;
                    break;
                }
            }

            if (!match) return null;

            try {
                ClassPool pool = new ClassPool(true);

                if (loader != null) {
                    pool.insertClassPath(new LoaderClassPath(loader));
                }

                String dotted = className.replace('/', '.');

                pool.insertClassPath(
                    new ByteArrayClassPath(dotted, classfile)
                );

                CtClass cc = pool.get(dotted);

                CtMethod target = null;

                for (CtMethod m : cc.getDeclaredMethods("loadNativeLibrary")) {
                    if (m.getParameterTypes().length == 0
                            && m.getReturnType() == CtClass.voidType) {
                        target = m;
                        break;
                    }
                }

                if (target == null) {
                    System.out.println(
                        TAG + "loadNativeLibrary() not found in " + dotted
                    );
                    return null;
                }

                target.insertBefore(PATCH);

                byte[] out = cc.toBytecode();

                cc.detach();

                System.out.println(TAG + "patched " + dotted);

                return out;

            } catch (Throwable t) {
                System.out.println(
                    TAG + "patch failed for " + className + ": " + t
                );

                t.printStackTrace();

                return null;
            }
        }
    }
}
