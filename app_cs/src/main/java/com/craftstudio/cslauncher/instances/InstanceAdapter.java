package com.craftstudio.cslauncher.instances;

import com.craftstudio.cslauncher.utils.ThemeColors;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import androidx.core.graphics.ColorUtils;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.fragments.InstanceVersionResolver;

import com.craftstudio.cslauncher.Tools;

import fr.spse.extended_view.ExtendedTextView;

/*
 * Adapter for listing launcher profiles in a Spinner
 */
public class InstanceAdapter extends BaseAdapter {
    private Instances mInstances;
    private int mSelectionIndex;
    private final InstanceAdapterExtra[] mExtraEntires;


    public InstanceAdapter(InstanceAdapterExtra[] extraEntries) {
        if(extraEntries == null) extraEntries = new InstanceAdapterExtra[0];
        mExtraEntires = extraEntries;
    }
    /**
     * @return how much entries (both instances and extra adapter entries) are in the adapter right now
     */
    @Override
    public int getCount() {
        if(mInstances == null) return mExtraEntires.length;
        return mInstances.list.size() + mExtraEntires.length;
    }
    /**
     * Gets the adapter entry at a given index
     * @param position index to retrieve
     * @return Instance, ProfileAdapterExtra or null
     */
    @Override
    public Object getItem(int position) {
        if(mInstances == null) return mExtraEntires[position];
        int instanceListSize = mInstances.list.size();
        int extraPosition = position - instanceListSize;
        if(position < instanceListSize) {
            return mInstances.list.get(position);
        }else if(extraPosition >= 0 && extraPosition < mExtraEntires.length){
            return mExtraEntires[extraPosition];
        }
        return null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_version_profile_layout, parent, false);
            // First bind only: shared press feedback for dropdown rows (engine is
            // idempotent and gated by the TYPE_BUTTONS preference)
            com.craftstudio.cslauncher.utils.CSAnimationEngine.attachPressFeedback(v);
        }
        setView(v, position, true);
        return v;
    }

    public void setViewInstance(View v, DisplayInstance i, int idx, boolean displaySelection) {
        ExtendedTextView extendedTextView = (ExtendedTextView) v;
        // One line, always - both for the closed spinner and the popup rows
        extendedTextView.setSingleLine(true);
        extendedTextView.setEllipsize(android.text.TextUtils.TruncateAt.END);

        //MinecraftProfile minecraftProfile = mProfiles.get(nm);
        //if(minecraftProfile == null) minecraftProfile = dummy;
        Drawable cachedIcon = InstanceIconProvider.fetchIcon(v.getResources(), i);
        extendedTextView.setCompoundDrawablesRelative(cachedIcon, null, extendedTextView.getCompoundsDrawables()[2], null);

        // Historically, the profile name "New" was hardcoded as the default profile name
        // We consider "New" the same as putting no name at all

        String profileName = plainProfileName(Tools.validOrNullString(i.name), i.versionId);
        // The raw id carries the loader prefix (fabric-loader-0.18.2-1.21.11); UI rows
        // never show that: plain short version for the list, plain name on the spinner.
        String versionName = Tools.validOrNullString(InstanceVersionResolver.shortVersion(i.versionId));

        if (Instance.VERSION_LATEST_RELEASE.equalsIgnoreCase(i.versionId))
            versionName = v.getContext().getString(R.string.profiles_latest_release);
        else if (Instance.VERSION_LATEST_SNAPSHOT.equalsIgnoreCase(i.versionId))
            versionName = v.getContext().getString(R.string.profiles_latest_snapshot);

        if (!displaySelection) {
            // The home-screen spinner chip: the profile NAME only
            String nameOnly = profileName != null ? profileName
                           : (versionName != null ? versionName : i.versionId);
            extendedTextView.setText(nameOnly != null ? nameOnly : "");
        } else if (versionName == null && profileName != null)
            extendedTextView.setText(profileName);
        else if (versionName != null && profileName == null)
            extendedTextView.setText(versionName);
        else extendedTextView.setText(String.format("%s - %s", profileName, versionName));

        // Set selected background if needed
        if(idx == mSelectionIndex && displaySelection) {
            extendedTextView.setBackgroundColor(ThemeColors.get(extendedTextView, com.google.android.material.R.attr.colorSecondaryContainer));
        }else {
            extendedTextView.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    public void setViewExtra(View v, InstanceAdapterExtra extra) {
        ExtendedTextView extendedTextView = (ExtendedTextView) v;
        extendedTextView.setCompoundDrawablesRelative(extra.icon, null, extendedTextView.getCompoundsDrawables()[2], null);
        extendedTextView.setText(extra.name);
        extendedTextView.setBackgroundColor(Color.TRANSPARENT);
    }

    /**
     * Cleans a profile name for display: some profiles are created with the raw
     * version/loader id appended to their name (e.g. "Spunky Optimized -
     * fabric-loader-0.18.2-1.21.11"). The home spinner must show the plain name
     * only, so any trailing technical suffix is trimmed. Names without a suffix
     * ("Spunky Boost", "Vanilla", …) pass through untouched.
     */
    static String plainProfileName(String name, String versionId) {
        if (name == null) return null;
        String n = name.trim();
        if (versionId != null && !versionId.isEmpty() && n.endsWith(versionId))
            n = n.substring(0, n.length() - versionId.length());
        // Trailing "- <loader id>" / "- forge 1.2.3" style suffixes
        n = n.replaceAll("(?i)[\\s\\-_–—]+(?:fabric|quilt|optifabric|legacy[- ]?fabric)?[-_ ]?loader[\\-_ ]?[\\w.+-]*$", "");
        n = n.replaceAll("(?i)[\\s\\-_–—]+(?:neo|legacy[- ])?forge[\\-_ ]?[\\w.+-]*$", "");
        n = n.replaceAll("(?i)[\\s\\-_–—]+liteloader[\\-_ ]?[\\w.+-]*$", "");
        // Trailing plain Minecraft version ("My Pack - 1.21.11")
        n = n.replaceAll("[\\s\\-_–—]+\\d+\\.\\d+[\\d.]*$", "");
        n = n.replaceAll("[\\s\\-_–—]+$", "").trim();
        return n.isEmpty() ? null : n;
    }

    public void setView(View v, int index, boolean displaySelection) {
        Object object = getItem(index);
        if(object instanceof DisplayInstance) {
            setViewInstance(v, (DisplayInstance) object, index, displaySelection);
        }else if(object instanceof InstanceAdapterExtra) {
            setViewExtra(v, (InstanceAdapterExtra) object);
        }
    }

    public void applySelectionIndex(int index) {
        mSelectionIndex = index;
    }

    public void applyInstances(Instances instances) {
        mInstances = instances;
        mSelectionIndex = instances.selectedIndex;
        notifyDataSetChanged();
    }
}
