package com.craftstudio.cslauncher.progresskeeper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

public class ProgressKeeper {
    private static final HashMap<String, List<ProgressListener>> sProgressListeners = new HashMap<>();
    private static final HashMap<String, ProgressState> sProgressStates = new HashMap<>();
    private static final List<TaskCountListener> sTaskCountListeners = new ArrayList<>();
    private static final List<GlobalProgressListener> sGlobalListeners = new ArrayList<>();

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
        }else if(shouldCallStarted){
            sProgressStates.put(progressRecord, (progressState = new ProgressState()));
        }
        if(shouldCallEnded || shouldCallStarted) updateTaskCount(sProgressStates.size());
        if(progressState != null) {
            progressState.progress = progress;
            progressState.resid = resid;
            progressState.varArg = va;
        }

        List<ProgressListener> progressListeners = sProgressListeners.get(progressRecord);
        if(progressListeners != null)
            for(ProgressListener listener : progressListeners) {
                    if(shouldCallStarted) listener.onProgressStarted();
                    else if(shouldCallEnded) listener.onProgressEnded();
                    else listener.onProgressUpdated(progress, resid, va);
            }
        // Screen-level observers (home PLAY download state) see every change
        notifyGlobalListenersLocked();
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

    public static boolean hasOngoingTasks() {
        return getTaskCount() > 0;
    }
}
