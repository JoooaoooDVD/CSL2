package com.craftstudio.cslauncher.utils.lifecycle;

import static com.craftstudio.cslauncher.game.GameActivity.INTENT_LAUNCH_CLASSPATH;
import static com.craftstudio.cslauncher.game.GameActivity.INTENT_LAUNCH_VERSION;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.craftstudio.cslauncher.game.GameActivity;
import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.progresskeeper.ProgressKeeper;
import com.craftstudio.cslauncher.tasks.mojson.MoJsonExtras;
import com.craftstudio.cslauncher.utils.NotificationUtils;

import java.io.File;

public class ContextAwareDoneListener implements MoJsonExtras.DoneListener, ContextExecutorTask {
    private final String mErrorString;
    private final String mNormalizedVersionid;
    private File[] classpath;

    public ContextAwareDoneListener(Context baseContext, String versionId) {
        this.mErrorString = baseContext.getString(R.string.mc_download_failed);
        this.mNormalizedVersionid = versionId;
    }

    private Intent createGameStartIntent(Context context) {
        Intent mainIntent = new Intent(context, GameActivity.class);
        mainIntent.putExtra(INTENT_LAUNCH_VERSION, mNormalizedVersionid);
        mainIntent.putExtra(INTENT_LAUNCH_CLASSPATH, classpath);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return mainIntent;
    }

    @Override
    public void onDownloadDone(File[] classpath) {
        this.classpath = classpath;
        ProgressKeeper.waitUntilDone(()->ContextExecutor.execute(this));
    }

    @Override
    public void onDownloadFailed(Throwable throwable) {
        Tools.showErrorRemote(mErrorString, throwable);
    }

    @Override
    public void executeWithActivity(Activity activity) {
        try {
            Intent gameStartIntent = createGameStartIntent(activity);
            activity.startActivity(gameStartIntent);
            activity.finish();
            android.os.Process.killProcess(android.os.Process.myPid()); //You should kill yourself, NOW!
        } catch (Throwable e) {
            Tools.showError(activity.getBaseContext(), e);
        }
    }

    @Override
    public void executeWithApplication(Context context) {
        Intent gameStartIntent = createGameStartIntent(context);
        // PLAY must start the game by itself. Even when no activity is registered at this exact
        // moment (a dialog, a permission prompt, a hand-over between activities) try to start
        // GameActivity directly; it lives in its own process so this is safe. The notification
        // below stays as the fallback for when Android refuses a background start.
        try {
            Intent direct = new Intent(gameStartIntent);
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(direct);
            android.util.Log.i("ContextAwareDoneListener", "Started the game without a foreground activity");
        } catch (Throwable t) {
            android.util.Log.w("ContextAwareDoneListener", "Direct game start failed, falling back to the notification", t);
        }
        // Since the game is a separate process anyway, it does not matter if it gets invoked
        // from somewhere other than the launcher activity.
        // The only problem may arise if the launcher starts doing something when the user starts the notification.
        // So, the notification is automatically removed once there are tasks ongoing in the ProgressKeeper
        NotificationUtils.sendBasicNotification(context,
                R.string.notif_download_finished,
                R.string.notif_download_finished_desc,
                gameStartIntent,
                NotificationUtils.PENDINGINTENT_CODE_GAME_START,
                NotificationUtils.NOTIFICATION_ID_GAME_START
        );
        // You should keep yourself safe, NOW!
        // otherwise android does weird things...
    }
}
