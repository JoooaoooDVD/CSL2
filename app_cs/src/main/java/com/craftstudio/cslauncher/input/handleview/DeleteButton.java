package com.craftstudio.cslauncher.input.handleview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.Button;

import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.input.buttons.ControlInterface;

@SuppressLint("AppCompatCustomView")
public class DeleteButton extends Button implements ActionButtonInterface {
    public DeleteButton(Context context) {super(context); init();}
    public DeleteButton(Context context, @Nullable AttributeSet attrs) {super(context, attrs); init();}

    public void init() {
        setOnClickListener(this);
        setAllCaps(true);
        setText(R.string.global_delete);

    }

    private ControlInterface mCurrentlySelectedButton = null;

    @Override
    public boolean shouldBeVisible() {
        return mCurrentlySelectedButton != null;
    }

    @Override
    public void setFollowedView(ControlInterface view) {
        mCurrentlySelectedButton = view;
    }

    @Override
    public void onClick() {
        if(mCurrentlySelectedButton == null) return;

        // Grab the layout before the view is detached: afterwards getParent() is null and the
        // pre-draw listeners registered on the removed view would never fire again, leaving the
        // resize handle / action row / side panel on screen for a control that no longer exists.
        com.craftstudio.cslauncher.input.ControlLayout layout = mCurrentlySelectedButton.getControlLayoutParent();
        mCurrentlySelectedButton.removeButton();
        mCurrentlySelectedButton = null;
        if(layout != null) layout.removeEditWindow();
    }
}
