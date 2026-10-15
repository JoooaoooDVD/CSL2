package com.craftstudio.cslauncher.prefs;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.Preference;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.extra.ExtraConstants;
import com.craftstudio.cslauncher.extra.ExtraCore;

public class BackButtonPreference extends Preference {
    public BackButtonPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @SuppressWarnings("unused") public BackButtonPreference(Context context) {
        this(context, null);
    }

    private void init(){
        setLayoutResource(R.layout.item_setting_back);
        if(getTitle() == null){
            setTitle(R.string.settings_screen_back);
        }
        if(getIcon() == null){
            setIcon(R.drawable.ic_px_arrow_left);
        }
    }


    @Override
    protected void onClick() {
        // It is caught by an ExtraListener in the LauncherActivity
        ExtraCore.setValue(ExtraConstants.BACK_PREFERENCE, "true");
    }
}
