package android.openfde;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.Activity;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.view.Window;
import android.os.RemoteException;
import android.os.Looper;
import android.view.Display;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.List;
import com.android.internal.policy.DecorView;
import com.android.internal.policy.AppTaskController;
import com.android.internal.policy.DecorWindowInsetsCallback;
import com.android.internal.policy.TaskRemoteServiceWrapper;
import com.android.internal.policy.SystemBarController;

/**
 * AppTaskControllerImpl - Implementation of AppTaskController interface.
 * Manages task operations and system bar controls for windowed applications.
 * Works in conjunction with TaskRemoteServiceWrapper for remote service operations.
 * @hide
 */
public class WmShellAppTaskController implements AppTaskController, DecorWindowInsetsCallback {
    private static final String TAG = "WmShellAppTaskController";

    private AppTaskStatusListener mStatusListener;
    private ActivityManager.RunningTaskInfo mTaskInfo;
    private boolean mIsRawCaptionHidden;
    private boolean mLinkedToWMshell;
    private boolean beenLinkToWmShell;
    private ActivityManager mActivityManager;
    private ActivityTaskManager mActivityTaskManager;
    private int mWindowingMode = AppTaskStatusListener.WINDOWING_MODE_FREEFORM;
    private boolean mSystemBarVisibility = true;
    private TaskRemoteServiceWrapper mServiceWrapper = TaskRemoteServiceWrapper.getInstance();
    private final Object mLock = new Object();
    private DecorView mDecorView = null;
    private WeakReference<Activity> mActivity;

    // Task operation constants
    public static final int TASK_CAPTION_OPERATION_CLOSE = 0;
    public static final int TASK_CAPTION_OPERATION_BACK = 1;
    public static final int TASK_CAPTION_OPERATION_FULLSCREEN = 2;
    public static final int TASK_CAPTION_OPERATION_MINIMIZE = 3;
    public static final int TASK_CAPTION_OPERATION_MAXIMIZE = 4;
    public static final int TASK_CAPTION_OPERATION_WINDOWDECORATION_RELAYOUT = 5;

    private static final int WINDOW_STATUS_BAR = 1;
    private static final int WINDOW_NAVIGATION_BAR = 2;

    /**
     * Initialize custom caption for the activity.
     *
     * @param activity       Weak reference to the activity
     * @param listener       Listener for task status changes
     * @param hideRawCaption Whether to hide the raw caption
     * @throws IllegalArgumentException if parameters are invalid
     */
    public void initCustomCaption(WeakReference<Activity> activity,
                                  AppTaskStatusListener listener,
                                  boolean hideRawCaption) {
        Log.d(TAG, "Initializing custom caption");

        if (listener == null && hideRawCaption) {
            Log.e(TAG, "Both listener and hideRawCaption cannot be null when hiding caption");
            throw new IllegalArgumentException(
                    "listener and hideRawCaption cannot be both null, custom caption need listen to task status.");
        }

        if (activity == null || activity.get() == null) {
            Log.e(TAG, "Activity is null");
            throw new IllegalArgumentException("activity is null.");
        }

        Context context = activity.get();
        mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mActivityTaskManager = (ActivityTaskManager)
                context.getSystemService(Context.ACTIVITY_TASK_SERVICE);

        mStatusListener = listener;
        mActivity = activity;
        mIsRawCaptionHidden = hideRawCaption;

        // Hide raw caption if requested
        if (hideRawCaption) {
            Log.i(TAG, "Hiding raw window decoration");
            activity.get().setWindowDecorationStatus(Window.WINDOW_DECORATION_FORCE_HIDE);
        }

        // Get decor view
        mDecorView = (DecorView) activity.get().getWindow().getDecorView();
        if (mDecorView == null) {
            throw new IllegalArgumentException("mDecorView is null. must after setContentView");
        }
        mDecorView.setWindowInsetsCallback(this);

        // Update system bar controller
        updateSystemBarController(null);

        // Get initial state
        mWindowingMode = getCurrentWindowingMode(activity.get());
        mSystemBarVisibility = getSystemBarVisibility();
        Log.d(TAG, "mSystemBarVisibility:" + mSystemBarVisibility + " taskSystembarVisiblity:" + mTaskInfo.taskSystembarVisiblity);


        mLinkedToWMshell = true;
        Log.i(TAG, "Custom caption initialized successfully");
    }

    public void reinit(){
        initCustomCaption(mActivity, mStatusListener, mIsRawCaptionHidden);
    }

    /**
     * Get foreground task information.
     *
     * @return RunningTaskInfo of foreground task or null if not available
     */
    private ActivityManager.RunningTaskInfo getForegroundTaskInfo() {
        try {
            List<ActivityManager.RunningTaskInfo> tasks = mActivityTaskManager.getTasks(1);
            if (tasks.isEmpty()) {
                Log.w(TAG, "No foreground task found");
                return null;
            }
            return tasks.get(0);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get foreground task info", e);
            return null;
        }
    }

    /**
     * Check system bar visibility status.
     *
     * @return true if both status bar and navigation bar are visible
     */
    private boolean getSystemBarVisibility() {
        Display display = getDisplay();
        int displayId = 0;
        if (display != null) {
            displayId = display.getDisplayId();
        }

        try {
            if (mServiceWrapper.isStatusBarServiceAvailable()) {
                boolean statusBarVisible = mServiceWrapper.getStatusBarService()
                        .getSystemBarVisibility(displayId, WINDOW_STATUS_BAR);
                boolean navigationBarVisible = mServiceWrapper.getStatusBarService()
                        .getSystemBarVisibility(displayId, WINDOW_NAVIGATION_BAR);

                mSystemBarVisibility = statusBarVisible && navigationBarVisible;
                Log.d(TAG, "System bar visibility - status: " + statusBarVisible +
                        ", navigation: " + navigationBarVisible);
            } else {
                Log.w(TAG, "Status bar service not available");
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to get system bar visibility", ex);
        }

        return mSystemBarVisibility;
    }

    /**
     * Update system bar controller registration.
     * This method registers a SystemBarController with TaskRemoteServiceWrapper
     * which will create and manage the IAppSystemBarController.Stub callback.
     */
    public void updateSystemBarController(SystemBarController systemBarController) {
        Log.d(TAG, "Updating system bar controller");

        Activity activity = mActivity != null ? mActivity.get() : null;
        if (activity == null) {
            Log.w(TAG, "Activity is null, cannot update system bar controller");
            return;
        }

        final ActivityManager.RunningTaskInfo taskInfo = getTaskInfoFromActivity(activity);
        if (taskInfo == null) {
            Log.w(TAG, "Task info is null");
            return;
        }

        // Skip if same task
        if (mTaskInfo != null && mTaskInfo.taskId == taskInfo.taskId) {
            Log.d(TAG, "Same task, skipping controller update");
            return;
        }

        try {
            // Unregister old controller if exists
            if (mTaskInfo != null) {
                Log.d(TAG, "Unregistering old system bar controller for task: " + mTaskInfo.taskId);
                mServiceWrapper.unregisterSystemBarController(mTaskInfo.taskId);
            }

            // Update task info
            mTaskInfo = taskInfo;
            Log.d(TAG, "Registering system bar controller for new task: " + taskInfo.taskId);
            if (systemBarController == null) {
                systemBarController = new SystemBarController() {
                    @Override
                    public void hideStatusBarNavigationBar() {
                        Log.d(TAG, "SystemBarController: hideStatusBarNavigationBar");
                        toggleStatusBarNavigationBar(true);
                    }

                    @Override
                    public void showStatusBarNavigationBar() {
                        Log.d(TAG, "SystemBarController: showStatusBarNavigationBar");
                        toggleStatusBarNavigationBar(false);
                    }
                };
            }

            // Create and register new controller
            // Note: TaskRemoteServiceWrapper will create the IAppSystemBarController.Stub callback
            mServiceWrapper.registerSystemBarController(taskInfo.taskId, systemBarController);
            beenLinkToWmShell = true;
            Log.i(TAG, "System bar controller updated successfully");
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error updating system bar controller", e);
        }
    }

    /**
     * Get task information from activity.
     *
     * @param activity The activity to get task info for
     * @return RunningTaskInfo or null if not found
     */
    public static ActivityManager.RunningTaskInfo getTaskInfoFromActivity(Activity activity) {
        if (activity == null) {
            Log.w(TAG, "Activity is null in getTaskInfoFromActivity");
            return null;
        }
        ActivityManager activityManager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        try {
            List<ActivityManager.RunningTaskInfo> runningTasks =
                    activityManager.getRunningTasks(Integer.MAX_VALUE);

            String packageName = activity.getPackageName();
            int taskId = activity.getTaskId();

            Log.d(TAG, "Looking for task, package: " + packageName + ", taskId: " + taskId);

            for (ActivityManager.RunningTaskInfo taskInfo : runningTasks) {
                if (taskInfo.topActivity != null &&
                        taskInfo.topActivity.getPackageName().equals(packageName) &&
                        taskInfo.id == taskId) {
                    Log.d(TAG, "Found matching task: " + taskInfo.taskId);
                    return taskInfo;
                }
            }

            Log.w(TAG, "No matching task found for activity");
            return null;
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception getting running tasks", e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error getting task info from activity", e);
            return null;
        }
    }

    /**
     * Get current windowing mode of the activity.
     *
     * @param activity The activity
     * @return Windowing mode constant
     */
    private int getCurrentWindowingMode(Activity activity) {
        // Implementation depends on available API
        // This is a placeholder - implement based on your framework
        try {
            // Example implementation - adjust based on your actual API
            if (activity != null && activity.getWindow() != null) {
                if(activity.getResources().getConfiguration().windowConfiguration.getWindowingMode() == 6){
                    return AppTaskStatusListener.WINDOWING_MODE_FULLSCREEN;
                }
                return activity.getResources().getConfiguration().windowConfiguration.getWindowingMode();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting windowing mode", e);
        }
        return AppTaskStatusListener.WINDOWING_MODE_UNDEFINED;
    }

    /**
     * Get display from activity.
     *
     * @return Display object or null if not available
     */
    private Display getDisplay() {
        if (mActivity != null && mActivity.get() != null) {
            return mActivity.get().getDisplay();
        }
        return null;
    }

    /**
     * Get context from activity.
     *
     * @return Context or null if activity is not available
     */
    private Context getContext() {
        if (mActivity != null && mActivity.get() != null) {
            return mActivity.get();
        }
        return null;
    }

    @Override
    public void closeTask() {
        Log.i(TAG, "Closing task");
        callTaskOperation(TASK_CAPTION_OPERATION_CLOSE);
    }

    @Override
    public void back() {
        Log.i(TAG, "Back operation");
        callTaskOperation(TASK_CAPTION_OPERATION_BACK);
    }

    @Override
    public void enterOrExitFullscreen() {
        Log.i(TAG, "Enter/Exit fullscreen");

        synchronized (mLock) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Log.e(TAG, "enterOrExitFullscreen must be called on main thread");
                throw new IllegalArgumentException("enterOrExitFullscreen must called on main thread");
            }

            // Handle different windowing modes
            if (mWindowingMode == AppTaskStatusListener.WINDOWING_MODE_FULLSCREEN &&
                    !mSystemBarVisibility) {
                Log.d(TAG, "Exiting fullscreen, showing system bars");
                if (mDecorView != null) {
                    mDecorView.showStatusBarNavigationBar();
                }
            } else if (mWindowingMode == AppTaskStatusListener.WINDOWING_MODE_FREEFORM) {
                Log.d(TAG, "Maximizing from freeform, hiding system bars");
                callTaskOperation(TASK_CAPTION_OPERATION_MAXIMIZE);
                toggleStatusBarNavigationBar(true);
            } else {
                Log.d(TAG, "Hiding system bars");
                toggleStatusBarNavigationBar(true);
            }

            // Trigger window decoration relayout
            callTaskOperation(TASK_CAPTION_OPERATION_WINDOWDECORATION_RELAYOUT);
        }
    }

    public void setSystemBarVisibleByInsetControl(boolean visible){
        mServiceWrapper.setLocalSystemBarVisiblity(visible);
    }

    public boolean getSystemBarVisibleByInsetControl(){
        return mServiceWrapper.getLocalSystemBarVisiblity();
    }

    public void toggleStatusBarNavigationBar(boolean hide){
        if (mDecorView != null) {
            mDecorView.post(() -> {
                if (mDecorView.isAttachedToWindow()) {
                    try {
                        if(hide){
                            mDecorView.hideStatusBarNavigationBar();
                        } else {
                            mDecorView.showStatusBarNavigationBar();
                        }
                        Log.i(TAG, "toggleStatusBarNavigationBar " + hide);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to toggleStatusBarNavigationBar", e);
                    }
                }
            });
        }
    }

    @Override
    public void minimize() {
        Log.i(TAG, "Minimizing task");
        callTaskOperation(TASK_CAPTION_OPERATION_MINIMIZE);
    }

    @Override
    public void maximizeOrNot() {
        Log.i(TAG, "Maximize/restore task");
        callTaskOperation(TASK_CAPTION_OPERATION_MAXIMIZE);
        if (mDecorView != null) {
            mDecorView.showStatusBarNavigationBar();
            Log.d(TAG, "System bars shown after maximize");
        }
    }

    @Override
    public void updateDecoration() {
        Log.d(TAG, "Update decoration");
        callTaskOperation(TASK_CAPTION_OPERATION_WINDOWDECORATION_RELAYOUT);
    }

    @Override
    public void onApplyWindowInsets() {
        Log.d(TAG, "Window insets applied");
        // Update system bar controller when window insets change
        updateSystemBarController(null);
        // Notify status change
        onStatusChanged();
        if(getSystemBarVisibilityStatus() !=
                getSystemBarVisibleByInsetControl()){
            enterOrExitFullscreen();
        }
    }

    /**
     * Handle status changes and notify listener.
     * This is called when windowing mode or system bar visibility changes.
     */
    private void onStatusChanged() {
        Log.d(TAG, "Task status changed");

        if (mLinkedToWMshell) {
            Activity activity = mActivity != null ? mActivity.get() : null;
            if (activity == null) {
                Log.w(TAG, "Activity is null, cannot update status");
                return;
            }

            boolean systemBarVisibility = getSystemBarVisibility();
            int currentWindowingMode = getCurrentWindowingMode(activity);

            Log.i(TAG, "New status - windowingMode: " + currentWindowingMode +
                    ", systemBarVisibility: " + systemBarVisibility);
            if (mStatusListener != null) {
                try {
                    mStatusListener.onStatusChanged(currentWindowingMode, systemBarVisibility);
                    Log.d(TAG, "Status change notified to listener");
                } catch (Exception e) {
                    Log.e(TAG, "Error in status listener callback", e);
                }
            } else {
                Log.w(TAG, "No status listener registered");
            }

            mWindowingMode = currentWindowingMode;
            mSystemBarVisibility = systemBarVisibility;
        } else {
            Log.d(TAG, "Not linked to WM shell, skipping status update");
        }
    }

    /**
     * Call task operation through service wrapper.
     *
     * @param operation The operation code to execute
     */
    public void callTaskOperation(int operation) {
        Log.d(TAG, "Calling task operation: " + operation);
        if(operation > TASK_CAPTION_OPERATION_WINDOWDECORATION_RELAYOUT ||
                operation < TASK_CAPTION_OPERATION_CLOSE){
            Log.e(TAG, "illegal opCode:" + operation);
            return;
        }

        synchronized (mLock) {
            // Ensure we have valid task info
            if (mTaskInfo == null) {
                mTaskInfo = getForegroundTaskInfo();
            }

            if (mTaskInfo != null) {
                Log.i(TAG, "Executing operation " + operation + " for task: " + mTaskInfo.taskId);
                try {
                    mServiceWrapper.executeTaskOperation(mTaskInfo.taskId, operation);
                    Log.i(TAG, "Task operation executed successfully");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to execute task operation", e);
                }
            } else {
                Log.w(TAG, "No task info available, cannot execute operation");
            }
        }
    }

    /**
     * Clean up resources and service connections.
     * This should be called when the activity is destroyed.
     */
    public void cleanup() {
        Log.i(TAG, "Cleaning up WmShellAppTaskController");

        // Unregister system bar controller
        if (mTaskInfo != null) {
            mTaskInfo.taskSystembarVisiblity = getSystemBarVisibility();
            Log.d(TAG, "cleanup taskSystembarVisiblity:" + mTaskInfo.taskSystembarVisiblity);
            try {
                mServiceWrapper.unregisterSystemBarController(mTaskInfo.taskId);
                Log.d(TAG, "System bar controller unregistered");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering system bar controller", e);
            }
        }

        // Cleanup service wrapper
        try {
            mServiceWrapper.cleanup();
            Log.d(TAG, "Service wrapper cleaned up");
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up service wrapper", e);
        }

        // Clear references to prevent memory leaks
        mStatusListener = null;
        mTaskInfo = null;
        mDecorView = null;
        mActivity = null;
        mLinkedToWMshell = false;

        Log.i(TAG, "WmShellAppTaskController cleanup completed");
    }

    /**
     * Check if linked to WM shell.
     *
     * @return true if linked, false otherwise
     */
    public boolean isLinkedToWMshell() {
        return mLinkedToWMshell;
    }

    public boolean hasBeenLinkToWmShell(){
        return beenLinkToWmShell;
    }

    public String getStatus() {
        if (mStatusListener != null) {
            return mStatusListener.onGetStatus(mWindowingMode, mSystemBarVisibility);
        }
        return "windowmode:" + mWindowingMode + "|systembar:" + mSystemBarVisibility;
    }

    /**
     * Get current task info.
     *
     * @return Current RunningTaskInfo or null
     */
    public ActivityManager.RunningTaskInfo getTaskInfo() {
        return mTaskInfo;
    }

    /**
     * Get current windowing mode.
     *
     * @return Windowing mode constant
     */
    public int getWindowingMode() {
        return mWindowingMode;
    }

    /**
     * Get system bar visibility.
     *
     * @return true if system bars are visible
     */
    public boolean getSystemBarVisibilityStatus() {
        return mSystemBarVisibility;
    }

    /**
     * Check if raw caption is hidden.
     *
     * @return true if raw caption is hidden
     */
    public boolean isRawCaptionHidden() {
        return mIsRawCaptionHidden;
    }
}