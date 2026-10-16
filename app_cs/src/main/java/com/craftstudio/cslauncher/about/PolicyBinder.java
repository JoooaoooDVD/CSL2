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
            {R.string.policy_s8_title, R.string.policy_s8_body},
    };

    /**
     * @param welcome true on first launch: "Welcome" title + agree button; false: read-only with a close button
     */
    public static void bind(@NonNull View root, boolean welcome, @NonNull Runnable onDone) {
        ViewGroup sections = root.findViewById(R.id.policy_sections);
        // Keep the sheet a comfortable width on tablets / wide screens
        View card = root.findViewById(R.id.policy_card);
        float density = root.getResources().getDisplayMetrics().density;
        int screenWidth = root.getResources().getDisplayMetrics().widthPixels;
        int maxWidth = (int) (820 * density);
        if (card != null && screenWidth > maxWidth + (int) (48 * density)) {
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            lp.width = maxWidth;
            card.setLayoutParams(lp);
        }
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
        TextView subtitle = root.findViewById(R.id.policy_subtitle);
        TextView footnote = root.findViewById(R.id.policy_footnote);
        TextView acceptText = root.findViewById(R.id.policy_accept_text);
        View accept = root.findViewById(R.id.policy_accept);
        View acceptIcon = root.findViewById(R.id.policy_accept_icon);
        View close = root.findViewById(R.id.policy_close);
        View agreeRow = root.findViewById(R.id.policy_agree_row);
        View check = root.findViewById(R.id.policy_check);
        View checkMark = root.findViewById(R.id.policy_check_mark);

        title.setText(welcome ? R.string.policy_title_welcome : R.string.policy_title_read);
        subtitle.setText(welcome ? R.string.policy_subtitle : R.string.policy_subtitle_read);
        eyebrow.setVisibility(welcome ? View.VISIBLE : View.GONE);
        agreeRow.setVisibility(welcome ? View.VISIBLE : View.INVISIBLE);
        acceptText.setText(welcome ? R.string.policy_accept : R.string.policy_close);
        acceptIcon.setVisibility(welcome ? View.VISIBLE : View.GONE);
        close.setVisibility(welcome ? View.GONE : View.VISIBLE);

        if (welcome) {
            // "I agree" stays dimmed until the consent box is ticked
            final boolean[] agreed = {false};
            accept.setAlpha(0.45f);
            agreeRow.setOnClickListener(v -> {
                agreed[0] = !agreed[0];
                check.setActivated(agreed[0]);
                checkMark.setVisibility(agreed[0] ? View.VISIBLE : View.INVISIBLE);
                if (agreed[0]) Motion.popIn(checkMark, 0L, 0.3f);
                Motion.bounce(check);
                accept.animate().cancel();
                accept.animate().alpha(agreed[0] ? 1f : 0.45f).setDuration(Motion.SHORT).start();
            });
            accept.setOnClickListener(v -> {
                if (agreed[0]) {
                    onDone.run();
                } else {
                    com.craftstudio.cslauncher.utils.motion.CreatePopupMotion.nudge(agreeRow);
                    Motion.bounce(check);
                    android.widget.Toast.makeText(root.getContext(), R.string.policy_agree_first, android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            accept.setOnClickListener(v -> onDone.run());
        }
        close.setOnClickListener(v -> onDone.run());
        CSAnimationEngine.attachPressFeedback(accept);
        CSAnimationEngine.attachPressFeedback(close);

        // Entrance: header rises, logo pops, sections cascade, the button lands last
        Motion.riseIn(root.findViewById(R.id.policy_header), 40L, 14f);
        Motion.popIn(root.findViewById(R.id.policy_logo), 120L, 0.6f);
        Motion.riseIn(root.findViewById(R.id.policy_ai_notice), 150L, 16f);
        Motion.cascadeChildren(sections, 220L, 50L, 18f);
        if (welcome) {
            // popIn would force alpha back to 1; the dimmed button only scales in
            accept.setScaleX(0.85f);
            accept.setScaleY(0.85f);
            accept.animate().scaleX(1f).scaleY(1f).setStartDelay(420L).setDuration(Motion.MEDIUM)
                    .setInterpolator(Motion.ENTER).start();
        } else {
            Motion.popIn(accept, 420L, 0.85f);
        }
        Motion.fadeIn(agreeRow, 380L, Motion.LONG);
    }
}
