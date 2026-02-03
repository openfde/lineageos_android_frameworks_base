package android.openfde;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;

/**
 * Listener interface for monitoring changes in application task status and windowing configuration.
 */
@FlaggedApi("android.openfde.openfde_api")
public interface AppTaskStatusListener {

    /** Indicates the windowing mode is not explicitly defined. */
    @FlaggedApi("android.openfde.openfde_api")
    int WINDOWING_MODE_UNDEFINED = 0;

    /** Indicates the task is in standard fullscreen mode. */
    @FlaggedApi("android.openfde.openfde_api")
    int WINDOWING_MODE_FULLSCREEN = 1;

    /** Indicates the task is in a floating, resizable freeform window. */
    @FlaggedApi("android.openfde.openfde_api")
    int WINDOWING_MODE_FREEFORM = 5;

    /**
     * Called when the task windowing mode or system bar visibility changes.
     *
     * @param windowingMode The current windowing mode.
     * @param isSystemBarVisible {@code true} if the Status/Navigation bars are currently shown.
     */
    @FlaggedApi("android.openfde.openfde_api")
    void onStatusChanged(int windowingMode, boolean isSystemBarVisible);

    /**
     * Helper method to retrieve a human-readable description of the current task state.
     *
     * @param windowingMode The current windowing mode.
     * @param isSystemBarVisible The current system bar visibility state.
     * @return A string representing the UI state.
     */
    @NonNull // 1. 必须标记返回值不为 null
    @FlaggedApi("android.openfde.openfde_api")
    default String onGetStatus(int windowingMode, boolean isSystemBarVisible){
        if(windowingMode == WINDOWING_MODE_FULLSCREEN && isSystemBarVisible){
            return "fullscreen just maximize";
        } else if( windowingMode == WINDOWING_MODE_FREEFORM ){
            return "freeform";
        } else if( windowingMode == WINDOWING_MODE_FULLSCREEN ){
            return "real fullscreen";
        }
        return "undefined";
    }

    /**
     * Generates a unique identifier for the listener instance.
     *
     * @return A unique string ID.
     */
    @NonNull // 3. 标记返回值不为 null
    @FlaggedApi("android.openfde.openfde_api")
    // 4. 更名：getListenerId -> onGetListenerId
    default String onGetListenerId() {
        return getClass().getSimpleName() + "@" + Integer.toHexString(hashCode());
    }
}