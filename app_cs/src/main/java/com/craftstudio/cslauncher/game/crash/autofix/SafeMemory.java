package com.craftstudio.cslauncher.game.crash.autofix;

/**
 * Device-safe heap size, mirroring the launcher's first-run heuristic (LauncherPreferences) and
 * clamped by the address space on 32-bit devices. Kept Android free for tests.
 */
public final class SafeMemory {
    private SafeMemory() {}

    /** Same table the launcher uses for its default allocation. */
    public static int defaultFor(int deviceRamMb) {
        if (deviceRamMb < 1024) return 296;
        if (deviceRamMb < 1536) return 448;
        if (deviceRamMb < 2048) return 656;
        if (deviceRamMb < 3064) return 936;
        if (deviceRamMb < 4096) return 1144;
        if (deviceRamMb < 6144) return 1536;
        return 2048;
    }

    /**
     * Picks a new allocation after an out-of-memory crash.
     * @param current the allocation that crashed (MB)
     * @param deviceRamMb total device RAM
     * @param is32Bit 32-bit process (address space ceiling ~1.2 GB)
     * @param heapExhausted true for "Java heap space" (needs more), false for native/system kills (needs less)
     * @return the value to use, or -1 when no change would help
     */
    public static int recommend(int current, int deviceRamMb, boolean is32Bit, boolean heapExhausted) {
        int def = defaultFor(deviceRamMb);
        int ceiling = is32Bit ? 1200 : Math.max(def, (int) (deviceRamMb * 0.55f));
        if (heapExhausted) {
            // Give more, but never beyond what the device can safely hand to one process.
            int target = Math.max(def, (int) (current * 1.25f));
            target = Math.min(target, ceiling);
            target = (target / 8) * 8;
            return target > current ? target : -1;
        } else {
            // Native/system pressure: step down toward the default, never below the floor.
            int floor = Math.min(def, 512);
            int target = Math.min(def, (int) (current * 0.8f));
            target = Math.max(target, floor);
            target = (target / 8) * 8;
            return target < current ? target : -1;
        }
    }
}
