package com.craftstudio.cslauncher.game.branding;

import java.io.IOException;
import java.io.InputStream;

/**
 * Where the bundled branding files come from. On Android this is the APK asset folder
 * {@code cs_branding/}; the desktop test harness feeds it from a plain directory so the
 * generator can be exercised against real client jars without a device.
 */
public interface BrandingAssetSource {
    /** @param name file name inside the branding asset folder, e.g. "minecraft.png" */
    InputStream open(String name) throws IOException;
}
