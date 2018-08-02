/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.work.impl;

import static androidx.work.State.CANCELLED;
import static androidx.work.State.ENQUEUED;
import static androidx.work.State.FAILED;
import static androidx.work.State.RUNNING;
import static androidx.work.State.SUCCEEDED;
import static androidx.work.impl.model.WorkSpec.SCHEDULE_NOT_REQUESTED_YET;

import android.content.Context;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.support.annotation.VisibleForTesting;
import android.support.annotation.WorkerThread;

import androidx.work.Configuration;
import androidx.work.Data;
import androidx.work.InputMerger;
import androidx.work.Logger;
import androidx.work.NonBlockingWorker;
import androidx.work.State;
import androidx.work.Worker;
import androidx.work.Worker.Result;
import androidx.work.impl.background.systemalarm.RescheduleReceiver;
import androidx.work.impl.model.DependencyDao;
import androidx.work.impl.model.WorkSpec;
import androidx.work.impl.model.WorkSpecDao;
import androidx.work.impl.model.WorkTagDao;
import androidx.work.impl.utils.PackageManagerHelper;
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A runnable that looks up the {@link WorkSpec} from the database for a given id, instantiates
 * its Worker, and then calls it.
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class WorkerWrapper implements Runnable {

    private static final String TAG = "WorkerWrapper";
    private Context mAppContext;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    String mWorkSpecId;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    ExecutionListener mListener;
    NonBlockingListener mWorkerListener;
    private List<Scheduler> mSchedulers;
    private Extras.RuntimeExtras mRuntimeExtras;
    private WorkSpec mWorkSpec;
    NonBlockingWorker mWorker;

    private Configuration mConfiguration;
    private WorkDatabase mWorkDatabase;
    private WorkSpecDao mWorkSpecDao;
    private DependencyDao mDependencyDao;
    private WorkTagDao mWorkTagDao;

    private List<String> mTags;
    private String mWorkDescription;

    private volatile boolean mInterrupted;

    WorkerWrapper(Builder builder) {
        mAppContext = builder.mAppContext;
        mWorkSpecId = builder.mWorkSpecId;
        mListener = builder.mListener;
        mSchedulers = builder.mSchedulers;
        mRuntimeExtras = builder.mRuntimeExtras;
        mWorkerListener = new NonBlockingListener(this);
        if (mRuntimeExtras == null) {
            // Create an instance of RuntimeExtras so we can make the Worker aware of its
            // ExecutionListener.
            mRuntimeExtras = new Extras.RuntimeExtras();
        }
        mRuntimeExtras.mExecutionListener = mWorkerListener;
        mWorker = builder.mWorker;

        mConfiguration = builder.mConfiguration;
        mWorkDatabase = builder.mWorkDatabase;
        mWorkSpecDao = mWorkDatabase.workSpecDao();
        mDependencyDao = mWorkDatabase.dependencyDao();
        mWorkTagDao = mWorkDatabase.workTagDao();
    }

    @WorkerThread
    @Override
    public void run() {
        mTags = mWorkTagDao.getTagsForWorkSpecId(mWorkSpecId);
        mWorkDescription = createWorkDescription(mTags);

        runWorker();
    }

    private void runWorker() {
        if (tryCheckForInterruptionAndNotify()) {
            return;
        }

        mWorkDatabase.beginTransaction();
        try {
            mWorkSpec = mWorkSpecDao.getWorkSpec(mWorkSpecId);
            if (mWorkSpec == null) {
                Logger.error(TAG, String.format("Didn't find WorkSpec for id %s", mWorkSpecId));
                notifyListener(false, false);
                return;
            }

            // Do a quick check to make sure we don't need to bail out in case this work is already
            // running, finished, or is blocked.
            if (mWorkSpec.state != ENQUEUED) {
                notifyIncorrectStatus();
                mWorkDatabase.setTransactionSuccessful();
                return;
            }

            // Needed for nested transactions, such as when we're in a dependent work request when
            // using a SynchronousExecutor.
            mWorkDatabase.setTransactionSuccessful();
        } finally {
            mWorkDatabase.endTransaction();
        }

        // Merge inputs.  This can be potentially expensive code, so this should not be done inside
        // a database transaction.
        Data input;
        if (mWorkSpec.isPeriodic()) {
            input = mWorkSpec.input;
        } else {
            InputMerger inputMerger = InputMerger.fromClassName(mWorkSpec.inputMergerClassName);
            if (inputMerger == null) {
                Logger.error(TAG, String.format("Could not create Input Merger %s",
                        mWorkSpec.inputMergerClassName));
                setFailedAndNotify();
                return;
            }
            List<Data> inputs = new ArrayList<>();
            inputs.add(mWorkSpec.input);
            inputs.addAll(mWorkSpecDao.getInputsFromPrerequisites(mWorkSpecId));
            input = inputMerger.merge(inputs);
        }

        Extras extras = new Extras(
                input,
                mTags,
                mRuntimeExtras,
                mWorkSpec.runAttemptCount);

        // Not always creating a worker here, as the WorkerWrapper.Builder can set a worker override
        // in test mode.
        if (mWorker == null) {
            mWorker = workerFromWorkSpec(mAppContext, mWorkSpec, extras);
        }

        if (mWorker == null) {
            Logger.error(TAG,
                    String.format("Could for create Worker %s", mWorkSpec.workerClassName));
            setFailedAndNotify();
            return;
        }

        // Try to set the work to the running state.  Note that this may fail because another thread
        // may have modified the DB since we checked last at the top of this function.
        if (trySetRunning()) {
            if (tryCheckForInterruptionAndNotify()) {
                return;
            }

            try {
                mWorker.onStartWork(mWorker);
            } catch (Exception | Error e) {
                Logger.error(TAG,
                        String.format("%s failed because it threw an exception/error",
                                mWorkDescription),
                        e);

                // Mark it as a failure
                onWorkFinished(Result.FAILURE);
            }
        } else {
            notifyIncorrectStatus();
        }
    }

    /**
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void onWorkFinished(@NonNull Result result) {
        try {
            mWorkDatabase.beginTransaction();
            if (!tryCheckForInterruptionAndNotify()) {
                State state = mWorkSpecDao.getState(mWorkSpecId);
                if (state == null) {
                    // state can be null here with a REPLACE on beginUniqueWork().
                    // Treat it as a failure, and rescheduleAndNotify() will
                    // turn into a no-op. We still need to notify potential observers
                    // holding on to wake locks on our behalf.
                    notifyListener(false, false);
                } else if (state == RUNNING) {
                    handleResult(result);
                } else if (!state.isFinished()) {
                    rescheduleAndNotify();
                }
                mWorkDatabase.setTransactionSuccessful();
            }
        } finally {
            mWorkDatabase.endTransaction();
        }

        // Try to schedule any newly-unblocked workers, and workers requiring rescheduling (such as
        // periodic work using AlarmManager).  This code runs after runWorker() because it should
        // happen in its own transaction.
        //
        // Further investigation: This could also happen as part of the Processor's
        // ExecutionListener callback.  Does that make more sense?
        Schedulers.schedule(mConfiguration, mWorkDatabase, mSchedulers);
    }

    /**
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void interrupt(boolean cancelled) {
        mInterrupted = true;
        // Worker can be null if run() hasn't been called yet.
        if (mWorker != null) {
            mWorker.stop(cancelled);
        }
    }

    private void notifyIncorrectStatus() {
        State status = mWorkSpecDao.getState(mWorkSpecId);
        if (status == RUNNING) {
            Logger.debug(TAG, String.format("Status for %s is RUNNING;"
                    + "not doing any work and rescheduling for later execution", mWorkSpecId));
            notifyListener(false, true);
        } else {
            Logger.debug(TAG,
                    String.format("Status for %s is %s; not doing any work", mWorkSpecId, status));
            notifyListener(false, false);
        }
    }

    private boolean tryCheckForInterruptionAndNotify() {
        if (mInterrupted) {
            Logger.info(TAG, String.format("Work interrupted for %s", mWorkDescription));
            State currentState = mWorkSpecDao.getState(mWorkSpecId);
            if (currentState == null) {
                // This can happen because of a beginUniqueWork(..., REPLACE, ...).  Notify the
                // listeners so we can clean up any wake locks, etc.
                notifyListener(false, false);
            } else {
                notifyListener(currentState == SUCCEEDED, !currentState.isFinished());
            }
            return true;
        }
        return false;
    }

    private void notifyListener(final boolean isSuccessful, final boolean needsReschedule) {
        if (mListener == null) {
            return;
        }

        try {
            // IMPORTANT: We are using a transaction here as to ensure that we have some guarantees
            // about the state of the world before we disable RescheduleReceiver.

            // Check to see if there is more work to be done. If there is no more work, then
            // disable RescheduleReceiver. Using a transaction here, as there could be more than
            // one thread looking at the list of eligible WorkSpecs.
            mWorkDatabase.beginTransaction();
            List<String> unfinishedWork = mWorkDatabase.workSpecDao().getAllUnfinishedWork();
            boolean noMoreWork = unfinishedWork == null || unfinishedWork.isEmpty();
            if (noMoreWork) {
                PackageManagerHelper.setComponentEnabled(
                        mAppContext, RescheduleReceiver.class, false);
            }
            WorkManagerTaskExecutor.getInstance().postToMainThread(new Runnable() {
                @Override
                public void run() {
                    mListener.onExecuted(mWorkSpecId, isSuccessful, needsReschedule);
                }
            });
            mWorkDatabase.setTransactionSuccessful();
        } finally {
            mWorkDatabase.endTransaction();
        }
    }

    private void handleResult(Worker.Result result) {
        switch (result) {
            case SUCCESS: {
                Logger.info(TAG, String.format("Worker result SUCCESS for %s", mWorkDescription));
                if (mWorkSpec.isPeriodic()) {
                    resetPeriodicAndNotify(true);
                } else {
                    setSucceededAndNotify();
                }
                break;
            }

            case RETRY: {
                Logger.info(TAG, String.format("Worker result RETRY for %s", mWorkDescription));
                rescheduleAndNotify();
                break;
            }

            case FAILURE:
            default: {
                Logger.info(TAG, String.format("Worker result FAILURE for %s", mWorkDescription));
                if (mWorkSpec.isPeriodic()) {
                    resetPeriodicAndNotify(false);
                } else {
                    setFailedAndNotify();
                }
            }
        }
    }

    private boolean trySetRunning() {
        boolean setToRunning = false;
        mWorkDatabase.beginTransaction();
        try {
            State currentState = mWorkSpecDao.getState(mWorkSpecId);
            if (currentState == ENQUEUED) {
                mWorkSpecDao.setState(RUNNING, mWorkSpecId);
                mWorkSpecDao.incrementWorkSpecRunAttemptCount(mWorkSpecId);
                setToRunning = true;
            }
            mWorkDatabase.setTransactionSuccessful();
        } finally {
            mWorkDatabase.endTransaction();
        }
        return setToRunning;
    }

    private void setFailedAndNotify() {
        mWorkDatabase.beginTransaction();
        try {
            recursivelyFailWorkAndDependents(mWorkSpecId);

            // Try to set the output for the failed work but check if the worker exists; this could
            // be a permanent error where we couldn't find or create the worker class.
            if (mWorker != null) {
                // Update Data as necessary.
                Data output = mWorker.getOutputData();
                mWorkSpecDao.setOutput(mWorkSpecId, output);
            }

            mWorkDatabase.setTransactionSuccessful();
        } finally {
            mWorkDatabase.endTransaction();
            notifyListener(false, false);
        }
    }

    private void recursivelyFailWorkAndDependents(String workSpecId) {
        List<String> dependentIds = mDependencyDao.getDependentWorkIds(workSpecId);
        for (String id : dependentIds) {
            recursivelyFailWorkAndDependents(id);
        }

        // Don't fail already cancelled work.
        if (mWorkSpecDao.getState(workSpecId) != CANCELLED) {
            mWorkSpecDao.setState(FAILED, workSpecId);
        }
    }

    private void rescheduleAndNotify() {
        mWorkDatabase.beginTransaction();
        try {
            mWorkSpecDao.setState(ENQUEUED, mWorkSpecId);
            // TODO(xbhatnag): Period Start Time is confusing for non-periodic work. Rename.
            mWorkSpecDao.setPeriodStartTime(mWorkSpecId, System.currentTimeMillis());
            mWorkDatabase.setTransactionSuccessful();
        } finally {
            mWorkDatabase.endTransaction();
            notifyListener(false, true);
        }
    }

    private void resetPeriodicAndNotify(boolean isSuccessful) {
        mWorkDatabase.beginTransaction();
        try {
            long currentPeriodStartTime = mWorkSpec.periodStartTime;
            long nextPeriodStartTime = currentPeriodStartTime + mWorkSpec.intervalDuration;
            mWorkSpecDao.setPeriodStartTime(mWorkSpecId, nextPeriodStartTime);
            mWorkSpecDao.setState(ENQUEUED, mWorkSpecId);
            mWorkSpecDao.resetWorkSpecRunAttemptCount(mWorkSpecId);
            if (Build.VERSION.SDK_INT < WorkManagerImpl.MIN_JOB_SCHEDULER_API_LEVEL) {
                // We only need to reset the schedule_requested_at bit for the AlarmManager
                // implementation because AlarmManager does not know about periodic WorkRequests.
                // Otherwise we end up double scheduling the Worker with an identical jobId, and
                // JobScheduler treats it as the first schedule for a PeriodicWorker. With the
                // AlarmManager implementation, this is not an problem as AlarmManager only cares
                // about the actual alarm itself.

                // We need to tell the schedulers that this WorkSpec is no longer occupying a slot.
                mWorkSpecDao.markWorkSpecScheduled(mWorkSpecId, SCHEDULE_NOT_REQUESTED_YET);
            }
            mWorkDatabase.setTransactionSuccessful();
        } finally {
            mWorkDatabase.endTransaction();
            notifyListener(isSuccessful, false);
        }
    }

    private void setSucceededAndNotify() {
        mWorkDatabase.beginTransaction();
        try {
            mWorkSpecDao.setState(SUCCEEDED, mWorkSpecId);

            // Update Data as necessary.
            Data output = mWorker.getOutputData();
            mWorkSpecDao.setOutput(mWorkSpecId, output);

            // Unblock Dependencies and set Period Start Time
            long currentTimeMillis = System.currentTimeMillis();
            List<String> dependentWorkIds = mDependencyDao.getDependentWorkIds(mWorkSpecId);
            for (String dependentWorkId : dependentWorkIds) {
                if (mDependencyDao.hasCompletedAllPrerequisites(dependentWorkId)) {
                    Logger.info(TAG,
                            String.format("Setting status to enqueued for %s", dependentWorkId));
                    mWorkSpecDao.setState(ENQUEUED, dependentWorkId);
                    mWorkSpecDao.setPeriodStartTime(dependentWorkId, currentTimeMillis);
                }
            }

            mWorkDatabase.setTransactionSuccessful();
        } finally {
            mWorkDatabase.endTransaction();
            notifyListener(true, false);
        }
    }

    private String createWorkDescription(List<String> tags) {
        StringBuilder sb = new StringBuilder("Work [ id=")
                .append(mWorkSpecId)
                .append(", tags={ ");

        boolean first = true;
        for (String tag : tags) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append(tag);
        }
        sb.append(" } ]");

        return sb.toString();
    }

    static Worker workerFromWorkSpec(@NonNull Context context,
            @NonNull WorkSpec workSpec,
            @NonNull Extras extras) {
        String workerClassName = workSpec.workerClassName;
        UUID workSpecId = UUID.fromString(workSpec.id);
        return workerFromClassName(
                context,
                workerClassName,
                workSpecId,
                extras);
    }

    /**
     * Creates a {@link Worker} reflectively & initializes the worker.
     *
     * @param context         The application {@link Context}
     * @param workerClassName The fully qualified class name for the {@link Worker}
     * @param workSpecId      The {@link WorkSpec} identifier
     * @param extras          The {@link Extras} for the worker
     * @return The instance of {@link Worker}
     *
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    @SuppressWarnings("ClassNewInstance")
    public static Worker workerFromClassName(
            @NonNull Context context,
            @NonNull String workerClassName,
            @NonNull UUID workSpecId,
            @NonNull Extras extras) {
        Context appContext = context.getApplicationContext();
        try {
            Class<?> clazz = Class.forName(workerClassName);
            Worker worker = (Worker) clazz.newInstance();
            Method internalInitMethod = NonBlockingWorker.class.getDeclaredMethod(
                    "internalInit",
                    Context.class,
                    UUID.class,
                    Extras.class);
            internalInitMethod.setAccessible(true);
            internalInitMethod.invoke(
                    worker,
                    appContext,
                    workSpecId,
                    extras);
            return worker;
        } catch (Exception e) {
            Logger.error(TAG, "Trouble instantiating " + workerClassName, e);
        }
        return null;
    }

    /**
     * Builder class for {@link WorkerWrapper}
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static class Builder {
        Context mAppContext;
        @Nullable
        NonBlockingWorker mWorker;
        Configuration mConfiguration;
        WorkDatabase mWorkDatabase;
        String mWorkSpecId;
        ExecutionListener mListener;
        List<Scheduler> mSchedulers;
        Extras.RuntimeExtras mRuntimeExtras;

        public Builder(@NonNull Context context,
                @NonNull Configuration configuration,
                @NonNull WorkDatabase database,
                @NonNull String workSpecId) {
            mAppContext = context.getApplicationContext();
            mConfiguration = configuration;
            mWorkDatabase = database;
            mWorkSpecId = workSpecId;
        }

        /**
         * @param listener The {@link ExecutionListener} which gets notified on completion of the
         *                 {@link Worker} with the given {@code workSpecId}.
         * @return The instance of {@link Builder} for chaining.
         */
        public Builder withListener(ExecutionListener listener) {
            mListener = listener;
            return this;
        }

        /**
         * @param schedulers The list of {@link Scheduler}s used for scheduling {@link Worker}s.
         * @return The instance of {@link Builder} for chaining.
         */
        public Builder withSchedulers(List<Scheduler> schedulers) {
            mSchedulers = schedulers;
            return this;
        }

        /**
         * @param runtimeExtras The {@link Extras.RuntimeExtras} for the {@link Worker}.
         * @return The instance of {@link Builder} for chaining.
         */
        public Builder withRuntimeExtras(Extras.RuntimeExtras runtimeExtras) {
            mRuntimeExtras = runtimeExtras;
            return this;
        }

        /**
         * @param worker The instance of {@link NonBlockingWorker} to be executed by
         * {@link WorkerWrapper}. Useful in the context of testing.
         * @return The instance of {@link Builder} for chaining.
         */
        @VisibleForTesting
        public Builder withWorker(NonBlockingWorker worker) {
            mWorker = worker;
            return this;
        }

        /**
         * @return The instance of {@link WorkerWrapper}.
         */
        public WorkerWrapper build() {
            return new WorkerWrapper(this);
        }
    }

    /**
     * An {@link ExecutionListener} that keeps track of {@link androidx.work.NonBlockingWorker}s
     * execution.
     */
    public static class NonBlockingListener implements ExecutionListener {

        private @NonNull WorkerWrapper mWorkerWrapper;

        public NonBlockingListener(@NonNull WorkerWrapper workerWrapper) {
            mWorkerWrapper = workerWrapper;
        }

        @Override
        public void onExecuted(@NonNull String workSpecId,
                boolean isSuccessful,
                boolean needsReschedule) {

            Result result = mWorkerWrapper.mWorker.getResult();
            mWorkerWrapper.onWorkFinished(result);
        }
    }
}
