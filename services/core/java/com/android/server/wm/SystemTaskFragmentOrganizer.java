/*
 * Copyright (C) 2026 The OpenFDE Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the OpenFDE project.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;

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
 * SystemTaskFragmentOrganizer
 *
 * This class is a custom TaskFragmentOrganizer used to implement a
 * non-standard split window behavior inside a single Task.
 *
 * Core idea:
 * - A Task is dynamically expanded (width doubled)
 * - Two TaskFragments are created inside the Task:
 *      left  -> primary activity
 *      right -> secondary activity
 * - The right container lives in the newly expanded area (not traditional split)
 *
 * Key responsibilities:
 *
 * 1. startSplit(...)
 *    Entry point for split logic.
 *    - If the task is NOT split:
 *         • Resize (expand) the Task
 *         • Create left/right TaskFragments
 *         • Move or start activities into corresponding fragments
 *    - If the task is already split:
 *         • Reuse the existing right TaskFragment
 *         • Reparent or start the secondary activity into it
 *
 * 2. onTransactionReady(...)
 *    Callback from system when TaskFragment changes occur.
 *    Dispatches events such as:
 *         • TaskFragment appeared
 *         • Info changed
 *         • Vanished
 *         • Parent config changed
 *
 * 3. updateContainersInTask(...)
 *    Keeps left/right TaskFragments in sync with Task bounds.
 *    Typically triggered when Task size/configuration changes.
 *
 * 4. onTaskFragmentVanished(...)
 *    Handles cleanup when a fragment is removed:
 *         • If right fragment disappears → shrink Task back to original size
 *         • If left fragment disappears → finish all activities in right fragment
 *
 * 5. contractTaskFragment(...)
 *    Restores Task size when exiting split mode.
 *
 * 6. pauseLeftIfNeed(...)
 *    Optional behavior:
 *         • Pause the left (primary) activity when right side becomes active
 *         • Used for app-specific compatibility (e.g., WeChat)
 *
 *
 * Important design notes:
 *
 * - Fragment tokens (IBinder) are NOT the real WindowContainerToken.
 *   Always use TaskFragmentInfo.getToken() when applying WCT operations.
 *
 * - TaskFragmentInfo is asynchronous:
 *   It is only available after callbacks like TYPE_TASK_FRAGMENT_APPEARED.
 *
 * - Reparenting rules:
 *   An Activity must NOT be reparented into the same TaskFragment it already belongs to,
 *   otherwise an IllegalArgumentException will be thrown.
 *
 * - Task expansion model:
 *   This implementation does NOT split the original bounds evenly.
 *   Instead, it expands the Task and places the secondary fragment in the new region.
 *
 * - Surface/layout timing:
 *   Task resize and TaskFragment creation are intentionally separated (via Handler post)
 *   to ensure correct configuration propagation and avoid initial rendering issues.
 *
 */
public class SystemTaskFragmentOrganizer extends TaskFragmentOrganizer {

    private static final String TAG = "SystemTaskFragmentOrganizer";
    private final ActivityTaskManagerService mAtmService;

    private final Map<Integer, IBinder> mLeftFragments = new HashMap<>();
    private final Map<Integer, IBinder> mRightFragments = new HashMap<>();
    final Map<IBinder, TaskFragmentInfo> mFragmentInfos = new ArrayMap<>();
    final Map<Integer, ActivityRecord> mSplitingActivityRecords = new ArrayMap<>();
    final Map<Integer, Float> mSplitRatios = new HashMap<>();
    private Configuration mConfiguration = new Configuration();
    private int mDisplayId;
    boolean mIsExpandedMode = false;

    public SystemTaskFragmentOrganizer(ActivityTaskManagerService atmService) {
        super(atmService.mH::post);
        mAtmService = atmService;
    }

    public void register() {
        super.registerOrganizer();
    }

    void startSplit(Task task, ActivityRecord primary,
                    ActivityRecord secondary, Intent secondaryIntent, float ratio) {
        if (task == null || primary == null) return;
        if (secondary.intent != null && secondary.intent.getComponent() != null
            && secondary.intent.getComponent().getClassName().contains("LoginSelectUI")){
           return;
        }
        if (mSplitingActivityRecords.get(task.mTaskId) == secondary) {
            Slog.w(TAG, "spliting " + secondary);
            return;
        }
        final long origId = Binder.clearCallingIdentity();
        final int taskId = task.mTaskId;
        final IBinder existingRight = mRightFragments.get(taskId);
        final boolean alreadySplit =
                existingRight != null && mFragmentInfos.get(existingRight) != null;
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        if (alreadySplit) {
            Slog.d(TAG, "startSplit: already split, reuse right TF");
            mAtmService.mH.postDelayed(() -> {
                pauseLeftIfNeed(primary);
            }, 1000);
            if (secondary != null) {
                TaskFragment currentTf = secondary.getTaskFragment();
                if (currentTf != null) {
                    mSplitingActivityRecords.put(taskId, secondary);
                    mIsExpandedMode = true;
                    Slog.d(TAG, "Activity already in target TF, skip reparent");
                    return;
                }
                wct.reparentActivityToTaskFragment(existingRight, secondary.token);
            } else if (secondaryIntent != null) {
                wct.startActivityInTaskFragment(existingRight, primary.token, secondaryIntent, null);
            }
            try {
                mAtmService.getWindowOrganizerController().applyTransaction(wct);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            mSplitRatios.put(task.mTaskId, ratio);
            final Rect taskBounds = task.getBounds();
            final int originalWidth = taskBounds.width();   // 原始宽度（左侧窗口宽度）
            final int newWidth = (int) (originalWidth / (1 - ratio));  // 新总宽度
            final int newHeight = taskBounds.height();
            final Rect newTaskBounds = new Rect(taskBounds.left, taskBounds.top,
                    taskBounds.left + newWidth, taskBounds.bottom);
            mAtmService.resizeTask(taskId, newTaskBounds, 0);
            mAtmService.mH.post(() -> {
                Slog.d(TAG, "startSplit: create new split");
                final IBinder primaryTfToken = new Binder();
                final IBinder secondaryTfToken = new Binder();
                final IBinder ownerToken = primary.token;
                final Rect leftBounds = new Rect(0, 0, originalWidth, newHeight);
                final Rect rightBounds = new Rect(originalWidth, 0, newWidth, newHeight);
                TaskFragmentCreationParams primaryParams =
                        new TaskFragmentCreationParams.Builder(getOrganizerToken(), primaryTfToken, ownerToken)
                                .setInitialRelativeBounds(leftBounds)
                                .build();
                wct.createTaskFragment(primaryParams);
                wct.reparentActivityToTaskFragment(primaryTfToken, primary.token);

                TaskFragmentCreationParams secondaryParams =
                        new TaskFragmentCreationParams.Builder(getOrganizerToken(), secondaryTfToken, ownerToken)
                                .setInitialRelativeBounds(rightBounds)
                                .setPairedPrimaryFragmentToken(primaryTfToken)
                                .build();
                wct.createTaskFragment(secondaryParams);
                if (secondary != null) {
                    wct.reparentActivityToTaskFragment(secondaryTfToken, secondary.token);
                } else if (secondaryIntent != null) {
                    wct.startActivityInTaskFragment(secondaryTfToken, ownerToken, secondaryIntent, null);
                }
                WindowContainerTransaction.TaskFragmentAdjacentParams adjacentParams =
                        new WindowContainerTransaction.TaskFragmentAdjacentParams();
                wct.setAdjacentTaskFragments(primaryTfToken, secondaryTfToken, adjacentParams);
                wct.setCompanionTaskFragment(primaryTfToken, secondaryTfToken);
                mLeftFragments.put(taskId, primaryTfToken);
                mRightFragments.put(taskId, secondaryTfToken);
                mSplitingActivityRecords.put(taskId, secondary);
                try {
                    mAtmService.getWindowOrganizerController().applyTransaction(wct);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            });
        }
        mIsExpandedMode = true;
        Binder.restoreCallingIdentity(origId);
    }

    void updateContainersInTask(WindowContainerTransaction wct, int taskId, Rect taskBounds,
                                Configuration configuration) {
        if (mRightFragments.get(taskId) == null) {
            Slog.d(TAG, "updateContainersInTask one activity taskId:" + taskId + " bounds:" + taskBounds);
            final IBinder primaryTfToken = mLeftFragments.get(taskId);
	    resizeTaskFragment(wct, primaryTfToken, null);
	    return;
        } else {
            Slog.d(TAG, "updateContainersInTask  taskId:" + taskId + " bounds:" + taskBounds);
            float ratio = mSplitRatios.get(taskId);
            final IBinder primaryTfToken = mLeftFragments.get(taskId);
            final IBinder secondaryTfToken = mRightFragments.get(taskId);
            final int totalWidth = taskBounds.width();
            int leftWidth = Math.round(totalWidth * (1 - ratio));
            int rightWidth = totalWidth - leftWidth; // 保证右侧填满剩余宽度，避免浮点误差
            final Rect left = new Rect(0, 0, leftWidth, taskBounds.height());
            final Rect right = new Rect(leftWidth, 0, totalWidth, taskBounds.height());
            resizeTaskFragment(wct, primaryTfToken, left);
            resizeTaskFragment(wct, secondaryTfToken, right);
            if (configuration.windowConfiguration.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
                updateWindowingMode(wct, primaryTfToken, WINDOWING_MODE_MULTI_WINDOW);
                updateWindowingMode(wct, secondaryTfToken, WINDOWING_MODE_MULTI_WINDOW);
            } else if (configuration.windowConfiguration.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
                updateWindowingMode(wct, primaryTfToken, WINDOWING_MODE_FREEFORM);
                updateWindowingMode(wct, secondaryTfToken, WINDOWING_MODE_FREEFORM);
            }
        }
	try {
            mAtmService.getWindowOrganizerController().applyTransaction(wct);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void updateWindowingMode(@NonNull WindowContainerTransaction wct,
                             @NonNull IBinder fragmentToken, int windowingMode) {
        if (fragmentToken == null || mFragmentInfos.get(fragmentToken) == null) {
            Slog.w(TAG, "Not yet get the fragment to update mode");
            return;
        }
        Slog.d(TAG, "updateWindowingMode: 更新窗口模式，token=" + fragmentToken + ", mode="
                + windowingMode);
        wct.setWindowingMode(mFragmentInfos.get(fragmentToken).getToken(), windowingMode);
    }

    void resizeTaskFragment(@NonNull WindowContainerTransaction wct, @NonNull IBinder fragmentToken,
                            @Nullable Rect relBounds) {
        if (fragmentToken == null || mFragmentInfos.get(fragmentToken) == null) {
            Slog.w(TAG, "Not yet get the fragment to resize");
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
                    onTaskFragmentAppeared(wct, info, taskId);
                    break;
                case TYPE_TASK_FRAGMENT_INFO_CHANGED: //2
                    updateTaskFragmentInfo(info);
                    onTaskFragmentInfoChanged(wct, info, taskId);
                    break;
                case TYPE_TASK_FRAGMENT_VANISHED: //3
                    onTaskFragmentVanished(wct, info, taskId);
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

    public void onTaskFragmentAppeared(WindowContainerTransaction wct, TaskFragmentInfo taskFragmentInfo,
                                       int taskId) {
        Slog.d(TAG, "onTaskFragmentAppeared() called with: taskFragmentInfo = [" + taskFragmentInfo + "]");
        IBinder token = taskFragmentInfo.getFragmentToken();
        if (token.equals(mRightFragments.get(taskId))) {
            final Rect taskBounds = taskFragmentInfo.getConfiguration().windowConfiguration.getBounds();
            Slog.d(TAG, "taskBounds = [" + taskBounds + "]");
        }
    }

    public void onTaskFragmentInfoChanged(WindowContainerTransaction wct, TaskFragmentInfo taskFragmentInfo,
                                          int taskId) {
        if (taskFragmentInfo != null && !taskFragmentInfo.hasRunningActivity()) {
            deleteTaskFragment(wct, taskFragmentInfo);
            removeTaskFragmentInfo(taskFragmentInfo);
        } else {
            pauseLeftIfNeed(taskFragmentInfo, taskId);
        }
        Slog.d(TAG, "onTaskFragmentInfoChanged() called with: taskFragmentInfo = [" + taskFragmentInfo + "]");
    }

    void pauseLeftIfNeed(IBinder token){
        TaskFragmentInfo leftInfo = mFragmentInfos.get(token);
        if (leftInfo == null) {
            Slog.w(TAG, "leftInfo is null");
            return;
        }
        List<IBinder> activities = leftInfo.getActivities();
        if (activities == null || activities.isEmpty()) {
            Slog.w(TAG, "left TF has no activities");
            return;
        }
        IBinder topToken = activities.get(activities.size() - 1);
        ActivityRecord topActivity =
                ActivityRecord.forTokenLocked(topToken);
        pauseLeftIfNeed(topActivity);
        Slog.d(TAG, "Top Activity in left TF: " + topActivity);
    }

    void pauseLeftIfNeed(ActivityRecord topActivity){
        if (topActivity != null) {
            String pkg = topActivity.packageName;
            if ("com.tencent.mm".equals(pkg)) {
                Slog.d(TAG, "left top activity is tencent wx");
                topActivity.pauseActivityLockedOnly();
                Slog.d(TAG, "Activity is foreground");
            }
        }
    }

    void pauseLeftIfNeed(TaskFragmentInfo taskFragmentInfo, int taskId){
        IBinder token = taskFragmentInfo.getFragmentToken();
        if (token.equals(mRightFragments.get(taskId))) {
            IBinder leftToken = mLeftFragments.get(taskId);
            pauseLeftIfNeed(leftToken);
        }
    }

    void deleteTaskFragment(WindowContainerTransaction wct, TaskFragmentInfo taskFragmentInfo) {
        if (taskFragmentInfo == null || taskFragmentInfo.getFragmentToken() == null
                || mFragmentInfos.get(taskFragmentInfo.getFragmentToken()) == null) {
            Slog.w(TAG, "fragments already delete");
            return;
        }
        wct.deleteTaskFragment(taskFragmentInfo.getFragmentToken());
    }

    void deleteTaskFragment(WindowContainerTransaction wct, IBinder token) {
        if (token == null
                || mFragmentInfos.get(token) == null) {
            Slog.w(TAG, "fragments already delete");
            return;
        }
        wct.deleteTaskFragment(token);
    }

    public void onTaskFragmentVanished(WindowContainerTransaction wct,
                                    TaskFragmentInfo taskFragmentInfo, int taskId) {
        Slog.d(TAG, "onTaskFragmentVanished() called with: taskFragmentInfo = [" + taskFragmentInfo + "]");
        if (mRightFragments.get(taskId) != null &&
                mRightFragments.get(taskId) == taskFragmentInfo.getFragmentToken()) {
            final Rect taskBounds = mConfiguration.windowConfiguration.getBounds();
            float ratio = mSplitRatios.get(taskId);
            Rect newTaskBounds = new Rect(
                taskBounds.left,
                taskBounds.top,
                taskBounds.left + (int)(taskBounds.width() * (1 - ratio)),
                taskBounds.bottom
            );
            mAtmService.mH.post(() -> {
                contractTaskFragment(mLeftFragments.get(taskId), taskId, newTaskBounds);
            });
            mSplitingActivityRecords.remove(taskId);
            mRightFragments.remove(taskId);

        } else if (mLeftFragments.get(taskId) != null &&
                mLeftFragments.get(taskId) == taskFragmentInfo.getFragmentToken()) {
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
        if (shouldUpdateContainer(taskFragmentInfo)) {
            updateContainersInTask(wct, taskId, taskBounds, taskFragmentInfo.getConfiguration());
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

    void contractTaskFragment(@NonNull IBinder fragmentToken,
                              int taskId, Rect bounds) {
        if (mFragmentInfos.get(fragmentToken) == null) {
            Slog.w(TAG, "expandTaskFragment fragment is removed ");
            return;
        }
        Slog.d(TAG, "contractTaskFragment: token=" + fragmentToken + " bounds:" + bounds + " taskId:" + taskId);
        mAtmService.resizeTask(taskId, bounds, 0);
        mRightFragments.remove(taskId);
        mSplitingActivityRecords.remove(taskId);
        Slog.d(TAG, "contractTaskFragment: 已完成收缩");
    }


    private static boolean isInPictureInPicture(@NonNull Configuration configuration) {
        return configuration.windowConfiguration.getWindowingMode() == WINDOWING_MODE_PINNED;
    }
}
