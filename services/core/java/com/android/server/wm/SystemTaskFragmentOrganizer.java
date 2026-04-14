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

/**
 * 精简版系统平行视界控制器
 */
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


    public SystemTaskFragmentOrganizer(ActivityTaskManagerService atmService) {
        // 使用主线程的 Executor 或者 ATM 的 Handler
        super(atmService.mH::post);
        mAtmService = atmService;
    }

    /**
     * 初始化并向系统注册自己
     */
    public void register() {
        // 注册到系统的 WindowOrganizerController
        super.registerOrganizer();
    }

    /**
     * 为指定的 Task 创建平行视界的左右两个 TaskFragment
     */
    public void createParallelTaskFragments(Task task) {
        if (mLeftFragments.containsKey(task.mTaskId)) {
            return; // 已经创建过了
        }

        WindowContainerTransaction wct = new WindowContainerTransaction();

        // 1. 计算左右 Bounds (以 50:50 为例，实际 PC 模式下需动态获取 task.getBounds())
        Rect taskBounds = task.getBounds();
        int midX = taskBounds.left + taskBounds.width() / 2;

        Rect leftBounds = new Rect(taskBounds.left, taskBounds.top, midX, taskBounds.bottom);
        Rect rightBounds = new Rect(midX, taskBounds.top, taskBounds.right, taskBounds.bottom);

        // 2. 创建左侧 Fragment
        IBinder leftToken = new Binder();
        TaskFragmentCreationParams leftParams = new TaskFragmentCreationParams.Builder(
                this.getOrganizerToken(), leftToken, task.mRemoteToken.asBinder())
                .setInitialRelativeBounds(leftBounds)
                .setWindowingMode(WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW)
                .build();
        wct.createTaskFragment(leftParams);

        // 3. 创建右侧 Fragment
        IBinder rightToken = new Binder();
        TaskFragmentCreationParams rightParams = new TaskFragmentCreationParams.Builder(
                this.getOrganizerToken(), rightToken, task.mRemoteToken.asBinder())
                .setInitialRelativeBounds(rightBounds)
                .setWindowingMode(WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW)
                .build();
        wct.createTaskFragment(rightParams);

        // 记录 Token
        mLeftFragments.put(task.mTaskId, leftToken);
        mRightFragments.put(task.mTaskId, rightToken);

        // 4. 提交事务给系统
//        try {
        mAtmService.mWindowOrganizerController.applyTransaction(wct);
//        } catch (RemoteException e) {
//            throw e.rethrowFromSystemServer();
//        }
    }

    void startSplit(Task task, ActivityRecord primary, ActivityRecord secondary, Intent secondaryIntent) {
        if (mSplitingActivityRecords.get(task.mTaskId) == secondary) {
            Slog.w(TAG, "spliting " + secondary);
            return;
        }
        final long origId = Binder.clearCallingIdentity();
        mSplitingActivityRecords.put(task.mTaskId, secondary);
        try {
            if (task == null || primary == null) return;
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            final IBinder primaryTfToken = new Binder();
            final IBinder secondaryTfToken = new Binder();
            final IBinder ownerToken = primary.token;
            final Rect taskBounds = task.getBounds();
            final int mid = taskBounds.width() / 2;
            final Rect left = new Rect(0, 0, mid, taskBounds.height());
            final Rect right = new Rect(mid, 0, taskBounds.width(), taskBounds.height());
            TaskFragmentCreationParams primaryParams =
                    new TaskFragmentCreationParams.Builder(
                            getOrganizerToken(),
                            primaryTfToken,
                            ownerToken)
                            .setInitialRelativeBounds(left)
                            .build();
            wct.createTaskFragment(primaryParams);
            wct.reparentActivityToTaskFragment(
                    primaryTfToken,
                    primary.token
            );

            TaskFragmentCreationParams secondaryParams =
                    new TaskFragmentCreationParams.Builder(
                            getOrganizerToken(),
                            secondaryTfToken,
                            ownerToken)
                            .setInitialRelativeBounds(right)
                            .setPairedPrimaryFragmentToken(primaryTfToken)
                            .build();

            wct.createTaskFragment(secondaryParams);
//            wct.startActivityInTaskFragment(secondaryTfToken, ownerToken, secondaryIntent, null );
            wct.reparentActivityToTaskFragment(
                    secondaryTfToken,
                    secondary.token
            );
            WindowContainerTransaction.TaskFragmentAdjacentParams adjacentParams
                    = new WindowContainerTransaction.TaskFragmentAdjacentParams();
//            adjacentParams.setShouldDelayPrimaryLastActivityRemoval(true);
            wct.setAdjacentTaskFragments(primaryTfToken, secondaryTfToken,
                    adjacentParams);
            wct.setCompanionTaskFragment(primaryTfToken, secondaryTfToken);
            mLeftFragments.put(task.mTaskId, primaryTfToken);
            mRightFragments.put(task.mTaskId, secondaryTfToken);
            try {
                mAtmService.getWindowOrganizerController().applyTransaction(wct);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void updateContainersInTask(WindowContainerTransaction wct, int taskId, Rect taskBounds) {
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

    /**
     * 获取指定 Task 的右侧容器 Token
     */
    public IBinder getRightFragmentToken(int taskId) {
        return mRightFragments.get(taskId);
    }

    @Override
    public void onTransactionReady(@NonNull TaskFragmentTransaction transaction) {
        super.onTransactionReady(transaction);
//            final TransactionRecord transactionRecord = mTransactionManager.startNewTransaction(
//                    transaction.getTransactionToken());
//            final WindowContainerTransaction wct = transactionRecord.getTransaction();
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
                    removeTaskFragmentInfo(info);
                    break;
                case TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED: //4
                    onTaskFragmentParentInfoChanged(wct, taskId,
                            change.getTaskFragmentParentInfo());
//                        onTaskFragmentVanished(info);
                    break;
                case TYPE_TASK_FRAGMENT_ERROR: //5
//                        final Bundle errorBundle = change.getErrorBundle();
//                        final IBinder errorToken = change.getErrorCallbackToken();
//                        final TaskFragmentInfo errorTaskFragmentInfo = errorBundle.getParcelable(
//                                KEY_ERROR_CALLBACK_TASK_FRAGMENT_INFO, TaskFragmentInfo.class);
//                        final int opType = errorBundle.getInt(KEY_ERROR_CALLBACK_OP_TYPE);
//                        final Throwable exception = errorBundle.getSerializable(
//                                KEY_ERROR_CALLBACK_THROWABLE, Throwable.class);
//                        if (errorTaskFragmentInfo != null) {
//                            mPresenter.updateTaskFragmentInfo(errorTaskFragmentInfo);
//                        }
//                        onTaskFragmentError(wct, errorToken, errorTaskFragmentInfo, opType,
//                                exception);
                    updateTaskFragmentInfo(info);
                    break;
                case TYPE_ACTIVITY_REPARENTED_TO_TASK: //6
//                        onActivityReparentedToTask(
//                                wct,
//                                taskId,
//                                change.getActivityIntent(),
//                                change.getActivityToken());
                    break;
                default:
//                        throw new IllegalArgumentException(
//                                "Unknown TaskFragmentEvent=" + change.getType());
            }

        }
        try {
            mAtmService.getWindowOrganizerController().applyTransaction(wct);
        } catch (RemoteException e) {
            Slog.e(TAG, e.getMessage());
        }

        // Notify the server, and the server should apply and merge the
        // WindowContainerTransaction to the active sync to finish the TaskFragmentTransaction.
//            transactionRecord.apply(false /* shouldApplyIndependently */);
//            updateCallbackIfNecessary();
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

    // --- 实现 TaskFragmentOrganizer 的抽象回调 ---

    //    @Override
    public void onTaskFragmentAppeared(TaskFragmentInfo taskFragmentInfo) {
        Slog.d(TAG, "onTaskFragmentAppeared() called with: taskFragmentInfo = [" + taskFragmentInfo + "]");
//        onTaskFragmentAppeared(taskFragmentInfo);
        // 这里可以监听到 Fragment 真正创建成功，可以做一些 UI 状态维护
    }

    //    @Override
    public void onTaskFragmentInfoChanged( WindowContainerTransaction wct, TaskFragmentInfo taskFragmentInfo, int taskId) {
        if (taskFragmentInfo != null && !taskFragmentInfo.hasRunningActivity()) {
            deleteTaskFragment(wct, taskFragmentInfo);
            removeTaskFragmentInfo(taskFragmentInfo);
        }
        Slog.d(TAG, "onTaskFragmentInfoChanged() called with: taskFragmentInfo = [" + taskFragmentInfo + "]");
//        onTaskFragmentInfoChanged(taskFragmentInfo);
        // 如果右侧容器内的 Activity 全部退出了，你可以在这里通过 WCT 删掉它，并把左侧拉满
    }

    void deleteTaskFragment(WindowContainerTransaction wct, TaskFragmentInfo taskFragmentInfo) {
        if(taskFragmentInfo == null || taskFragmentInfo.getFragmentToken() == null
                || mFragmentInfos.get(taskFragmentInfo.getFragmentToken()) == null){
            Slog.w(TAG, "fragments already delete");
            return;
        }
        wct.deleteTaskFragment(taskFragmentInfo.getFragmentToken());
//        try {
//            mAtmService.getWindowOrganizerController().applyTransaction(wct);
//        } catch (RemoteException e) {
//            Slog.e(TAG, e.getMessage());
//        }
    }

    void deleteTaskFragment(WindowContainerTransaction wct, IBinder token) {
        if(token == null
                || mFragmentInfos.get(token) == null){
            Slog.w(TAG, "fragments already delete");
            return;
        }
        wct.deleteTaskFragment(token);
//        try {
//            mAtmService.getWindowOrganizerController().applyTransaction(wct);
//        } catch (RemoteException e) {
//            Slog.e(TAG, e.getMessage());
//        }
    }

    //    @Override
    public void onTaskFragmentVanished(WindowContainerTransaction wct, TaskFragmentInfo taskFragmentInfo, int taskId) {
        Slog.d(TAG, "onTaskFragmentVanished() called with: taskFragmentInfo = [" + taskFragmentInfo + "]");
//        onTaskFragmentVanished(taskFragmentInfo);
        if (mRightFragments.get(taskId) != null &&
                mRightFragments.get(taskId) == taskFragmentInfo.getFragmentToken() )
        {
            expandTaskFragment(wct, mLeftFragments.get(taskId));
            mSplitingActivityRecords.remove(taskId);
            mRightFragments.remove(taskId);
        }else if(mLeftFragments.get(taskId) != null &&
                mLeftFragments.get(taskId) == taskFragmentInfo.getFragmentToken())
        {
            deleteTaskFragment(wct, mRightFragments.get(taskId));
//            deleteTaskFragment(wct, taskFragmentInfo);
            mRightFragments.remove(taskId);
            mLeftFragments.remove(taskId);
        }
        removeTaskFragmentInfo(taskFragmentInfo);
    }

    public void onTaskFragmentParentInfoChanged(WindowContainerTransaction wct, int taskId, TaskFragmentParentInfo taskFragmentInfo) {
        Slog.d(TAG, "onTaskFragmentParentInfoChanged() called with: taskFragmentInfo = [" + taskFragmentInfo.getConfiguration() + "]");
        final Rect taskBounds = taskFragmentInfo.getConfiguration().windowConfiguration.getBounds();
        if (shouldUpdateContainer(taskFragmentInfo)) {
            updateContainersInTask(wct, taskId, taskBounds);
        }
        mConfiguration = taskFragmentInfo.getConfiguration();
        mDisplayId = taskFragmentInfo.getDisplayId();
    }

    boolean shouldUpdateContainer(@NonNull TaskFragmentParentInfo info) {
        final Configuration configuration = info.getConfiguration();
        return info.isVisible()
                // No need to update presentation in PIP until the Task exit PIP.
                && !isInPictureInPicture(configuration)
                // If the task properties equals regardless of starting position, don't need to
                // update the container.
                && (mConfiguration.diffPublicOnly(configuration) != 0
                || mDisplayId != info.getDisplayId());
    }

    void expandTaskFragment(WindowContainerTransaction wct, @NonNull IBinder fragmentToken) {
        if(mFragmentInfos.get(fragmentToken) == null){
            Slog.w(TAG, "expandTaskFragment fragment is removed ");
            return;
        }

        Slog.d(TAG, "expandTaskFragment: 展开 TaskFragment，token=" + fragmentToken);
        resizeTaskFragment(wct, fragmentToken, new Rect());
        wct.clearAdjacentTaskFragments(fragmentToken);
        wct.setWindowingMode(mFragmentInfos.get(fragmentToken).getToken(), WINDOWING_MODE_UNDEFINED);
        Slog.d(TAG, "expandTaskFragment: 已完成展开");
//        try {
//            mAtmService.getWindowOrganizerController().applyTransaction(wct);
//        } catch (RemoteException e) {
//            throw e.rethrowFromSystemServer();
//        }
    }


    private static boolean isInPictureInPicture(@NonNull Configuration configuration) {
        return configuration.windowConfiguration.getWindowingMode() == WINDOWING_MODE_PINNED;
    }
}