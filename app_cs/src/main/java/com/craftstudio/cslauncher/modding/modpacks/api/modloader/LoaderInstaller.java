package com.craftstudio.cslauncher.modding.modpacks.api.modloader;

import com.craftstudio.cslauncher.instances.InstanceInstaller;

import java.io.IOException;

public interface LoaderInstaller {
    boolean requiresGuiInstallation();
    InstanceInstaller createInstaller() throws IOException;
    String installHeadlessly() throws IOException;
}
