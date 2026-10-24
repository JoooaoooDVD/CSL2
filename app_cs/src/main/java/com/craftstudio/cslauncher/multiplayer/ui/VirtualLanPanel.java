package com.craftstudio.cslauncher.multiplayer.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.instances.Instance;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.multiplayer.AndroidTunnelEnvironment;
import com.craftstudio.cslauncher.multiplayer.ServersDat;
import com.craftstudio.cslauncher.multiplayer.VirtualLanClient;
import com.craftstudio.cslauncher.multiplayer.VirtualLanRepository;
import com.craftstudio.cslauncher.multiplayer.core.SessionCode;
import com.craftstudio.cslauncher.multiplayer.core.VirtualLanState;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Controller for {@code view_virtual_lan.xml}. Pure presentation: it binds the service client,
 * renders {@link VirtualLanState} snapshots and forwards user actions. Hosted by
 * {@link VirtualLanFragment} (launcher) and {@link VirtualLanPopup} (in game).
 */
public final class VirtualLanPanel implements VirtualLanClient.Listener {
    public interface Host {
        @NonNull Activity activity();
        /** True inside the game: "Join World" can only point at the in-game Multiplayer menu */
        boolean inGame();
        /** Close button (popup only) */
        void onCloseRequested();
    }

    private enum Screen { HOME, SESSION, RESULT }

    private final Host mHost;
    private final View mRoot;
    private final Context mContext;
    private final VirtualLanClient mClient;
    private final VirtualLanRepository mRepo;
    private final boolean mSupported;

    // header
    private final View mBack, mClose, mHelp;
    // home
    private final ViewGroup mHome;
    private final View mUnsupported, mActionCreate, mActionJoin, mJoinBox, mCodeError, mAdvancedBox, mHelpCard;
    private final EditText mCodeInput, mNameInput, mRelayInput;
    private final TextView mJoinGo, mRecentClear, mRecentEmpty, mAdvancedToggle;
    private final LinearLayout mRecentList;
    // session
    private final ViewGroup mSession;
    private final View mHero, mProgressTrack, mProgressBar, mCodeRow, mCopy, mShare, mGuestCard, mMembersCard, mEventsCard;
    private final View mStatusDot;
    private final TextView mStatusTitle, mStatusDetail, mRolePill, mCodeText;
    private final TextView mWorldName, mWorldSub, mWorldPill, mWorldNote;
    private final TextView mGuestHost, mGuestNote, mJoinWorld, mCopyAddress;
    private final TextView mConnPill, mConnNote, mMembersTitle, mMembersEmpty, mEnd, mDiagnostics;
    private final LinearLayout mMembers, mEvents;
    private final View mStatPlayers, mStatPort, mStatVip, mStatLatency, mStatRelays, mStatNat;
    // result
    private final ViewGroup mResult;
    private final ImageView mResultIcon;
    private final TextView mResultTitle, mResultMessage, mResultTips, mResultRetry, mResultBack;

    private Screen mScreen = Screen.HOME;
    @Nullable private ValueAnimator mSweep;
    @NonNull private VirtualLanState mLast = VirtualLanState.IDLE;
    private boolean mJoinOpen, mAdvancedOpen, mHelpOpen, mCelebrated, mShown;
    private long mLastEventSeq;
    private final Map<String, View> mMemberRows = new HashMap<>();
    @Nullable private String mPendingJoinCode;
    private boolean mPendingCreate;

    public VirtualLanPanel(@NonNull View root, @NonNull Host host) {
        mHost = host;
        mRoot = root;
        mContext = root.getContext();
        mRepo = new VirtualLanRepository(mContext);
        mClient = new VirtualLanClient(mContext, this);
        mSupported = AndroidTunnelEnvironment.isSupported(mContext);

        mBack = root.findViewById(R.id.vl_back);
        mClose = root.findViewById(R.id.vl_close);
        mHelp = root.findViewById(R.id.vl_help);
        mHome = root.findViewById(R.id.vl_home);
        mUnsupported = root.findViewById(R.id.vl_unsupported);
        mActionCreate = root.findViewById(R.id.vl_action_create);
        mActionJoin = root.findViewById(R.id.vl_action_join);
        mJoinBox = root.findViewById(R.id.vl_join_box);
        mCodeInput = root.findViewById(R.id.vl_code_input);
        mJoinGo = root.findViewById(R.id.vl_join_go);
        mCodeError = root.findViewById(R.id.vl_code_error);
        mRecentClear = root.findViewById(R.id.vl_recent_clear);
        mRecentList = root.findViewById(R.id.vl_recent_list);
        mRecentEmpty = root.findViewById(R.id.vl_recent_empty);
        mNameInput = root.findViewById(R.id.vl_name_input);
        mAdvancedToggle = root.findViewById(R.id.vl_advanced_toggle);
        mAdvancedBox = root.findViewById(R.id.vl_advanced_box);
        mRelayInput = root.findViewById(R.id.vl_relay_input);
        mHelpCard = root.findViewById(R.id.vl_help_card);

        mSession = root.findViewById(R.id.vl_session);
        mHero = root.findViewById(R.id.vl_hero);
        mStatusDot = root.findViewById(R.id.vl_status_dot);
        mStatusTitle = root.findViewById(R.id.vl_status_title);
        mStatusDetail = root.findViewById(R.id.vl_status_detail);
        mRolePill = root.findViewById(R.id.vl_role_pill);
        mProgressTrack = root.findViewById(R.id.vl_progress_track);
        mProgressBar = root.findViewById(R.id.vl_progress_bar);
        mCodeRow = root.findViewById(R.id.vl_code_row);
        mCodeText = root.findViewById(R.id.vl_code_text);
        mCopy = root.findViewById(R.id.vl_copy);
        mShare = root.findViewById(R.id.vl_share);
        mWorldName = root.findViewById(R.id.vl_world_name);
        mWorldSub = root.findViewById(R.id.vl_world_sub);
        mWorldPill = root.findViewById(R.id.vl_world_pill);
        mWorldNote = root.findViewById(R.id.vl_world_note);
        mStatPlayers = root.findViewById(R.id.vl_stat_players);
        mStatPort = root.findViewById(R.id.vl_stat_port);
        mStatVip = root.findViewById(R.id.vl_stat_vip);
        mGuestCard = root.findViewById(R.id.vl_guest_card);
        mGuestHost = root.findViewById(R.id.vl_guest_host);
        mGuestNote = root.findViewById(R.id.vl_guest_note);
        mJoinWorld = root.findViewById(R.id.vl_join_world);
        mCopyAddress = root.findViewById(R.id.vl_copy_address);
        mConnPill = root.findViewById(R.id.vl_conn_pill);
        mConnNote = root.findViewById(R.id.vl_conn_note);
        mStatLatency = root.findViewById(R.id.vl_stat_latency);
        mStatRelays = root.findViewById(R.id.vl_stat_relays);
        mStatNat = root.findViewById(R.id.vl_stat_nat);
        mMembersCard = root.findViewById(R.id.vl_members_card);
        mMembersTitle = root.findViewById(R.id.vl_members_title);
        mMembers = root.findViewById(R.id.vl_members);
        mMembersEmpty = root.findViewById(R.id.vl_members_empty);
        mEventsCard = root.findViewById(R.id.vl_events_card);
        mEvents = root.findViewById(R.id.vl_events);
        mEnd = root.findViewById(R.id.vl_end);
        mDiagnostics = root.findViewById(R.id.vl_diagnostics);
        View sendLogs = root.findViewById(R.id.vl_send_logs);
        if (sendLogs != null) sendLogs.setOnClickListener(v -> sendLogs());
        View copyHome = root.findViewById(R.id.vl_copy_diag_home);
        if (copyHome != null) copyHome.setOnClickListener(v -> copy(diagnostics(mLast), R.string.vlan_copied_diagnostics));

        mResult = root.findViewById(R.id.vl_result);
        mResultIcon = root.findViewById(R.id.vl_result_icon);
        mResultTitle = root.findViewById(R.id.vl_result_title);
        mResultMessage = root.findViewById(R.id.vl_result_message);
        mResultTips = root.findViewById(R.id.vl_result_tips);
        mResultRetry = root.findViewById(R.id.vl_result_retry);
        mResultBack = root.findViewById(R.id.vl_result_back);

        setupHome();
        setupSession();
        setupResult();
        mClose.setVisibility(View.GONE);
        mBack.setVisibility(View.GONE);
    }

    /** Shows the close button (in-game popup) */
    public void showCloseButton() {
        mClose.setVisibility(View.VISIBLE);
        mClose.setOnClickListener(v -> mHost.onCloseRequested());
        CSAnimationEngine.attachPressFeedback(mClose);
    }

    // ───────────────────────────── lifecycle ─────────────────────────────

    public void onShow() {
        if (mShown) return;
        mShown = true;
        mNameInput.setText(mRepo.playerName());
        mRelayInput.setText(join(mRepo.customRelays(), "\n"));
        renderRecent();
        mUnsupported.setVisibility(mSupported ? View.GONE : View.VISIBLE);
        boolean pending = mPendingCreate || mPendingJoinCode != null;
        // Coming back from the system VPN dialog: stay on the session screen, do not reset
        showScreen(pending ? Screen.SESSION : Screen.HOME, false);
        mClient.bind();
        if (pending && mAwaitingConsent) {
            // The consent activity may have been killed by the ROM before it could deliver its
            // result. The OS knows the truth: granted => start directly (the service ignores a
            // duplicate start), still not granted => treat as declined.
            mRoot.postDelayed(this::resolvePendingConsent, 600L);
        }
    }

    public void onHide() {
        if (!mShown) return;
        mShown = false;
        persistInputs();
        stopSweep();
        mRoot.removeCallbacks(mConsentWatchdog);
        mClient.unbind();
    }

    /** No state after asking for consent for this long => resolve it ourselves */
    private static final long CONSENT_WATCHDOG_MS = 45_000L;
    private final Runnable mConsentWatchdog = this::resolvePendingConsent;

    private void armConsentWatchdog() {
        mRoot.removeCallbacks(mConsentWatchdog);
        mRoot.postDelayed(mConsentWatchdog, CONSENT_WATCHDOG_MS);
    }

    private void resolvePendingConsent() {
        if (!mShown || !mAwaitingConsent) return;
        if (mClient.state().isActive()) { mAwaitingConsent = false; return; }
        boolean pendingCreate = mPendingCreate;
        String pendingJoin = mPendingJoinCode;
        if (!pendingCreate && pendingJoin == null) { mAwaitingConsent = false; return; }
        if (!needsVpnConsent()) {
            mAwaitingConsent = false;
            if (pendingCreate) mClient.createSession(mRepo.playerName(), mRepo.customRelays());
            else mClient.joinSession(pendingJoin, mRepo.playerName(), mRepo.customRelays());
        } else {
            mAwaitingConsent = false;
            mPendingCreate = false;
            mPendingJoinCode = null;
            VirtualLanState failed = new VirtualLanState.Builder()
                    .role(pendingCreate ? VirtualLanState.Role.HOST : VirtualLanState.Role.GUEST)
                    .sessionCode(pendingJoin == null ? "" : pendingJoin)
                    .phase(VirtualLanState.Phase.FAILED)
                    .reason(VirtualLanState.Reason.VPN_DENIED, "").build();
            onState(failed);
        }
    }

    /** Back press: closes inline boxes first, then lets the host handle it */
    public boolean onBackPressed() {
        if (mScreen == Screen.HOME && mJoinOpen) {
            toggleJoin(false);
            return true;
        }
        if (mScreen == Screen.RESULT) {
            goHome();
            return true;
        }
        return false;
    }

    private void persistInputs() {
        mRepo.setPlayerName(mNameInput.getText().toString());
        mRepo.setCustomRelays(mRelayInput.getText().toString());
    }

    // ───────────────────────────── setup ─────────────────────────────

    private void setupHome() {
        CSAnimationEngine.attachPressFeedback(mActionCreate);
        CSAnimationEngine.attachPressFeedback(mActionJoin);
        CSAnimationEngine.attachPressFeedback(mJoinGo);
        CSAnimationEngine.attachPressFeedback(mHelp);
        mActionCreate.setOnClickListener(v -> onCreateClicked());
        mActionJoin.setOnClickListener(v -> {
            toggleJoin(!mJoinOpen);
            if (mJoinOpen) focus(mCodeInput);
        });
        mJoinGo.setOnClickListener(v -> onJoinClicked());
        mCodeInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) { onJoinClicked(); return true; }
            return false;
        });
        mCodeInput.addTextChangedListener(new TextWatcher() {
            private boolean mEditing;
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (mEditing) return;
                mEditing = true;
                String formatted = SessionCode.formatPartial(s.toString());
                if (!formatted.equals(s.toString())) {
                    s.replace(0, s.length(), formatted);
                }
                mEditing = false;
                if (mCodeError.getVisibility() == View.VISIBLE) MultiplayerMotion.toggleBox(mCodeError, false);
            }
        });
        mHelp.setOnClickListener(v -> {
            if (mScreen != Screen.HOME) {
                Toast.makeText(mContext, R.string.vlan_help_in_session, Toast.LENGTH_SHORT).show();
                return;
            }
            mHelpOpen = !mHelpOpen;
            MultiplayerMotion.toggleBox(mHelpCard, mHelpOpen);
            if (mHelpOpen) mRepo.setHelpSeen();
        });
        mAdvancedToggle.setOnClickListener(v -> {
            mAdvancedOpen = !mAdvancedOpen;
            MultiplayerMotion.toggleBox(mAdvancedBox, mAdvancedOpen);
        });
        mRecentClear.setOnClickListener(v -> {
            mRepo.clearRecent();
            renderRecent();
        });
    }

    private void setupSession() {
        CSAnimationEngine.attachPressFeedback(mCopy);
        CSAnimationEngine.attachPressFeedback(mShare);
        CSAnimationEngine.attachPressFeedback(mJoinWorld);
        CSAnimationEngine.attachPressFeedback(mCopyAddress);
        CSAnimationEngine.attachPressFeedback(mEnd);
        CSAnimationEngine.attachPressFeedback(mDiagnostics);
        mCopy.setOnClickListener(v -> copy(mLast.sessionCode, R.string.vlan_copied_code));
        mShare.setOnClickListener(v -> shareInvite());
        mEnd.setOnClickListener(v -> {
            mClient.endSession();
            mEnd.setEnabled(false);
        });
        mJoinWorld.setOnClickListener(v -> onJoinWorld());
        mCopyAddress.setOnClickListener(v -> copy(mCopyAddress.getText().toString(), R.string.vlan_copied_address));
        mDiagnostics.setOnClickListener(v -> copy(diagnostics(mLast), R.string.vlan_copied_diagnostics));
        stat(mStatPlayers, R.string.vlan_stat_players, "–");
        stat(mStatPort, R.string.vlan_stat_lan_port, "–");
        stat(mStatVip, R.string.vlan_stat_virtual_ip, "–");
        stat(mStatLatency, R.string.vlan_stat_latency, "–");
        stat(mStatRelays, R.string.vlan_stat_relays, "–");
        stat(mStatNat, R.string.vlan_stat_nat, "–");
    }

    private void setupResult() {
        CSAnimationEngine.attachPressFeedback(mResultRetry);
        CSAnimationEngine.attachPressFeedback(mResultBack);
        mResultBack.setOnClickListener(v -> goHome());
        mResultRetry.setOnClickListener(v -> {
            VirtualLanState s = mLast;
            mClient.dismissFinalState();
            if (s.isGuest() && !s.sessionCode.isEmpty()) startJoin(s.sessionCode);
            else onCreateClicked();
        });
    }

    // ───────────────────────────── actions ─────────────────────────────

    private void onCreateClicked() {
        if (!mSupported) { shakeUnsupported(); return; }
        if (mClient.state().isActive()) { showScreen(Screen.SESSION, true); return; }
        persistInputs();
        mPendingCreate = true;
        mAwaitingConsent = false;
        if (needsVpnConsent()) {
            mAwaitingConsent = true;
            launchConsent("host", null);
            armConsentWatchdog();
        } else {
            mClient.createSession(mRepo.playerName(), mRepo.customRelays());
        }
        showScreen(Screen.SESSION, true);
    }

    /** True when Android still has to show its VPN consent dialog for this app */
    private boolean needsVpnConsent() {
        try {
            return android.net.VpnService.prepare(mContext) != null;
        } catch (Throwable t) {
            return false; // no VPN support: the service reports VPN_FAILED with the real reason
        }
    }

    private void launchConsent(String role, @Nullable String code) {
        Intent i = new Intent(mContext, com.craftstudio.cslauncher.multiplayer.VpnConsentActivity.class)
                .putExtra(com.craftstudio.cslauncher.multiplayer.VpnConsentActivity.EXTRA_ROLE, role)
                .putExtra(com.craftstudio.cslauncher.multiplayer.VpnConsentActivity.EXTRA_CODE, code)
                .putExtra(com.craftstudio.cslauncher.multiplayer.VpnConsentActivity.EXTRA_PLAYER, mRepo.playerName())
                .putStringArrayListExtra(com.craftstudio.cslauncher.multiplayer.VpnConsentActivity.EXTRA_RELAYS, new java.util.ArrayList<>(mRepo.customRelays()));
        if (!(mContext instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivity(i);
        } catch (Throwable t) {
            mAwaitingConsent = false;
            // fall back to a direct start; the session will fail with VPN_FAILED and say why
            if ("host".equals(role)) mClient.createSession(mRepo.playerName(), mRepo.customRelays());
            else if (code != null) mClient.joinSession(code, mRepo.playerName(), mRepo.customRelays());
        }
    }

    private boolean mAwaitingConsent;

    private void onJoinClicked() {
        if (!mSupported) { shakeUnsupported(); return; }
        SessionCode code = SessionCode.extract(mCodeInput.getText().toString());
        if (code == null) {
            MultiplayerMotion.toggleBox(mCodeError, true);
            MultiplayerMotion.fail(mJoinBox);
            return;
        }
        hideKeyboard(mCodeInput);
        startJoin(code.toString());
    }

    private void startJoin(@NonNull String code) {
        if (mClient.state().isActive()) { showScreen(Screen.SESSION, true); return; }
        persistInputs();
        mPendingJoinCode = code;
        mAwaitingConsent = false;
        if (needsVpnConsent()) {
            mAwaitingConsent = true;
            launchConsent("guest", code);
            armConsentWatchdog();
        } else {
            mClient.joinSession(code, mRepo.playerName(), mRepo.customRelays());
        }
        showScreen(Screen.SESSION, true);
    }

    private void shakeUnsupported() {
        mUnsupported.setVisibility(View.VISIBLE);
        MultiplayerMotion.fail(mUnsupported);
    }

    private void onJoinWorld() {
        VirtualLanState s = mLast;
        if (!s.phase.isConnected() || s.localGamePort <= 0) return;
        String address = gameAddress(s);
        Instance instance = Instances.loadSelectedInstance();
        if (instance == null) {
            Toast.makeText(mContext, R.string.no_instance, Toast.LENGTH_SHORT).show();
            return;
        }
        File gameDir = instance.getGameDirectory();
        String label = s.worldName.isEmpty() ? (s.hostName.isEmpty() ? s.sessionCode : s.hostName) : s.worldName;
        CSLauncherApplication.sExecutorService.execute(() -> {
            boolean ok;
            try {
                ServersDat.upsert(gameDir, label, address);
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean written = ok;
            mRoot.post(() -> {
                if (!mShown) return;
                if (!written) {
                    Toast.makeText(mContext, R.string.vlan_servers_dat_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                if (mHost.inGame()) {
                    Toast.makeText(mContext, mContext.getString(R.string.vlan_join_world_ingame_toast), Toast.LENGTH_LONG).show();
                    mHost.onCloseRequested();
                } else {
                    Toast.makeText(mContext, mContext.getString(R.string.vlan_join_world_launch_toast), Toast.LENGTH_LONG).show();
                    com.craftstudio.cslauncher.extra.ExtraCore.setValue(com.craftstudio.cslauncher.extra.ExtraConstants.LAUNCH_GAME, true);
                }
            });
        });
    }

    private void shareInvite() {
        VirtualLanState s = mLast;
        if (s.sessionCode.isEmpty()) return;
        String text = mContext.getString(R.string.vlan_invite_text, mRepo.playerName(), s.sessionCode);
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        try {
            mHost.activity().startActivity(Intent.createChooser(send, mContext.getString(R.string.vlan_share_invite)));
        } catch (Exception e) {
            copy(text, R.string.vlan_copied_code);
        }
    }

    private void copy(@Nullable String text, int toast) {
        if (text == null || text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("Virtual LAN", text));
        Toast.makeText(mContext, toast, Toast.LENGTH_SHORT).show();
    }

    private void goHome() {
        mClient.dismissFinalState();
        renderRecent();
        showScreen(Screen.HOME, true);
    }

    // ───────────────────────────── client callbacks ─────────────────────────────

    /** Last failed / ended state and the engine output, kept so diagnostics still say what happened after Back */
    @Nullable private VirtualLanState mLastFinal;
    @NonNull private List<String> mEngineLog = new ArrayList<>();

    @Override
    public void onEngineLog(@NonNull List<String> lines) {
        if (!lines.isEmpty()) mEngineLog = new ArrayList<>(lines);
    }

    @Override
    public void onServiceConnected() {
        mClient.requestLog();
    }

    @Override
    public void onState(@NonNull VirtualLanState state) {
        VirtualLanState previous = mLast;
        mLast = state;
        if (state.phase == VirtualLanState.Phase.FAILED || state.phase == VirtualLanState.Phase.ENDED) {
            mLastFinal = state;
            mClient.requestLog();
        }
        if (!state.sessionCode.isEmpty() && (state.isActive() || state.phase == VirtualLanState.Phase.ENDED)) {
            mRepo.remember(state);
        }
        if (state.isActive()) {
            mPendingCreate = false;
            mPendingJoinCode = null;
            mAwaitingConsent = false;
            mRoot.removeCallbacks(mConsentWatchdog);
            if (mScreen != Screen.SESSION) showScreen(Screen.SESSION, true);
            renderSession(state, previous);
        } else if (state.phase == VirtualLanState.Phase.FAILED || state.phase == VirtualLanState.Phase.ENDED) {
            mPendingCreate = false;
            mPendingJoinCode = null;
            mAwaitingConsent = false;
            mRoot.removeCallbacks(mConsentWatchdog);
            if (state.isGuest() && state.reason != VirtualLanState.Reason.NONE) {
                Instance instance = Instances.loadSelectedInstance();
                if (instance != null) {
                    File dir = instance.getGameDirectory();
                    CSLauncherApplication.sExecutorService.execute(() -> ServersDat.remove(dir));
                }
            }
            renderResult(state);
            if (mScreen != Screen.RESULT) showScreen(Screen.RESULT, true);
        } else {
            // IDLE: only move home when we were not just asked to start something
            if (mScreen == Screen.SESSION && !mPendingCreate && mPendingJoinCode == null) showScreen(Screen.HOME, true);
        }
    }

    // ───────────────────────────── screens ─────────────────────────────

    private void showScreen(Screen target, boolean animate) {
        if (mScreen == target && (target != Screen.HOME || mHome.getVisibility() == View.VISIBLE)) return;
        Screen from = mScreen;
        mScreen = target;
        ViewGroup in = screenView(target);
        if (target == Screen.SESSION) {
            renderSession(mLast, VirtualLanState.IDLE);
        } else {
            stopSweep();
        }
        if (target == Screen.HOME) {
            mCelebrated = false;
            mLastEventSeq = 0;
            mMemberRows.clear();
            mMembers.removeAllViews();
            mEvents.removeAllViews();
            mEnd.setEnabled(true);
        }
        if (!animate) {
            mHome.setVisibility(target == Screen.HOME ? View.VISIBLE : View.GONE);
            mSession.setVisibility(target == Screen.SESSION ? View.VISIBLE : View.GONE);
            mResult.setVisibility(target == Screen.RESULT ? View.VISIBLE : View.GONE);
            return;
        }
        ViewGroup out = from == target ? null : screenView(from);
        MultiplayerMotion.screenExit(out, () -> MultiplayerMotion.screenEnter(in));
        if (target == Screen.SESSION && mLast.phase.isBusy()) startSweep();
    }

    private ViewGroup screenView(Screen s) {
        switch (s) {
            case SESSION: return mSession;
            case RESULT: return mResult;
            default: return mHome;
        }
    }

    private void toggleJoin(boolean open) {
        mJoinOpen = open;
        MultiplayerMotion.toggleBox(mJoinBox, open);
        if (!open) {
            hideKeyboard(mCodeInput);
            MultiplayerMotion.toggleBox(mCodeError, false);
        }
    }

    // ───────────────────────────── home rendering ─────────────────────────────

    private void renderRecent() {
        List<VirtualLanRepository.Recent> recent = mRepo.recent();
        mRecentList.removeAllViews();
        mRecentEmpty.setVisibility(recent.isEmpty() ? View.VISIBLE : View.GONE);
        mRecentClear.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(mContext);
        for (VirtualLanRepository.Recent r : recent) {
            View row = inflater.inflate(R.layout.item_virtual_lan_recent, mRecentList, false);
            ((TextView) row.findViewById(R.id.vl_recent_code)).setText(r.code);
            String who = r.hosted ? mContext.getString(R.string.vlan_recent_hosted)
                    : (r.hostName.isEmpty() ? mContext.getString(R.string.vlan_recent_joined) : mContext.getString(R.string.vlan_recent_joined_from, r.hostName));
            String sub = r.worldName.isEmpty() ? who : who + " · " + r.worldName;
            ((TextView) row.findViewById(R.id.vl_recent_sub)).setText(sub + " · " + relativeTime(r.time));
            TextView action = row.findViewById(R.id.vl_recent_action);
            action.setText(r.hosted ? R.string.vlan_copy : R.string.vlan_rejoin);
            CSAnimationEngine.attachPressFeedback(action);
            View.OnClickListener act = v -> {
                if (r.hosted) copy(r.code, R.string.vlan_copied_code);
                else startJoin(r.code);
            };
            action.setOnClickListener(act);
            row.setOnClickListener(v -> {
                if (r.hosted) { copy(r.code, R.string.vlan_copied_code); return; }
                toggleJoin(true);
                mCodeInput.setText(r.code);
                mCodeInput.setSelection(mCodeInput.getText().length());
            });
            row.findViewById(R.id.vl_recent_delete).setOnClickListener(v -> MultiplayerMotion.memberOut(row, () -> {
                mRepo.forget(r.code);
                renderRecent();
            }));
            mRecentList.addView(row);
        }
    }

    private String relativeTime(long time) {
        long diff = Math.max(0, System.currentTimeMillis() - time);
        long m = diff / 60000;
        if (m < 1) return mContext.getString(R.string.vlan_time_now);
        if (m < 60) return mContext.getString(R.string.vlan_time_minutes, m);
        long h = m / 60;
        if (h < 24) return mContext.getString(R.string.vlan_time_hours, h);
        return mContext.getString(R.string.vlan_time_days, h / 24);
    }

    // ───────────────────────────── session rendering ─────────────────────────────

    private void renderSession(@NonNull VirtualLanState s, @NonNull VirtualLanState previous) {
        boolean host = s.isHost() || (s.role == VirtualLanState.Role.NONE && mPendingCreate);
        mRolePill.setText(host ? R.string.vlan_role_host : R.string.vlan_role_guest);
        mEnd.setText(host ? R.string.vlan_end_session : R.string.vlan_disconnect);
        mEnd.setEnabled(true);

        // status
        int color = statusColor(s.phase);
        MultiplayerMotion.tintShape(mStatusDot, color);
        MultiplayerMotion.textSwap(mStatusTitle, phaseTitle(s));
        mStatusDetail.setText(phaseDetail(s));
        if (s.phase.isBusy()) {
            mProgressTrack.setVisibility(View.VISIBLE);
            startSweep();
        } else {
            stopSweep();
            mProgressTrack.setVisibility(View.GONE);
        }
        if (s.phase.isConnected() && !previous.phase.isConnected() && !mCelebrated) {
            mCelebrated = true;
            MultiplayerMotion.celebrate(mStatusDot);
        }
        if (s.phase == VirtualLanState.Phase.RECONNECTING && previous.phase != VirtualLanState.Phase.RECONNECTING) {
            MultiplayerMotion.fail(mHero);
        }

        // code
        String code = !s.sessionCode.isEmpty() ? s.sessionCode : (mPendingJoinCode != null ? mPendingJoinCode : "····-····-····");
        mCodeText.setText(code);
        mCodeRow.setVisibility(View.VISIBLE);
        mShare.setVisibility(host ? View.VISIBLE : View.GONE);

        // world
        boolean knowsWorld = s.worldOpen || !s.worldName.isEmpty();
        if (s.worldOpen) {
            mWorldName.setText(s.worldName.isEmpty() ? mContext.getString(R.string.vlan_world_unnamed) : s.worldName);
            String ver = s.versionName.isEmpty() ? "" : s.versionName;
            mWorldSub.setText(ver.isEmpty() ? mContext.getString(R.string.vlan_world_lan_port, s.lanPort)
                    : ver + " · " + mContext.getString(R.string.vlan_world_lan_port, s.lanPort));
            mWorldPill.setText(R.string.vlan_world_open);
            MultiplayerMotion.tintShape(mWorldPill, ContextCompat.getColor(mContext, R.color.vl_ok_bg));
            mWorldPill.setTextColor(ContextCompat.getColor(mContext, R.color.vl_ok));
            mWorldNote.setText(host ? R.string.vlan_world_note_host_open : R.string.vlan_world_note_guest_open);
        } else {
            mWorldName.setText(knowsWorld ? s.worldName : mContext.getString(R.string.vlan_world_none));
            mWorldSub.setText(host ? R.string.vlan_world_sub_host_closed : R.string.vlan_world_sub_guest_closed);
            mWorldPill.setText(R.string.vlan_world_closed);
            MultiplayerMotion.tintShape(mWorldPill, ContextCompat.getColor(mContext, R.color.vl_neutral_bg));
            mWorldPill.setTextColor(ContextCompat.getColor(mContext, R.color.vl_text_dim));
            mWorldNote.setText(host ? R.string.vlan_world_note_host_closed : R.string.vlan_world_note_guest_closed);
        }
        stat(mStatPlayers, R.string.vlan_stat_players, s.worldOpen ? s.playersOnline + "/" + s.playersMax : "–");
        stat(mStatPort, R.string.vlan_stat_lan_port, s.lanPort > 0 ? String.valueOf(s.lanPort) : "–");
        String vip = host ? s.localVip : s.hostVip;
        stat(mStatVip, host ? R.string.vlan_stat_virtual_ip : R.string.vlan_stat_host_ip, vip.isEmpty() ? "–" : vip);

        // guest card
        if (host) {
            mGuestCard.setVisibility(View.GONE);
        } else {
            mGuestCard.setVisibility(View.VISIBLE);
            mGuestHost.setText(s.hostName.isEmpty() ? mContext.getString(R.string.vlan_host_unknown)
                    : mContext.getString(R.string.vlan_host_named, s.hostName));
            boolean ready = s.phase.isConnected() && s.localGamePort > 0;
            boolean playable = ready && s.worldOpen && s.gameReachable;
            mJoinWorld.setEnabled(playable);
            mJoinWorld.setAlpha(playable ? 1f : 0.55f);
            String address = ready ? gameAddress(s) : "\u2013";
            mCopyAddress.setText(address);
            mCopyAddress.setEnabled(ready);
            if (!ready) mGuestNote.setText(R.string.vlan_guest_note_waiting);
            else if (!s.worldOpen) mGuestNote.setText(R.string.vlan_guest_note_world_closed);
            else if (!s.gameReachable) mGuestNote.setText(R.string.vlan_guest_note_verifying);
            else mGuestNote.setText(mHost.inGame() ? R.string.vlan_guest_note_ready_ingame : R.string.vlan_guest_note_ready);
        }

        // connection
        renderConnection(s, host);

        // members (host)
        if (host) {
            mMembersCard.setVisibility(View.VISIBLE);
            renderMembers(s);
        } else {
            mMembersCard.setVisibility(View.GONE);
        }

        // events
        renderEvents(s);
    }

    private void renderConnection(VirtualLanState s, boolean host) {
        String pill;
        int pillBg, pillFg;
        switch (s.phase) {
            case CONNECTED_DIRECT:
                pill = mContext.getString(R.string.vlan_path_direct);
                pillBg = R.color.vl_ok_bg; pillFg = R.color.vl_ok; break;
            case CONNECTED_RELAY:
                pill = mContext.getString(R.string.vlan_path_relay);
                pillBg = R.color.vl_info_bg; pillFg = R.color.vl_info; break;
            case RECONNECTING:
                pill = mContext.getString(R.string.vlan_path_reconnecting);
                pillBg = R.color.vl_warn_bg; pillFg = R.color.vl_warn; break;
            default:
                pill = mContext.getString(R.string.vlan_path_pending);
                pillBg = R.color.vl_neutral_bg; pillFg = R.color.vl_text_dim;
        }
        mConnPill.setText(pill);
        MultiplayerMotion.tintShape(mConnPill, ContextCompat.getColor(mContext, pillBg));
        mConnPill.setTextColor(ContextCompat.getColor(mContext, pillFg));

        double latency = host ? s.relayLatencyMs : s.hostLatencyMs;
        int latencyLabel = host ? R.string.vlan_stat_relay_latency : R.string.vlan_stat_latency;
        stat(mStatLatency, latencyLabel, latency >= 0 ? formatMs(latency) : "–");
        stat(mStatRelays, R.string.vlan_stat_relays, s.relayNodesConnected > 0 ? String.valueOf(s.relayNodesConnected) : (s.phase == VirtualLanState.Phase.STARTING ? "–" : "0"));
        stat(mStatNat, R.string.vlan_stat_nat, s.natType.isEmpty() ? "–" : s.natType);

        StringBuilder note = new StringBuilder();
        if (!s.relayNames.isEmpty()) {
            note.append(mContext.getString(R.string.vlan_conn_relay_names, join(s.relayNames.size() > 2 ? s.relayNames.subList(0, 2) : s.relayNames, ", ")));
            if (s.relayNames.size() > 2) note.append(" +").append(s.relayNames.size() - 2);
        }
        if (!s.tunnelProto.isEmpty()) {
            if (note.length() > 0) note.append(" · ");
            note.append(s.tunnelProto);
        }
        if (!s.engineVersion.isEmpty()) {
            if (note.length() > 0) note.append(" · ");
            note.append(mContext.getString(R.string.vlan_conn_engine, s.engineVersion));
        }
        if (host && s.inGame > 0) {
            if (note.length() > 0) note.append(" · ");
            note.append(mContext.getString(R.string.vlan_conn_in_game, s.inGame));
        }
        mConnNote.setText(note.length() == 0 ? mContext.getString(R.string.vlan_conn_note_pending) : note.toString());
    }

    private void renderMembers(VirtualLanState s) {
        mMembersTitle.setText(mContext.getString(R.string.vlan_players_count, s.members.size()));
        mMembersEmpty.setVisibility(s.members.isEmpty() ? View.VISIBLE : View.GONE);
        List<String> seen = new ArrayList<>();
        LayoutInflater inflater = LayoutInflater.from(mContext);
        int index = 0;
        for (VirtualLanState.Member m : s.members) {
            seen.add(m.id);
            View row = mMemberRows.get(m.id);
            if (row == null) {
                row = inflater.inflate(R.layout.item_virtual_lan_member, mMembers, false);
                View kick = row.findViewById(R.id.vl_member_kick);
                CSAnimationEngine.attachPressFeedback(kick);
                final String id = m.id;
                kick.setOnClickListener(v -> {
                    mClient.kick(id);
                    v.setEnabled(false);
                });
                mMembers.addView(row);
                mMemberRows.put(m.id, row);
                MultiplayerMotion.memberIn(row, index);
            }
            ((TextView) row.findViewById(R.id.vl_member_name)).setText(m.name);
            StringBuilder sub = new StringBuilder();
            if (!m.vip.isEmpty()) sub.append(m.vip);
            if (m.direct != null) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(mContext.getString(m.direct ? R.string.vlan_member_direct : R.string.vlan_member_relay));
            }
            if (m.latencyMs >= 0) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(formatMs(m.latencyMs));
            }
            if (sub.length() == 0) sub.append(mContext.getString(R.string.vlan_member_connecting));
            ((TextView) row.findViewById(R.id.vl_member_sub)).setText(sub);
            index++;
        }
        for (String id : new ArrayList<>(mMemberRows.keySet())) {
            if (seen.contains(id)) continue;
            View row = mMemberRows.remove(id);
            MultiplayerMotion.memberOut(row, () -> mMembers.removeView(row));
        }
    }

    private void renderEvents(VirtualLanState s) {
        if (s.events.isEmpty()) {
            mEventsCard.setVisibility(View.GONE);
            return;
        }
        mEventsCard.setVisibility(View.VISIBLE);
        for (VirtualLanState.Event e : s.events) {
            if (e.seq <= mLastEventSeq) continue;
            mLastEventSeq = e.seq;
            TextView line = new TextView(mContext);
            line.setTextColor(ContextCompat.getColor(mContext, R.color.vl_text_dim));
            line.setTextSize(12f);
            line.setPadding(0, dp(3), 0, dp(3));
            line.setText(eventText(e));
            mEvents.addView(line, 0);
            MultiplayerMotion.memberIn(line, 0);
            while (mEvents.getChildCount() > 5) mEvents.removeViewAt(mEvents.getChildCount() - 1);
        }
    }

    private String eventText(VirtualLanState.Event e) {
        switch (e.type) {
            case PLAYER_JOINED: return mContext.getString(R.string.vlan_event_joined, e.arg);
            case PLAYER_LEFT: return mContext.getString(R.string.vlan_event_left, e.arg);
            case WORLD_OPENED: return mContext.getString(R.string.vlan_event_world_opened, e.arg);
            case WORLD_CLOSED: return mContext.getString(R.string.vlan_event_world_closed);
            case PATH_DIRECT: return mContext.getString(R.string.vlan_event_path_direct);
            case PATH_RELAY: return mContext.getString(R.string.vlan_event_path_relay);
            case RECONNECTING: return mContext.getString(R.string.vlan_event_reconnecting);
            case RECOVERED: return mContext.getString(R.string.vlan_event_recovered);
            default: return e.type.name();
        }
    }

    // ───────────────────────────── result rendering ─────────────────────────────

    private void renderResult(VirtualLanState s) {
        boolean failed = s.phase == VirtualLanState.Phase.FAILED;
        boolean soft = s.reason == VirtualLanState.Reason.LEFT || s.reason == VirtualLanState.Reason.HOST_ENDED
                || s.reason == VirtualLanState.Reason.GAME_CLOSED || s.reason == VirtualLanState.Reason.EXPIRED;
        mResultIcon.setImageResource(failed ? R.drawable.ic_vl_alert : (soft ? R.drawable.ic_vl_check : R.drawable.ic_vl_alert));
        mResultIcon.setColorFilter(ContextCompat.getColor(mContext, failed ? R.color.vl_bad : (soft ? R.color.vl_ok : R.color.vl_warn)));
        mResultTitle.setText(failed ? R.string.vlan_result_failed : R.string.vlan_result_ended);
        mResultMessage.setText(reasonText(s));
        String tips = reasonTips(s.reason);
        mResultTips.setText(tips);
        mResultTips.setVisibility(tips.isEmpty() ? View.GONE : View.VISIBLE);
        boolean canRetry = s.reason != VirtualLanState.Reason.UNSUPPORTED_DEVICE && s.reason != VirtualLanState.Reason.KICKED
                && !(s.isGuest() && s.reason == VirtualLanState.Reason.HOST_ENDED);
        mResultRetry.setVisibility(canRetry ? View.VISIBLE : View.GONE);
        mResultRetry.setText(s.isGuest() ? R.string.vlan_rejoin : R.string.vlan_try_again);
        if (failed) MultiplayerMotion.fail(mResultIcon);
    }

    // ───────────────────────────── text mapping ─────────────────────────────

    private String phaseTitle(VirtualLanState s) {
        switch (s.phase) {
            case STARTING: return mContext.getString(R.string.vlan_phase_starting);
            case DISCOVERING: return mContext.getString(s.isHost() ? R.string.vlan_phase_connecting : R.string.vlan_phase_discovering);
            case ESTABLISHING: return mContext.getString(R.string.vlan_phase_establishing);
            case CONNECTED_DIRECT: return mContext.getString(s.isHost() ? R.string.vlan_phase_live : R.string.vlan_phase_connected_direct);
            case CONNECTED_RELAY: return mContext.getString(s.isHost() ? R.string.vlan_phase_live_relay : R.string.vlan_phase_connected_relay);
            case RECONNECTING: return mContext.getString(R.string.vlan_phase_reconnecting);
            case FAILED: return mContext.getString(R.string.vlan_phase_failed);
            case ENDED: return mContext.getString(R.string.vlan_result_ended);
            default: return mContext.getString(mAwaitingConsent ? R.string.vlan_phase_vpn_permission : R.string.vlan_phase_connecting);
        }
    }

    private String phaseDetail(VirtualLanState s) {
        switch (s.phase) {
            case STARTING: return mContext.getString(R.string.vlan_detail_starting);
            case DISCOVERING: return withStage(s, mContext.getString(s.isHost() ? R.string.vlan_detail_host_discovering : R.string.vlan_detail_guest_discovering));
            case ESTABLISHING: return withStage(s, mContext.getString(R.string.vlan_detail_establishing));
            case CONNECTED_DIRECT:
            case CONNECTED_RELAY:
                if (s.isHost()) {
                    return s.members.isEmpty() ? mContext.getString(R.string.vlan_detail_host_live_empty)
                            : mContext.getString(R.string.vlan_detail_host_live, s.members.size());
                }
                return mContext.getString(s.worldOpen ? R.string.vlan_detail_guest_ready : R.string.vlan_detail_guest_no_world);
            case RECONNECTING: return mContext.getString(R.string.vlan_detail_reconnecting);
            default: return mAwaitingConsent ? mContext.getString(R.string.vlan_detail_vpn_permission) : s.detail;
        }
    }

    /** Through the VPN the host's virtual IP is used; without it the loopback forward */
    private static String gameAddress(VirtualLanState s) {
        if (!s.gameHost.isEmpty() && s.gamePort > 0) return s.gameHost + ":" + s.gamePort;
        return "127.0.0.1:" + s.localGamePort;
    }

    /** Busy phases show the latest engine stage underneath so a stall is explainable without the log */
    private static String withStage(VirtualLanState s, String base) {
        if (s.stages.isEmpty()) return base;
        String last = s.stages.get(s.stages.size() - 1);
        int sp = last.indexOf(' ');
        if (sp > 0 && last.startsWith("+")) last = last.substring(sp + 1);
        return base + "\n" + last;
    }

    private String reasonText(VirtualLanState s) {
        switch (s.reason) {
            case UNSUPPORTED_DEVICE: return mContext.getString(R.string.vlan_unsupported);
            case ENGINE_FAILED: return mContext.getString(R.string.vlan_reason_engine_failed) + (s.detail.isEmpty() ? "" : "\n" + s.detail);
            case ENGINE_CRASHED: return mContext.getString(R.string.vlan_reason_engine_crashed) + (s.detail.isEmpty() ? "" : "\n" + s.detail);
            case NO_NETWORK: return mContext.getString(R.string.vlan_reason_no_network);
            case NO_RELAY_REACHABLE: return mContext.getString(R.string.vlan_reason_no_relay);
            case RELAY_LOST: return mContext.getString(R.string.vlan_reason_relay_lost);
            case HOST_NOT_FOUND: return mContext.getString(R.string.vlan_reason_host_not_found);
            case HOST_UNREACHABLE: return mContext.getString(R.string.vlan_reason_host_unreachable) + (s.detail.isEmpty() ? "" : "\n" + s.detail);
            case VPN_DENIED: return mContext.getString(R.string.vlan_reason_vpn_denied);
            case VPN_REVOKED: return mContext.getString(R.string.vlan_reason_vpn_revoked);
            case VPN_FAILED: return mContext.getString(R.string.vlan_reason_vpn_failed) + (s.detail.isEmpty() ? "" : "\n" + s.detail);
            case HOST_LOST: return mContext.getString(R.string.vlan_reason_host_lost);
            case HOST_ENDED: return mContext.getString(R.string.vlan_reason_host_ended);
            case KICKED: return mContext.getString(R.string.vlan_reason_kicked);
            case EXPIRED: return mContext.getString(R.string.vlan_reason_expired);
            case LEFT: return mContext.getString(s.isHost() ? R.string.vlan_reason_left_host : R.string.vlan_reason_left_guest);
            case GAME_CLOSED: return mContext.getString(R.string.vlan_reason_game_closed);
            case PORT_BUSY: return mContext.getString(R.string.vlan_reason_port_busy);
            case INTERNAL: return mContext.getString(R.string.vlan_reason_internal) + (s.detail.isEmpty() ? "" : "\n" + s.detail);
            default: return s.detail;
        }
    }

    private String reasonTips(VirtualLanState.Reason reason) {
        switch (reason) {
            case NO_RELAY_REACHABLE:
            case RELAY_LOST:
            case NO_NETWORK:
                return mContext.getString(R.string.vlan_tips_network);
            case HOST_NOT_FOUND:
                return mContext.getString(R.string.vlan_tips_host_not_found);
            case HOST_UNREACHABLE:
            case HOST_LOST:
                return mContext.getString(R.string.vlan_tips_host_lost);
            case ENGINE_FAILED:
            case ENGINE_CRASHED:
            case PORT_BUSY:
                return mContext.getString(R.string.vlan_tips_engine);
            case VPN_DENIED:
            case VPN_REVOKED:
            case VPN_FAILED:
                return mContext.getString(R.string.vlan_tips_vpn);
            default:
                return "";
        }
    }

    private int statusColor(VirtualLanState.Phase phase) {
        int res;
        switch (phase) {
            case CONNECTED_DIRECT: res = R.color.vl_ok; break;
            case CONNECTED_RELAY: res = R.color.vl_info; break;
            case RECONNECTING: res = R.color.vl_warn; break;
            case FAILED: res = R.color.vl_bad; break;
            case ENDED: res = R.color.vl_neutral; break;
            default: res = R.color.vl_platinum;
        }
        return ContextCompat.getColor(mContext, res);
    }

    /** Beta feedback: diagnostics + the tail of the launcher log as one file, through the share sheet. */
    private void sendLogs() {
        try {
            StringBuilder report = new StringBuilder();
            report.append(diagnostics(mLast)).append('\n');
            report.append("device=").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                    .append(" android=").append(android.os.Build.VERSION.RELEASE).append(" sdk=").append(android.os.Build.VERSION.SDK_INT)
                    .append(" abi=").append(android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "?")
                    .append(" launcher=").append(com.craftstudio.cslauncher.BuildConfig.VERSION_NAME)
                    .append(" build=").append(com.craftstudio.cslauncher.BuildConfig.VERSION_CODE).append('\n');
            report.append("\n==== VPN bridge state ====\n");
            com.craftstudio.cslauncher.multiplayer.VpnBridgeState.Snapshot vs = com.craftstudio.cslauncher.multiplayer.VpnBridgeState.read(mContext);
            report.append("state=").append(vs.state.isEmpty() ? "none" : vs.state).append(vs.stale ? " (stale heartbeat: process gone)" : "")
                    .append(" pid=").append(vs.pid).append(" vip=").append(vs.vip)
                    .append(vs.error.isEmpty() ? "" : " error=" + vs.error).append('\n');
            report.append("\n==== VPN service log ====\n");
            report.append(com.craftstudio.cslauncher.multiplayer.VpnBridgeState.tail(com.craftstudio.cslauncher.multiplayer.VpnBridgeState.serviceLog(mContext), 24 * 1024));
            report.append("\n==== VPN bridge (tun2socks) log ====\n");
            report.append(com.craftstudio.cslauncher.multiplayer.VpnBridgeState.tail(com.craftstudio.cslauncher.multiplayer.VpnBridgeState.bridgeLog(mContext), 48 * 1024));
            java.io.File hs = newestHsErr();
            if (hs != null) {
                report.append("\n==== JVM crash file ").append(hs.getName()).append(" (head) ====\n");
                String head = com.craftstudio.cslauncher.multiplayer.VpnBridgeState.tail(hs, 1 << 20);
                report.append(head.length() > 12000 ? head.substring(0, 12000) + "\n[...]\n" : head);
            }
            java.io.File log = new java.io.File(com.craftstudio.cslauncher.Tools.DIR_GAME_HOME, "latestlog.txt");
            report.append("\n==== launcher log (tail) ====\n");
            if (log.isFile()) {
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(log, "r")) {
                    long max = 96 * 1024;
                    long start = Math.max(0, raf.length() - max);
                    raf.seek(start);
                    byte[] buf = new byte[(int) (raf.length() - start)];
                    raf.readFully(buf);
                    report.append(new String(buf, "UTF-8"));
                }
            } else {
                report.append("(no latestlog.txt)\n");
            }
            java.io.File dir = new java.io.File(mContext.getCacheDir(), "reports");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            java.io.File out = new java.io.File(dir, "cs-virtual-lan-report.txt");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(report.toString().getBytes("UTF-8"));
            }
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(mContext, mContext.getPackageName() + ".updates", out);
            android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            send.putExtra(android.content.Intent.EXTRA_SUBJECT, mContext.getString(R.string.vlan_report_title));
            send.putExtra(android.content.Intent.EXTRA_TEXT, mContext.getString(R.string.vlan_report_title));
            send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            android.content.Intent chooser = android.content.Intent.createChooser(send, mContext.getString(R.string.vlan_send_logs));
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(chooser);
        } catch (Throwable t) {
            android.widget.Toast.makeText(mContext, R.string.vlan_report_failed, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Newest hs_err_pid*.log from the last hour, if the JVM died natively while Virtual LAN was on. */
    @androidx.annotation.Nullable
    private static java.io.File newestHsErr() {
        java.io.File best = null;
        for (String dirName : new String[]{com.craftstudio.cslauncher.Tools.DIR_GAME_HOME, com.craftstudio.cslauncher.Tools.DIR_GAME_NEW}) {
            if (dirName == null) continue;
            java.io.File[] files = new java.io.File(dirName).listFiles();
            if (files == null) continue;
            for (java.io.File f : files) {
                if (f.isFile() && f.getName().startsWith("hs_err_pid") && f.getName().endsWith(".log")
                        && (best == null || f.lastModified() > best.lastModified())) best = f;
            }
        }
        if (best != null && System.currentTimeMillis() - best.lastModified() > 60 * 60 * 1000L) return null;
        return best;
    }

    private String diagnostics(VirtualLanState s) {
        StringBuilder b = new StringBuilder("CS Launcher Plus - Virtual LAN diagnostics\n");
        b.append("role=").append(s.role).append(" phase=").append(s.phase).append(" reason=").append(s.reason).append('\n');
        b.append("code=").append(s.sessionCode).append('\n');
        b.append("vip=").append(s.localVip).append(" hostVip=").append(s.hostVip).append('\n');
        b.append("relays=").append(s.relayNodesConnected).append(' ').append(s.relayNames).append(" relayLatency=").append(s.relayLatencyMs).append('\n');
        b.append("nat=").append(s.natType).append(" proto=").append(s.tunnelProto).append(" engine=").append(s.engineVersion).append('\n');
        b.append("world=").append(s.worldOpen).append(' ').append(s.worldName).append(" v=").append(s.versionName)
                .append(" lanPort=").append(s.lanPort).append(" proxy=").append(s.proxyPort).append(" dir=").append(s.directoryPort).append('\n');
        b.append("players=").append(s.playersOnline).append('/').append(s.playersMax).append(" inGame=").append(s.inGame).append('\n');
        b.append("host=").append(s.hostName).append(" hostLatency=").append(s.hostLatencyMs).append(" localGamePort=").append(s.localGamePort)
                .append(" gameReachable=").append(s.gameReachable).append(" gameLatency=").append(s.gameLatencyMs).append('\n');
        b.append("vpn=").append(s.vpnUp).append(" gameAddress=").append(gameAddress(s)).append(" socks5=").append(s.socks5Port).append('\n');
        for (VirtualLanState.Member m : s.members) {
            b.append("member ").append(m.name).append(' ').append(m.vip).append(" lat=").append(m.latencyMs).append(" direct=").append(m.direct).append('\n');
        }
        b.append("detail=").append(s.detail).append('\n');
        b.append("stages:\n");
        for (String line : s.stages) b.append("  ").append(line).append('\n');
        VirtualLanState last = mLastFinal;
        if (last != null && last != s && s.phase == VirtualLanState.Phase.IDLE) {
            b.append("\n---- last session (").append(last.role).append(' ').append(last.phase).append(" reason=").append(last.reason).append(") ----\n");
            b.append("detail=").append(last.detail).append('\n');
            b.append("relays=").append(last.relayNodesConnected).append(' ').append(last.relayNames)
                    .append(" vip=").append(last.localVip).append(" engine=").append(last.engineVersion).append('\n');
            b.append("stages:\n");
            for (String line : last.stages) b.append("  ").append(line).append('\n');
        }
        if (!mEngineLog.isEmpty()) {
            b.append("\n---- engine output (tail) ----\n");
            int from = Math.max(0, mEngineLog.size() - 80);
            for (int i = from; i < mEngineLog.size(); i++) b.append("  ").append(mEngineLog.get(i)).append('\n');
        }
        return b.toString();
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private void stat(View tile, int label, String value) {
        ((TextView) tile.findViewById(R.id.vl_stat_label)).setText(label);
        TextView v = tile.findViewById(R.id.vl_stat_value);
        if (!value.contentEquals(v.getText())) v.setText(value);
    }

    private static String formatMs(double ms) {
        if (ms < 1) return "<1 ms";
        return String.format(Locale.US, ms < 10 ? "%.1f ms" : "%.0f ms", ms);
    }

    private static String join(List<String> items, String sep) {
        StringBuilder b = new StringBuilder();
        for (String s : items) {
            if (b.length() > 0) b.append(sep);
            b.append(s);
        }
        return b.toString();
    }

    private void startSweep() {
        if (mSweep != null || mScreen != Screen.SESSION) return;
        mSweep = MultiplayerMotion.sweep(mProgressTrack, mProgressBar);
    }

    private void stopSweep() {
        if (mSweep != null) {
            mSweep.cancel();
            mSweep = null;
        }
        mProgressBar.setTranslationX(0f);
    }

    private void focus(EditText field) {
        field.requestFocus();
        InputMethodManager imm = (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard(View field) {
        InputMethodManager imm = (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.clearFocus();
    }

    private int dp(int v) {
        return Math.round(v * mContext.getResources().getDisplayMetrics().density);
    }
}
