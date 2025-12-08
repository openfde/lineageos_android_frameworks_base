package com.android.internal.policy;

/** @hide */
public interface AppTaskController {

    void close();
    void back();
    void enterOrExitFullscreen();
    void minimize();
    void maximizeOrNot();
    void updateDecoration();

}