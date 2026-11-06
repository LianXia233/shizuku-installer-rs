package io.github.lianxia233.shizukuinst;

import android.app.Application;
import android.content.Context;

public final class App extends Application {
    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
    }

    public static Context context() {
        return appContext;
    }
}
