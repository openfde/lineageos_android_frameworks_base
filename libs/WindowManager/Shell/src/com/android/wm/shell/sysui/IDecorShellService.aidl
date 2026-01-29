package com.android.wm.shell.sysui;

interface IDecorShellService {
    void setDecorEnabled(int taskId, boolean enabled);
    void maximizeTask();
    void minimizeTask();
    void closeTask();
    boolean isTaskMaximized();
}
