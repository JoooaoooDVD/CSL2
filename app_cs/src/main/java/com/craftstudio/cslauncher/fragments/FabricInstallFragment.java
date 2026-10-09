package com.craftstudio.cslauncher.fragments;

import com.craftstudio.cslauncher.modding.fabric.FabriclikeUtils;

public class FabricInstallFragment extends FabriclikeInstallFragment {

    public static final String TAG = "FabricInstallFragment";

    public FabricInstallFragment() {
        super(FabriclikeUtils.FABRIC_UTILS, TAG);
    }
}
