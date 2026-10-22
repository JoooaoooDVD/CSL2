package com.craftstudio.cslauncher.discord;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import java.io.File;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;

/**
 * CS Launcher Plus — Official Discord Rich Presence & Account Linking Sheet.
 *
 * Dedicated bottom sheet with native Discord presence toggle, 1-tap OAuth2 account linking,
 * and live Discord dark profile preview.
 */
public final class CSDiscordRpcSheet {

    private CSDiscordRpcSheet() {}

    public static void show(final Activity activity) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.cs_discord_rpc_sheet);
        dialog.setCanceledOnTouchOutside(true);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setDimAmount(0.60f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setWindowAnimations(R.style.CSBottomSheetAnimation);
        }

        final Context context = activity;
        final Handler handler = new Handler(Looper.getMainLooper());
        final DiscordPresenceManager presenceMgr = DiscordPresenceManager.getInstance(context);

        View root = dialog.findViewById(R.id.cs_discord_root);

        View close = dialog.findViewById(R.id.cs_discord_close);
        if (close != null) {
            close.setOnClickListener(v -> dialog.dismiss());
        }

        // Master switch
        SwitchCompat switchMaster = dialog.findViewById(R.id.cs_discord_switch_master);
        ImageView statusDot = dialog.findViewById(R.id.cs_discord_status_dot);
        TextView statusText = dialog.findViewById(R.id.cs_discord_status_text);

        // Account linking views
        ImageView accountAvatar = dialog.findViewById(R.id.cs_discord_account_avatar);
        TextView accountName = dialog.findViewById(R.id.cs_discord_account_name);
        TextView accountStatus = dialog.findViewById(R.id.cs_discord_account_status);
        Button btnLink = dialog.findViewById(R.id.cs_discord_btn_link);
        Button btnUnlink = dialog.findViewById(R.id.cs_discord_btn_unlink);

        // Preview views
        TextView previewDetails = dialog.findViewById(R.id.cs_discord_preview_details);
        TextView previewState = dialog.findViewById(R.id.cs_discord_preview_state);
        TextView previewElapsed = dialog.findViewById(R.id.cs_discord_preview_elapsed);

        // Options switches
        SwitchCompat switchVersion = dialog.findViewById(R.id.cs_discord_toggle_version);
        SwitchCompat switchServer = dialog.findViewById(R.id.cs_discord_toggle_server);
        SwitchCompat switchOnlyInGame = dialog.findViewById(R.id.cs_discord_toggle_only_in_game);
        SwitchCompat switchElapsed = dialog.findViewById(R.id.cs_discord_toggle_elapsed);
        SwitchCompat switchMods = dialog.findViewById(R.id.cs_discord_toggle_mods);

        // 1. Master Switch state
        boolean rpcEnabled = presenceMgr.isRpcEnabled();
        if (switchMaster != null) {
            switchMaster.setChecked(rpcEnabled);
            updateStatusUi(rpcEnabled, DiscordSdkBridge.isInitialized(), statusDot, statusText);
            switchMaster.setOnCheckedChangeListener((btn, isChecked) -> {
                presenceMgr.setRpcEnabled(isChecked);
                updateStatusUi(isChecked, DiscordSdkBridge.isInitialized(), statusDot, statusText);
                if (isChecked) {
                    handler.postDelayed(() -> updateStatusUi(true, DiscordSdkBridge.isInitialized(), statusDot, statusText), 900L);
                }
            });
        }

        // Live preview updater
        Runnable updatePreview = () -> {
            try {
                Instance instance = Instances.loadSelectedInstance();
                String profileName = presenceMgr.resolveProfileOrModpackName(instance);
                String mcVersion = presenceMgr.resolveMinecraftVersion(instance);
                File gameDir = instance != null ? instance.getGameDirectory() : null;
                File modsDir = gameDir != null ? new File(gameDir, "mods") : null;
                int modCount = 0;
                if (modsDir != null && modsDir.exists()) {
                    File[] jars = modsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
                    if (jars != null) modCount = jars.length;
                }

                String modText = (presenceMgr.isShowModsEnabled() && modCount > 0) ? " (" + modCount + " Mods)" : "";
                String verText = presenceMgr.isShowVersionEnabled() ? " " + mcVersion : "";
                String detailsText = "Playing Minecraft" + verText + modText;

                String modSuffix = (presenceMgr.isShowModsEnabled() && modCount > 0) ? " (" + modCount + " Mods)" : "";
                String stateText = "Profile: " + profileName + modSuffix;

                if (previewDetails != null) previewDetails.setText(detailsText);
                if (previewState != null) previewState.setText(stateText);
                if (previewElapsed != null) {
                    previewElapsed.setVisibility(presenceMgr.isShowElapsedEnabled() ? View.VISIBLE : View.GONE);
                }
            } catch (Throwable ignored) {}
        };

        // 2. Options Switches
        if (switchVersion != null) {
            switchVersion.setChecked(presenceMgr.isShowVersionEnabled());
            switchVersion.setOnCheckedChangeListener((btn, isChecked) -> {
                presenceMgr.setShowVersionEnabled(isChecked);
                updatePreview.run();
            });
        }
        if (switchServer != null) {
            switchServer.setChecked(presenceMgr.isShowServerEnabled());
            switchServer.setOnCheckedChangeListener((btn, isChecked) -> {
                presenceMgr.setShowServerEnabled(isChecked);
                updatePreview.run();
            });
        }
        if (switchOnlyInGame != null) {
            switchOnlyInGame.setChecked(presenceMgr.isOnlyInGameEnabled());
            switchOnlyInGame.setOnCheckedChangeListener((btn, isChecked) -> {
                presenceMgr.setOnlyInGameEnabled(isChecked);
                updatePreview.run();
            });
        }
        if (switchElapsed != null) {
            switchElapsed.setChecked(presenceMgr.isShowElapsedEnabled());
            switchElapsed.setOnCheckedChangeListener((btn, isChecked) -> {
                presenceMgr.setShowElapsedEnabled(isChecked);
                updatePreview.run();
            });
        }
        if (switchMods != null) {
            switchMods.setChecked(presenceMgr.isShowModsEnabled());
            switchMods.setOnCheckedChangeListener((btn, isChecked) -> {
                presenceMgr.setShowModsEnabled(isChecked);
                updatePreview.run();
            });
        }

        // 3. Bind Account UI Helper
        Runnable bindAccountUi = new Runnable() {
            @Override
            public void run() {
                DiscordAccountStorage.DiscordProfile profile = DiscordAccountStorage.loadAccount(context);
                if (profile.isLinked) {
                    if (accountName != null) {
                        accountName.setText("Connected: @" + profile.getEffectiveName());
                        accountName.setTextColor(Color.parseColor("#57F287")); // Discord Green
                    }
                    if (accountStatus != null) {
                        accountStatus.setText("User ID: " + (profile.userId != null ? profile.userId : "Connected"));
                    }
                    if (btnLink != null) btnLink.setVisibility(View.GONE);
                    if (btnUnlink != null) btnUnlink.setVisibility(View.VISIBLE);
                    if (accountAvatar != null && profile.avatarUrl != null) {
                        DiscordAvatarLoader.loadAvatar(context, profile.avatarUrl, accountAvatar);
                    }
                } else {
                    if (accountName != null) {
                        accountName.setText("Link Discord Account");
                        accountName.setTextColor(Color.parseColor("#F2F3F5"));
                    }
                    if (accountStatus != null) {
                        accountStatus.setText("1-Tap Official OAuth · No token or setup needed");
                    }
                    if (btnLink != null) btnLink.setVisibility(View.VISIBLE);
                    if (btnUnlink != null) btnUnlink.setVisibility(View.GONE);
                    if (accountAvatar != null) {
                        accountAvatar.setImageResource(R.drawable.ic_brand_discord);
                    }
                }
            }
        };

        bindAccountUi.run();

        // Asynchronously refresh connected profile to verify latest avatar & name
        presenceMgr.getAuthManager().refreshConnectedProfile(() -> {
            if (dialog.isShowing()) {
                bindAccountUi.run();
            }
        });

        // Link button click
        if (btnLink != null) {
            btnLink.setOnClickListener(v -> {
                Toast.makeText(context, "Opening Discord authorization...", Toast.LENGTH_SHORT).show();
                presenceMgr.getAuthManager().startAuthorization(activity, new DiscordAuthManager.AuthCallback() {
                    @Override
                    public void onSuccess(String username, String userId, @Nullable String avatarUrl) {
                        handler.post(() -> {
                            if (dialog.isShowing()) {
                                Toast.makeText(context, "Connected to Discord: @" + username, Toast.LENGTH_LONG).show();
                                bindAccountUi.run();
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        handler.post(() -> {
                            if (dialog.isShowing()) {
                                Toast.makeText(context, "Discord auth: " + error, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
            });
        }

        // Unlink button click
        if (btnUnlink != null) {
            btnUnlink.setOnClickListener(v -> {
                presenceMgr.unlinkCurrentAccount();
                Toast.makeText(context, "Discord account unlinked", Toast.LENGTH_SHORT).show();
                bindAccountUi.run();
            });
        }

        // 4. Update preview card with current data
        updatePreview.run();

        dialog.show();

        // Slide up animation
        if (root != null) {
            root.setTranslationY(140f * activity.getResources().getDisplayMetrics().density);
            root.animate().translationY(0f).setDuration(260L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
    }

    private static void updateStatusUi(boolean enabled, boolean sdkReady,
                                       @Nullable ImageView dot,
                                       @Nullable TextView text) {
        if (dot != null) {
            dot.setImageResource(enabled && sdkReady
                    ? R.drawable.cs_discord_dot_green : R.drawable.cs_discord_dot_gray);
        }
        if (text != null) {
            if (!enabled) {
                text.setText("Discord presence disabled");
                text.setTextColor(Color.parseColor("#949BA4"));
            } else if (sdkReady) {
                text.setText("Connected · presence publishing");
                text.setTextColor(Color.parseColor("#57F287"));
            } else {
                text.setText("Enabled · waiting for Discord");
                text.setTextColor(Color.parseColor("#F0B35A"));
            }
        }
    }
}
