package software.amazon.awssdk.greengrass_nucleus_android;

import android.app.Application;

public class ContextHolder {

    private static ContextHolder INSTANCE;

    private ContextHolder() {
    }

    public static ContextHolder getInstance() {
        if (INSTANCE == null)
            synchronized (ContextHolder.class) {
                if (INSTANCE == null)
                    INSTANCE = new ContextHolder();
            }
        return INSTANCE;
    }

    public Application context;
}