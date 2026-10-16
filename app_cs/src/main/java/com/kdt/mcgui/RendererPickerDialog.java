package com.kdt.mcgui;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.CreatePopupMotion;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.util.List;

/**
 * XML based renderer picker shared by the video settings, the instance editor and the
 * create-instance dialog. Every compatible renderer gets a card with its graphics API badge,
 * a short human description, the supported game version range and an optional tag.
 */
public class RendererPickerDialog extends CSPopupDialog {

    public interface Callback {
        /** @param index position in the id list given to the dialog, or {@code ids.size()} for "launcher default" */
        void onRendererPicked(int index);
    }

    private final List<String> mIds;
    private final List<String> mNames;
    private final int mSelected;
    @Nullable private final String mDefaultLabel;
    private final Callback mCallback;

    /**
     * @param defaultLabel when non-null an extra "use launcher default" card is added first and
     *                     reported as index {@code ids.size()}
     */
    public RendererPickerDialog(@NonNull Context context, @NonNull List<String> ids, @NonNull List<String> names,
                                int selected, @Nullable String defaultLabel, @NonNull Callback callback) {
        super(context);
        mIds = ids;
        mNames = names;
        mSelected = selected;
        mDefaultLabel = defaultLabel;
        mCallback = callback;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_renderer_picker);
        View card = findViewById(R.id.rp_card);
        setupPopup(findViewById(R.id.rp_scrim), card, true);
        sizeCard(card);

        View close = findViewById(R.id.rp_close);
        close.setOnClickListener(v -> onDismissRequested());
        CSAnimationEngine.attachPressFeedback(close);

        ViewGroup list = findViewById(R.id.rp_list);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        Context ctx = getContext();

        if (mDefaultLabel != null) {
            boolean selected = mSelected < 0 || mSelected >= mIds.size();
            addCard(inflater, list, "AUTO", mDefaultLabel, ctx.getString(R.string.rp_default_desc),
                    null, null, 0, selected, mIds.size());
        }
        for (int i = 0; i < mIds.size(); i++) {
            String id = mIds.get(i);
            Info info = infoFor(id);
            String name = i < mNames.size() ? shortName(mNames.get(i)) : id;
            addCard(inflater, list, info.badge, name, ctx.getString(info.desc),
                    ctx.getString(info.versions), info.tag == 0 ? null : ctx.getString(info.tag), info.tagStyle,
                    i == mSelected, i);
        }

        CreatePopupMotion.chooserContent(findViewById(R.id.rp_header), new View[0], findViewById(R.id.rp_subtitle));
        Motion.cascadeChildren(list, 120L, 40L, 14f);
        Motion.fadeIn(findViewById(R.id.rp_footer), 320L, 260L);
    }

    private void addCard(LayoutInflater inflater, ViewGroup list, String badge, String name, String desc,
                         @Nullable String versions, @Nullable String tag, int tagStyle, boolean selected, int index) {
        View row = inflater.inflate(R.layout.item_renderer_card, list, false);
        ((TextView) row.findViewById(R.id.rc_badge)).setText(badge);
        ((TextView) row.findViewById(R.id.rc_name)).setText(name);
        ((TextView) row.findViewById(R.id.rc_desc)).setText(desc);
        TextView versionsView = row.findViewById(R.id.rc_versions);
        if (versions == null || versions.isEmpty()) versionsView.setVisibility(View.GONE);
        else versionsView.setText(versions);
        TextView tagView = row.findViewById(R.id.rc_tag);
        if (tag != null) {
            tagView.setVisibility(View.VISIBLE);
            tagView.setText(tag);
            if (tagStyle == TAG_WARN) {
                tagView.setBackgroundResource(R.drawable.bg_lib_badge_warning);
                tagView.setTextColor(0xFFF7A325);
            } else if (tagStyle == TAG_NEUTRAL) {
                tagView.setBackgroundResource(R.drawable.bg_lib_badge_neutral);
                tagView.setTextColor(getContext().getColor(R.color.hm_text_dim));
            }
        }
        row.setActivated(selected);
        row.findViewById(R.id.rc_check).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.setOnClickListener(v -> {
            if (isClosing()) return;
            for (int i = 0; i < list.getChildCount(); i++) {
                View other = list.getChildAt(i);
                other.setActivated(other == row);
                other.findViewById(R.id.rc_check).setVisibility(other == row ? View.VISIBLE : View.INVISIBLE);
            }
            Motion.bounce(row.findViewById(R.id.rc_badge));
            Motion.popIn(row.findViewById(R.id.rc_check), 0L, 0.4f);
            row.postDelayed(() -> close(() -> mCallback.onRendererPicked(index)), 220L);
        });
        CSAnimationEngine.attachPressFeedback(row);
        list.addView(row);
    }

    private void sizeCard(@NonNull View card) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        float d = metrics.density;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = Math.min((int) (560 * d), metrics.widthPixels - (int) (40 * d));
        // Cap the height so the list scrolls instead of the card growing past the screen
        int maxHeight = metrics.heightPixels - (int) (24 * d);
        int wanted = (int) (Math.max(mIds.size() + (mDefaultLabel != null ? 1 : 0), 1) * 80 * d + 170 * d);
        params.height = wanted > maxHeight ? maxHeight : ViewGroup.LayoutParams.WRAP_CONTENT;
        card.setLayoutParams(params);
    }

    /** "GL4ES (OpenGL ES 2) - 1.21.4 and lower" -> "GL4ES" */
    private static String shortName(String displayName) {
        if (displayName == null) return "";
        int cut = displayName.indexOf(" (");
        if (cut < 0) cut = displayName.indexOf(" - ");
        return cut > 0 ? displayName.substring(0, cut).trim() : displayName.trim();
    }

    private static final int TAG_GOOD = 0, TAG_WARN = 1, TAG_NEUTRAL = 2;

    private static final class Info {
        final String badge; final int desc, versions, tag, tagStyle;
        Info(String badge, int desc, int versions, int tag, int tagStyle) {
            this.badge = badge; this.desc = desc; this.versions = versions; this.tag = tag; this.tagStyle = tagStyle;
        }
    }

    private static Info infoFor(String id) {
        switch (id) {
            case "opengles2":
                return new Info("GL ES 2", R.string.rp_desc_gl4es, R.string.rp_ver_upto_1214, R.string.rp_tag_recommended, TAG_GOOD);
            case "opengles3_ltw":
                return new Info("GL ES 3", R.string.rp_desc_ltw, R.string.rp_ver_117_plus, R.string.rp_tag_fast, TAG_GOOD);
            case "vulkan_zink":
                return new Info("VULKAN", R.string.rp_desc_zink, R.string.rp_ver_all, R.string.rp_tag_shaders, TAG_NEUTRAL);
            case "vulkan_legacyzink":
                return new Info("VULKAN", R.string.rp_desc_legacy_zink, R.string.rp_ver_all, R.string.rp_tag_slow, TAG_WARN);
            case "freedreno_kgsl":
                return new Info("ADRENO", R.string.rp_desc_freedreno, R.string.rp_ver_all, R.string.rp_tag_shaders, TAG_NEUTRAL);
            case "mesa_desktop":
            case "mesa_desktop_ext":
                return new Info("MESA", R.string.rp_desc_mesa, R.string.rp_ver_all, R.string.rp_tag_experimental, TAG_WARN);
            case "opengles_mobileglues":
                return new Info("GL ES", R.string.rp_desc_mobileglues, R.string.rp_ver_117_plus, 0, TAG_GOOD);
            case "opengles_sfpew":
                return new Info("GL ES", R.string.rp_desc_sfpew, R.string.rp_ver_all, 0, TAG_GOOD);
            default:
                return new Info("GL", R.string.rp_desc_generic, R.string.rp_ver_all, 0, TAG_GOOD);
        }
    }
}
