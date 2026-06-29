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

package com.android.wm.shell.freeform;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_FREEFORM;

import android.graphics.Rect;
import android.app.ActivityManager.RunningTaskInfo;
import android.util.Log;
import android.view.SurfaceControl;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.desktopmode.DesktopModeStatus;
import com.android.wm.shell.desktopmode.DesktopModeTaskRepository;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.WindowDecorViewModel;

import java.io.PrintWriter;
import java.util.Optional;
import android.util.SparseArray;

/**
 * {@link ShellTaskOrganizer.TaskListener} for {@link
 * ShellTaskOrganizer#TASK_LISTENER_TYPE_FREEFORM}.
 */
public class FreeformTaskListener implements ShellTaskOrganizer.TaskListener,
        ShellTaskOrganizer.FocusListener {
    private static final String TAG = "FreeformTaskListener";

    private static String formatTaskForLog(RunningTaskInfo taskInfo) {
        if (taskInfo == null) {
            return "taskInfo=null";
        }
        final String topActivity = taskInfo.topActivity != null
                ? taskInfo.topActivity.flattenToShortString() : "null";
        return "taskId=" + taskInfo.taskId
                + ", windowingMode=" + taskInfo.getWindowingMode()
                + ", visible=" + taskInfo.isVisible
                + ", focused=" + taskInfo.isFocused
                + ", isResizeable=" + taskInfo.isResizeable
                + ", positionInParent=" + taskInfo.positionInParent
                + ", bounds=" + taskInfo.configuration.windowConfiguration.getBounds()
                + ", topActivity=" + topActivity;
    }

    private final ShellTaskOrganizer mShellTaskOrganizer;
    private final Optional<DesktopModeTaskRepository> mDesktopModeTaskRepository;
    private final WindowDecorViewModel mWindowDecorationViewModel;

    private final SparseArray<State> mTasks = new SparseArray<>();

    private static class State {
        RunningTaskInfo mTaskInfo;
        SurfaceControl mLeash;
    }

    public FreeformTaskListener(
            ShellInit shellInit,
            ShellTaskOrganizer shellTaskOrganizer,
            Optional<DesktopModeTaskRepository> desktopModeTaskRepository,
            WindowDecorViewModel windowDecorationViewModel) {
        mShellTaskOrganizer = shellTaskOrganizer;
        mWindowDecorationViewModel = windowDecorationViewModel;
        mDesktopModeTaskRepository = desktopModeTaskRepository;
        if (shellInit != null) {
            shellInit.addInitCallback(this::onInit, this);
        }
    }

    private void onInit() {
        mShellTaskOrganizer.addListenerForType(this, TASK_LISTENER_TYPE_FREEFORM);
        if (DesktopModeStatus.isEnabled()) {
            mShellTaskOrganizer.addFocusListener(this);
        }
    }

    private void forceTaskPositionAndCrop(
            RunningTaskInfo taskInfo, SurfaceControl leash, SurfaceControl.Transaction t) {
        if (taskInfo == null || leash == null) {
            return;
        }
        // 最大化/全屏时跳过，由 WindowDecoration.relayout 的 isFullscreen 分支处理
        if (taskInfo.getWindowingMode() != WINDOWING_MODE_FREEFORM) {
            return;
        }
        final Rect bounds = taskInfo.configuration.windowConfiguration.getBounds();
        if (bounds == null || bounds.isEmpty()) {
            Log.d(TAG, "[窗口装饰兜底] forceTaskPositionAndCrop 跳过，原因=bounds 无效，"
                    + formatTaskForLog(taskInfo));
            return;
        }
        final int width = bounds.width();
        final int height = bounds.height();
        t.setWindowCrop(leash, width, height)
                .setPosition(leash, taskInfo.positionInParent.x, taskInfo.positionInParent.y);
    }

    @Override
    public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        if (mTasks.get(taskInfo.taskId) != null) {
            throw new IllegalStateException("Task appeared more than once: #" + taskInfo.taskId);
        }
        final State state = new State();
        state.mTaskInfo = taskInfo;
        state.mLeash = leash;
        mTasks.put(taskInfo.taskId, state);
        if (!Transitions.ENABLE_SHELL_TRANSITIONS) {
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            mWindowDecorationViewModel.onTaskOpening(taskInfo, leash, t, t);
            t.apply();
        } else if (leash != null) {
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            forceTaskPositionAndCrop(taskInfo, leash, t);
            t.apply();
        }

        if (DesktopModeStatus.isEnabled()) {
            mDesktopModeTaskRepository.ifPresent(repository -> {
                repository.addOrMoveFreeformTaskToTop(taskInfo.taskId);
                if (taskInfo.isVisible) {
                    if (repository.addActiveTask(taskInfo.displayId, taskInfo.taskId)) {
                        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                                "Adding active freeform task: #%d", taskInfo.taskId);
                    }
                    repository.updateVisibleFreeformTasks(taskInfo.displayId, taskInfo.taskId,
                            true);
                }
            });
        }
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Freeform Task Vanished: #%d",
                taskInfo.taskId);

        mTasks.remove(taskInfo.taskId);

        if (DesktopModeStatus.isEnabled()) {
            mDesktopModeTaskRepository.ifPresent(repository -> {
                repository.removeFreeformTask(taskInfo.taskId);
                if (repository.removeActiveTask(taskInfo.taskId)) {
                    ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                            "Removing active freeform task: #%d", taskInfo.taskId);
                }
                repository.updateVisibleFreeformTasks(taskInfo.displayId, taskInfo.taskId, false);
            });
        }

        if (!Transitions.ENABLE_SHELL_TRANSITIONS) {
            mWindowDecorationViewModel.destroyWindowDecoration(taskInfo);
        }
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        final State state = mTasks.get(taskInfo.taskId);

        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Freeform Task Info Changed: #%d",
                taskInfo.taskId);
        if (state == null) {
            mWindowDecorationViewModel.onTaskInfoChanged(taskInfo);
        } else {
            state.mTaskInfo = taskInfo;
            final boolean needFallbackCreate = Transitions.ENABLE_SHELL_TRANSITIONS
                    && taskInfo.isVisible
                    && state.mLeash != null
                    && !mWindowDecorationViewModel.hasWindowDecor(taskInfo.taskId);
            if (needFallbackCreate) {
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                mWindowDecorationViewModel.onTaskOpening(taskInfo, state.mLeash, t, t);
                mWindowDecorationViewModel.onTaskChanging(taskInfo, state.mLeash, t, t);
                t.apply();
            } else {
                mWindowDecorationViewModel.onTaskInfoChanged(taskInfo);
                if (Transitions.ENABLE_SHELL_TRANSITIONS
                        && taskInfo.isVisible
                        && state.mLeash != null
                        && mWindowDecorationViewModel.hasWindowDecor(taskInfo.taskId)) {
                    SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                    mWindowDecorationViewModel.onTaskChanging(taskInfo, state.mLeash, t, t);
                    t.apply();
                }
            }
        }
        if (DesktopModeStatus.isEnabled()) {
            mDesktopModeTaskRepository.ifPresent(repository -> {
                if (taskInfo.isVisible) {
                    if (repository.addActiveTask(taskInfo.displayId, taskInfo.taskId)) {
                        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                                "Adding active freeform task: #%d", taskInfo.taskId);
                    }
                }
                repository.updateVisibleFreeformTasks(taskInfo.displayId, taskInfo.taskId,
                        taskInfo.isVisible);
            });
        }
    }

    @Override
    public void onFocusTaskChanged(RunningTaskInfo taskInfo) {
        if (taskInfo.getWindowingMode() != WINDOWING_MODE_FREEFORM) {
            return;
        }
                + formatTaskForLog(taskInfo));
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG,
                "Freeform Task Focus Changed: #%d focused=%b",
                taskInfo.taskId, taskInfo.isFocused);
        if (DesktopModeStatus.isEnabled() && taskInfo.isFocused) {
            mDesktopModeTaskRepository.ifPresent(repository -> {
                repository.addOrMoveFreeformTaskToTop(taskInfo.taskId);
            });
        }
    }

    @Override
    public void attachChildSurfaceToTask(int taskId, SurfaceControl.Builder b) {
        b.setParent(findTaskSurface(taskId));
    }

    @Override
    public void reparentChildSurfaceToTask(int taskId, SurfaceControl sc,
            SurfaceControl.Transaction t) {
        t.reparent(sc, findTaskSurface(taskId));
    }

    private SurfaceControl findTaskSurface(int taskId) {
        if (!mTasks.contains(taskId)) {
            throw new IllegalArgumentException("There is no surface for taskId=" + taskId);
        }
        return mTasks.get(taskId).mLeash;
    }

    @Override
    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + this);
        pw.println(innerPrefix + mTasks.size() + " tasks");
    }

    @Override
    public String toString() {
        return TAG;
    }
}
