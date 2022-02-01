package com.aws.greengrass.nucleus.androidservice;

import static com.aws.greengrass.ipc.IPCEventStreamService.DEFAULT_PORT_NUMBER;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import com.aws.greengrass.easysetup.GreengrassSetup;
import java.io.File;

public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = MainActivity.class.getSimpleName();
    public static Context context;

    private Thread nucleusParentThread = new Thread(() -> {
        Log.d(LOG_TAG, "Nucleus parent thread started");

        /** Perform periodic activities here */
        try {
            // get application writable folder
            File dir = getFilesDir();

            // build greengrass v2 path and create it
            File greengrass = new File(dir, "greengrass");
            File greengrassV2 = new File(greengrass, "v2");
            greengrassV2.mkdirs();

            // set required properties
            System.setProperty("log.store", "FILE");
            System.setProperty("root", greengrassV2.getAbsolutePath());
            System.setProperty("ipc.socket.port", String.valueOf(DEFAULT_PORT_NUMBER));

            final String [] fakeArgs = { "--setup-system-service", "false" };
            GreengrassSetup.main(fakeArgs);

            /* FIXME: android: Implement right way */
            while(true) {
                Thread.sleep(30 * 1000);
            }
        } catch (Exception e) {
            Log.d(LOG_TAG, "Nucleus parent thread is terminated by exception");
        }
    });



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        MainActivity.context = MainActivity.this;
        nucleusParentThread.start();
    }
}
