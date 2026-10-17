package com.craftstudio.cslauncher.progresskeeper;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

public class ProgressKeeper {
    private static final HashMap<String, List<ProgressListener>> sProgressListeners = new HashMap<>();
    private static final HashMap<String, ProgressState> sProgressStates = new HashMap<>();
    private static final List<TaskCountListener> sTaskCountListeners = new ArrayList<>();
    private static final List<GlobalProgressListener> sGlobalListeners = new ArrayList<>();

    /**
     * Downloads report progress ~30 times a second per task. Pushing every tick to the UI
     * (home PLAY button, progress dock, notification) used to flood the main thread and made
     * the launcher stutter while downloading. Plain updates are now coalesced: at most one UI
     * dispatch per record every {@link #UI_INTERVAL_MS}, always carrying the latest values.
     * Start / end events are still delivered immediately.
     */
    private static final long UI_INTERVAL_MS = 100L;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static final HashSet<String> sPendingRecords = new HashSet<>();
    private static boolean sFlushScheduled;
    private static long sLastFlush;
    private static final Runnable sFlush = ProgressKeeper::flushPending;

    /** Set by {@link #cancelAll()}, cleared once every task has ended */
    private static volatile boolean sCancelRequested;
    /**
     * Bumped on every {@link #cancelAll()}. Download loops remember the generation they started
     * in and only abort when it changed, so a cancel can never leak into downloads that begin
     * later. (The plain flag used to stay true until every progress record ended; a single stuck
     * record after a cancel then made every later download - content installs, world zips,
     * CurseForge files - fail instantly with "cancelled".)
     */
    private static final java.util.concurrent.atomic.AtomicLong sCancelGeneration = new java.util.concurrent.atomic.AtomicLong();
    private static final long CANCEL_FLAG_TTL_MS = 5000L;

    /**
     * Launch bookkeeping for the home PLAY button: the game pipeline always runs through the
     * downloader (to verify files), so without this every tap would look like a download.
     * {@link #sLaunching} is set when PLAY starts the pipeline, {@link #sRealDownload} only once
     * an actual file is fetched from the network. Both reset when every task has ended.
     */
    private static volatile boolean sLaunching;
    private static volatile boolean sRealDownload;

    /** PLAY was tapped: the next tasks are part of a game launch */
    public static void markLaunching() {
        sLaunching = true;
        sRealDownload = false;
    }

    /** A downloader is really fetching files (not just verifying them) */
    public static void markRealDownload() {
        sRealDownload = true;
    }

    public static boolean isLaunching() { return sLaunching; }
    public static boolean isRealDownload() { return sRealDownload; }

    /** Asks every running task to stop as soon as it can (checked by the downloaders) */
    public static void cancelAll() {
        if (getTaskCount() == 0) return;
        sCancelRequested = true;
        sCancelGeneration.incrementAndGet();
        // Safety net: the running loops react within milliseconds; never let the flag outlive them
        sHandler.postDelayed(() -> sCancelRequested = false, CANCEL_FLAG_TTL_MS);
    }

    /** @return whether the user asked to cancel the running tasks */
    public static boolean isCancelRequested() {
        return sCancelRequested;
    }

    /** Snapshot to pass to {@link #throwIfCancelledSince}/{@link #isCancelledSince} when a download starts */
    public static long cancelGeneration() {
        return sCancelGeneration.get();
    }

    /** @return true when the user tapped cancel after the given generation was taken */
    public static boolean isCancelledSince(long generation) {
        return sCancelGeneration.get() != generation;
    }

    /** Throws when the user cancelled after the given generation was taken - call inside download loops */
    public static void throwIfCancelledSince(long generation) throws CancelledException {
        if (isCancelledSince(generation)) throw new CancelledException();
    }

    /**
     * Legacy check kept for callers without a generation: only true while a cancel is being
     * processed (cleared when the tasks end or after a few seconds), never sticky.
     */
    public static void throwIfCancelled() throws CancelledException {
        if (sCancelRequested) throw new CancelledException();
    }

    /** Raised by download loops once the user tapped cancel */
    public static class CancelledException extends java.io.IOException {
        public CancelledException() { super("Download cancelled by the user"); }
    }

    /** @return true when this throwable (or one of its causes) is a user cancellation */
    public static boolean isCancellation(Throwable throwable) {
        while (throwable != null) {
            if (throwable instanceof CancelledException || throwable instanceof InterruptedException) return true;
            throwable = throwable.getCause();
        }
        return false;
    }

    /**
     * Notified about every progress change regardless of the record, plus once
     * immediately when registered. Carries the active task count and an aggregate
     * percent (0-100) across all running tasks, or -1 while unknown.
     * Callbacks run on whichever thread submitted the progress - keep them short
     * and hop to the UI thread if the work touches views.
     */
    public interface GlobalProgressListener {
        void onGlobalProgress(int taskCount, int aggregatePercent);
    }

    public static synchronized void addGlobalListener(GlobalProgressListener listener) {
        if (sGlobalListeners.contains(listener)) return;
        sGlobalListeners.add(listener);
        listener.onGlobalProgress(sProgressStates.size(), aggregateProgressLocked());
    }

    public static synchronized void removeGlobalListener(GlobalProgressListener listener) {
        sGlobalListeners.remove(listener);
    }

    /** Average percent across the active tasks, or -1 when nothing measurable is running */
    public static synchronized int getAggregateProgress() {
        return aggregateProgressLocked();
    }

    private static int aggregateProgressLocked() {
        if (sProgressStates.isEmpty()) return -1;
        int sum = 0;
        int n = 0;
        for (ProgressState state : sProgressStates.values()) {
            if (state.progress >= 0) {
                sum += state.progress;
                n++;
            }
        }
        return n == 0 ? -1 : Math.round(sum / (float) n);
    }

    private static void notifyGlobalListenersLocked() {
        if (sGlobalListeners.isEmpty()) return;
        int count = sProgressStates.size();
        int percent = aggregateProgressLocked();
        for (GlobalProgressListener listener : new ArrayList<>(sGlobalListeners)) {
            listener.onGlobalProgress(count, percent);
        }
    }

    public static synchronized void submitProgress(String progressRecord, int progress, int resid, Object... va) {
        ProgressState progressState = sProgressStates.get(progressRecord);
        boolean shouldCallStarted = progressState == null;
        boolean shouldCallEnded = resid == -1 && progress == -1;
        if(shouldCallEnded) {
            shouldCallStarted = false;
            sProgressStates.remove(progressRecord);
            sPendingRecords.remove(progressRecord);
        }else if(shouldCallStarted){
            sProgressStates.put(progressRecord, (progressState = new ProgressState()));
        }
        if(shouldCallEnded || shouldCallStarted) {
            if (sProgressStates.isEmpty()) {
                sCancelRequested = false;
                sLaunching = false;
                sRealDownload = false;
            }
            updateTaskCount(sProgressStates.size());
        }
        if(progressState != null) {
            progressState.progress = progress;
            progressState.resid = resid;
            progressState.varArg = va;
        }

        if (shouldCallStarted || shouldCallEnded) {
            // Lifecycle events go out right away
            List<ProgressListener> progressListeners = sProgressListeners.get(progressRecord);
            if(progressListeners != null)
                for(ProgressListener listener : new ArrayList<>(progressListeners)) {
                    if(shouldCallStarted) listener.onProgressStarted();
                    else listener.onProgressEnded();
                }
            notifyGlobalListenersLocked();
            return;
        }
        // Plain updates are coalesced (see UI_INTERVAL_MS)
        sPendingRecords.add(progressRecord);
        scheduleFlushLocked();
    }

    private static void scheduleFlushLocked() {
        if (sFlushScheduled) return;
        sFlushScheduled = true;
        long wait = Math.max(0L, UI_INTERVAL_MS - (android.os.SystemClock.uptimeMillis() - sLastFlush));
        sHandler.postDelayed(sFlush, wait);
    }

    /** Main thread: delivers the latest state of every record that changed since the last flush */
    private static void flushPending() {
        ArrayList<Runnable> calls = new ArrayList<>();
        boolean notifyGlobal;
        int count;
        int percent;
        ArrayList<GlobalProgressListener> globals;
        synchronized (ProgressKeeper.class) {
            globals = new ArrayList<>(sGlobalListeners);
            sFlushScheduled = false;
            sLastFlush = android.os.SystemClock.uptimeMillis();
            notifyGlobal = !sPendingRecords.isEmpty();
            for (String record : sPendingRecords) {
                final ProgressState state = sProgressStates.get(record);
                final List<ProgressListener> listeners = sProgressListeners.get(record);
                if (state == null || listeners == null || listeners.isEmpty()) continue;
                final int progress = state.progress;
                final int resid = state.resid;
                final Object[] args = state.varArg;
                for (ProgressListener listener : new ArrayList<>(listeners)) {
                    calls.add(() -> listener.onProgressUpdated(progress, resid, args));
                }
            }
            sPendingRecords.clear();
            count = sProgressStates.size();
            percent = aggregateProgressLocked();
        }
        for (Runnable call : calls) call.run();
        if (notifyGlobal) {
            for (GlobalProgressListener listener : globals) {
                listener.onGlobalProgress(count, percent);
            }
        }
    }

    private static void updateTaskCount(int count) {
        synchronized (sTaskCountListeners) {
            Iterator<TaskCountListener> iterator = sTaskCountListeners.iterator();
            while(iterator.hasNext()) {
                if(iterator.next().onUpdateTaskCount(count)) iterator.remove();
            }
        }
    }

    public static synchronized boolean hasProgressKey(String key) {
        return sProgressStates.get(key) != null;
    }

    public static synchronized void addListener(String progressRecord, ProgressListener listener) {
        ProgressState state = sProgressStates.get(progressRecord);
        if(state != null && (state.resid != -1 || state.progress != -1)) {
            listener.onProgressStarted();
            listener.onProgressUpdated(state.progress, state.resid, state.varArg);
        }else{
            listener.onProgressEnded();
        }
        List<ProgressListener> listenerWeakReferenceList = sProgressListeners.get(progressRecord);
        if(listenerWeakReferenceList == null) sProgressListeners.put(progressRecord, (listenerWeakReferenceList = new ArrayList<>()));
        listenerWeakReferenceList.add(listener);
    }

    public static synchronized void removeListener(String progressRecord, ProgressListener listener) {
        List<ProgressListener> listenerWeakReferenceList = sProgressListeners.get(progressRecord);
        if(listenerWeakReferenceList != null) listenerWeakReferenceList.remove(listener);
    }

    public static void addTaskCountListener(TaskCountListener listener) {
        addTaskCountListener(listener, true);
    }
    public static void addTaskCountListener(TaskCountListener listener, boolean runUpdate) {
        if(runUpdate) synchronized (ProgressKeeper.class) {
            listener.onUpdateTaskCount(sProgressStates.size());
        }
        synchronized (sTaskCountListeners) {
            if(!sTaskCountListeners.contains(listener)) sTaskCountListeners.add(listener);
        }
    }
    public static void removeTaskCountListener(TaskCountListener listener) {
        synchronized (sTaskCountListeners) {
            sTaskCountListeners.remove(listener);
        }
    }

    /**
     * Waits until all tasks are done and runs the runnable, or if there were no pending process remaining
     * The runnable runs from the thread that updated the task count last, and it might be the UI thread,
     * so don't put long running processes in it
     * @param runnable the runnable to run when no tasks are remaining
     */
    public static void waitUntilDone(final Runnable runnable) {
        // If we do it the other way the listener would be removed before it was added, which will cause a listener object leak
        if(getTaskCount() == 0) {
            runnable.run();
            return;
        }
        TaskCountListener listener = taskCount -> {
            if(taskCount == 0) {
                runnable.run();
                return true;
            }
            return false;
        };
        addTaskCountListener(listener);
    }

    public static synchronized int getTaskCount() {
        return sProgressStates.size();
    }

    /**
     * A detached snapshot of every running task, safe to read from any thread.
     * Used by the foreground service to build its notification lines.
     */
    public static synchronized java.util.Map<String, ProgressState> snapshotStates() {
        java.util.HashMap<String, ProgressState> copy = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, ProgressState> entry : sProgressStates.entrySet()) {
            ProgressState source = entry.getValue();
            ProgressState clone = new ProgressState();
            clone.progress = source.progress;
            clone.resid = source.resid;
            clone.varArg = source.varArg;
            copy.put(entry.getKey(), clone);
        }
        return copy;
    }

    public static boolean hasOngoingTasks() {
        return getTaskCount() > 0;
    }
}
