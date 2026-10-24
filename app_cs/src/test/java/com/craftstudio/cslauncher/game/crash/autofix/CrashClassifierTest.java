package com.craftstudio.cslauncher.game.crash.autofix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class CrashClassifierTest {

    @Test public void outOfMemoryHeap() {
        CrashAnalysis a = Fixtures.analyze("oom_heap.log", 1, false, "fabric");
        assertEquals(CrashCategory.OUT_OF_MEMORY, a.category);
        assertEquals(1, a.plan.size());
        assertEquals(RepairStep.Kind.ADJUST_MEMORY, a.plan.get(0).kind);
        assertEquals("heap", a.plan.get(0).detail);
        assertTrue(a.safeToAutoFix);
        assertTrue(a.confidence > 0.9f);
    }

    @Test public void outOfMemoryNative() {
        CrashAnalysis a = Fixtures.analyze("oom_native.log", 134, true, "forge");
        assertEquals(CrashCategory.OUT_OF_MEMORY, a.category);
        assertEquals("native", a.plan.get(0).detail);
    }

    @Test public void sigkillWithoutJavaErrorIsMemoryPressure() {
        CrashAnalysis a = Fixtures.analyze("clean_exit_noise.log", 9, true, "fabric");
        assertEquals(CrashCategory.OUT_OF_MEMORY, a.category);
        assertEquals("native", a.plan.get(0).detail);
        assertTrue(a.confidence < 0.7f);
    }

    @Test public void invalidVmOption() {
        CrashAnalysis a = Fixtures.analyze("jvm_arg.log", 1, false, "");
        assertEquals(CrashCategory.INVALID_JVM_ARGUMENT, a.category);
        assertEquals(RepairStep.Kind.PRUNE_JVM_ARGS, a.plan.get(0).kind);
        assertEquals("-XX:UseZGCFoo", a.plan.get(0).target);
        assertFalse(a.requiresConfirmation);
    }

    @Test public void invalidOption() {
        CrashAnalysis a = Fixtures.analyze("jvm_arg_option.log", 1, false, "");
        assertEquals(CrashCategory.INVALID_JVM_ARGUMENT, a.category);
        assertEquals("-Xfoo", a.plan.get(0).target);
    }

    @Test public void fabricMissingDependencies() {
        CrashAnalysis a = Fixtures.analyze("fabric_missing_dep.log", 1, false, "fabric");
        assertEquals(CrashCategory.MISSING_DEPENDENCY, a.category);
        assertEquals(2, a.plan.size());
        assertEquals("fabric-api", a.plan.get(0).target);
        assertEquals("sodium", a.plan.get(1).target);
        assertEquals("0.5.8", a.plan.get(1).detail);
        assertEquals("fabric-api", a.suspectComponent);
        assertTrue(a.safeToAutoFix);
    }

    @Test public void fabricWrongGameVersionIsIncompatibleNotMissing() {
        CrashAnalysis a = Fixtures.analyze("fabric_wrong_version.log", 1, false, "fabric");
        assertEquals(CrashCategory.INCOMPATIBLE_MOD_VERSION, a.category);
        assertEquals(RepairStep.Kind.DISABLE_MOD, a.plan.get(0).kind);
        assertEquals("lithium", a.plan.get(0).target);
        assertTrue(a.requiresConfirmation);
    }

    @Test public void forgeMissingDependencies() {
        CrashAnalysis a = Fixtures.analyze("forge_missing_dep.log", 1, false, "forge");
        assertEquals(CrashCategory.MISSING_DEPENDENCY, a.category);
        assertEquals(Arrays.asList("geckolib", "citadel"), Arrays.asList(a.plan.get(0).target, a.plan.get(1).target));
        assertEquals("[4.4,)", a.plan.get(0).detail);
    }

    @Test public void mixinFailureNamesTheMod() {
        CrashAnalysis a = Fixtures.analyze("mixin_conflict.log", 1, false, "fabric");
        assertEquals(CrashCategory.MOD_CONFLICT, a.category);
        assertEquals(RepairStep.Kind.DISABLE_MOD, a.plan.get(0).kind);
        assertEquals("entityculling", a.plan.get(0).target);
        assertTrue(a.requiresConfirmation);
    }

    @Test public void unsupportedClassVersionNeedsJava21() {
        CrashAnalysis a = Fixtures.analyze("unsupported_class.log", 1, false, "fabric");
        assertEquals(CrashCategory.JAVA_RUNTIME_ERROR, a.category);
        assertEquals(RepairStep.Kind.SELECT_RUNTIME, a.plan.get(0).kind);
        assertEquals("21", a.plan.get(0).target);
    }

    @Test public void corruptConfigFileIsNamed() {
        CrashAnalysis a = Fixtures.analyze("corrupt_config.log", 1, false, "fabric");
        assertEquals(CrashCategory.CORRUPTED_CONFIGURATION, a.category);
        assertEquals(RepairStep.Kind.RESET_CONFIG, a.plan.get(0).kind);
        assertEquals("sodium-extra.json", a.plan.get(0).target);
        assertTrue(a.requiresConfirmation);
    }

    @Test public void corruptLibrary() {
        CrashAnalysis a = Fixtures.analyze("corrupt_library.log", 1, false, "fabric");
        assertEquals(CrashCategory.CORRUPTED_LIBRARY, a.category);
        assertEquals(RepairStep.Kind.VERIFY_LIBRARIES, a.plan.get(0).kind);
        assertTrue(a.plan.get(0).target.endsWith("fabric-loader-0.16.5.jar"));
    }

    @Test public void graphicsInit() {
        CrashAnalysis a = Fixtures.analyze("graphics_init.log", 1, false, "");
        assertEquals(CrashCategory.GRAPHICS_INITIALIZATION_ERROR, a.category);
        assertEquals(RepairStep.Kind.FALLBACK_RENDERER, a.plan.get(0).kind);
    }

    @Test public void shaderError() {
        CrashAnalysis a = Fixtures.analyze("shader_error.log", 1, false, "fabric");
        assertEquals(CrashCategory.SHADER_ERROR, a.category);
        assertEquals(RepairStep.Kind.DISABLE_SHADERS, a.plan.get(0).kind);
        assertEquals("ComplementaryReimagined_r5.2.zip", a.suspectComponent);
    }

    @Test public void resourcePack() {
        CrashAnalysis a = Fixtures.analyze("resource_pack.log", 1, false, "");
        assertEquals(CrashCategory.RESOURCE_PACK_ERROR, a.category);
        assertEquals("Faithful-32x.zip", a.plan.get(0).target);
        assertFalse(a.requiresConfirmation);
    }

    @Test public void nativeRendererLibrary() {
        CrashAnalysis a = Fixtures.analyze("native_lib.log", 1, false, "");
        assertEquals(CrashCategory.NATIVE_LIBRARY_ERROR, a.category);
        assertEquals("libgl4es_114.so", a.suspectComponent);
        assertEquals(RepairStep.Kind.FALLBACK_RENDERER, a.plan.get(0).kind);
    }

    @Test public void diskFullHasNoAutomaticFix() {
        CrashAnalysis a = Fixtures.analyze("disk_full.log", 1, false, "");
        assertEquals(CrashCategory.DISK_OR_STORAGE_ERROR, a.category);
        assertTrue(a.plan.isEmpty());
        assertFalse(a.safeToAutoFix);
    }

    @Test public void networkDownload() {
        CrashAnalysis a = Fixtures.analyze("network_download.log", 1, false, "");
        assertEquals(CrashCategory.NETWORK_DOWNLOAD_ERROR, a.category);
        assertEquals(RepairStep.Kind.CHECK_NETWORK, a.plan.get(0).kind);
    }

    @Test public void quietLogWithExitCodeIsUnknownAndNeverFakesAFix() {
        CrashAnalysis a = Fixtures.analyze("clean_exit_noise.log", 1, false, "fabric");
        assertEquals(CrashCategory.UNKNOWN_ERROR, a.category);
        assertTrue(a.plan.isEmpty());
        assertFalse(a.safeToAutoFix);
    }

    @Test public void quietSignalDeathIsReportedWithoutAFix() {
        CrashAnalysis a = Fixtures.analyze("clean_exit_noise.log", 11, true, "fabric");
        assertEquals(CrashCategory.NATIVE_LIBRARY_ERROR, a.category);
        assertTrue(a.plan.isEmpty());
        assertFalse(a.safeToAutoFix);
    }

    @Test public void emptyLogIsUnknown() {
        CrashAnalysis a = CrashClassifier.analyze(new CrashClassifier.Input(Collections.<String>emptyList(), 1, false, null, ""));
        assertEquals(CrashCategory.UNKNOWN_ERROR, a.category);
        assertFalse(a.safeToAutoFix);
    }

    @Test public void prefixStripping() {
        assertEquals("Hello", CrashClassifier.stripPrefix("[12:00:01] [main/INFO]: Hello"));
        assertEquals("Hello", CrashClassifier.stripPrefix("[12:00:01] [Render thread/ERROR] [minecraft/Foo]: Hello"));
        assertEquals("plain", CrashClassifier.stripPrefix("plain"));
    }

    @Test public void signatureIsStablePerComponent() {
        CrashAnalysis a = Fixtures.analyze("mixin_conflict.log", 1, false, "fabric");
        CrashAnalysis b = Fixtures.analyze("mixin_conflict.log", 134, true, "fabric");
        assertEquals(a.signature, b.signature);
        assertEquals("MOD_CONFLICT|entityculling", a.signature);
    }
}
