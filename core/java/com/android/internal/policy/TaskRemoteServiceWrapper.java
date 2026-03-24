package com.android.internal.policy;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.policy.ITaskCaptionOperationService;
import android.content.Context;
import android.os.ServiceManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import android.app.Activity;
import java.lang.ref.WeakReference;

/**
 * TaskRemoteServiceWrapper - Wrapper class for remote service operations related to tasks.
 * This class manages connections to status bar and task caption services,
 * and handles system bar controller registration/unregistration.
 *
 * @hide
 */
public class TaskRemoteServiceWrapper {
    private static final String TAG = "TaskRemoteServiceWrapper";
    private static volatile TaskRemoteServiceWrapper sInstance;

    private IStatusBarService mStatusBarService;
    private ITaskCaptionOperationService mTaskCaptionService;
    private final Object mStatusBarServiceLock = new Object();
    private final Object mTaskCaptionServiceLock = new Object();

    private SystemBarController mSystemBarController;
    private SystemBarCallback mSystemBarCallback;
    private final Object mCallbackLock = new Object();
    private int mTaskId = -1;

    private ConcurrentHashMap mActivityMap = new ConcurrentHashMap<Integer, WeakReference<Activity>>();
    private ConcurrentHashMap mLastSetModeMap = new ConcurrentHashMap<Integer, Integer>();
    private ConcurrentHashMap mBarControllerMap = new ConcurrentHashMap<Integer, SystemBarController>();

    /**
     * Private constructor for singleton pattern.
     */
    private TaskRemoteServiceWrapper() {
        Log.d(TAG, "TaskRemoteServiceWrapper instance created");
    }

    /**
     * Get singleton instance of TaskRemoteServiceWrapper.
     *
     * @return The singleton instance
     */
    public static TaskRemoteServiceWrapper getInstance() {
        if (sInstance == null) {
            synchronized (TaskRemoteServiceWrapper.class) {
                if (sInstance == null) {
                    sInstance = new TaskRemoteServiceWrapper();
                    Log.d(TAG, "Singleton instance initialized");
                }
            }
        }
        return sInstance;
    }

    /**
     * Get StatusBarService instance with lazy initialization and binder health check.
     *
     * @return IStatusBarService instance or null if service is unavailable
     */
    public IStatusBarService getStatusBarService() {
        synchronized (mStatusBarServiceLock) {
            if (mStatusBarService == null || !mStatusBarService.asBinder().isBinderAlive()) {
                Log.i(TAG, "Acquiring STATUS_BAR_SERVICE");
                mStatusBarService = IStatusBarService.Stub.asInterface(
                        ServiceManager.getService(Context.STATUS_BAR_SERVICE));
                if (mStatusBarService == null) {
                    Log.w(TAG, "Failed to get STATUS_BAR_SERVICE");
                } else {
                    Log.i(TAG, "STATUS_BAR_SERVICE acquired successfully");
                    setupDeathRecipient(mStatusBarService.asBinder(),
                            () -> {
                                mStatusBarService = null;
                                Log.w(TAG, "STATUS_BAR_SERVICE binder died, reference cleared");
                            });
                }
            }
            return mStatusBarService;
        }
    }

    public int getLastSetWindowMode(int taskId) {
        int mode = mLastSetModeMap.get(taskId) == null ? 5 : (int) mLastSetModeMap.get(taskId);
        android.util.Log.d(TAG, "getLastSetWindowMode() taskId: " + taskId + " mode:" + mode);
        return mode;
    }

    public void updateLastSetWindowMode(int taskId, int mode) {
        android.util.Log.d(TAG, "updateLastSetWindowMode() called with: taskId = [" + taskId + "], mode = [" + mode + "]");
        mLastSetModeMap.put(taskId, mode);
    }

    public void updateLastSetWindowModeIfNull(int taskId, int mode) {
        if (!mLastSetModeMap.containsKey(taskId)) {
            updateLastSetWindowMode(taskId, mode);
        }
    }

    /**
     * Execute task operation with specified operation code.
     *
     * @param taskId The task identifier
     * @param opCode The operation code to execute
     */
    public void executeTaskOperation(int taskId, int opCode) {
        Log.d(TAG, "Executing task operation, taskId: " + taskId + ", opCode: " + opCode);
        try {
            getOperationService().executeTaskOperation(taskId, opCode);
            Log.i(TAG, "Task operation executed successfully");
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to execute task operation, taskId: " + taskId + ", opCode: " + opCode, e);
        } catch (NullPointerException e) {
            Log.e(TAG, "Operation service is null, taskId: " + taskId, e);
        }
    }


    public void unregisterSystemBarController(int taskId, WeakReference<Activity> actRef) {
        Log.d(TAG, "Unregistering system bar controller, taskId: " + taskId);
        if(actRef != mActivityMap.get(taskId)){
            Log.w(TAG, "already unregister");
            return;
        }
        unregisterSystemBarController(taskId);
    }

    /**
     * Unregister system bar controller for the specified task.
     *
     * @param taskId The task identifier
     */
    public void unregisterSystemBarController(int taskId) {
        Log.d(TAG, "Unregistering system bar controller, taskId: " + taskId);
        synchronized (mCallbackLock) {
            try {
                getOperationService().unregisterSystemBarController(taskId);
                Log.i(TAG, "System bar controller unregistered successfully");
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister system bar controller, taskId: " + taskId, e);
            }
        }
        this.mTaskId = -1;
    }

    public boolean isRegistered(){
        return mTaskId == -1;
    }

    /**
     * Register system bar controller for the specified task.
     *
     * @param taskId The task identifier
     * @param controller The SystemBarController instance
     */
    public void registerSystemBarController(int taskId,
             AppTaskController appTaskController, SystemBarController controller) {
        Log.d(TAG, "Registering system bar controller, taskId: " + taskId);
        this.mTaskId = taskId;
        if(mBarControllerMap.get(taskId) == controller){
            Log.w(TAG, "already register bar controller");
            return;
        }
        mBarControllerMap.put(taskId, controller);
        synchronized (mCallbackLock) {
            // Unregister existing controller first
//            unregisterSystemBarController(taskId);
            SystemBarCallback systemBarCallback = new SystemBarCallback(controller, appTaskController, taskId);
            try {
                getOperationService().registerSystemBarController(taskId, systemBarCallback);
                Log.i(TAG, "System bar controller registered successfully for task: " + taskId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to register system bar controller, taskId: " + taskId, e);
                systemBarCallback = null;
            }
        }
    }

    public void unregisterSystemBarController(){
        if(mTaskId != -1){
            unregisterSystemBarController(mTaskId);
        }
    }

    /**
     * Get TaskCaptionOperationService instance with lazy initialization.
     *
     * @return ITaskCaptionOperationService instance or null if service is unavailable
     */
    public ITaskCaptionOperationService getOperationService() {
        synchronized (mTaskCaptionServiceLock) {
            if (mTaskCaptionService == null || !mTaskCaptionService.asBinder().isBinderAlive()) {
                Log.i(TAG, "Acquiring TASK_CAPTION_OPERATION service");
                mTaskCaptionService = ITaskCaptionOperationService.Stub.asInterface(
                        ServiceManager.getService("TASK_CAPTION_OPERATION"));
                if (mTaskCaptionService == null) {
                    Log.w(TAG, "Failed to get TASK_CAPTION_OPERATION service");
                } else {
                    Log.i(TAG, "TASK_CAPTION_OPERATION service acquired successfully");
                    setupDeathRecipient(mTaskCaptionService.asBinder(),
                            () -> {
                                mTaskCaptionService = null;
                                Log.w(TAG, "TASK_CAPTION_OPERATION binder died, reference cleared");
                            });
                }
            }
            return mTaskCaptionService;
        }
    }

    /**
     * Set up death recipient for binder to handle service death events.
     *
     * @param binder The IBinder to monitor
     * @param onDeath Runnable to execute when binder dies
     */
    private void setupDeathRecipient(IBinder binder, Runnable onDeath) {
        if (binder == null) {
            Log.w(TAG, "Cannot setup death recipient for null binder");
            return;
        }

        try {
            binder.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    Log.w(TAG, "Binder died, cleaning up service reference");
                    onDeath.run();
                }
            }, 0);
            Log.d(TAG, "Death recipient setup successfully");
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to setup death recipient", e);
        }
    }

    public void putActivityRef(int taskId, WeakReference<Activity> actRef){
        mActivityMap.put(taskId, actRef);
    }

    /**
     * Clean up all resources and service connections.
     */
    public void cleanup(WeakReference<Activity> actRef) {
        Log.d(TAG, "Cleaning up TaskRemoteServiceWrapper resources act:" + actRef.get());
        unregisterSystemBarController(mTaskId, actRef);

        synchronized (mStatusBarServiceLock) {
            mStatusBarService = null;
            Log.d(TAG, "Status bar service reference cleared");
        }
        synchronized (mTaskCaptionServiceLock) {
            mTaskCaptionService = null;
            Log.d(TAG, "Task caption service reference cleared");
        }
    }

    /**
     * Check if StatusBarService is available.
     *
     * @return true if service is available, false otherwise
     */
    public boolean isStatusBarServiceAvailable() {
        boolean available = getStatusBarService() != null;
        Log.v(TAG, "StatusBarService available: " + available);
        return available;
    }

    /**
     * Check if TaskCaptionOperationService is available.
     *
     * @return true if service is available, false otherwise
     */
    public boolean isTaskCaptionServiceAvailable() {
        boolean available = getOperationService() != null;
        Log.v(TAG, "TaskCaptionService available: " + available);
        return available;
    }

    /**
     * Inner callback class for system bar controller operations.
     * Handles communication between remote service and local controller.
     */
    private static class SystemBarCallback extends IAppSystemBarController.Stub {
        private final WeakReference<SystemBarController> mControllerRef;
        private final AppTaskController mAppTaskController;

        private final int mRegisteredTaskId;

        /**
         * Constructor for SystemBarCallback.
         *
         * @param controller The SystemBarController instance
         * @param taskId The task identifier
         */
        SystemBarCallback(SystemBarController controller, AppTaskController appTaskController,  int taskId) {
            mControllerRef = new WeakReference<>(controller);
            mAppTaskController = appTaskController;
            mRegisteredTaskId = taskId;
            Log.d(TAG, "SystemBarCallback created for taskId: " + taskId + " appTaskController: " + appTaskController);
        }

        @Override
        public void enterOrExistFullScreen(){
            final AppTaskController controller = mAppTaskController;
            if (controller == null) {
                Log.w(TAG, "AppTaskController has been garbage collected");
                return;
            }
            Log.d(TAG, "enterOrExistFullScreen() called");
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Log.d(TAG, "Switching to main thread for system bar operation");
                Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.post(() -> executeenterOrExistFullScreen(controller));
            } else {
                executeenterOrExistFullScreen(controller);
            }
        }

        @Override
        public void maximizeOrNot(){
            final AppTaskController controller = mAppTaskController;
            if (controller == null) {
                Log.w(TAG, "AppTaskController has been garbage collected");
                return;
            }
            Log.d(TAG, "enterOrExistFullScreen() called");
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Log.d(TAG, "Switching to main thread for system bar operation");
                Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.post(() -> executeMaximizeOrNot(controller));
            } else {
                executeMaximizeOrNot(controller);
            }
        }

        private void executeMaximizeOrNot(AppTaskController controller) {
            Log.d(TAG, "Executing maximizeOrNot");
            controller.maximizeOrNot();
        }

        @Override
        public void hideSystemBar(int taskId, final boolean hide) {
            Log.d(TAG, "hideSystemBar called, taskId: " + taskId + ", hide: " + hide);

            // Verify task ID matches
            if (taskId != mRegisteredTaskId) {
                Log.w(TAG, "Received callback for different task: " + taskId
                        + ", expected: " + mRegisteredTaskId);
                return;
            }

            final SystemBarController controller = mControllerRef.get();
            if (controller == null) {
                Log.w(TAG, "SystemBarController has been garbage collected");
                return;
            }

            // Ensure UI operations run on main thread
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Log.d(TAG, "Switching to main thread for system bar operation");
                Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.post(() -> executeHideSystemBar(controller, hide));
            } else {
                executeHideSystemBar(controller, hide);
            }
        }

        private void executeenterOrExistFullScreen(AppTaskController controller) {
            Log.d(TAG, "Executing enterOrExistFullScreen");
            controller.enterOrExitFullscreen();
        }

        /**
         * Execute system bar hide/show operation.
         *
         * @param controller The SystemBarController instance
         * @param hide true to hide, false to show
         */
        private void executeHideSystemBar(SystemBarController controller, boolean hide) {
            Log.d(TAG, "Executing hideSystemBar, hide: " + hide);
            try {
                if (hide) {
                    controller.hideStatusBarNavigationBar();
                    Log.i(TAG, "System bars hidden successfully");
                } else {
                    controller.showStatusBarNavigationBar();
                    Log.i(TAG, "System bars shown successfully");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to change system bar visibility, hide: " + hide, e);
            }
        }
    }
}