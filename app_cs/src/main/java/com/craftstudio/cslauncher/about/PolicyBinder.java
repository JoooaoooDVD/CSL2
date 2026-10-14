package com.craftstudio.cslauncher.about;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * Fills {@code fragment_policy} with the policy sections. Shared by the first-launch welcome
 * overlay ({@link WelcomePolicy}) and the read-only {@link com.craftstudio.cslauncher.fragments.PolicyFragment}.
 */
public final class PolicyBinder {
    private PolicyBinder() {}

    private static final int[][] SECTIONS = {
            {R.string.policy_s1_title, R.string.policy_s1_body},
            {R.string.policy_s2_title, R.string.policy_s2_body},
            {R.string.policy_s3_title, R.string.policy_s3_body},
            {R.string.policy_s4_title, R.string.policy_s4_body},
            {R.string.policy_s5_title, R.string.policy_s5_body},
            {R.string.policy_s6_title, R.string.policy_s6_body},
            {R.string.policy_s7_title, R.string.policy_s7_body},
    };

    /**
     * @param welcome true on first launch: "Welcome" title + agree button; false: read-only with a close button
     */
    public static void bind(@NonNull View root, boolean welcome, @NonNull Runnable onDone) {
        ViewGroup sections = root.findViewById(R.id.policy_sections);
        LayoutInflater inflater = LayoutInflater.from(root.getContext());
        sections.removeAllViews();
        for (int i = 0; i < SECTIONS.length; i++) {
            View row = inflater.inflate(R.layout.item_policy_section, sections, false);
            ((TextView) row.findViewById(R.id.ps_num)).setText(String.valueOf(i + 1));
            ((TextView) row.findViewById(R.id.ps_title)).setText(SECTIONS[i][0]);
            ((TextView) row.findViewById(R.id.ps_body)).setText(SECTIONS[i][1]);
            sections.addView(row);
        }

        TextView title = root.findViewById(R.id.policy_title);
        TextView eyebrow = root.findViewById(R.id.policy_eyebrow);
        TextView footnote = root.findViewById(R.id.policy_footnote);
        TextView acceptText = root.findViewById(R.id.policy_accept_text);
        View accept = root.findViewById(R.id.policy_accept);
        View close = root.findViewById(R.id.policy_close);

        title.setText(welcome ? R.string.policy_title_welcome : R.string.policy_title_read);
        eyebrow.setVisibility(welcome ? View.VISIBLE : View.GONE);
        footnote.setVisibility(welcome ? View.VISIBLE : View.INVISIBLE);
        acceptText.setText(welcome ? R.string.policy_accept : R.string.policy_close);
        close.setVisibility(welcome ? View.GONE : View.VISIBLE);

        accept.setOnClickListener(v -> onDone.run());
        close.setOnClickListener(v -> onDone.run());
        CSAnimationEngine.attachPressFeedback(accept);
        CSAnimationEngine.attachPressFeedback(close);

        // Entrance: header rises, logo pops, sections cascade, the button lands last
        Motion.riseIn(root.findViewById(R.id.policy_header), 40L, 14f);
        Motion.popIn(root.findViewById(R.id.policy_logo), 120L, 0.6f);
        Motion.cascadeChildren(sections, 160L, 55L, 18f);
        Motion.popIn(accept, 160L + 55L * SECTIONS.length, 0.85f);
        Motion.fadeIn(footnote, 260L, Motion.LONG);
    }
}
