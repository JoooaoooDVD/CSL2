package com.craftstudio.cslauncher.fragments;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.craftstudio.cslauncher.CSLauncherApplication;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.extra.ExtraCore;
import com.craftstudio.cslauncher.instances.Instances;
import com.craftstudio.cslauncher.modding.fabric.FabriclikeUtils;
import com.craftstudio.cslauncher.modding.fabric.FabricVersion;
import com.craftstudio.cslauncher.modding.ModloaderDownloadListener;
import com.craftstudio.cslauncher.modding.ModloaderListenerProxy;
import com.craftstudio.cslauncher.modding.modpacks.FabricApiInstaller;
import com.craftstudio.cslauncher.modding.modpacks.SelfReferencingFuture;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Future;

public abstract class FabriclikeInstallFragment extends Fragment implements ModloaderDownloadListener, CompoundButton.OnCheckedChangeListener {
    private final FabriclikeUtils mFabriclikeUtils;
    private final String mExtraTag;
    private Spinner mGameVersionSpinner;
    private FabricVersion[] mGameVersionArray;
    private Future<?> mGameVersionFuture;
    private String mSelectedGameVersion;
    private Spinner mLoaderVersionSpinner;
    private FabricVersion[] mLoaderVersionArray;
    private Future<?> mLoaderVersionFuture;
    private String mSelectedLoaderVersion;
    private ProgressBar mProgressBar;
    private Button mStartButton;
    private View mRetryView;
    private CheckBox mOnlyStableCheckbox;
    /** Fabric API auto-install toggle, only shown for the real Fabric loader */
    private View mFabricApiRow;
    private com.google.android.material.materialswitch.MaterialSwitch mFabricApiSwitch;
    private TextView mFabricApiSubtitle;
    private volatile boolean mInstallFabricApi;
    protected FabriclikeInstallFragment(FabriclikeUtils mFabriclikeUtils, String mFragmentTag) {
        super(R.layout.fragment_fabric_install);
        this.mFabriclikeUtils = mFabriclikeUtils;
        this.mExtraTag = mFragmentTag + "_proxy";
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mAppContext = context.getApplicationContext();
    }

    private Context mAppContext;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        CSAnimationEngine.animateDialogContentView(view.findViewById(R.id.fabric_installer_banner));
        mStartButton = view.findViewById(R.id.fabric_installer_start_button);
        CSAnimationEngine.attachPressFeedback(mStartButton);
        mStartButton.setOnClickListener(this::onClickStart);
        mGameVersionSpinner = view.findViewById(R.id.fabric_installer_game_ver_spinner);
        mGameVersionSpinner.setOnItemSelectedListener(new GameVersionSelectedListener());
        mLoaderVersionSpinner = view.findViewById(R.id.fabric_installer_loader_ver_spinner);
        mLoaderVersionSpinner.setOnItemSelectedListener(new LoaderVersionSelectedListener());
        mProgressBar = view.findViewById(R.id.fabric_installer_progress_bar);
        mRetryView = view.findViewById(R.id.fabric_installer_retry_layout);
        mOnlyStableCheckbox = view.findViewById(R.id.fabric_installer_only_stable_checkbox);
        mOnlyStableCheckbox.setOnCheckedChangeListener(this);
        view.findViewById(R.id.fabric_installer_retry_button).setOnClickListener(this::onClickRetry);
        mFabricApiRow = view.findViewById(R.id.fabric_installer_api_row);
        mFabricApiSwitch = view.findViewById(R.id.fabric_installer_api_switch);
        mFabricApiSubtitle = view.findViewById(R.id.fabric_installer_api_subtitle);
        if (supportsFabricApi()) {
            mFabricApiRow.setVisibility(View.VISIBLE);
            boolean enabled = FabricApiInstaller.isAutoInstallEnabled();
            mFabricApiSwitch.setChecked(enabled);
            mInstallFabricApi = enabled;
            mFabricApiSubtitle.setText(enabled ? R.string.fabric_api_toggle_desc : R.string.fabric_api_toggle_desc_off);
            mFabricApiSwitch.setOnCheckedChangeListener((button, checked) -> {
                mInstallFabricApi = checked;
                FabricApiInstaller.setAutoInstallEnabled(checked);
                mFabricApiSubtitle.setText(checked ? R.string.fabric_api_toggle_desc : R.string.fabric_api_toggle_desc_off);
            });
            // Tapping anywhere on the row flips the switch (easier on phones)
            mFabricApiRow.setOnClickListener(v -> mFabricApiSwitch.toggle());
            CSAnimationEngine.attachPressFeedback(mFabricApiRow);
        } else {
            mFabricApiRow.setVisibility(View.GONE);
            mInstallFabricApi = false;
        }
        ((TextView)view.findViewById(R.id.fabric_installer_label_loader_ver)).setText(getString(R.string.fabric_dl_loader_version, mFabriclikeUtils.getName()));
        ModloaderListenerProxy proxy = getListenerProxy();
        if(proxy != null) {
            mStartButton.setEnabled(false);
            proxy.attachListener(this);
        }
        updateGameVersions();
    }

    @Override
    public void onStop() {
        cancelFutureChecked(mGameVersionFuture);
        cancelFutureChecked(mLoaderVersionFuture);
        ModloaderListenerProxy proxy = getListenerProxy();
        if(proxy != null) {
            proxy.detachListener();
        }
        super.onStop();
    }

    private void onClickStart(View v) {
        if(ProgressKeeper.hasOngoingTasks()) {
            Toast.makeText(v.getContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }
        ModloaderListenerProxy proxy = new ModloaderListenerProxy();
        proxy.attachListener(this);
        setListenerProxy(proxy);
        mStartButton.setEnabled(false);
        CSLauncherApplication.sExecutorService.execute(this::performInstallation);
    }

    /** Fabric API is a Fabric-loader library; Quilt ships its own (QFAPI) and Legacy Fabric has none */
    private boolean supportsFabricApi() {
        return mFabriclikeUtils == FabriclikeUtils.FABRIC_UTILS;
    }

    private void performInstallation() {
        try {
            final String gameVersion = mSelectedGameVersion;
            String versionId = mFabriclikeUtils.install(gameVersion, mSelectedLoaderVersion);
            if(versionId == null) {
                getListenerProxy().onDataNotAvailable();
                return;
            }
            com.craftstudio.cslauncher.instances.Instance instance = Instances.createInstance((i)->{
                i.name = mFabriclikeUtils.getName();
                i.icon = mFabriclikeUtils.getIconName();
                i.versionId = versionId;
            }, versionId);
            if (mInstallFabricApi && supportsFabricApi() && gameVersion != null) {
                installFabricApi(instance, gameVersion);
            }
            getListenerProxy().onDownloadFinished(null);
        }catch (IOException e) {
            Tools.showErrorRemote(e);
        }
    }

    /**
     * Downloads the matching Fabric API into the new profile. Runs on the install executor,
     * never fails the profile creation: problems are reported as a toast and the user can
     * add Fabric API from the Mod Browser later.
     */
    private void installFabricApi(com.craftstudio.cslauncher.instances.Instance instance, String gameVersion) {
        final Context appContext = mAppContext;
        if (appContext == null) return;
        String message;
        try {
            String key = LauncherPreferences.PREF_DISABLE_CURSEFORGE_API ? null : LauncherPreferences.resolveCurseforgeApiKey(appContext);
            FabricApiInstaller.Result result = FabricApiInstaller.install(appContext, instance, gameVersion, key);
            switch (result) {
                case INSTALLED: message = appContext.getString(R.string.fabric_api_installed, gameVersion); break;
                case ALREADY_PRESENT: message = appContext.getString(R.string.fabric_api_already); break;
                default: message = appContext.getString(R.string.fabric_api_not_available, gameVersion); break;
            }
        } catch (Exception e) {
            Log.w("FabricApi", "auto-install failed", e);
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            message = appContext.getString(R.string.fabric_api_failed, why);
        }
        final String text = message;
        Tools.runOnUiThread(() -> Toast.makeText(appContext, text, Toast.LENGTH_LONG).show());
    }

    @SuppressWarnings("unused")
    private void onClickRetry(View v) {
        mStartButton.setEnabled(false);
        mRetryView.setVisibility(View.GONE);
        mLoaderVersionSpinner.setAdapter(null);
        if(mGameVersionArray == null) {
            mGameVersionSpinner.setAdapter(null);
            updateGameVersions();
            return;
        }
        updateLoaderVersions();
    }

    @Override
    public void onDownloadFinished(File downloadedFile) {
        Tools.runOnUiThread(()->{

            getListenerProxy().detachListener();
            setListenerProxy(null);
            mStartButton.setEnabled(true);
            // This works because the due to the fact that we have transitioned here
            // without adding a transaction to the back stack, which caused the previous
            // transaction to be amended (i guess?? thats how the back stack dump looks like)
            // we can get back to the main fragment with just one back stack pop.
            // For some reason that amendment causes the transaction to lose its tag
            // so we cant use the tag here.
            getParentFragmentManager().popBackStackImmediate();
        });
    }

    @Override
    public void onDataNotAvailable() {
        Tools.runOnUiThread(()->{
            Context context = requireContext();
            getListenerProxy().detachListener();
            setListenerProxy(null);
            mStartButton.setEnabled(true);
            Tools.dialog(context,
                    context.getString(R.string.global_error),
                    context.getString(R.string.fabric_dl_cant_read_meta, mFabriclikeUtils.getName()));
        });
    }

    @Override
    public void onDownloadError(Exception e) {
        Tools.runOnUiThread(()-> {
            Context context = requireContext();
            getListenerProxy().detachListener();
            setListenerProxy(null);
            mStartButton.setEnabled(true);
            Tools.showError(context, e);
        });
    }

    private void cancelFutureChecked(Future<?> future) {
        if(future != null && !future.isCancelled()) future.cancel(true);
    }

    private void startLoading() {
        mProgressBar.setVisibility(View.VISIBLE);
        mStartButton.setEnabled(false);
    }

    private void stopLoading() {
        mProgressBar.setVisibility(View.GONE);
        // The "visibility on" is managed by the spinners
    }

    private ArrayAdapter<FabricVersion> createAdapter(FabricVersion[] fabricVersions, boolean onlyStable) {
        ArrayList<FabricVersion> filteredVersions = new ArrayList<>(fabricVersions.length);
        for(FabricVersion fabricVersion : fabricVersions) {
            if(!onlyStable || fabricVersion.stable) filteredVersions.add(fabricVersion);
        }
        filteredVersions.trimToSize();
        return new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, filteredVersions);
    }

    private void onException(Future<?> myFuture, Exception e) {
        Tools.runOnUiThread(()->{
            if(myFuture.isCancelled()) return;
            stopLoading();
            if(e != null) Tools.showError(requireContext(), e);
            mRetryView.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onCheckedChanged(@NonNull CompoundButton compoundButton, boolean b) {
        updateGameSpinner();
        updateLoaderSpinner();
    }

    class LoaderVersionSelectedListener implements AdapterView.OnItemSelectedListener {

        @Override
        public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
            mSelectedLoaderVersion = ((FabricVersion) adapterView.getAdapter().getItem(i)).version;
            mStartButton.setEnabled(mSelectedGameVersion != null);
        }

        @Override
        public void onNothingSelected(AdapterView<?> adapterView) {
            mSelectedLoaderVersion = null;
            mStartButton.setEnabled(false);
        }
    }

    class LoadLoaderVersionsTask implements SelfReferencingFuture.FutureInterface {
        @Override
        public void run(Future<?> myFuture) {
            Log.i("LoadLoaderVersions", "Starting...");
            try {
                mLoaderVersionArray = mFabriclikeUtils.downloadLoaderVersions(mSelectedGameVersion);
                if(mLoaderVersionArray != null) onFinished(myFuture);
                else onException(myFuture, null);
            }catch (IOException e) {
                onException(myFuture, e);
            }
        }
        private void onFinished(Future<?> myFuture) {
            Tools.runOnUiThread(()->{
                if(myFuture.isCancelled()) return;
                stopLoading();
                updateLoaderSpinner();
            });
        }
    }

    private void updateLoaderVersions() {
        startLoading();
        mLoaderVersionFuture = new SelfReferencingFuture(new LoadLoaderVersionsTask()).startOnExecutor(CSLauncherApplication.sExecutorService);
    }

    private void updateLoaderSpinner() {
        if(mLoaderVersionArray == null || isDetached()) return;
        mLoaderVersionSpinner.setAdapter(createAdapter(mLoaderVersionArray, mOnlyStableCheckbox.isChecked()));
    }

    class GameVersionSelectedListener implements AdapterView.OnItemSelectedListener {
        @Override
        public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
            mSelectedGameVersion = ((FabricVersion) adapterView.getAdapter().getItem(i)).version;
            cancelFutureChecked(mLoaderVersionFuture);
            updateLoaderVersions();
        }

        @Override
        public void onNothingSelected(AdapterView<?> adapterView) {
            mSelectedGameVersion = null;
            if(mLoaderVersionFuture != null) mLoaderVersionFuture.cancel(true);
            adapterView.setAdapter(null);
        }

    }

    class LoadGameVersionsTask implements SelfReferencingFuture.FutureInterface {
        @Override
        public void run(Future<?> myFuture) {
            try {
                mGameVersionArray = mFabriclikeUtils.downloadGameVersions();
                if(mGameVersionArray != null) onFinished(myFuture);
                else onException(myFuture, null);
            }catch (IOException e) {
                onException(myFuture, e);
            }
        }
        private void onFinished(Future<?> myFuture) {
            Tools.runOnUiThread(()->{
                if(myFuture.isCancelled()) return;
                stopLoading();
                updateGameSpinner();
            });
        }
    }

    private void updateGameVersions() {
        startLoading();
        mGameVersionFuture = new SelfReferencingFuture(new LoadGameVersionsTask()).startOnExecutor(CSLauncherApplication.sExecutorService);
    }

    private void updateGameSpinner() {
        if(mGameVersionArray == null || isDetached()) return;
        mGameVersionSpinner.setAdapter(createAdapter(mGameVersionArray, mOnlyStableCheckbox.isChecked()));
    }

    private ModloaderListenerProxy getListenerProxy() {
        return (ModloaderListenerProxy) ExtraCore.getValue(mExtraTag);
    }
    private void setListenerProxy(ModloaderListenerProxy listenerProxy) {
        ExtraCore.setValue(mExtraTag, listenerProxy);
    }
}
