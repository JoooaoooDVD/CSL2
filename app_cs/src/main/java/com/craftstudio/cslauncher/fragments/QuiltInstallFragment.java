package com.craftstudio.cslauncher.fragments;

import com.craftstudio.cslauncher.modding.fabric.FabriclikeUtils;
import com.craftstudio.cslauncher.modding.ModloaderListenerProxy;

public class QuiltInstallFragment extends FabriclikeInstallFragment {

    public static final String TAG = "QuiltInstallFragment";
    private static ModloaderListenerProxy sTaskProxy;

    public QuiltInstallFragment() {
        super(FabriclikeUtils.QUILT_UTILS, TAG);
    }
}
