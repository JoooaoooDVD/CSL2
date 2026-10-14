package com.craftstudio.cslauncher.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.BuildConfig;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.Motion;

/**
 * About CS Launcher Plus: version, community links, the launchers it is built on
 * (PojavLauncher, Amethyst, MojoLauncher), every open source component and the legal bits.
 */
public class AboutFragment extends Fragment {
    public static final String TAG = "AboutFragment";

    public static final String URL_DISCORD = "https://discord.gg/dmteYqZZyY";
    public static final String URL_GITHUB = "https://github.com/craftstudioteam";

    /** One of the launchers this project stands on */
    private static final class Project {
        final @DrawableRes int logo;
        final String name;
        final @StringRes int role;
        final @StringRes int description;
        final String license;
        final String url;

        Project(int logo, String name, int role, int description, String license, String url) {
            this.logo = logo; this.name = name; this.role = role; this.description = description;
            this.license = license; this.url = url;
        }
    }

    /** One open source component or online service */
    private static final class Credit {
        final String name;
        final String description;
        final String license;
        final String url;

        Credit(String name, String description, String license, String url) {
            this.name = name; this.description = description; this.license = license; this.url = url;
        }
    }

    private static final Project[] PROJECTS = {
            new Project(R.drawable.about_logo_pojav, "PojavLauncher", R.string.about_role_base, R.string.about_pojav_desc,
                    "LGPL-3.0", "https://github.com/PojavLauncherTeam/PojavLauncher"),
            new Project(R.drawable.about_logo_amethyst, "Amethyst", R.string.about_role_successor, R.string.about_amethyst_desc,
                    "LGPL-3.0", "https://github.com/AngelAuraMC/Amethyst-Android"),
            new Project(R.drawable.about_logo_mojo, "MojoLauncher", R.string.about_role_upstream, R.string.about_mojo_desc,
                    "LGPL-3.0", "https://github.com/MojoLauncher/MojoLauncher"),
    };

    private static final Credit[] LIBRARIES = {
            new Credit("Boardwalk", "The project that first ran Minecraft: Java Edition on Android — the ancestor of every Pojav-based launcher.", "Apache-2.0", "https://github.com/zhuowei/Boardwalk"),
            new Credit("OpenJDK (multiarch)", "Java runtimes 8, 17 and 21 built for Android by the PojavLauncher team.", "GPL-2.0 + CE", "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch"),
            new Credit("GLFW (Android)", "Window, input and EGL context backend for LWJGL 3.", "zlib", "https://github.com/MojoLauncher/glfw"),
            new Credit("SDL3 (MojoSDL)", "Alternative window and input backend used by newer game versions.", "zlib", "https://github.com/MojoLauncher/MojoSDL"),
            new Credit("LWJGL 3", "Lightweight Java Game Library used by Minecraft 1.13+.", "BSD-3-Clause", "https://github.com/LWJGL/lwjgl3"),
            new Credit("LWJGL2-GLFW", "LWJGL 2 reimplemented on top of GLFW for legacy versions.", "BSD-3-Clause", "https://github.com/MojoLauncher/lwjgl2-glfw"),
            new Credit("Holy GL4ES", "OpenGL 2.1 to OpenGL ES translation layer.", "MIT", "https://github.com/artdeell/gl4es_extra_extra"),
            new Credit("LTW", "Lightweight OpenGL translation wrapper with modern extension support.", "LGPL-3.0", "https://github.com/artdeell/LTW"),
            new Credit("MobileGlues", "OpenGL 4.x on OpenGL ES 3.2 renderer for Adreno and Mali GPUs.", "LGPL-2.1", "https://github.com/MobileGL-Dev/MobileGlues"),
            new Credit("Mesa 3D (Zink / Virgl)", "Open source graphics drivers, used for the Vulkan-backed Zink renderer.", "MIT", "https://gitlab.freedesktop.org/mesa/mesa"),
            new Credit("jreexec", "Launches the Java runtime in a clean process on Android.", "MIT", "https://github.com/MojoLauncher/jreexec"),
            new Credit("bhook", "PLT hook framework, used to trap the game exit code.", "MIT", "https://github.com/bytedance/bhook"),
            new Credit("authlib-injector", "Lets third-party account systems like Ely.by authenticate in the game.", "AGPL-3.0", "https://github.com/yushijinhun/authlib-injector"),
            new Credit("OpenAL Soft", "3D audio output library.", "LGPL-2.0", "https://github.com/kcat/openal-soft"),
            new Credit("Oboe", "Low-latency Android audio from Google.", "Apache-2.0", "https://github.com/google/oboe"),
            new Credit("pro-grade", "Java security manager used to sandbox the game.", "Apache-2.0", "https://github.com/pro-grade/pro-grade"),
            new Credit("Forge / NeoForge / Fabric / Quilt", "Mod loaders installed by the launcher. Each has its own license.", "Various", "https://fabricmc.net/"),
            new Credit("AndroidX & Material Components", "Jetpack libraries and the Material 3 design system.", "Apache-2.0", "https://github.com/material-components/material-components-android"),
            new Credit("Gson & Apache Commons", "JSON parsing and IO helpers.", "Apache-2.0", "https://github.com/google/gson"),
    };

    private static final Credit[] SERVICES = {
            new Credit("Microsoft / Xbox Live / Mojang", "Official account login and game downloads.", "ToS", "https://www.minecraft.net/"),
            new Credit("Modrinth", "Open platform for mods, resource packs, shaders, data packs and modpacks.", "API", "https://modrinth.com/"),
            new Credit("CurseForge", "Mods, modpacks, resource packs, shaders, data packs and worlds.", "API", "https://www.curseforge.com/minecraft"),
            new Credit("Ely.by", "Free account system with skins and capes for offline servers.", "ToS", "https://ely.by/"),
            new Credit("mclo.gs", "Log sharing service used by \"Share log\".", "ToS", "https://mclo.gs/"),
            new Credit("Mineskin", "Minecraft avatars and skin rendering.", "ToS", "https://mineskin.eu/"),
    };

    public AboutFragment() {
        super(R.layout.fragment_about);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View back = view.findViewById(R.id.about_back);
        back.setOnClickListener(v -> Tools.removeCurrentFragment(requireActivity()));
        CSAnimationEngine.attachPressFeedback(back);

        TextView version = view.findViewById(R.id.about_version);
        version.setText(getString(R.string.about_version_fmt, getString(R.string.brand_version)));
        version.setOnLongClickListener(v -> {
            Toast.makeText(v.getContext(), BuildConfig.VERSION_NAME, Toast.LENGTH_LONG).show();
            return true;
        });

        View discord = view.findViewById(R.id.about_discord);
        View github = view.findViewById(R.id.about_github);
        discord.setOnClickListener(v -> open(URL_DISCORD));
        github.setOnClickListener(v -> open(URL_GITHUB));
        CSAnimationEngine.attachPressFeedback(discord);
        CSAnimationEngine.attachPressFeedback(github);

        ViewGroup projects = view.findViewById(R.id.about_projects);
        LayoutInflater inflater = LayoutInflater.from(view.getContext());
        for (Project project : PROJECTS) {
            View card = inflater.inflate(R.layout.item_about_project, projects, false);
            ((ImageView) card.findViewById(R.id.ap_logo)).setImageResource(project.logo);
            ((TextView) card.findViewById(R.id.ap_name)).setText(project.name);
            ((TextView) card.findViewById(R.id.ap_role)).setText(project.role);
            ((TextView) card.findViewById(R.id.ap_desc)).setText(project.description);
            ((TextView) card.findViewById(R.id.ap_license)).setText(project.license);
            ((TextView) card.findViewById(R.id.ap_url)).setText(project.url.replace("https://", ""));
            card.setOnClickListener(v -> open(project.url));
            CSAnimationEngine.attachPressFeedback(card);
            projects.addView(card);
        }

        fillCredits(inflater, view.findViewById(R.id.about_libraries), LIBRARIES, R.drawable.ic_about_code);
        fillCredits(inflater, view.findViewById(R.id.about_services), SERVICES, R.drawable.ic_about_link);

        ViewGroup legal = view.findViewById(R.id.about_legal);
        addLegal(inflater, legal, R.drawable.ic_about_shield, R.string.about_legal_license, R.string.about_legal_license_desc,
                "LGPL-3.0", () -> open("https://www.gnu.org/licenses/lgpl-3.0.html"));
        addLegal(inflater, legal, R.drawable.ic_about_shield, R.string.about_legal_privacy, R.string.about_legal_privacy_desc,
                null, () -> Tools.swapFragment(requireActivity(), PolicyFragment.class, PolicyFragment.TAG, null));
        addLegal(inflater, legal, R.drawable.ic_about_heart, R.string.about_legal_mojang, R.string.about_legal_mojang_desc,
                null, () -> open("https://www.minecraft.net/eula"));

        // Entrance choreography
        Motion.riseIn(view.findViewById(R.id.about_hero), 30L, 16f);
        Motion.popIn(view.findViewById(R.id.about_logo), 120L, 0.5f);
        Motion.popChildren(view.findViewById(R.id.about_links), 180L, 60L, 0.9f);
        Motion.cascadeChildren(projects, 260L, 70L, 18f);
        Motion.fadeIn(view.findViewById(R.id.about_libraries), 420L, Motion.LONG);
        Motion.fadeIn(view.findViewById(R.id.about_services), 480L, Motion.LONG);
        Motion.fadeIn(legal, 540L, Motion.LONG);
        Motion.fadeIn(view.findViewById(R.id.about_footer), 640L, Motion.LONG);

        // A little delight: tapping the logo makes it bounce
        View logo = view.findViewById(R.id.about_logo);
        logo.setOnClickListener(v -> Motion.bounce(v));
    }

    private void fillCredits(LayoutInflater inflater, ViewGroup container, Credit[] credits, @DrawableRes int icon) {
        for (Credit credit : credits) {
            View row = inflater.inflate(R.layout.item_about_lib, container, false);
            ((ImageView) row.findViewById(R.id.al_icon)).setImageResource(icon);
            ((TextView) row.findViewById(R.id.al_name)).setText(credit.name);
            ((TextView) row.findViewById(R.id.al_desc)).setText(credit.description);
            ((TextView) row.findViewById(R.id.al_license)).setText(credit.license);
            row.setOnClickListener(v -> open(credit.url));
            container.addView(row);
        }
    }

    private void addLegal(LayoutInflater inflater, ViewGroup container, @DrawableRes int icon,
                          @StringRes int title, @StringRes int description, @Nullable String badge, Runnable action) {
        View row = inflater.inflate(R.layout.item_about_lib, container, false);
        ((ImageView) row.findViewById(R.id.al_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.al_name)).setText(title);
        ((TextView) row.findViewById(R.id.al_desc)).setText(description);
        TextView badgeView = row.findViewById(R.id.al_license);
        if (badge == null) {
            badgeView.setVisibility(View.GONE);
        } else {
            badgeView.setText(badge);
        }
        row.setOnClickListener(v -> action.run());
        container.addView(row);
    }

    private void open(String url) {
        if (getActivity() != null) Tools.openURL(requireActivity(), url);
    }
}
