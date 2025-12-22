/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.windowdecor;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.os.Handler;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.freeform.FreeformTaskTransitionStarter;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.transition.Transitions;
import android.util.Log;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.os.SystemProperties;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import com.android.internal.util.CompatibleConfig;
import android.text.TextUtils;
import android.widget.Toast;

/**
 * View model for the window decoration with a caption and shadows. Works with
 * {@link CaptionWindowDecoration}.
 */
public class CaptionWindowDecorViewModel implements WindowDecorViewModel {
    private static final String TAG = "CaptionWindowDecorViewModel";
    private final ShellTaskOrganizer mTaskOrganizer;
    private final Context mContext;
    private final Handler mMainHandler;
    private final Choreographer mMainChoreographer;
    private final DisplayController mDisplayController;
    private final SyncTransactionQueue mSyncQueue;
    private final Transitions mTransitions;
    private TaskOperations mTaskOperations;
    private boolean mDragging = false;
    private boolean mLastSavedStateIsMaximized;
    private int mRunningTaskId;

    private final SparseArray<CaptionWindowDecoration> mWindowDecorByTaskId = new SparseArray<>();
    private final SparseArray<Boolean> mLastSavedStateIsMaximizedByTaskId = new SparseArray<>();
    private final SparseArray<Boolean> mIsFullscreenEnabledByTaskId = new SparseArray<>();
    private class UpdateWindowDecorationRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "UpdateWindowDecorationRunnable start");
            updateWindowDecoration();
        }
    }
    private final UpdateWindowDecorationRunnable mUpdateWindowDecorationRunnable = new UpdateWindowDecorationRunnable();

    private final BroadcastReceiver mFullscreenEnabledDisabled  = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.fde.fullscreen.ENABLE_OR_DISABLE".equals(action)) {
                final int mode = intent.getIntExtra("mode", 0);
                RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mRunningTaskId);
                switch (mode){
                    case 0:
                        Log.d(TAG,"onReceive 0 disable fullscreen mRunningTaskId: " + mRunningTaskId);
                        if(taskInfo != null){
                            mIsFullscreenEnabledByTaskId.put(mRunningTaskId, false);
                            mLastSavedStateIsMaximized = mLastSavedStateIsMaximizedByTaskId.get(mRunningTaskId, false);
                            Log.d(TAG, "mLastSavedStateIsMaximized: " + mLastSavedStateIsMaximized);
                            if(!mLastSavedStateIsMaximized){
                                mTaskOperations.maximizeTask(taskInfo);
                            }else{
                                mMainHandler.removeCallbacks(mUpdateWindowDecorationRunnable);
                                mMainHandler.postDelayed(mUpdateWindowDecorationRunnable, 500);
                            }
                        }
                        break;
                    case 1:
                        Log.d(TAG,"onReceive 1 enable fullscreen mRunningTaskId: " + mRunningTaskId);
                        if(taskInfo != null){
                            if(!mIsFullscreenEnabledByTaskId.get(mRunningTaskId, false)){
                                mIsFullscreenEnabledByTaskId.put(mRunningTaskId, true);
                                mLastSavedStateIsMaximized = mTaskOperations.isTaskMaximized(taskInfo);
                                mLastSavedStateIsMaximizedByTaskId.put(mRunningTaskId, mLastSavedStateIsMaximized);
                            }
                            Log.d(TAG, "mLastSavedStateIsMaximized: " + mLastSavedStateIsMaximized);
                            if(!mTaskOperations.isTaskMaximized(taskInfo)){
                                mTaskOperations.maximizeTask(taskInfo);
                            }
                            mMainHandler.removeCallbacks(mUpdateWindowDecorationRunnable);
                            mMainHandler.postDelayed(mUpdateWindowDecorationRunnable, 500);
                        }
                        break;
                    case 2:
                        Log.d(TAG,"onReceive 2 mRunningTaskId: " + mRunningTaskId);
                        if(taskInfo != null){
                            mMainHandler.removeCallbacks(mUpdateWindowDecorationRunnable);
                            mMainHandler.postDelayed(mUpdateWindowDecorationRunnable, 500);
                        }
                        break;
                }
            }
        }
    };

    private void updateWindowDecoration(){
        Log.d(TAG, "updateWindowDecoration mRunningTaskId: " + mRunningTaskId);
        RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mRunningTaskId);
        if(taskInfo == null) return;
        final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(mRunningTaskId);
        if (decoration == null) return;
        decoration.relayout(taskInfo);
        setupCaptionColor(taskInfo, decoration);
        setCaptionLable(decoration);
    }

    public CaptionWindowDecorViewModel(
            Context context,
            Handler mainHandler,
            Choreographer mainChoreographer,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            SyncTransactionQueue syncQueue,
            Transitions transitions) {
        mContext = context;
        mMainHandler = mainHandler;
        mMainChoreographer = mainChoreographer;
        mTaskOrganizer = taskOrganizer;
        mDisplayController = displayController;
        mSyncQueue = syncQueue;
        mTransitions = transitions;
        if (!Transitions.ENABLE_SHELL_TRANSITIONS) {
            mTaskOperations = new TaskOperations(null, mContext, mSyncQueue);
        }
        IntentFilter statusFilter = new IntentFilter();
        statusFilter.addAction("com.fde.fullscreen.ENABLE_OR_DISABLE");
        mContext.registerReceiver(mFullscreenEnabledDisabled, statusFilter, Context.RECEIVER_EXPORTED);
    }

    @Override
    public void setFreeformTaskTransitionStarter(FreeformTaskTransitionStarter transitionStarter) {
        mTaskOperations = new TaskOperations(transitionStarter, mContext, mSyncQueue);
    }

    @Override
    public void setSplitScreenController(SplitScreenController splitScreenController) {}

    @Override
    public boolean onTaskOpening(
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        if(taskInfo.isFocused){
            mRunningTaskId = taskInfo.taskId;
            Log.d(TAG,"onTaskOpening mRunningTaskId: " + mRunningTaskId);
            if(taskInfo.topActivity != null && taskInfo.topActivity.getPackageName() != null) {
                String packageName = taskInfo.topActivity.getPackageName();
                String selection = "PACKAGE_NAME = ? AND KEY_CODE = ? AND ACTIVITY_NAME = ?";
                String[] selectionArgsWithoutActivity = {packageName,"forcedMaximizeStart", ""};
                String resultStrWithoutActivity = CompatibleConfig.queryStringValueData(mContext, selection, selectionArgsWithoutActivity);
                Log.d(TAG,"forcedMaximizeStart resultStrWithoutActivity: " + resultStrWithoutActivity);
                boolean forcedMaximizeStart = false;
                if(TextUtils.equals(resultStrWithoutActivity, "true")){
                    forcedMaximizeStart = true;
                    Log.d(TAG,"onTaskOpening packageName: " + packageName + ", forcedMaximizeStart: " + forcedMaximizeStart);
                }else{
                    String activityName = extractActivityName(taskInfo.topActivity.getClassName());
                    String[] selectionArgs = {packageName,"forcedMaximizeStart", activityName};
                    String resultStr = CompatibleConfig.queryStringValueData(mContext, selection, selectionArgs);
                    Log.d(TAG,"forcedMaximizeStart resultStr: " + resultStr);
                    if(TextUtils.equals(resultStr, "true")){
                        forcedMaximizeStart = true;
                        Log.d(TAG,"onTaskOpening className: " + taskInfo.topActivity.getClassName() + ", forcedMaximizeStart: " + forcedMaximizeStart);
                    }
                }
                if(!mTaskOperations.isTaskMaximized(taskInfo) && forcedMaximizeStart){
                    Log.d(TAG, "onTaskOpening forcedMaximizeStart for " + taskInfo.topActivity.getClassName());
                    mTaskOperations.maximizeTask(taskInfo);
                }

            }
        }
        if (!shouldShowWindowDecor(taskInfo)) return false;
        createWindowDecoration(taskInfo, taskSurface, startT, finishT);
        return true;
    }

    private String extractActivityName(String fullClassName) {
        if (fullClassName == null || fullClassName.isEmpty()) {
            return "";
        }
        int lastDotIndex = fullClassName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return fullClassName;
        }
        return fullClassName.substring(lastDotIndex + 1);
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        Log.d(TAG,"onTaskInfoChanged taskInfo.taskId: " + taskInfo.taskId + ", taskInfo.isFocused: " + taskInfo.isFocused);
        Log.d(TAG,"onTaskInfoChanged taskInfo: " + taskInfo );
        if(taskInfo.isFocused){
            mRunningTaskId = taskInfo.taskId;
            Log.d(TAG,"onTaskInfoChanged mRunningTaskId: " + mRunningTaskId);
            mMainHandler.removeCallbacks(mUpdateWindowDecorationRunnable);
            mMainHandler.postDelayed(mUpdateWindowDecorationRunnable, 500);
        }
        final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);

        if (decoration == null) return;

        decoration.relayout(taskInfo);
        setupCaptionColor(taskInfo, decoration);
        setCaptionLable(decoration);
    }

    @Override
    public void onTaskChanging(
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        Log.d(TAG,"onTaskChanging taskInfo.taskId: " + taskInfo.taskId + ", taskInfo.isFocused: " + taskInfo.isFocused);
        if(taskInfo.isFocused){
            mRunningTaskId = taskInfo.taskId;
            Log.d(TAG,"onTaskChanging mRunningTaskId: " + mRunningTaskId);
            mMainHandler.removeCallbacks(mUpdateWindowDecorationRunnable);
            mMainHandler.postDelayed(mUpdateWindowDecorationRunnable, 500);
        }
        final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);

        if (!shouldShowWindowDecor(taskInfo)) {
            if (decoration != null) {
                destroyWindowDecoration(taskInfo);
            }
            return;
        }

        if (decoration == null) {
            createWindowDecoration(taskInfo, taskSurface, startT, finishT);
        } else {
            decoration.relayout(taskInfo, startT, finishT, false /* applyStartTransactionOnDraw */,
                    false /* setTaskCropAndPosition */);
        }
    }

    @Override
    public void onTaskClosing(
            RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
            Log.d(TAG,"onTaskClosing taskInfo.taskId: " + taskInfo.taskId);
        mLastSavedStateIsMaximizedByTaskId.delete(taskInfo.taskId);
        mIsFullscreenEnabledByTaskId.delete(taskInfo.taskId);
        final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (decoration == null) return;

        decoration.relayout(taskInfo, startT, finishT, false /* applyStartTransactionOnDraw */,
                false /* setTaskCropAndPosition */);
    }

    @Override
    public void destroyWindowDecoration(RunningTaskInfo taskInfo) {
        final CaptionWindowDecoration decoration =
                mWindowDecorByTaskId.removeReturnOld(taskInfo.taskId);
        if (decoration == null) return;

        decoration.close();
    }

    private void setupCaptionColor(RunningTaskInfo taskInfo, CaptionWindowDecoration decoration) {
        final int statusBarColor = taskInfo.taskDescription.getStatusBarColor();
        decoration.setCaptionColor(statusBarColor, taskInfo);
    }

    private void setCaptionLable(CaptionWindowDecoration decoration){
        decoration.setCaptionLable();
    }

    private boolean shouldShowWindowDecor(RunningTaskInfo taskInfo) {
        return taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM
                || (taskInfo.getActivityType() == ACTIVITY_TYPE_STANDARD
                && taskInfo.configuration.windowConfiguration.getDisplayWindowingMode()
                == WINDOWING_MODE_FREEFORM);
    }

    private void createWindowDecoration(
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final CaptionWindowDecoration oldDecoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (oldDecoration != null) {
            // close the old decoration if it exists to avoid two window decorations being added
            oldDecoration.close();
        }
        final CaptionWindowDecoration windowDecoration =
                new CaptionWindowDecoration(
                        mContext,
                        mDisplayController,
                        mTaskOrganizer,
                        taskInfo,
                        taskSurface,
                        mMainHandler,
                        mMainChoreographer,
                        mSyncQueue);
        mWindowDecorByTaskId.put(taskInfo.taskId, windowDecoration);

        final FluidResizeTaskPositioner taskPositioner =
                new FluidResizeTaskPositioner(mTaskOrganizer, mTransitions, windowDecoration,
                        mDisplayController, 0 /* disallowedAreaForEndBoundsHeight */, mTaskOperations, mWindowDecorByTaskId);
        final CaptionTouchEventListener touchEventListener =
                new CaptionTouchEventListener(taskInfo, taskPositioner);
        windowDecoration.setCaptionListeners(touchEventListener, touchEventListener);
        windowDecoration.setDragPositioningCallback(taskPositioner);
        windowDecoration.setDragDetector(touchEventListener.mDragDetector);
        windowDecoration.setTaskDragResizer(taskPositioner);
        windowDecoration.relayout(taskInfo, startT, finishT,
                false /* applyStartTransactionOnDraw */, false /* setTaskCropAndPosition */);
        setupCaptionColor(taskInfo, windowDecoration);
    }


    private class CaptionTouchEventListener implements
            View.OnClickListener, View.OnTouchListener, DragDetector.MotionEventHandler {

        private final int mTaskId;
        private final WindowContainerToken mTaskToken;
        private final DragPositioningCallback mDragPositioningCallback;
        private final DragDetector mDragDetector;
        private final int mDisplayId;

        private int mDragPointerId = -1;
        private boolean mIsDragging;

        long[] mHits = new long[2];
        public void doubleClick() {
            System.arraycopy(mHits, 1, mHits, 0, mHits.length - 1);
            mHits[mHits.length - 1] = SystemClock.uptimeMillis();
            if (mHits[0] >= (SystemClock.uptimeMillis() - 500)) {
                mHits[mHits.length - 1] = 0;
                RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
                mTaskOperations.maximizeTask(taskInfo);
            }
        }

        private CaptionTouchEventListener(
                RunningTaskInfo taskInfo,
                DragPositioningCallback dragPositioningCallback) {
            mTaskId = taskInfo.taskId;
            mTaskToken = taskInfo.token;
            mDragPositioningCallback = dragPositioningCallback;
            mDragDetector = new DragDetector(this);
            mDisplayId = taskInfo.displayId;
        }

        @Override
        public void onClick(View v) {
            final int id = v.getId();
            if (id == R.id.close_window) {
                mTaskOperations.closeTask(mTaskToken);
                RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
                if( taskInfo.topActivity != null && taskInfo.magicWindowType == 1){
                    RunningTaskInfo magicTaskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId,
                            taskInfo.topActivity.getPackageName(), taskInfo.magicWindowType);
                    if(magicTaskInfo != null){
                        mTaskOperations.closeTask(magicTaskInfo.token);
                    }
                }
            } else if (id == R.id.back_button) {
                Log.d(TAG, "onClick back_button");
                mTaskOperations.injectBackKey(mDisplayId);
            } else if (id == R.id.fullscreen_window) {
                Log.d(TAG, "onClick fullscreen_window");
                mMainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mTaskOperations.injectKey(mDisplayId, KeyEvent.KEYCODE_F11);
                    }
                },80);
            }else if (id == R.id.minimize_window) {
                mTaskOperations.minimizeTask(mTaskToken);
                RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
                if( taskInfo.topActivity != null && taskInfo.magicWindowType != 0){
                    RunningTaskInfo magicTaskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId,
                            taskInfo.topActivity.getPackageName(), taskInfo.magicWindowType);
                    if(magicTaskInfo != null){
                        mTaskOperations.minimizeTask(magicTaskInfo.token);
                    }
                }
            } else if (id == R.id.maximize_window) {
                Log.d(TAG, "onClick maximize_window");
                RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
                if(taskInfo.topActivity != null && taskInfo.topActivity.getPackageName() != null) {
                    String packageName = taskInfo.topActivity.getPackageName();
                    String selection = "PACKAGE_NAME = ? AND KEY_CODE = ? AND ACTIVITY_NAME = ?";
                    String[] selectionArgsWithoutActivity = {packageName,"forcedMaximizeStart", ""};
                    String resultStrWithoutActivity = CompatibleConfig.queryStringValueData(mContext, selection, selectionArgsWithoutActivity);
                    Log.d(TAG,"forcedMaximizeStart resultStrWithoutActivity: " + resultStrWithoutActivity);
                    boolean forcedMaximizeStart = false;
                    if(TextUtils.equals(resultStrWithoutActivity, "true")){
                        forcedMaximizeStart = true;
                        Log.d(TAG,"onClick maximize packageName: " + packageName + ", forcedMaximizeStart: " + forcedMaximizeStart);
                    }else{
                        String activityName = extractActivityName(taskInfo.topActivity.getClassName());
                        String[] selectionArgs = {packageName,"forcedMaximizeStart", activityName};
                        String resultStr = CompatibleConfig.queryStringValueData(mContext, selection, selectionArgs);
                        Log.d(TAG,"forcedMaximizeStart resultStr: " + resultStr);
                        if(TextUtils.equals(resultStr, "true")){
                            forcedMaximizeStart = true;
                            Log.d(TAG,"onClick maximize className: " + taskInfo.topActivity.getClassName() + ", forcedMaximizeStart: " + forcedMaximizeStart);
                        }
                    }
                    if(mTaskOperations.isTaskMaximized(taskInfo) && forcedMaximizeStart){
                        Log.d(TAG, "onClick maximize for " + taskInfo.topActivity.getClassName());
                        Toast.makeText( mContext, R.string.forced_maximized, Toast.LENGTH_SHORT).show();
                        return;
                    }

                }
                mTaskOperations.maximizeTask(taskInfo);
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if(v.getId() == R.id.maximize_window || v.getId() == R.id.caption) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    final RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
                    if (!taskInfo.isFocused) {
                        final WindowContainerTransaction wct = new WindowContainerTransaction();
                        wct.reorder(mTaskToken, true /* onTop */);
                        mSyncQueue.queue(wct);
                    }
                }
            }

            if (v.getId() != R.id.caption
                    && v.getId() != R.id.fullscreen_window) {
                return false;
            }
            if (e.getAction() == MotionEvent.ACTION_UP) {
                if(!mDragging){
                    doubleClick();
                }
                mDragging = false;
            }
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                mDragging = true;
            }
            return mDragDetector.onMotionEvent(e);
        }

        /**
         * @param e {@link MotionEvent} to process
         * @return {@code true} if a drag is happening; or {@code false} if it is not
         */
        @Override
        public boolean handleMotionEvent(@Nullable View v, MotionEvent e) {
            final RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
            if (taskInfo != null && taskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
                return false;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    mDragPointerId = e.getPointerId(0);
                    mDragPositioningCallback.onDragPositioningStart(
                            0 /* ctrlType */, e.getRawX(0), e.getRawY(0));
                    mIsDragging = false;
                    return false;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (e.findPointerIndex(mDragPointerId) == -1) {
                        mDragPointerId = e.getPointerId(0);
                    }
                    final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(mTaskId);
                    // If a decor's resize drag zone is active, don't also try to reposition it.
                    if (decoration.isHandlingDragResize()) break;
                    final int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    mDragPositioningCallback.onDragPositioningMove(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    mIsDragging = true;
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (e.findPointerIndex(mDragPointerId) == -1) {
                        mDragPointerId = e.getPointerId(0);
                    }
                    final int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    mDragPositioningCallback.onDragPositioningEnd(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    final boolean wasDragging = mIsDragging;
                    mIsDragging = false;
                    return wasDragging;
                }
            }
            return true;
        }
    }
}