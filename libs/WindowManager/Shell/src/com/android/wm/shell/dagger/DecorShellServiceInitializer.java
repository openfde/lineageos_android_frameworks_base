package com.android.wm.shell.dagger;

import dagger.Module;
import dagger.Provides;
import javax.inject.Inject;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.windowdecor.WindowDecorViewModel;
import android.util.Log;
import android.os.ServiceManager;
import com.android.wm.shell.sysui.DecorShellService;

@WMSingleton
public final class DecorShellServiceInitializer {

    @Inject
    public DecorShellServiceInitializer(
            ShellInit shellInit,
            WindowDecorViewModel decorViewModel
    ) {
        Log.w("DecorShellServiceInit", "constructor called");

        shellInit.addInitCallback(() -> {
            Log.w("DecorShellServiceInit", "register decor_shell");
            ServiceManager.addService(
                    "decor_shell",
                    new DecorShellService(decorViewModel)
            );
        }, 1);
    }
}
