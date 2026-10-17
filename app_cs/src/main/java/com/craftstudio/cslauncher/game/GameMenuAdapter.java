package com.craftstudio.cslauncher.game;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;

/** Rows of the in-game menu: icon tile, label and chevron (item_game_menu.xml) */
public class GameMenuAdapter extends ArrayAdapter<String> {
    private final int[] mIcons;
    private final LayoutInflater mInflater;

    public GameMenuAdapter(@NonNull Context context, @NonNull String[] labels, @NonNull @DrawableRes int[] icons) {
        super(context, R.layout.item_game_menu, R.id.gm_label, labels);
        mIcons = icons;
        mInflater = LayoutInflater.from(context);
    }

    /** Icons for R.array.menu_ingame, in order */
    public static final int[] INGAME_ICONS = {
            R.drawable.ic_gm_power,     // Force close
            R.drawable.ic_gm_terminal,  // Log output
            R.drawable.ic_gm_keyboard,  // Send custom keycode
            R.drawable.ic_gm_sliders,   // Quick settings
            R.drawable.ic_gm_gamepad,   // Custom controls
            R.drawable.ic_gm_package,   // Browse resources
            R.drawable.ic_gm_lan,       // Virtual LAN multiplayer
    };

    /** Icons for R.array.menu_customcontrol, in order */
    public static final int[] EDITOR_ICONS = {
            R.drawable.ic_gm_add_button, // Add button
            R.drawable.ic_gm_layers,     // Add drawer
            R.drawable.ic_gm_joystick,   // Add joystick
            R.drawable.ic_gm_folder,     // Load
            R.drawable.ic_gm_save,       // Save
            R.drawable.ic_gm_star,       // Set as default
            R.drawable.ic_gm_logout,     // Exit editor
    };

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View row = convertView != null ? convertView : mInflater.inflate(R.layout.item_game_menu, parent, false);
        TextView label = row.findViewById(R.id.gm_label);
        ImageView icon = row.findViewById(R.id.gm_icon);
        label.setText(getItem(position));
        icon.setImageResource(position < mIcons.length ? mIcons[position] : R.drawable.ic_gm_chevron);
        return row;
    }
}
