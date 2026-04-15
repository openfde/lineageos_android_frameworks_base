package com.android.server.wm;

import android.app.WindowConfiguration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.view.RemoteAnimationAdapter;
import android.window.ITaskFragmentOrganizer;
import android.window.TaskFragmentCreationParams;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOrganizer;
import android.window.WindowContainerTransaction;
import android.window.TaskFragmentTransaction;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import static android.window.TaskFragmentOperation.OP_TYPE_REPARENT_ACTIVITY_TO_TASK_FRAGMENT;
import static android.window.TaskFragmentOperation.OP_TYPE_START_ACTIVITY_IN_TASK_FRAGMENT;
import static android.window.TaskFragmentOrganizer.KEY_ERROR_CALLBACK_OP_TYPE;
import static android.window.TaskFragmentOrganizer.KEY_ERROR_CALLBACK_TASK_FRAGMENT_INFO;
import static android.window.TaskFragmentOrganizer.KEY_ERROR_CALLBACK_THROWABLE;
import static android.window.TaskFragmentOrganizer.TASK_FRAGMENT_TRANSIT_CLOSE;
import static android.window.TaskFragmentOrganizer.TASK_FRAGMENT_TRANSIT_OPEN;
import static android.window.TaskFragmentTransaction.TYPE_ACTIVITY_REPARENTED_TO_TASK;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_APPEARED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_ERROR;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_INFO_CHANGED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_VANISHED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.window.TaskFragmentCreationParams;
import android.window.WindowContainerTransaction;
import android.os.RemoteException;
import com.android.server.wm.ActivityRecord;
import com.android.server.wm.Task;
import com.android.server.wm.ActivityTaskManagerService;
import android.util.ArrayMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import android.util.Slog;
import android.window.TaskFragmentParentInfo;
import android.content.res.Configuration;

public class SystemTaskFragmentOrganizer extends TaskFragmentOrganizer {

    private static final String TAG = "SystemTaskFragmentOrganizer";
    private final ActivityTaskManagerService mAtmService;

    // 用于记录某个 Task 对应的左、右 TaskFragment 的 Binder Token
    private final Map<Integer, IBinder> mLeftFragments = new HashMap<>();
    private final Map<Integer, IBinder> mRightFragments = new HashMap<>();
    final Map<IBinder, TaskFragmentInfo> mFragmentInfos = new ArrayMap<>();
    final Map<Integer, ActivityRecord> mSplitingActivityRecords = new ArrayMap<>();
    private Configuration mConfiguration = new Configuration();
    private int mDisplayId;
    boolean mIsExpandedMode = false;

    public SystemTaskFragmentOrganizer(ActivityTaskManagerService atmService) {
        // 使用主线程的 Executor 或者 ATM 的 Handler
        super(atmService.mH::post);
        mAtmService = atmService;
    }

    public void register() {
        // 注册到系统的 WindowOrganizerController
        super.registerOrganizer();
    }

    public void createParallelTaskFragments(Task task) {
        if (mLeftFragments.containsKey(task.mTaskId)) {
            return; // 已经创建过了
        }

        WindowContainerTransaction wct = new WindowContainerTransaction();

        Rect taskBounds = task.getBounds();
        int midX = taskBounds.left + taskBounds.width() / 2;

        Rect leftBounds = new Rect(taskBounds.left, taskBounds.top, midX, taskBounds.bottom);
        Rect rightBounds = new Rect(midX, taskBounds.top, taskBounds.right, taskBounds.bottom);

        IBinder leftToken = new Binder();
        TaskFragmentCreationParams leftParams = new TaskFragmentCreationParams.Builder(
                this.getOrganizerToken(), leftToken, task.mRemoteToken.asBinder())
                .setInitialRelativeBounds(leftBounds)
                .setWindowingMode(WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW)
                .build();
        wct.createTaskFragment(leftParams);

        IBinder rightToken = new Binder();
        TaskFragmentCreationParams rightParams = new TaskFragmentCreationParams.Builder(
                this.getOrganizerToken(), rightToken, task.mRemoteToken.asBinder())
                .setInitialRelativeBounds(rightBounds)
                .setWindowingMode(WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW)
                .build();
        wct.createTaskFragment(rightParams);

        mLeftFragments.put(task.mTaskId, leftToken);
        mRightFragments.put(task.mTaskId, rightToken);

        mAtmService.mWindowOrganizerController.applyTransaction(wct);
    }

    void startSplit(Task task, ActivityRecord primary,
                    ActivityRecord secondary, Intent secondaryIntent) {

        if (task == null || primary == null) return;
        if (mSplitingActivityRecords.get(task.mTaskId) == secondary) {
            Slog.w(TAG, "spliting " + secondary);
            return;
        }
        final long origId = Binder.clearCallingIdentity();
        try {
            final int taskId = task.mTaskId;

            final IBinder existingRight = mRightFragments.get(taskId);
            final boolean alreadySplit =
                    existingRight != null && mFragmentInfos.get(existingRight) != null;

            final WindowContainerTransaction wct = new WindowContainerTransaction();

            if (alreadySplit) {
                // ==============================
                // ✅ 已经分屏 → 复用右侧 TF
                // ==============================

                Slog.d(TAG, "startSplit: already split, reuse right TF");

                // 👉 方式1：已有 ActivityRecord
                if (secondary != null) {
                    wct.reparentActivityToTaskFragment(
                            existingRight,
                            secondary.token
                    );
                }
                // 👉 方式2：需要新启动 Activity
                else if (secondaryIntent != null) {
                    wct.startActivityInTaskFragment(
                            existingRight,
                            primary.token,
                            secondaryIntent,
                            null
                    );
                }

                mSplitingActivityRecords.put(taskId, secondary);

            } else {
                // ==============================
                // 🆕 第一次 split
                // ==============================

                Slog.d(TAG, "startSplit: create new split");

                final IBinder primaryTfToken = new Binder();
                final IBinder secondaryTfToken = new Binder();
                final IBinder ownerToken = primary.token;

                final Rect taskBounds = task.getBounds();
//                final int mid = taskBounds.width() / 2;

                final Rect left = new Rect(0, 0, taskBounds.width(), taskBounds.height());
                final Rect right = new Rect(taskBounds.width(), 0, taskBounds.width() * 2, taskBounds.height());

                // 👉 primary TF
                TaskFragmentCreationParams primaryParams =
                        new TaskFragmentCreationParams.Builder(
                                getOrganizerToken(),
                                primaryTfToken,
                                ownerToken)
                                .setInitialRelativeBounds(left)
                                .build();

                wct.createTaskFragment(primaryParams);
                wct.reparentActivityToTaskFragment(primaryTfToken, primary.token);

                // 👉 secondary TF
                TaskFragmentCreationParams secondaryParams =
                        new TaskFragmentCreationParams.Builder(
                                getOrganizerToken(),
                                secondaryTfToken,
                                ownerToken)
                                .setInitialRelativeBounds(right)
                                .setPairedPrimaryFragmentToken(primaryTfToken)
                                .build();

                wct.createTaskFragment(secondaryParams);

                if (secondary != null) {
                    wct.reparentActivityToTaskFragment(
                            secondaryTfToken,
                            secondary.token
                    );
                } else if (secondaryIntent != null) {
                    wct.startActivityInTaskFragment(
                            secondaryTfToken,
                            ownerToken,
                            secondaryIntent,
                            null
                    );
                }

                // 👉 建立分屏关系
                WindowContainerTransaction.TaskFragmentAdjacentParams adjacentParams =
                        new WindowContainerTransaction.TaskFragmentAdjacentParams();

                wct.setAdjacentTaskFragments(primaryTfToken, secondaryTfToken, adjacentParams);
                wct.setCompanionTaskFragment(primaryTfToken, secondaryTfToken);

                // 👉 记录
                mLeftFragments.put(taskId, primaryTfToken);
                mRightFragments.put(taskId, secondaryTfToken);
                mSplitingActivityRecords.put(taskId, secondary);
            }
            mIsExpandedMode = true;
            // 👉 提交事务
            mAtmService.getWindowOrganizerController().applyTransaction(wct);

        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void updateContainersInTask(WindowContainerTransaction wct, int taskId, Rect taskBounds) {
        if(mRightFragments.get(taskId) == null){
            Slog.e(TAG, "only one activity, no need to update");
            return;
        }
        final IBinder primaryTfToken = mLeftFragments.get(taskId);
        final IBinder secondaryTfToken = mRightFragments.get(taskId);
        final int mid = taskBounds.width() / 2;
        final Rect left = new Rect(0, 0, mid, taskBounds.height());
        final Rect right = new Rect(mid, 0, taskBounds.width(), taskBounds.height());
        resizeTaskFragment(wct, primaryTfToken, left);
        resizeTaskFragment(wct, secondaryTfToken, right);
        try {
            mAtmService.getWindowOrganizerController().applyTransaction(wct);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void resizeTaskFragment(@NonNull WindowContainerTransaction wct, @NonNull IBinder fragmentToken,
                            @Nullable Rect relBounds) {
        if (fragmentToken == null || mFragmentInfos.get(fragmentToken) == null) {
            return;
        }
        if (relBounds == null) {
            relBounds = new Rect();
        }
        wct.setRelativeBounds(mFragmentInfos.get(fragmentToken).getToken(), relBounds);
    }

    public IBinder getRightFragmentToken(int taskId) {
        return mRightFragments.get(taskId);
    }

    @Override
    public void onTransactionReady(@NonNull TaskFragmentTransaction transaction) {
        super.onTransactionReady(transaction);
        final List<TaskFragmentTransaction.Change> changes = transaction.getChanges();
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        for (TaskFragmentTransaction.Change change : changes) {
            final int taskId = change.getTaskId();
            final TaskFragmentInfo info = change.getTaskFragmentInfo();
            Slog.d(TAG, "onTransactionReady type: " + change.getType());
            switch (change.getType()) {
                case TYPE_TASK_FRAGMENT_APPEARED: //1
                    updateTaskFragmentInfo(info);
                    onTaskFragmentAppeared(info);
                    break;
                case TYPE_TASK_FRAGMENT_INFO_CHANGED: //2
                    updateTaskFragmentInfo(info);
                    onTaskFragmentInfoChanged(wct, info, taskId);
                    break;
                case TYPE_TASK_FRAGMENT_VANISHED: //3
                    onTaskFragmentVanished(wct,info, taskId);
                    break;
                case TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED: //4
                    onTaskFragmentParentInfoChanged(wct, taskId,
                            change.getTaskFragmentParentInfo());
                    break;
                case TYPE_TASK_FRAGMENT_ERROR: //5
                    updateTaskFragmentInfo(info);
                    break;
                case TYPE_ACTIVITY_REPARENTED_TO_TASK: //6
                    break;
                default:
            }

        }
        try {
            mAtmService.getWindowOrganizerController().applyTransaction(wct);
        } catch (RemoteException e) {
            Slog.e(TAG, e.getMessage());
        }
    }

    void updateTaskFragmentInfo(@NonNull TaskFragmentInfo taskFragmentInfo) {
        if (taskFragmentInfo == null) {
            Slog.d(TAG, "updateTaskFragmentInfo info is null");
            return;
        }
        Slog.d(TAG, "updateTaskFragmentInfo: 更新 TaskFragment 信息，token="
                + taskFragmentInfo.getFragmentToken());
        mFragmentInfos.put(taskFragmentInfo.getFragmentToken(), taskFragmentInfo);
    }

    void removeTaskFragmentInfo(@NonNull TaskFragmentInfo taskFragmentInfo) {
        if (taskFragmentInfo == null) {
            Slog.d(TAG, "removeTaskFragmentInfo info is null");
            return;
        }
        Slog.d(TAG, "removeTaskFragmentInfo: 移除 TaskFragment 信息，token="
                + taskFragmentInfo.getFragmentToken());
        mFragmentInfos.remove(taskFragmentInfo.getFragmentToken());
    }

    //    @Override
    public void onTaskFragmentAppeared(TaskFragmentInfo taskFragmentInfo) {
        Slog.d(TAG, "onTaskFragmentAppeared() called with: taskFragmentInfo = [" + taskFragmentInfo + "]");
    }

    public void onTaskFragmentInfoChanged( WindowContainerTransaction wct, TaskFragmentInfo taskFragmentInfo, int taskId) {
        if (taskFragmentInfo != null && !taskFragmentInfo.hasRunningActivity()) {
            deleteTaskFragment(wct, taskFragmentInfo);
            removeTaskFragmentInfo(taskFragmentInfo);
        }
        Slog.d(TAG, "onTaskFragmentInfoChanged() called with: taskFragmentInfo = [" + taskFragmentInfo + "]");
    }

    void deleteTaskFragment(WindowContainerTransaction wct, TaskFragmentInfo taskFragmentInfo) {
        if(taskFragmentInfo == null || taskFragmentInfo.getFragmentToken() == null
                || mFragmentInfos.get(taskFragmentInfo.getFragmentToken()) == null){
            Slog.w(TAG, "fragments already delete");
            return;
        }
        wct.deleteTaskFragment(taskFragmentInfo.getFragmentToken());
    }

    void deleteTaskFragment(WindowContainerTransaction wct, IBinder token) {
        if(token == null
                || mFragmentInfos.get(token) == null){
            Slog.w(TAG, "fragments already delete");
            return;
        }
        wct.deleteTaskFragment(token);
    }

    //    @Override
    public void onTaskFragmentVanished(WindowContainerTransaction wct,
                                       TaskFragmentInfo taskFragmentInfo, int taskId) {
        Slog.d(TAG, "onTaskFragmentVanished() called with: taskFragmentInfo = [" + taskFragmentInfo + "]");
        if (mRightFragments.get(taskId) != null &&
                mRightFragments.get(taskId) == taskFragmentInfo.getFragmentToken()) {
            expandTaskFragment(wct, mLeftFragments.get(taskId), taskId);
            mSplitingActivityRecords.remove(taskId);
            mRightFragments.remove(taskId);
        } else if (mLeftFragments.get(taskId) != null &&
                mLeftFragments.get(taskId) == taskFragmentInfo.getFragmentToken()) {
//            deleteTaskFragment(wct, mRightFragments.get(taskId));
//            mLeftFragments.remove(taskId);
            TaskFragmentInfo info = mFragmentInfos.get(mRightFragments.get(taskId));
            ActivityRecord secondary = mSplitingActivityRecords.get(taskId);
            if (info != null) {
                List<IBinder> activities = info.getActivities();
                if (activities != null) {
                    for (IBinder token : activities) {
                        wct.finishActivity(token);
                    }
                }
            } else if (secondary != null) {
                wct.finishActivity(secondary.token);
            }
            mRightFragments.remove(taskId);
        }
        removeTaskFragmentInfo(taskFragmentInfo);
    }

    public void onTaskFragmentParentInfoChanged(WindowContainerTransaction wct, int taskId, TaskFragmentParentInfo taskFragmentInfo) {
        Slog.d(TAG, "onTaskFragmentParentInfoChanged() called with: taskFragmentInfo = [" + taskFragmentInfo.getConfiguration() + "]");
        final Rect taskBounds = taskFragmentInfo.getConfiguration().windowConfiguration.getBounds();
        if (shouldUpdateContainer(taskFragmentInfo) && !mIsExpandedMode) {
            updateContainersInTask(wct, taskId, taskBounds);
        }
        mConfiguration = taskFragmentInfo.getConfiguration();
        mDisplayId = taskFragmentInfo.getDisplayId();
        mIsExpandedMode = false;
    }

    boolean shouldUpdateContainer(@NonNull TaskFragmentParentInfo info) {
        final Configuration configuration = info.getConfiguration();
        return info.isVisible()
                && !isInPictureInPicture(configuration)
                && (mConfiguration.diffPublicOnly(configuration) != 0
                || mDisplayId != info.getDisplayId());
    }

    void expandTaskFragment(WindowContainerTransaction wct, @NonNull IBinder fragmentToken,
                            int taskId) {
        if(mFragmentInfos.get(fragmentToken) == null){
            Slog.w(TAG, "expandTaskFragment fragment is removed ");
            return;
        }

        Slog.d(TAG, "expandTaskFragment: 展开 TaskFragment，token=" + fragmentToken);
        IBinder left = mLeftFragments.get(taskId);
        TaskFragmentInfo leftInfo = mFragmentInfos.get(left);
        Rect leftBounds = new Rect(leftInfo.getConfiguration().windowConfiguration.getBounds());
        Task task = mAtmService.mRootWindowContainer.anyTaskForId(taskId);
        wct.setBounds(task.mRemoteToken.toWindowContainerToken(), leftBounds);
        mRightFragments.remove(taskId);
        mSplitingActivityRecords.remove(taskId);
        Slog.d(TAG, "expandTaskFragment: 已完成展开");

    }


    private static boolean isInPictureInPicture(@NonNull Configuration configuration) {
        return configuration.windowConfiguration.getWindowingMode() == WINDOWING_MODE_PINNED;
    }
}