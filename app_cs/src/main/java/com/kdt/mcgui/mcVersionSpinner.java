package com.kdt.mcgui;

import com.craftstudio.cslauncher.utils.AnimationManager;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import com.craftstudio.cslauncher.utils.CSAnimationEngine;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.transition.Transition;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import com.craftstudio.cslauncher.R;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.extra.ExtraConstants;
import com.craftstudio.cslauncher.extra.ExtraCore;
import com.craftstudio.cslauncher.extra.ExtraListener;
import com.craftstudio.cslauncher.fragments.InstanceEditorFragment;
import com.craftstudio.cslauncher.fragments.SearchModFragment;
import com.craftstudio.cslauncher.instances.DisplayInstance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.instances.InstanceAdapter;
import com.craftstudio.cslauncher.instances.InstanceAdapterExtra;

import java.io.IOException;

import fr.spse.extended_view.ExtendedTextView;

/**
 * A class implementing custom spinner like behavior, notably:
 * dropdown popup view with a custom direction.
 */
public class mcVersionSpinner extends ExtendedTextView {
    private static final int VERSION_SPINNER_PROFILE_CREATE = 0;
    public mcVersionSpinner(@NonNull Context context) {
        super(context);
        init();
    }
    public mcVersionSpinner(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    public mcVersionSpinner(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    /* The class is in charge of displaying its own list with adapter content being known in advance */
    private ProfilePickerDialog mPickerDialog;
    private CSPopupDialog mCreateDialog;
    private int mSelectedIndex;

    private final InstanceAdapter mProfileAdapter = new InstanceAdapter(new InstanceAdapterExtra[]{
            new InstanceAdapterExtra(VERSION_SPINNER_PROFILE_CREATE,
                    R.string.create_instance,
                    ResourcesCompat.getDrawable(getResources(), R.drawable.ic_add, getContext().getTheme())),
    });


    /** Set the selection AND saves it as a shared preference */
    public void setProfileSelection(int position){
        setSelection(position);
        Instances.setSelectedInstance((DisplayInstance) mProfileAdapter.getItem(position));
    }

    public void setSelection(int position){
        mProfileAdapter.setView(this, position, false);
        mSelectedIndex = position;
        mProfileAdapter.applySelectionIndex(mSelectedIndex);
    }

    public void openProfileEditor(FragmentActivity fragmentActivity) {
        Object currentSelection = mProfileAdapter.getItem(mSelectedIndex);
        if(currentSelection instanceof InstanceAdapterExtra) {
            performExtraAction((InstanceAdapterExtra) currentSelection);
        }else{
            Tools.swapFragment(fragmentActivity, InstanceEditorFragment.class, InstanceEditorFragment.TAG, null);
        }
    }

    private void applyInstances(Instances instances) {
        mProfileAdapter.applyInstances(instances);
        setSelection(instances.selectedIndex);
    }

    /** Reload profiles from the file, forcing the spinner to consider the new data */
    public void reloadProfiles() {
        CSLauncherApplication.sExecutorService.execute(()->{
            try {
                final Instances instances = Instances.loadDisplay();
                Tools.runOnUiThread(()->applyInstances(instances));
            } catch (final IOException e) {
                Tools.runOnUiThread(()->Tools.showError(getContext(), e));
            }
        });
    }

    /** Initialize various behaviors */
    private void init(){
        // Setup various attributes
        setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen._12ssp));
        setGravity(Gravity.CENTER_VERTICAL);
        int startPadding = getContext().getResources().getDimensionPixelOffset(R.dimen._17sdp);
        int endPadding = getContext().getResources().getDimensionPixelOffset(R.dimen._5sdp);
        setPaddingRelative(startPadding, 0, endPadding, 0);
        setCompoundDrawablePadding(startPadding);
        // The selected-profile chip must stay on one line: long profile names are
        // ellipsized instead of wrapping (which used to blow up the home card height).
        // Done programmatically because ExtendedTextView ignores the XML singleLine.
        setHorizontallyScrolling(true);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        // The profile chip is a real button: shared CS press feedback (gated by the
        // TYPE_BUTTONS preference, skipped when the style already provides one)
        CSAnimationEngine.attachPressFeedback(this);
        addOnAttachStateChangeListener(new ExtraAttachListener());
        setSelection(0);

        // Profile list: a centered popup (ProfilePickerDialog) instead of a drop-down
        setOnClickListener(v -> showProfilePicker());
    }

    /** Opens the profile picker popup, also used by the home version chip / PLAY chevron */
    public void showProfilePicker() {
        if (mPickerDialog != null && mPickerDialog.isShowing()) return;
        java.util.List<DisplayInstance> instances = new java.util.ArrayList<>();
        int extraIndex = -1;
        for (int i = 0; i < mProfileAdapter.getCount(); i++) {
            Object item = mProfileAdapter.getItem(i);
            if (item instanceof DisplayInstance) instances.add((DisplayInstance) item);
            else if (item instanceof InstanceAdapterExtra && extraIndex < 0) extraIndex = i;
        }
        // Instances come first in the adapter, so list positions == adapter positions
        final int createIndex = extraIndex;
        int selected = mSelectedIndex < instances.size() ? mSelectedIndex : -1;
        mPickerDialog = new ProfilePickerDialog(getContext(), instances, selected, new ProfilePickerDialog.Callback() {
            @Override
            public void onProfileSelected(int position) {
                if (position != mSelectedIndex) setProfileSelection(position);
            }

            @Override
            public void onProfileEdit(int position) {
                if (position != mSelectedIndex) setProfileSelection(position);
                if (getContext() instanceof FragmentActivity) openProfileEditor((FragmentActivity) getContext());
            }

            @Override
            public void onCreateInstance() {
                Object item = createIndex >= 0 ? mProfileAdapter.getItem(createIndex) : null;
                if (item instanceof InstanceAdapterExtra) performExtraAction((InstanceAdapterExtra) item);
            }
        });
        mPickerDialog.setOnDismissListener(d -> mPickerDialog = null);
        mPickerDialog.show();
    }

    private void performExtraAction(InstanceAdapterExtra extra) {
        //Replace with switch-case if you want to add more extra actions
        if (extra.id == VERSION_SPINNER_PROFILE_CREATE) showCreateChooser();
    }

    /** Create New Instance: Normal / CS Client / Modpack chooser popup */
    private void showCreateChooser() {
        if (!(getContext() instanceof FragmentActivity)) return;
        final FragmentActivity activity = (FragmentActivity) getContext();
        if (mCreateDialog != null && mCreateDialog.isShowing()) return;
        mCreateDialog = new CreateChooserDialog(activity, new CreateChooserDialog.Callback() {
            @Override
            public void onNormal() {
                showCreateForm();
            }

            @Override
            public void onModpack() {
                if (activity.isFinishing()) return;
                Tools.swapFragment(activity, SearchModFragment.class, SearchModFragment.TAG, null);
            }
        });
        mCreateDialog.show();
    }

    /** Normal profile form; its back button returns to the chooser */
    private void showCreateForm() {
        if (!(getContext() instanceof FragmentActivity) || ((FragmentActivity) getContext()).isFinishing()) return;
        mCreateDialog = new CreateInstanceDialog(getContext(), this::showCreateChooser);
        mCreateDialog.show();
    }


    class ExtraAttachListener implements OnAttachStateChangeListener, ExtraListener<Void> {
        @Override
        public void onViewAttachedToWindow(@NonNull View view) {
            reloadProfiles();
            ExtraCore.addExtraListener(ExtraConstants.REFRESH_VERSION_SPINNER, this);
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull View view) {
            // Screen going away (activity finishing / fragment replaced): no leaked popup window
            if (mPickerDialog != null && mPickerDialog.isShowing()) mPickerDialog.dismiss();
            if (mCreateDialog != null && mCreateDialog.isShowing()) mCreateDialog.dismiss();
            ExtraCore.removeExtraListenerFromValue(ExtraConstants.REFRESH_VERSION_SPINNER, this);
        }

        @Override
        public boolean onValueSet(String key, @NonNull Void value) {
            post(mcVersionSpinner.this::reloadProfiles);
            ExtraCore.consumeValue(key);
            return false;
        }
    }
}
