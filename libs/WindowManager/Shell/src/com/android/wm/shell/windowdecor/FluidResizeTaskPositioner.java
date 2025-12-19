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

import static android.view.WindowManager.TRANSIT_CHANGE;

import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;
import com.android.wm.shell.common.DisplayLayout;
import android.util.SparseArray;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.transition.Transitions;

import java.util.function.Supplier;
import android.util.Log;

/**
 * A task positioner that resizes/relocates task contents as it is dragged.
 * Utilizes {@link DragPositioningCallbackUtility} to determine new task bounds.
 *
 * This positioner applies the final bounds after a resize or drag using a shell transition in order
 * to utilize the startAnimation callback to set the final task position and crop. In most cases,
 * the transition will be aborted since the final bounds are usually the same bounds set in the
 * final {@link #onDragPositioningMove} call. In this case, the cropping and positioning would be
 * set by {@link WindowDecoration#relayout} due to the final bounds change; however, it is important
 * that we send the final shell transition since we still utilize the {@link #onTransitionConsumed}
 * callback.
 */
class FluidResizeTaskPositioner implements DragPositioningCallback,
        TaskDragResizer, Transitions.TransitionHandler {
    private static final String TAG = "FluidResizeTaskPositioner";
    private final ShellTaskOrganizer mTaskOrganizer;
    private final Transitions mTransitions;
    private TaskOperations mTaskOperations;
    private SparseArray<CaptionWindowDecoration> mWindowDecorByTaskId;

    private final WindowDecoration mWindowDecoration;
    private final Supplier<SurfaceControl.Transaction> mTransactionSupplier;
    private DisplayController mDisplayController;
    private DragPositioningCallbackUtility.DragStartListener mDragStartListener;
    private final Rect mStableBounds = new Rect();
    private final Rect mTaskBoundsAtDragStart = new Rect();
    private final PointF mRepositionStartPoint = new PointF();
    private final Rect mRepositionTaskBounds = new Rect();
    // If a task move (not resize) finishes with the positions y less than this value, do not
    // finalize the bounds there using WCT#setBounds
    private final int mDisallowedAreaForEndBoundsHeight;
    private boolean mHasDragResized;
    private boolean mIsResizingOrAnimatingResize;
    private int mCtrlType;
    private IBinder mDragResizeEndTransition;
    @Surface.Rotation private int mRotation;

    FluidResizeTaskPositioner(ShellTaskOrganizer taskOrganizer, Transitions transitions,
                              WindowDecoration windowDecoration, DisplayController displayController,
                              int disallowedAreaForEndBoundsHeight, TaskOperations taskOperations, SparseArray<CaptionWindowDecoration> decorationSparseArray) {
        this(taskOrganizer, transitions, windowDecoration, displayController,
                dragStartListener -> {}, SurfaceControl.Transaction::new,
                disallowedAreaForEndBoundsHeight);
        mTaskOperations = taskOperations;
        mWindowDecorByTaskId = decorationSparseArray;
    }

    FluidResizeTaskPositioner(ShellTaskOrganizer taskOrganizer,
                              Transitions transitions,
                              WindowDecoration windowDecoration,
                              DisplayController displayController,
                              DragPositioningCallbackUtility.DragStartListener dragStartListener,
                              Supplier<SurfaceControl.Transaction> supplier,
                              int disallowedAreaForEndBoundsHeight) {
        mTaskOrganizer = taskOrganizer;
        mTransitions = transitions;
        mWindowDecoration = windowDecoration;
        mDisplayController = displayController;
        mDragStartListener = dragStartListener;
        mTransactionSupplier = supplier;
        mDisallowedAreaForEndBoundsHeight = disallowedAreaForEndBoundsHeight;
    }

    @Override
    public Rect onDragPositioningStart(int ctrlType, float x, float y) {
        Log.d(TAG, "onDragPositioningStart called: ctrlType=" + ctrlType +
                ", x=" + x + ", y=" + y);

        mCtrlType = ctrlType;

        mTaskBoundsAtDragStart.set(
                mWindowDecoration.mTaskInfo.configuration.windowConfiguration.getBounds());

        mRepositionStartPoint.set(x, y);
        mDragStartListener.onDragStart(mWindowDecoration.mTaskInfo.taskId);

        if (mCtrlType != CTRL_TYPE_UNDEFINED && !mWindowDecoration.mTaskInfo.isFocused) {
            Log.i(TAG, "Non-undefined ctrlType and task not focused. Reordering task to front.");
            WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.reorder(mWindowDecoration.mTaskInfo.token, true);
            mTaskOrganizer.applyTransaction(wct);
        }

        mRepositionTaskBounds.set(mTaskBoundsAtDragStart);
        Log.d(TAG, "mRepositionTaskBounds initialized to: " + mRepositionTaskBounds);

        int rotation = mWindowDecoration
                .mTaskInfo.configuration.windowConfiguration.getDisplayRotation();

        boolean needUpdateStableBounds = mStableBounds.isEmpty() || mRotation != rotation;

        if (needUpdateStableBounds) {
            mRotation = rotation;

            int displayId = mWindowDecoration.mDisplay.getDisplayId();

            DisplayLayout displayLayout = mDisplayController.getDisplayLayout(displayId);
            if (displayLayout != null) {
                displayLayout.getStableBounds(mStableBounds);
            } else {
                mStableBounds.setEmpty();
            }
        }

        Rect resultBounds = new Rect(mRepositionTaskBounds);
        Log.d(TAG, "onDragPositioningStart returning initial bounds: " + resultBounds);

        return resultBounds;
    }

    @Override
    public Rect onDragPositioningMove(float x, float y) {
        Log.d(TAG, "onDragPositioningMove called: x=" + x + ", y=" + y +
                ", mCtrlType=" + mCtrlType + ", isResizing=" + isResizing());

        final WindowContainerTransaction wct = new WindowContainerTransaction();

        PointF delta = DragPositioningCallbackUtility.calculateDelta(x, y, mRepositionStartPoint);

        if (isResizing() && DragPositioningCallbackUtility.changeBounds(mCtrlType,
                mRepositionTaskBounds, mTaskBoundsAtDragStart, mStableBounds, delta,
                mDisplayController, mWindowDecoration)) {

            Rect targetBounds  = new Rect();
            RunningTaskInfo magicTaskInfo = getMagicTaskBounds(mWindowDecoration.mTaskInfo,
                    mRepositionTaskBounds , targetBounds);
            // The task is being resized, send the |dragResizing| hint to core with the first
            // bounds-change wct.
            if (!mHasDragResized) {
                // This is the first bounds change since drag resize operation started.
                if(magicTaskInfo != null){
                    wct.setDragResizing(magicTaskInfo.token, true /* dragResizing */);
                }
                wct.setDragResizing(mWindowDecoration.mTaskInfo.token, true /* dragResizing */);
                mHasDragResized = true;
            }

            wct.setBounds(mWindowDecoration.mTaskInfo.token, mRepositionTaskBounds);
            if(magicTaskInfo != null){
                wct.setBounds(magicTaskInfo.token, targetBounds);
                WindowDecoration magicWindowDecoration =
                        mWindowDecorByTaskId.get(magicTaskInfo.taskId);
                if (magicWindowDecoration != null && magicWindowDecoration.mTaskSurface != null) {
                    final SurfaceControl.Transaction t = mTransactionSupplier.get();
                    t.setPosition(magicWindowDecoration.mTaskSurface, targetBounds.left, targetBounds.top)
                            .setWindowCrop(magicWindowDecoration.mTaskSurface, targetBounds.width(), targetBounds.height());
                    t.apply();
                }
            }
            mTaskOrganizer.applyTransaction(wct);
            mIsResizingOrAnimatingResize = true;

        } else if (mCtrlType == CTRL_TYPE_UNDEFINED) {
            Log.d(TAG, "CTRL_TYPE_UNDEFINED");

            final SurfaceControl.Transaction t = mTransactionSupplier.get();
            DragPositioningCallbackUtility.setPositionOnDrag(mWindowDecoration,
                    mRepositionTaskBounds, mTaskBoundsAtDragStart, mRepositionStartPoint, t, x, y);
            t.apply();

            Rect targetBounds  = new Rect();
            RunningTaskInfo magicTaskInfo = getMagicTaskBounds(mWindowDecoration.mTaskInfo, mRepositionTaskBounds , targetBounds);
            if(magicTaskInfo != null){
                    final SurfaceControl.Transaction t1 = mTransactionSupplier.get();
                    WindowDecoration magicWindowDecoration =
                            mWindowDecorByTaskId.get(magicTaskInfo.taskId);
                    t1.setPosition(magicWindowDecoration.mTaskSurface, targetBounds.left, targetBounds.top);
                    t1.apply();
            }
        }

        Rect resultBounds = new Rect(mRepositionTaskBounds);
        Log.d(TAG, "onDragPositioningMove returning bounds: " + resultBounds);
        return resultBounds;
    }

    @Override
    public Rect onDragPositioningEnd(float x, float y) {
        Log.d(TAG, "onDragPositioningEnd called: x=" + x + ", y=" + y);
        if (isResizing() && mHasDragResized) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();

            wct.setDragResizing(mWindowDecoration.mTaskInfo.token, false /* dragResizing */);

            PointF delta = DragPositioningCallbackUtility.calculateDelta(x, y, mRepositionStartPoint);

            boolean boundsChanged = DragPositioningCallbackUtility.changeBounds(mCtrlType, mRepositionTaskBounds,
                    mTaskBoundsAtDragStart, mStableBounds, delta, mDisplayController,
                    mWindowDecoration);

            if (boundsChanged) {
                wct.setBounds(mWindowDecoration.mTaskInfo.token, mRepositionTaskBounds);
            }

            updateMagicTaskBounds(wct);
            mDragResizeEndTransition = mTransitions.startTransition(TRANSIT_CHANGE, wct, this);

        } else if (mCtrlType == CTRL_TYPE_UNDEFINED
                && DragPositioningCallbackUtility.isBelowDisallowedArea(
                mDisallowedAreaForEndBoundsHeight, mTaskBoundsAtDragStart, mRepositionStartPoint,
                y)) {

            final WindowContainerTransaction wct = new WindowContainerTransaction();

            DragPositioningCallbackUtility.onDragEnd(mRepositionTaskBounds,
                    mTaskBoundsAtDragStart, mRepositionStartPoint, x, y,
                    mWindowDecoration.calculateValidDragArea());
            wct.setBounds(mWindowDecoration.mTaskInfo.token, mRepositionTaskBounds);
            updateMagicTaskBounds(wct);
            mTransitions.startTransition(TRANSIT_CHANGE, wct, this);

        } else if(mCtrlType == CTRL_TYPE_UNDEFINED
                && !DragPositioningCallbackUtility.isBelowDisallowedArea(
                mDisallowedAreaForEndBoundsHeight, mTaskBoundsAtDragStart, mRepositionStartPoint,
                y)){

            int offsetY = mDisallowedAreaForEndBoundsHeight - mRepositionTaskBounds.top;

            mRepositionTaskBounds.offset(offsetY, 0);

            final WindowContainerTransaction wct = new WindowContainerTransaction();

            wct.setBounds(mWindowDecoration.mTaskInfo.token, mRepositionTaskBounds);

            updateMagicTaskBounds(wct);
            mTransitions.startTransition(TRANSIT_CHANGE, wct, this);
        }

        mTaskBoundsAtDragStart.setEmpty();
        mRepositionStartPoint.set(0, 0);
        mCtrlType = CTRL_TYPE_UNDEFINED;
        mHasDragResized = false;

        Rect resultBounds = new Rect(mRepositionTaskBounds);
        Log.d(TAG, "onDragPositioningEnd 返回边界: " + resultBounds);

        return resultBounds;
    }

    private void updateMagicTaskBounds(WindowContainerTransaction wct) {
        Rect targetBounds  = new Rect();
        RunningTaskInfo magicTaskInfo = getMagicTaskBounds(mWindowDecoration.mTaskInfo,
                mRepositionTaskBounds , targetBounds);
        if(magicTaskInfo != null){
            wct.setDragResizing(magicTaskInfo.token, false /* dragResizing */);
            wct.setBounds(magicTaskInfo.token, targetBounds);
            WindowDecoration magicWindowDecoration =
                    mWindowDecorByTaskId.get(magicTaskInfo.taskId);
            if (magicWindowDecoration != null && magicWindowDecoration.mTaskSurface != null) {
                final SurfaceControl.Transaction t = mTransactionSupplier.get();
                t.setPosition(magicWindowDecoration.mTaskSurface, targetBounds.left, targetBounds.top)
                        .setWindowCrop(magicWindowDecoration.mTaskSurface, targetBounds.width(), targetBounds.height());
                t.apply();
            }
        }
    }

    private boolean isResizing() {
        return (mCtrlType & CTRL_TYPE_TOP) != 0 || (mCtrlType & CTRL_TYPE_BOTTOM) != 0
                || (mCtrlType & CTRL_TYPE_LEFT) != 0 || (mCtrlType & CTRL_TYPE_RIGHT) != 0;
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                                  @NonNull SurfaceControl.Transaction startTransaction,
                                  @NonNull SurfaceControl.Transaction finishTransaction,
                                  @NonNull Transitions.TransitionFinishCallback finishCallback) {
        for (TransitionInfo.Change change: info.getChanges()) {
            final SurfaceControl sc = change.getLeash();
            final Rect endBounds = change.getEndAbsBounds();
            final Point endPosition = change.getEndRelOffset();
            startTransaction.setWindowCrop(sc, endBounds.width(), endBounds.height())
                    .setPosition(sc,  endPosition.x, endPosition.y);
            finishTransaction.setWindowCrop(sc, endBounds.width(), endBounds.height())
                    .setPosition(sc,  endPosition.x, endPosition.y);

            // Log.w(TAG,"startAnimation endPosition.x "+endPosition.x + ",endPosition.y: "+endPosition.y);   
            // Log.w(TAG,"startAnimation endBounds.left "+endBounds.left + ",endBounds.top: "+endBounds.top);       
        }

        startTransaction.apply();
        if (transition.equals(mDragResizeEndTransition)) {
            mIsResizingOrAnimatingResize = false;
            mDragResizeEndTransition = null;
        }
        finishCallback.onTransitionFinished(null);
        return true;
    }

    private RunningTaskInfo getMagicTaskBounds(RunningTaskInfo taskInfo, Rect sourceBounds,
                                               Rect targetBounds){
        if(mWindowDecoration.mTaskInfo.topActivity != null){
            RunningTaskInfo magicTaskInfo = mTaskOrganizer.getRunningTaskInfo(taskInfo.taskId,
                    taskInfo.topActivity.getPackageName(), taskInfo.magicWindowType);
            if(magicTaskInfo != null){
                targetBounds.set(
                        magicTaskInfo.configuration.windowConfiguration.getBounds());
                int width = targetBounds.width();
                int height = sourceBounds.height();
                if( mWindowDecoration.mTaskInfo.magicWindowType  == 1) {
                    targetBounds.left = sourceBounds.right;
                    targetBounds.right = targetBounds.left + width;
                    targetBounds.top = sourceBounds.top;
                    targetBounds.bottom = targetBounds.top + height;
                } else {
                    targetBounds.right = sourceBounds.left;
                    targetBounds.left = targetBounds.right - width;
                    targetBounds.top = sourceBounds.top;
                    targetBounds.bottom = targetBounds.top + height;
                }
                return magicTaskInfo;
            }
        }
        return null;
    }

    /**
     * We should never reach this as this handler's transitions are only started from shell
     * explicitly.
     */
    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                                                    @NonNull TransitionRequestInfo request) {
        return null;
    }

    @Override
    public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
                                     @Nullable SurfaceControl.Transaction finishTransaction) {
        if (transition.equals(mDragResizeEndTransition)) {
            mIsResizingOrAnimatingResize = false;
            mDragResizeEndTransition = null;
        }
    }

    @Override
    public boolean isResizingOrAnimating() {
        return mIsResizingOrAnimatingResize;
    }
}
