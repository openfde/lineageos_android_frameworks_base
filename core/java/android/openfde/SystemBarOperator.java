package android.openfde;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;

/**
 * Interface for controlling the visibility of system-level UI components.
 * <p>
 * This is primarily used to manage the System Bar and Navigation Bar state for the current task.
 * Implementations of this interface are typically obtained via
 * {@link AppTaskControllerProxy#getSystemBarOperator()}.
 * </p>
 */
@FlaggedApi("android.openfde.openfde_api") // 1. 类级别添加特性标志
public interface SystemBarOperator {

    /**
     * Toggles the visibility of both the Status Bar and the Navigation Bar.
     *
     * @param hide {@code true} to hide the system bars for an immersive experience;
     * {@code false} to show them.
     */
    @FlaggedApi("android.openfde.openfde_api") // 2. 方法级别添加特性标志
    void toggleStatusBarNavigationBar(boolean hide);
}