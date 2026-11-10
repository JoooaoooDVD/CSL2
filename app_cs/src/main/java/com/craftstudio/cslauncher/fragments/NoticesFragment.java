package com.craftstudio.cslauncher.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.utils.motion.Motion;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Shows a license or notices text bundled under {@code assets/licenses/} (About > Legal).
 * Arguments: {@link #ARG_ASSET} file name, {@link #ARG_TITLE}, {@link #ARG_SUBTITLE}, {@link #ARG_URL}.
 */
public class NoticesFragment extends Fragment {
    public static final String TAG = "NoticesFragment";
    public static final String ARG_ASSET = "asset";
    public static final String ARG_TITLE = "title";
    public static final String ARG_SUBTITLE = "subtitle";
    public static final String ARG_URL = "url";

    public NoticesFragment() {
        super(R.layout.fragment_notices);
    }

    public static Bundle args(String asset, String title, String subtitle, @Nullable String url) {
        Bundle b = new Bundle();
        b.putString(ARG_ASSET, asset);
        b.putString(ARG_TITLE, title);
        b.putString(ARG_SUBTITLE, subtitle);
        if (url != null) b.putString(ARG_URL, url);
        return b;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle a = getArguments() == null ? new Bundle() : getArguments();
        ((TextView) view.findViewById(R.id.nt_title)).setText(a.getString(ARG_TITLE, ""));
        ((TextView) view.findViewById(R.id.nt_sub)).setText(a.getString(ARG_SUBTITLE, ""));
        TextView text = view.findViewById(R.id.nt_text);
        text.setText(readAsset(a.getString(ARG_ASSET, "")));
        view.findViewById(R.id.nt_back).setOnClickListener(v -> {
            if (getActivity() != null) Tools.removeCurrentFragment(requireActivity());
        });
        TextView open = view.findViewById(R.id.nt_open);
        final String url = a.getString(ARG_URL);
        if (url == null) {
            open.setVisibility(View.GONE);
        } else {
            open.setOnClickListener(v -> { if (getActivity() != null) Tools.openURL(requireActivity(), url); });
        }
        Motion.riseIn(view.findViewById(R.id.nt_scroll), 40L, 14f);
    }

    private String readAsset(String name) {
        if (name.isEmpty()) return "";
        try (InputStream in = requireContext().getAssets().open("licenses/" + name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return getString(R.string.about_notices_missing);
        }
    }
}
