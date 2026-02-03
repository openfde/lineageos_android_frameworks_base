package android.openfde;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface for communicating with the Window Manager Shell (WmShell).
 * Provides a standardized way to trigger window-level operations via operation codes.
 */
@FlaggedApi("android.openfde.openfde_api")
public interface WmShellCaller {

    /** @hide */
    @IntDef(prefix = {"OPERATION_"}, value = {
            OPERATION_CLOSE,
            OPERATION_BACK,
            OPERATION_FULLSCREEN,
            OPERATION_MINIMIZE,
            OPERATION_MAXIMIZE,
            OPERATION_WINDOWDECORATION_RELAYOUT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ShellOperation {}

    /** Operation code to close the current application task. */
    @FlaggedApi("android.openfde.openfde_api")
    int OPERATION_CLOSE = 0;

    /** Operation code to simulate a back button press. */
    @FlaggedApi("android.openfde.openfde_api")
    int OPERATION_BACK = 1;

    /** Operation code to toggle the task into fullscreen mode. */
    @FlaggedApi("android.openfde.openfde_api")
    int OPERATION_FULLSCREEN = 2;

    /** Operation code to minimize the task window. */
    @FlaggedApi("android.openfde.openfde_api")
    int OPERATION_MINIMIZE = 3;

    /** Operation code to maximize the task window. */
    @FlaggedApi("android.openfde.openfde_api")
    int OPERATION_MAXIMIZE = 4;

    /** Operation code to request a relayout of the window decoration (caption/borders). */
    @FlaggedApi("android.openfde.openfde_api")
    int OPERATION_WINDOWDECORATION_RELAYOUT = 5;

    /**
     * Executes a specific task operation.
     *
     * @param opCode The identifier of the operation to perform.
     */
    @FlaggedApi("android.openfde.openfde_api")
    void callTaskOperation(@ShellOperation int opCode);
}