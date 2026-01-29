
package com.android.wm.shell.sysui;
import com.android.wm.shell.windowdecor.WindowDecorViewModel;
import android.util.Log;

public class DecorShellService extends IDecorShellService.Stub {
    private static final String TAG = "DecorShellService";
    private final WindowDecorViewModel mDecorViewModel;

    public DecorShellService(WindowDecorViewModel vm) {
        mDecorViewModel = vm;
    }

    @Override
    public void setDecorEnabled(int taskId, boolean enabled) {
        // mDecorViewModel.setDecorEnabled(taskId, enabled);
    }

    @Override
    public void maximizeTask() {
        Log.w(TAG,"maximizeTask..........");
        mDecorViewModel.maximizeTask();
    }

    @Override
    public void minimizeTask() {
        Log.w(TAG,"minimizeTask..........");
        mDecorViewModel.minimizeTask();
    }

    @Override
    public void closeTask() {
        mDecorViewModel.closeTask();
    }

    @Override
    public boolean isTaskMaximized() {
       return  mDecorViewModel.isTaskMaximized();
    }
}
