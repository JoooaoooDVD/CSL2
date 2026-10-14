package com.kdt.mcgui;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.multirt.Runtime;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.util.List;

/**
 * Java runtime picker of the profile editor: "Auto" on top, then every installed runtime
 * as a card with its Java major version, name, version string and architecture.
 */
public class RuntimePickerDialog extends CSPopupDialog {

    public interface Callback {
        /** @param index position in the list given to the dialog; the last entry is "Auto / default" */
        void onRuntimePicked(int index);
    }

    private final List<Runtime> mRuntimes;
    private final int mSelected;
    private final Callback mCallback;

    public RuntimePickerDialog(@NonNull Context context, @NonNull List<Runtime> runtimes, int selected, @NonNull Callback callback) {
        super(context);
        mRuntimes = runtimes;
        mSelected = selected;
        mCallback = callback;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_runtime_picker);
        View card = findViewById(R.id.rt_card);
        setupPopup(findViewById(R.id.rt_scrim), card, true);
        sizeCard(card);

        View close = findViewById(R.id.rt_close);
        close.setOnClickListener(v -> onDismissRequested());
        CSAnimationEngine.attachPressFeedback(close);

        ViewGroup list = findViewById(R.id.rt_list);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        int defaultIndex = mRuntimes.size() - 1;
        boolean defaultSelected = mSelected < 0 || mSelected >= defaultIndex;

        // "Auto" first
        addRow(inflater, list, "AUTO", getContext().getString(R.string.pe_default_runtime).replace("<", "").replace(">", ""),
                getContext().getString(R.string.rt_auto_desc), null, defaultSelected, defaultIndex);

        for (int i = 0; i < defaultIndex; i++) {
            Runtime runtime = mRuntimes.get(i);
            boolean corrupt = runtime.versionString == null;
            String badge = corrupt ? "!" : (runtime.javaVersion > 0 ? String.valueOf(runtime.javaVersion) : "?");
            String desc = corrupt ? getContext().getString(R.string.rt_corrupt)
                    : (runtime.javaVersion > 0 ? getContext().getString(R.string.rt_java_fmt, runtime.javaVersion) + "  ·  " : "") + runtime.versionString;
            addRow(inflater, list, badge, runtime.name.replace(".tar.xz", ""), desc, runtime.arch, i == mSelected, i);
        }

        CreatePopupMotion.chooserContent(findViewById(R.id.rt_header), new View[0], findViewById(R.id.rt_subtitle));
        Motion.cascadeChildren(list, 120L, 45L, 14f);
    }

    private void addRow(LayoutInflater inflater, ViewGroup list, String badge, String name, String desc,
                        String arch, boolean selected, int index) {
        View row = inflater.inflate(R.layout.item_runtime_option, list, false);
        ((TextView) row.findViewById(R.id.ro_badge)).setText(badge);
        ((TextView) row.findViewById(R.id.ro_name)).setText(name);
        ((TextView) row.findViewById(R.id.ro_desc)).setText(desc);
        TextView archView = row.findViewById(R.id.ro_arch);
        if (arch == null || arch.isEmpty()) archView.setVisibility(View.GONE);
        else archView.setText(arch);
        row.setActivated(selected);
        row.findViewById(R.id.ro_check).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.setOnClickListener(v -> {
            if (isClosing()) return;
            for (int i = 0; i < list.getChildCount(); i++) {
                View other = list.getChildAt(i);
                other.setActivated(other == row);
                other.findViewById(R.id.ro_check).setVisibility(other == row ? View.VISIBLE : View.INVISIBLE);
            }
            Motion.bounce(row.findViewById(R.id.ro_badge));
            Motion.popIn(row.findViewById(R.id.ro_check), 0L, 0.4f);
            row.postDelayed(() -> close(() -> mCallback.onRuntimePicked(index)), 220L);
        });
        CSAnimationEngine.attachPressFeedback(row);
        list.addView(row);
    }

    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = Math.min((int) (520 * d), metrics.widthPixels - (int) (40 * d));
        card.setLayoutParams(params);
    }
}
