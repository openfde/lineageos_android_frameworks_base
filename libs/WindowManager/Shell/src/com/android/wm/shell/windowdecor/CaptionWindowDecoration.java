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

import android.app.ActivityManager.RunningTaskInfo;
import android.app.WindowConfiguration;
import android.app.WindowConfiguration.WindowingMode;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Handler;
import android.os.SystemProperties;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewConfiguration;
import android.window.WindowContainerTransaction;
import android.widget.TextView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ImageButton;


import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;
import android.util.Log;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.ViewParent;
import android.view.ViewRootImpl;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.internal.util.CompatibleConfig;
import android.text.TextUtils;
import java.util.Set;

/**
 * Defines visuals and behaviors of a window decoration of a caption bar and shadows. It works with
 * {@link CaptionWindowDecorViewModel}. The caption bar contains a back button, minimize button,
 * maximize button and close button.
 */
public class CaptionWindowDecoration extends WindowDecoration<WindowDecorLinearLayout> {
    private static final String TAG = "CaptionWindowDecoration";
    private final Handler mHandler;
    private final Choreographer mChoreographer;
    private final SyncTransactionQueue mSyncQueue;

    private View.OnClickListener mOnCaptionButtonClickListener;
    private View.OnTouchListener mOnCaptionTouchListener;
    private DragPositioningCallback mDragPositioningCallback;
    private DragResizeInputListener mDragResizeListener;
    private DragDetector mDragDetector;
    private String mTopActivity;
    private boolean mDragResizeable = true;
    private static final Set<String> SPECIAL_PACKAGES = Set.of(
            "com.android.packageinstaller",
            "com.fde.fde_linux_app_launcher"
    );
    private static final String RESOLVER_ACTIVITY = "com.android.internal.app.ResolverActivity";
    private RelayoutParams mRelayoutParams = new RelayoutParams();
    private final RelayoutResult<WindowDecorLinearLayout> mResult =
            new RelayoutResult<>();

    CaptionWindowDecoration(
            Context context,
            DisplayController displayController,
            ShellTaskOrganizer taskOrganizer,
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            Handler handler,
            Choreographer choreographer,
            SyncTransactionQueue syncQueue,
            CaptionWindowDecorViewModel viewmodel) {
        super(context, displayController, taskOrganizer, taskInfo, taskSurface,
                taskInfo.getConfiguration(), viewmodel);

        mHandler = handler;
        mChoreographer = choreographer;
        mSyncQueue = syncQueue;
    }

    void setCaptionListeners(
            View.OnClickListener onCaptionButtonClickListener,
            View.OnTouchListener onCaptionTouchListener) {
        mOnCaptionButtonClickListener = onCaptionButtonClickListener;
        mOnCaptionTouchListener = onCaptionTouchListener;
    }

    void setDragPositioningCallback(DragPositioningCallback dragPositioningCallback) {
        mDragPositioningCallback = dragPositioningCallback;
    }

    @Override
    Rect calculateValidDragArea() {
        final int leftButtonsWidth = loadDimensionPixelSize(mContext.getResources(),
                R.dimen.caption_left_buttons_width);

        // On a smaller screen, don't require as much empty space on screen, as offscreen
        // drags will be restricted too much.
        final int requiredEmptySpaceId = mDisplayController.getDisplayContext(mTaskInfo.displayId)
                .getResources().getConfiguration().smallestScreenWidthDp >= 600
                ? R.dimen.freeform_required_visible_empty_space_in_header :
                R.dimen.small_screen_required_visible_empty_space_in_header;
        final int requiredEmptySpace = loadDimensionPixelSize(mContext.getResources(),
                requiredEmptySpaceId);

        final int rightButtonsWidth = loadDimensionPixelSize(mContext.getResources(),
                R.dimen.caption_right_buttons_width);
        final int taskWidth = mTaskInfo.configuration.windowConfiguration.getBounds().width();
        final DisplayLayout layout = mDisplayController.getDisplayLayout(mTaskInfo.displayId);
        final int displayWidth = layout.width();
        final Rect stableBounds = new Rect();
        layout.getStableBounds(stableBounds);
        return new Rect(
                determineMinX(leftButtonsWidth, rightButtonsWidth, requiredEmptySpace,
                        taskWidth),
                stableBounds.top,
                determineMaxX(leftButtonsWidth, rightButtonsWidth, requiredEmptySpace, taskWidth,
                        displayWidth),
                determineMaxY(requiredEmptySpace, stableBounds));
    }


    /**
     * Determine the lowest x coordinate of a freeform task. Used for restricting drag inputs.
     */
    private int determineMinX(int leftButtonsWidth, int rightButtonsWidth, int requiredEmptySpace,
            int taskWidth) {
        // Do not let apps with < 48dp empty header space go off the left edge at all.
        if (leftButtonsWidth + rightButtonsWidth + requiredEmptySpace > taskWidth) {
            return 0;
        }
        return -taskWidth + requiredEmptySpace + rightButtonsWidth;
    }

    /**
     * Determine the highest x coordinate of a freeform task. Used for restricting drag inputs.
     */
    private int determineMaxX(int leftButtonsWidth, int rightButtonsWidth, int requiredEmptySpace,
            int taskWidth, int displayWidth) {
        // Do not let apps with < 48dp empty header space go off the right edge at all.
        if (leftButtonsWidth + rightButtonsWidth + requiredEmptySpace > taskWidth) {
            return displayWidth - taskWidth;
        }
        return displayWidth - requiredEmptySpace - leftButtonsWidth;
    }

    /**
     * Determine the highest y coordinate of a freeform task. Used for restricting drag inputs.
     */
    private int determineMaxY(int requiredEmptySpace, Rect stableBounds) {
        return stableBounds.bottom - requiredEmptySpace;
    }


    void setDragDetector(DragDetector dragDetector) {
        mDragDetector = dragDetector;
        mDragDetector.setTouchSlop(ViewConfiguration.get(mContext).getScaledTouchSlop());
    }

    @Override
    void relayout(RunningTaskInfo taskInfo) {
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        // The crop and position of the task should only be set when a task is fluid resizing. In
        // all other cases, it is expected that the transition handler positions and crops the task
        // in order to allow the handler time to animate before the task before the final
        // position and crop are set.
        final boolean shouldSetTaskPositionAndCrop = mTaskDragResizer.isResizingOrAnimating();
        // Use |applyStartTransactionOnDraw| so that the transaction (that applies task crop) is
        // synced with the buffer transaction (that draws the View). Both will be shown on screen
        // at the same, whereas applying them independently causes flickering. See b/270202228.
        relayout(taskInfo, t, t, true /* applyStartTransactionOnDraw */,
                shouldSetTaskPositionAndCrop);
    }

    private String queryStringValueData(String keyCode,String packageName,String activityName){
        String selection = "PACKAGE_NAME = ? AND KEY_CODE = ? AND ACTIVITY_NAME = ?";
        String[] selectionArgs= {packageName,keyCode, activityName};
        return CompatibleConfig.queryStringValueData(mContext, keyCode, packageName,activityName);
    } 

    void relayout(RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT, SurfaceControl.Transaction finishT,
            boolean applyStartTransactionOnDraw, boolean setTaskCropAndPosition) {
        int shadowRadiusID = taskInfo.isFocused
                ? R.dimen.freeform_decor_shadow_focused_thickness
                : R.dimen.freeform_decor_shadow_unfocused_thickness;
        boolean shouldUseZeroShadow = (taskInfo.topActivity == null) ||
                (taskInfo.topActivity.getPackageName() != null && SPECIAL_PACKAGES.contains(taskInfo.topActivity.getPackageName())) ||
                RESOLVER_ACTIVITY.equals(taskInfo.topActivity.getClassName());

        if (shouldUseZeroShadow) {
            shadowRadiusID = R.dimen.freeform_decor_shadow_focused_0_thickness;
        }

            final boolean isFreeform =
                taskInfo.getWindowingMode() == WindowConfiguration.WINDOWING_MODE_FREEFORM;
        boolean isDragResizeable = isFreeform && taskInfo.isResizeable;

        if(taskInfo.topActivity != null && taskInfo.topActivity.getPackageName() != null && !TextUtils.equals(mTopActivity, taskInfo.topActivity.getClassName())) {
            String packageName = taskInfo.topActivity.getPackageName();
            String recordPackageName = SystemProperties.get("com.fde.record.package", "null");

            String forcedPortraitMode =  queryStringValueData("forcedPortraitMode",packageName, "");
            //String enableMagicWindow =  queryStringValueData("enableMagicWindow",packageName, "");
           
            if(TextUtils.equals(forcedPortraitMode, "true") || TextUtils.equals(recordPackageName, packageName)){
                isDragResizeable = false;
                Log.d(TAG,"relayout packageName: " + packageName + ", isDragResizeable: " + isDragResizeable);
            }else{
                String activityName = extractActivityName(taskInfo.topActivity.getClassName());
                forcedPortraitMode =  queryStringValueData("forcedPortraitMode",packageName, activityName);
                Log.d(TAG,"forcedPortraitMode forcedPortraitMode: " + forcedPortraitMode);
                if(TextUtils.equals(forcedPortraitMode, "true") ){
                    isDragResizeable = false;
                    Log.d(TAG,"relayout className: " + taskInfo.topActivity.getClassName() + ", isDragResizeable: " + isDragResizeable);
                }
            }
            mTopActivity = taskInfo.topActivity.getClassName();
            mDragResizeable = isDragResizeable;
        }
        if(taskInfo.topActivity != null && !TextUtils.equals(mTopActivity, taskInfo.topActivity.getClassName())){
            mDragResizeable = isDragResizeable;
        }

        final WindowDecorLinearLayout oldRootView = mResult.mRootView;
        final SurfaceControl oldDecorationSurface = mDecorationContainerSurface;
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        mRelayoutParams.reset();
        mRelayoutParams.mRunningTaskInfo = taskInfo;
        mRelayoutParams.mLayoutResId = R.layout.caption_window_decor;
        mRelayoutParams.mCaptionHeightId = getCaptionHeightId(taskInfo.getWindowingMode());
        mRelayoutParams.mShadowRadiusId = shadowRadiusID;
        mRelayoutParams.mApplyStartTransactionOnDraw = applyStartTransactionOnDraw;
        mRelayoutParams.mSetTaskPositionAndCrop = setTaskCropAndPosition;
        mRelayoutParams.mAllowCaptionInputFallthrough = false;

        relayout(mRelayoutParams, startT, finishT, wct, oldRootView, mResult);
        // After this line, mTaskInfo is up-to-date and should be used instead of taskInfo

        mTaskOrganizer.applyTransaction(wct);

        if (mResult.mRootView == null) {
            // This means something blocks the window decor from showing, e.g. the task is hidden.
            // Nothing is set up in this case including the decoration surface.
            return;
        }
        if (oldRootView != mResult.mRootView) {
            setupRootView();
        }

        bindData(mResult.mRootView, taskInfo);

        if (!mDragResizeable) {
            closeDragResizeListener();
            return;
        }

        if (oldDecorationSurface != mDecorationContainerSurface || mDragResizeListener == null) {
            closeDragResizeListener();
            mDragResizeListener = new DragResizeInputListener(
                    mContext,
                    mHandler,
                    mChoreographer,
                    mDisplay.getDisplayId(),
                    0 /* taskCornerRadius */,
                    mDecorationContainerSurface,
                    mDragPositioningCallback,
                    mSurfaceControlBuilderSupplier,
                    mSurfaceControlTransactionSupplier,
                    mDisplayController);
        }

        final int touchSlop = ViewConfiguration.get(mResult.mRootView.getContext())
                .getScaledTouchSlop();
        mDragDetector.setTouchSlop(touchSlop);

        final int resize_handle = mResult.mRootView.getResources()
                .getDimensionPixelSize(R.dimen.freeform_resize_handle);
        final int resize_corner = mResult.mRootView.getResources()
                .getDimensionPixelSize(R.dimen.freeform_resize_corner);
        mDragResizeListener.setGeometry(
                mResult.mWidth, mResult.mHeight, resize_handle, resize_corner, touchSlop);
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

    /**
     * Sets up listeners when a new root view is created.
     */
    private void setupRootView() {
        final View caption = mResult.mRootView.findViewById(R.id.caption);
        caption.setOnTouchListener(mOnCaptionTouchListener);
        final ImageView close = caption.findViewById(R.id.close_window);
        close.setOnClickListener(mOnCaptionButtonClickListener);
        final Button back = caption.findViewById(R.id.back_button);
        back.setOnClickListener(mOnCaptionButtonClickListener);
        final ImageView fullscreen = caption.findViewById(R.id.fullscreen_window);
        fullscreen.setOnClickListener(mOnCaptionButtonClickListener);
        fullscreen.setOnTouchListener(mOnCaptionTouchListener);
        final ImageView minimize = caption.findViewById(R.id.minimize_window);
        minimize.setOnClickListener(mOnCaptionButtonClickListener);
        final ImageView maximize = caption.findViewById(R.id.maximize_window);
        maximize.setOnClickListener(mOnCaptionButtonClickListener);
        maximize.setOnTouchListener(mOnCaptionTouchListener);
        PackageManager pm = mContext.getApplicationContext().getPackageManager();
        final TextView applicationLable = caption.findViewById(R.id.application_lable);
        if(mTaskInfo != null && mTaskInfo.topActivityInfo != null
            && mTaskInfo.topActivityInfo.applicationInfo != null){
            CharSequence appName = pm.getApplicationLabel(mTaskInfo.topActivityInfo.applicationInfo);
            if(appName != null && !"null".equals(appName.toString())){
                applicationLable.setText(appName);
            }
        }else{
            applicationLable.setText("");
        }
    }

    private void bindData(View rootView, RunningTaskInfo taskInfo) {
        final boolean isFullscreen =
                taskInfo.getWindowingMode() == WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
        ImageView maximize = rootView.findViewById(R.id.maximize_window);
                maximize.setImageResource(isFullscreen ? R.drawable.icon_exitmaximize
                        : R.drawable.icon_maximize);
    }

    void setCaptionColor(int captionColor, RunningTaskInfo taskInfo) {
        if (mResult.mRootView == null) {
            return;
        }

        final View caption = mResult.mRootView.findViewById(R.id.caption);
        /*final GradientDrawable captionDrawable = (GradientDrawable) caption.getBackground();
        captionDrawable.setColor(captionColor);*/

        int buttonTintColorRes =
                isDarkTheme(mContext)
                        ? R.color.decor_button_light_color
                        : R.color.decor_button_dark_color;

        final View captionSub = caption.findViewById(R.id.caption_sub);
        if(isDarkTheme(mContext)){
            captionSub.setBackgroundColor(mContext.getResources().getColor(R.color.desktop_mode_caption_handle_bar_dark));
        }else{
            captionSub.setBackgroundColor(mContext.getResources().getColor(R.color.desktop_mode_caption_handle_bar_light));
        }
        if(!SystemProperties.getBoolean("fde.systemui.blurlevel", false)){
            setBackgroundBlurRadius(caption, 40, 10f);
        }

        final ColorStateList buttonTintColor =
                caption.getResources().getColorStateList(buttonTintColorRes, null /* theme */);

        final Button back = caption.findViewById(R.id.back_button);
        final Drawable backBackground = back.getBackground();
        backBackground.setTintList(buttonTintColor);

        final TextView applicationLable = caption.findViewById(R.id.application_lable);
        applicationLable.setTextColor(buttonTintColor);
        // final ImageView fullscreen = caption.findViewById(R.id.fullscreen_window);
        // final Drawable fullscreenBackground = fullscreen.getBackground();
        // fullscreenBackground.setTintList(ColorStateList.valueOf(mContext.getResources().getColor(R.color.title_icon_light)));

        // final ImageView minimize = caption.findViewById(R.id.minimize_window);
        // final Drawable minimizeBackground =  minimize.getBackground();
        // minimizeBackground.setTintList(ColorStateList.valueOf(mContext.getResources().getColor(R.color.title_icon)));

        // final ImageButton maximize = caption.findViewById(R.id.maximize_window);
        // final Drawable maximizeBackground = maximize.getBackground();
        // maximizeBackground.setTintList(buttonTintColor);

        // final ImageButton close = caption.findViewById(R.id.close_window);
        // final Drawable closeBackground = close.getBackground();
        // closeBackground.setTintList(buttonTintColor);
    }

    public void setBackgroundBlurRadius(View view, int radius, float cornerRadius) {
        if (view == null) {
            return;
        }

        ViewParent target = view.getParent();
        while (target != null) {
            if (target instanceof ViewRootImpl) {
                break;
            }
            target = target.getParent();
        }

        if (target instanceof ViewRootImpl) {
            ViewRootImpl viewRootImpl = (ViewRootImpl) target;
            Drawable blurDrawable = viewRootImpl.createBackgroundBlurDrawable(radius);
            ((BackgroundBlurDrawable)blurDrawable).setCornerRadius(cornerRadius, cornerRadius, 0f, 0f);
            Drawable realDrawable = view.getBackground();
            LayerDrawable layerDrawable = new LayerDrawable(new Drawable[]{realDrawable, blurDrawable});
            view.setBackground(layerDrawable);

        }
    }

    private boolean isDarkTheme(Context context) {
        if(context != null){
            int nightModeFlags = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
        }
        return false;
    }

    void setCaptionLable(){
        if (mResult.mRootView == null) {
            return;
        }

        final View caption = mResult.mRootView.findViewById(R.id.caption);
        final TextView applicationLable = caption.findViewById(R.id.application_lable);

        PackageManager pm = mContext.getApplicationContext().getPackageManager();
        if(mTaskInfo != null && mTaskInfo.topActivityInfo != null
            && mTaskInfo.topActivityInfo.applicationInfo != null){
            CharSequence appName = pm.getApplicationLabel(mTaskInfo.topActivityInfo.applicationInfo);
            if(appName != null && !"null".equals(appName.toString())){
                applicationLable.setText(appName);
            }
        }else{
            applicationLable.setText("");
        }

        if(mTaskInfo != null && mTaskInfo.taskDescription != null){
            if(mTaskInfo.taskDescription.getLabel() != null){
                String titleName = mTaskInfo.taskDescription.getLabel();
                if(titleName != null && !"null".equals(titleName)){
                    applicationLable.setText(titleName);
                }
            }
            Log.d(TAG, "WindowDecorationStatus: " + mTaskInfo.taskDescription.getWindowDecorationStatus() + ", taskId: " + mTaskInfo.taskId);
        }
    }

    boolean isHandlingDragResize() {
        return mDragResizeListener != null && mDragResizeListener.isHandlingDragResize();
    }

    private void closeDragResizeListener() {
        if (mDragResizeListener == null) {
            return;
        }
        mDragResizeListener.close();
        mDragResizeListener = null;
    }

    @Override
    public void close() {
        closeDragResizeListener();
        super.close();
    }

    @Override
    int getCaptionHeightId(@WindowingMode int windowingMode) {
        return R.dimen.freeform_decor_caption_height;
    }

    @Override
    int getCaptionViewId() {
        return R.id.caption;
    }
}
