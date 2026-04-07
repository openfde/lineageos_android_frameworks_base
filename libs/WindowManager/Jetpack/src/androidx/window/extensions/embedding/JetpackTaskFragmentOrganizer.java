/*
 * Copyright (C) 2021 The Android Open Source Project
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

package androidx.window.extensions.embedding;

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.window.TaskFragmentOperation.OP_TYPE_REORDER_TO_FRONT;
import static android.window.TaskFragmentOperation.OP_TYPE_SET_ANIMATION_PARAMS;
import static android.window.TaskFragmentOperation.OP_TYPE_SET_DIM_ON_TASK;
import static android.window.TaskFragmentOperation.OP_TYPE_SET_ISOLATED_NAVIGATION;

import static androidx.window.extensions.embedding.SplitContainer.getFinishPrimaryWithSecondaryBehavior;
import static androidx.window.extensions.embedding.SplitContainer.getFinishSecondaryWithPrimaryBehavior;
import static androidx.window.extensions.embedding.SplitContainer.shouldFinishAssociatedContainerWhenStacked;
import static androidx.window.extensions.embedding.SplitContainer.shouldFinishPrimaryWithSecondary;
import static androidx.window.extensions.embedding.SplitContainer.shouldFinishSecondaryWithPrimary;

import android.app.Activity;
import android.app.WindowConfiguration.WindowingMode;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.Log;
import android.window.TaskFragmentAnimationParams;
import android.window.TaskFragmentCreationParams;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOperation;
import android.window.TaskFragmentOrganizer;
import android.window.TaskFragmentTransaction;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Platform default Extensions implementation of {@link TaskFragmentOrganizer} to organize
 * task fragments.
 *
 * All calls into methods of this class are expected to be on the UI thread.
 */
class JetpackTaskFragmentOrganizer extends TaskFragmentOrganizer {

    private static final String TAG = "JetpackTaskFragmentOrganizer";

    /** Mapping from the client assigned unique token to the {@link TaskFragmentInfo}. */
    @VisibleForTesting
    final Map<IBinder, TaskFragmentInfo> mFragmentInfos = new ArrayMap<>();

    @NonNull
    private final TaskFragmentCallback mCallback;

    @VisibleForTesting
    @Nullable
    TaskFragmentAnimationController mAnimationController;

    /**
     * Callback that notifies the controller about changes to task fragments.
     */
    interface TaskFragmentCallback {
        void onTransactionReady(@NonNull TaskFragmentTransaction transaction);
    }

    /**
     * @param executor  callbacks from WM Core are posted on this executor. It should be tied to the
     *                  UI thread that all other calls into methods of this class are also on.
     */
    JetpackTaskFragmentOrganizer(@NonNull Executor executor,
                                 @NonNull TaskFragmentCallback callback) {
        super(executor);
        Log.d(TAG, "构造函数: JetpackTaskFragmentOrganizer 实例创建，executor=" + executor);
        mCallback = callback;
    }

    @Override
    public void unregisterOrganizer() {
        Log.d(TAG, "unregisterOrganizer: 开始注销组织器");
        if (mAnimationController != null) {
            Log.d(TAG, "unregisterOrganizer: 清除动画控制器并注销远端动画");
            mAnimationController.unregisterRemoteAnimations();
            mAnimationController = null;
        }
        super.unregisterOrganizer();
        Log.d(TAG, "unregisterOrganizer: 注销完成");
    }

    /**
     * Overrides the animation for transitions of embedded activities organized by this organizer.
     */
    void overrideSplitAnimation() {
        Log.d(TAG, "overrideSplitAnimation: 开始覆盖分屏动画");
        if (mAnimationController == null) {
            Log.d(TAG, "overrideSplitAnimation: 创建新的 TaskFragmentAnimationController");
            mAnimationController = new TaskFragmentAnimationController(this);
        }
        mAnimationController.registerRemoteAnimations();
        Log.d(TAG, "overrideSplitAnimation: 已注册远端动画");
    }

    /**
     * Starts a new Activity and puts it into split with an existing Activity side-by-side.
     * @param launchingFragmentToken    token for the launching TaskFragment. If it exists, it will
     *                                  be resized based on {@param launchingFragmentBounds}.
     *                                  Otherwise, we will create a new TaskFragment with the given
     *                                  token for the {@param launchingActivity}.
     * @param launchingRelBounds    the initial relative bounds for the launching TaskFragment.
     * @param launchingActivity the Activity to put on the left hand side of the split as the
     *                          primary.
     * @param secondaryFragmentToken    token to create the secondary TaskFragment with.
     * @param secondaryRelBounds    the initial relative bounds for the secondary TaskFragment
     * @param activityIntent    Intent to start the secondary Activity with.
     * @param activityOptions   ActivityOptions to start the secondary Activity with.
     * @param windowingMode     the windowing mode to set for the TaskFragments.
     * @param splitAttributes   the {@link SplitAttributes} to represent the split.
     */
    void startActivityToSide(@NonNull WindowContainerTransaction wct,
                             @NonNull IBinder launchingFragmentToken, @NonNull Rect launchingRelBounds,
                             @NonNull Activity launchingActivity, @NonNull IBinder secondaryFragmentToken,
                             @NonNull Rect secondaryRelBounds, @NonNull Intent activityIntent,
                             @Nullable Bundle activityOptions, @NonNull SplitRule rule,
                             @WindowingMode int windowingMode, @NonNull SplitAttributes splitAttributes) {
        Log.d(TAG, "startActivityToSide: 开始将 Activity 启动到侧边分屏，主 fragmentToken="
                + launchingFragmentToken + ", 次 fragmentToken=" + secondaryFragmentToken);
        Log.d(TAG, "startActivityToSide: launchingRelBounds=" + launchingRelBounds
                + ", secondaryRelBounds=" + secondaryRelBounds
                + ", windowingMode=" + windowingMode);
        Log.d(TAG, "startActivityToSide: activityIntent=" + activityIntent
                + ", activityOptions=" + activityOptions);
        Log.d(TAG, "startActivityToSide: splitRule=" + rule
                + ", splitAttributes=" + splitAttributes);

        final IBinder ownerToken = launchingActivity.getActivityToken();

        // Create or resize the launching TaskFragment.
        if (mFragmentInfos.containsKey(launchingFragmentToken)) {
            Log.d(TAG, "startActivityToSide: 主 TaskFragment 已存在，执行 resize");
            resizeTaskFragment(wct, launchingFragmentToken, launchingRelBounds);
            updateWindowingMode(wct, launchingFragmentToken, windowingMode);
        } else {
            Log.d(TAG, "startActivityToSide: 主 TaskFragment 不存在，创建并重新父 Activity");
            createTaskFragmentAndReparentActivity(wct, launchingFragmentToken, ownerToken,
                    launchingRelBounds, windowingMode, launchingActivity);
        }
        updateAnimationParams(wct, launchingFragmentToken, splitAttributes);

        // Create a TaskFragment for the secondary activity.
        Log.d(TAG, "startActivityToSide: 为次要 Activity 创建 TaskFragment");
        final TaskFragmentCreationParams fragmentOptions = new TaskFragmentCreationParams.Builder(
                getOrganizerToken(), secondaryFragmentToken, ownerToken)
                .setInitialRelativeBounds(secondaryRelBounds)
                .setWindowingMode(windowingMode)
                // Make sure to set the paired fragment token so that the new TaskFragment will be
                // positioned right above the paired TaskFragment.
                // This is needed in case we need to launch a placeholder Activity to split below a
                // transparent always-expand Activity.
                .setPairedPrimaryFragmentToken(launchingFragmentToken)
                .build();
        createTaskFragment(wct, fragmentOptions);
        updateAnimationParams(wct, secondaryFragmentToken, splitAttributes);
        wct.startActivityInTaskFragment(secondaryFragmentToken, ownerToken, activityIntent,
                activityOptions);
        Log.d(TAG, "startActivityToSide: 已请求启动次要 Activity");

        // Set adjacent to each other so that the containers below will be invisible.
        setAdjacentTaskFragmentsWithRule(wct, launchingFragmentToken, secondaryFragmentToken, rule);
        setCompanionTaskFragment(wct, launchingFragmentToken, secondaryFragmentToken, rule,
                false /* isStacked */);
        Log.d(TAG, "startActivityToSide: 完成分屏设置");
    }

    /**
     * Expands an existing TaskFragment to fill parent.
     * @param wct WindowContainerTransaction in which the task fragment should be resized.
     * @param fragmentToken token of an existing TaskFragment.
     */
    void expandTaskFragment(@NonNull WindowContainerTransaction wct,
                            @NonNull IBinder fragmentToken) {
        Log.d(TAG, "expandTaskFragment: 展开 TaskFragment，token=" + fragmentToken);
        resizeTaskFragment(wct, fragmentToken, new Rect());
        clearAdjacentTaskFragments(wct, fragmentToken);
        updateWindowingMode(wct, fragmentToken, WINDOWING_MODE_UNDEFINED);
        updateAnimationParams(wct, fragmentToken, TaskFragmentAnimationParams.DEFAULT);
        Log.d(TAG, "expandTaskFragment: 已完成展开");
    }

    /**
     * Expands an Activity to fill parent by moving it to a new TaskFragment.
     * @param fragmentToken token to create new TaskFragment with.
     * @param activity      activity to move to the fill-parent TaskFragment.
     */
    void expandActivity(@NonNull WindowContainerTransaction wct, @NonNull IBinder fragmentToken,
                        @NonNull Activity activity) {
        Log.d(TAG, "expandActivity: 将 Activity 展开至全屏，新 fragmentToken=" + fragmentToken
                + ", activity=" + activity);
        createTaskFragmentAndReparentActivity(
                wct, fragmentToken, activity.getActivityToken(), new Rect(),
                WINDOWING_MODE_UNDEFINED, activity);
        updateAnimationParams(wct, fragmentToken, TaskFragmentAnimationParams.DEFAULT);
        Log.d(TAG, "expandActivity: 已完成展开");
    }

    /**
     * @param ownerToken The token of the activity that creates this task fragment. It does not
     *                   have to be a child of this task fragment, but must belong to the same task.
     */
    void createTaskFragment(@NonNull WindowContainerTransaction wct, @NonNull IBinder fragmentToken,
                            @NonNull IBinder ownerToken, @NonNull Rect relBounds,
                            @WindowingMode int windowingMode) {
        Log.d(TAG, "createTaskFragment: 创建 TaskFragment，fragmentToken=" + fragmentToken
                + ", ownerToken=" + ownerToken + ", bounds=" + relBounds + ", mode=" + windowingMode);
        createTaskFragment(wct, fragmentToken, ownerToken, relBounds, windowingMode,
                null /* pairedActivityToken */);
    }

    /**
     * @param ownerToken The token of the activity that creates this task fragment. It does not
     *                   have to be a child of this task fragment, but must belong to the same task.
     * @param pairedActivityToken The token of the activity that will be reparented to this task
     *                            fragment. When it is not {@code null}, the task fragment will be
     *                            positioned right above it.
     */
    void createTaskFragment(@NonNull WindowContainerTransaction wct, @NonNull IBinder fragmentToken,
                            @NonNull IBinder ownerToken, @NonNull Rect relBounds, @WindowingMode int windowingMode,
                            @Nullable IBinder pairedActivityToken) {
        Log.d(TAG, "createTaskFragment(详细): fragmentToken=" + fragmentToken
                + ", pairedActivityToken=" + pairedActivityToken);
        final TaskFragmentCreationParams fragmentOptions = new TaskFragmentCreationParams.Builder(
                getOrganizerToken(), fragmentToken, ownerToken)
                .setInitialRelativeBounds(relBounds)
                .setWindowingMode(windowingMode)
                .setPairedActivityToken(pairedActivityToken)
                .build();
        createTaskFragment(wct, fragmentOptions);
    }

    void createTaskFragment(@NonNull WindowContainerTransaction wct,
                            @NonNull TaskFragmentCreationParams fragmentOptions) {
        Log.d(TAG, "createTaskFragment (使用 CreationParams): fragmentToken="
                + fragmentOptions.getFragmentToken());
        if (mFragmentInfos.containsKey(fragmentOptions.getFragmentToken())) {
            Log.w(TAG, "createTaskFragment: 警告，TaskFragment 已存在，token="
                    + fragmentOptions.getFragmentToken());
            throw new IllegalArgumentException(
                    "There is an existing TaskFragment with fragmentToken="
                            + fragmentOptions.getFragmentToken());
        }
        wct.createTaskFragment(fragmentOptions);
        Log.d(TAG, "createTaskFragment: 已提交创建请求");
    }

    /**
     * @param ownerToken The token of the activity that creates this task fragment. It does not
     *                   have to be a child of this task fragment, but must belong to the same task.
     */
    private void createTaskFragmentAndReparentActivity(@NonNull WindowContainerTransaction wct,
                                                       @NonNull IBinder fragmentToken, @NonNull IBinder ownerToken, @NonNull Rect relBounds,
                                                       @WindowingMode int windowingMode, @NonNull Activity activity) {
        Log.d(TAG, "createTaskFragmentAndReparentActivity: 创建 TaskFragment 并重新父 Activity, token="
                + fragmentToken + ", activity=" + activity);
        final IBinder reparentActivityToken = activity.getActivityToken();
        createTaskFragment(wct, fragmentToken, ownerToken, relBounds, windowingMode,
                reparentActivityToken);
        wct.reparentActivityToTaskFragment(fragmentToken, reparentActivityToken);
        Log.d(TAG, "createTaskFragmentAndReparentActivity: 已完成重新父操作");
    }

    /**
     * Sets the two given TaskFragments as adjacent to each other with respecting the given
     * {@link SplitRule} for {@link WindowContainerTransaction.TaskFragmentAdjacentParams}.
     */
    void setAdjacentTaskFragmentsWithRule(@NonNull WindowContainerTransaction wct,
                                          @NonNull IBinder primary, @NonNull IBinder secondary, @NonNull SplitRule splitRule) {
        Log.d(TAG, "setAdjacentTaskFragmentsWithRule: 设置相邻 TaskFragment，primary=" + primary
                + ", secondary=" + secondary);
        WindowContainerTransaction.TaskFragmentAdjacentParams adjacentParams = null;
        final boolean finishSecondaryWithPrimary =
                SplitContainer.shouldFinishSecondaryWithPrimary(splitRule);
        final boolean finishPrimaryWithSecondary =
                SplitContainer.shouldFinishPrimaryWithSecondary(splitRule);
        if (finishSecondaryWithPrimary || finishPrimaryWithSecondary) {
            Log.d(TAG, "setAdjacentTaskFragmentsWithRule: 设置延迟移除标志，finishSecondaryWithPrimary="
                    + finishSecondaryWithPrimary + ", finishPrimaryWithSecondary="
                    + finishPrimaryWithSecondary);
            adjacentParams = new WindowContainerTransaction.TaskFragmentAdjacentParams();
            adjacentParams.setShouldDelayPrimaryLastActivityRemoval(finishSecondaryWithPrimary);
            adjacentParams.setShouldDelaySecondaryLastActivityRemoval(finishPrimaryWithSecondary);
        }
        setAdjacentTaskFragments(wct, primary, secondary, adjacentParams);
    }

    void setAdjacentTaskFragments(@NonNull WindowContainerTransaction wct,
                                  @NonNull IBinder primary, @NonNull IBinder secondary,
                                  @Nullable WindowContainerTransaction.TaskFragmentAdjacentParams adjacentParams) {
        Log.d(TAG, "setAdjacentTaskFragments: primary=" + primary + ", secondary=" + secondary);
        wct.setAdjacentTaskFragments(primary, secondary, adjacentParams);
    }

    void clearAdjacentTaskFragments(@NonNull WindowContainerTransaction wct,
                                    @NonNull IBinder fragmentToken) {
        Log.d(TAG, "clearAdjacentTaskFragments: 清除相邻关系，fragmentToken=" + fragmentToken);
        // Clear primary will also clear secondary.
        wct.clearAdjacentTaskFragments(fragmentToken);
    }

    void setCompanionTaskFragment(@NonNull WindowContainerTransaction wct,
                                  @NonNull IBinder primary, @NonNull IBinder secondary, @NonNull SplitRule splitRule,
                                  boolean isStacked) {
        Log.d(TAG, "setCompanionTaskFragment: 设置伴随 TaskFragment，primary=" + primary
                + ", secondary=" + secondary + ", isStacked=" + isStacked);
        final boolean finishPrimaryWithSecondary;
        if (isStacked) {
            finishPrimaryWithSecondary = shouldFinishAssociatedContainerWhenStacked(
                    getFinishPrimaryWithSecondaryBehavior(splitRule));
        } else {
            finishPrimaryWithSecondary = shouldFinishPrimaryWithSecondary(splitRule);
        }
        setCompanionTaskFragment(wct, primary, finishPrimaryWithSecondary ? secondary : null);

        final boolean finishSecondaryWithPrimary;
        if (isStacked) {
            finishSecondaryWithPrimary = shouldFinishAssociatedContainerWhenStacked(
                    getFinishSecondaryWithPrimaryBehavior(splitRule));
        } else {
            finishSecondaryWithPrimary = shouldFinishSecondaryWithPrimary(splitRule);
        }
        setCompanionTaskFragment(wct, secondary, finishSecondaryWithPrimary ? primary : null);
        Log.d(TAG, "setCompanionTaskFragment: 完成，primary 伴随标志=" + finishPrimaryWithSecondary
                + ", secondary 伴随标志=" + finishSecondaryWithPrimary);
    }

    void setCompanionTaskFragment(@NonNull WindowContainerTransaction wct, @NonNull IBinder primary,
                                  @Nullable IBinder secondary) {
        Log.d(TAG, "setCompanionTaskFragment: 设置 primary=" + primary + " 的伴随 fragment="
                + secondary);
        wct.setCompanionTaskFragment(primary, secondary);
    }

    void resizeTaskFragment(@NonNull WindowContainerTransaction wct, @NonNull IBinder fragmentToken,
                            @Nullable Rect relBounds) {
        Log.d(TAG, "resizeTaskFragment: 调整 TaskFragment 大小，token=" + fragmentToken
                + ", newBounds=" + relBounds);
        if (!mFragmentInfos.containsKey(fragmentToken)) {
            Log.e(TAG, "resizeTaskFragment: 未找到 TaskFragment，token=" + fragmentToken);
            throw new IllegalArgumentException(
                    "Can't find an existing TaskFragment with fragmentToken=" + fragmentToken);
        }
        if (relBounds == null) {
            relBounds = new Rect();
        }
        wct.setRelativeBounds(mFragmentInfos.get(fragmentToken).getToken(), relBounds);
    }

    void updateWindowingMode(@NonNull WindowContainerTransaction wct,
                             @NonNull IBinder fragmentToken, @WindowingMode int windowingMode) {
        Log.d(TAG, "updateWindowingMode: 更新窗口模式，token=" + fragmentToken + ", mode="
                + windowingMode);
        if (!mFragmentInfos.containsKey(fragmentToken)) {
            Log.e(TAG, "updateWindowingMode: 未找到 TaskFragment，token=" + fragmentToken);
            throw new IllegalArgumentException(
                    "Can't find an existing TaskFragment with fragmentToken=" + fragmentToken);
        }
        wct.setWindowingMode(mFragmentInfos.get(fragmentToken).getToken(), windowingMode);
    }

    /**
     * Updates the {@link TaskFragmentAnimationParams} for the given TaskFragment based on
     * {@link SplitAttributes}.
     */
    void updateAnimationParams(@NonNull WindowContainerTransaction wct,
                               @NonNull IBinder fragmentToken, @NonNull SplitAttributes splitAttributes) {
        Log.d(TAG, "updateAnimationParams: 根据 SplitAttributes 更新动画参数，token=" + fragmentToken);
        updateAnimationParams(wct, fragmentToken, createAnimationParamsOrDefault(splitAttributes));
    }

    void updateAnimationParams(@NonNull WindowContainerTransaction wct,
                               @NonNull IBinder fragmentToken, @NonNull TaskFragmentAnimationParams animationParams) {
        Log.d(TAG, "updateAnimationParams: 设置动画参数，token=" + fragmentToken + ", params="
                + animationParams);
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_SET_ANIMATION_PARAMS)
                .setAnimationParams(animationParams)
                .build();
        wct.addTaskFragmentOperation(fragmentToken, operation);
    }

    void deleteTaskFragment(@NonNull WindowContainerTransaction wct,
                            @NonNull IBinder fragmentToken) {
        Log.d(TAG, "deleteTaskFragment: 删除 TaskFragment，token=" + fragmentToken);
        wct.deleteTaskFragment(fragmentToken);
    }

    void reorderTaskFragmentToFront(@NonNull WindowContainerTransaction wct,
                                    @NonNull IBinder fragmentToken) {
        Log.d(TAG, "reorderTaskFragmentToFront: 将 TaskFragment 移至最前，token=" + fragmentToken);
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_REORDER_TO_FRONT).build();
        wct.addTaskFragmentOperation(fragmentToken, operation);
    }

    void setTaskFragmentIsolatedNavigation(@NonNull WindowContainerTransaction wct,
                                           @NonNull IBinder fragmentToken, boolean isolatedNav) {
        Log.d(TAG, "setTaskFragmentIsolatedNavigation: 设置隔离导航，token=" + fragmentToken
                + ", isolatedNav=" + isolatedNav);
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_SET_ISOLATED_NAVIGATION).setIsolatedNav(isolatedNav).build();
        wct.addTaskFragmentOperation(fragmentToken, operation);
    }

    void setTaskFragmentDimOnTask(@NonNull WindowContainerTransaction wct,
                                  @NonNull IBinder fragmentToken, boolean dimOnTask) {
        Log.d(TAG, "setTaskFragmentDimOnTask: 设置任务变暗，token=" + fragmentToken
                + ", dimOnTask=" + dimOnTask);
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_SET_DIM_ON_TASK).setDimOnTask(dimOnTask).build();
        wct.addTaskFragmentOperation(fragmentToken, operation);
    }

    void updateTaskFragmentInfo(@NonNull TaskFragmentInfo taskFragmentInfo) {
        Log.d(TAG, "updateTaskFragmentInfo: 更新 TaskFragment 信息，token="
                + taskFragmentInfo.getFragmentToken());
        mFragmentInfos.put(taskFragmentInfo.getFragmentToken(), taskFragmentInfo);
    }

    void removeTaskFragmentInfo(@NonNull TaskFragmentInfo taskFragmentInfo) {
        Log.d(TAG, "removeTaskFragmentInfo: 移除 TaskFragment 信息，token="
                + taskFragmentInfo.getFragmentToken());
        mFragmentInfos.remove(taskFragmentInfo.getFragmentToken());
    }

    @Override
    public void onTransactionReady(@NonNull TaskFragmentTransaction transaction) {
        Log.d(TAG, "onTransactionReady: 事务已就绪，transaction=" + transaction);
        mCallback.onTransactionReady(transaction);
    }

    private static TaskFragmentAnimationParams createAnimationParamsOrDefault(
            @Nullable SplitAttributes splitAttributes) {
        if (splitAttributes == null) {
            Log.d(TAG, "createAnimationParamsOrDefault: splitAttributes 为 null，使用默认动画参数");
            return TaskFragmentAnimationParams.DEFAULT;
        }
        final AnimationBackground animationBackground = splitAttributes.getAnimationBackground();
        if (animationBackground instanceof AnimationBackground.ColorBackground colorBackground) {
            int color = colorBackground.getColor();
            Log.d(TAG, "createAnimationParamsOrDefault: 使用颜色背景动画参数，color=" + color);
            return new TaskFragmentAnimationParams.Builder()
                    .setAnimationBackgroundColor(color)
                    .build();
        } else {
            Log.d(TAG, "createAnimationParamsOrDefault: 非颜色背景，使用默认动画参数");
            return TaskFragmentAnimationParams.DEFAULT;
        }
    }
}