package com.craftstudio.cslauncher.input.handleview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.math.MathUtils;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.input.buttons.ControlInterface;

/**
 * Layout floating around a Control Button, displaying contextual actions
 */
public class ActionRow extends LinearLayout {

    public static final int SIDE_LEFT = 0x0;
    public static final int SIDE_TOP = 0x1;
    public static final int SIDE_RIGHT = 0x2;
    public static final int SIDE_BOTTOM = 0x3;
    public static final int SIDE_AUTO = 0x4;

    public ActionRow(Context context) {
        super(context); init();
    }
    public ActionRow(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs); init();
    }

    public final ViewTreeObserver.OnPreDrawListener mFollowedViewListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if(mFollowedView == null || !mFollowedView.isShown() || !isEditorActive()){
                hide();
                return true;
            }

            setNewPosition();
            return true;
        }
    };
    private final ActionButtonInterface[] actionButtons = new ActionButtonInterface[3];
    private View mFollowedView = null;
    private final int mSide = SIDE_AUTO;

    /** Add action buttons and configure them */
    private void init(){
        setTranslationZ(11);
        setVisibility(GONE);
        setOrientation(HORIZONTAL);
        setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                getResources().getDimensionPixelOffset(R.dimen._40sdp)
        ));

        actionButtons[0] = new DeleteButton(getContext());
        actionButtons[1] = new CloneButton(getContext());
        actionButtons[2] = new AddSubButton(getContext());

        // Navy pill with icon + label actions (Delete is red)
        setBackgroundResource(R.drawable.bg_ce_action_row);
        int pad = Math.round(4 * getResources().getDisplayMetrics().density);
        setPadding(pad, pad, pad, pad);
        styleAction((android.widget.Button) actionButtons[0], R.drawable.ic_cc_delete, R.color.hm_text);
        styleAction((android.widget.Button) actionButtons[1], R.drawable.ic_cc_copy, R.color.hm_text);
        styleAction((android.widget.Button) actionButtons[2], R.drawable.ic_cc_sub, R.color.hm_text);

        // This is not pretty code, don't do this.
        for(ActionButtonInterface buttonInterface: actionButtons){
            View button = ((View)(buttonInterface));
            addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        setElevation(5F);
    }

    /** Pill action look: transparent background, 16dp tinted icon, bold label */
    private void styleAction(android.widget.Button button, int iconRes, int colorRes) {
        float density = getResources().getDisplayMetrics().density;
        int color = androidx.core.content.ContextCompat.getColor(getContext(), colorRes);
        button.setBackgroundResource(R.drawable.bg_ce_action_btn);
        button.setStateListAnimator(null);
        button.setAllCaps(false);
        button.setTextColor(color);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(Math.round(12 * density), 0, Math.round(14 * density), 0);
        button.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.Drawable icon = androidx.core.content.ContextCompat.getDrawable(getContext(), iconRes);
        if (icon != null) {
            icon = androidx.core.graphics.drawable.DrawableCompat.wrap(icon.mutate());
            androidx.core.graphics.drawable.DrawableCompat.setTint(icon, color);
            int size = Math.round(16 * density);
            icon.setBounds(0, 0, size, size);
            button.setCompoundDrawablesRelative(icon, null, null, null);
            button.setCompoundDrawablePadding(Math.round(6 * density));
        }
        com.craftstudio.cslauncher.utils.CSAnimationEngine.attachPressFeedback(button);
    }

    public void setFollowedButton(ControlInterface controlInterface){
        if(mFollowedView != null)
            mFollowedView.getViewTreeObserver().removeOnPreDrawListener(mFollowedViewListener);

        boolean anyAction = false;
        for(ActionButtonInterface buttonInterface: actionButtons){
            buttonInterface.setFollowedView(controlInterface);
            boolean visible = buttonInterface.shouldBeVisible();
            anyAction |= visible;
            ((View)(buttonInterface)).setVisibility(visible ? VISIBLE : GONE);
        }

        // Deselecting (null) or nothing to offer: the row must disappear entirely. Showing the
        // pill with every action GONE left an empty 8dp-wide padded background on screen — the
        // stray vertical line seen next to the last edited control, in the editor and in game.
        if(controlInterface == null || !anyAction || !isEditorActive()){
            mFollowedView = null;
            setVisibility(GONE);
            return;
        }

        boolean appearing = getVisibility() != VISIBLE;
        setVisibility(VISIBLE);
        if (appearing) com.craftstudio.cslauncher.utils.motion.Motion.popIn(this, 0, 0.8f);
        mFollowedView = (View) controlInterface;
        mFollowedView.getViewTreeObserver().addOnPreDrawListener(mFollowedViewListener);
    }

    /** The row only exists for the control editor; in normal gameplay it must never show. */
    private boolean isEditorActive(){
        android.view.ViewParent parent = getParent();
        if(parent instanceof com.craftstudio.cslauncher.input.ControlLayout){
            return ((com.craftstudio.cslauncher.input.ControlLayout) parent).getModifiable();
        }
        return false;
    }

    private float getXPosition(int side){
        if(side == SIDE_LEFT){
            return mFollowedView.getX() - getWidth();
        }else if(side == SIDE_RIGHT){
            return mFollowedView.getX() + mFollowedView.getWidth();
        }else{
            return mFollowedView.getX() + mFollowedView.getWidth()/2f - getWidth()/2f;
        }
    }

    private float getYPosition(int side){
        if(side == SIDE_TOP){
            return mFollowedView.getY() - getHeight();
        } else if(side == SIDE_BOTTOM){
            return mFollowedView.getY() + mFollowedView.getHeight();
        }else{
            return mFollowedView.getY() + mFollowedView.getHeight()/2f - getHeight()/2f;
        }
    }

    private void setNewPosition(){
        if(mFollowedView == null) return;
        int side = pickSide();

        setX(MathUtils.clamp(getXPosition(side), 0, ((ViewGroup) getParent()).getWidth() - getWidth()));
        setY(getYPosition(side));
    }

    private int pickSide(){
        if(mFollowedView == null) return mSide; //Value should not matter

        if(mSide != SIDE_AUTO) return mSide;
        //TODO improve the "algo"
        ViewGroup parent = ((ViewGroup) mFollowedView.getParent());
        if(parent == null) return mSide;//Value should not matter

        int side = SIDE_TOP;
        float futurePos = getYPosition(side);
        if(futurePos + getHeight() > (parent.getHeight() + getHeight()/2f)){
            side = SIDE_TOP;
        }else if (futurePos < -getHeight()/2f){
            side = SIDE_BOTTOM;
        }

        return side;
    }

    public void hide(){
        if(mFollowedView != null)
            mFollowedView.getViewTreeObserver().removeOnPreDrawListener(mFollowedViewListener);
        mFollowedView = null;
        setVisibility(GONE);
    }
}
