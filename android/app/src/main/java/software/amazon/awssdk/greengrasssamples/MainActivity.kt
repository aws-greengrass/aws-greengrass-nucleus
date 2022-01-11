package software.amazon.awssdk.greengrasssamples

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.aws.greengrass.easysetup.GreengrassSetup
import java.io.File

class MainActivity : AppCompatActivity() {
    companion object {
        lateinit var context: Context
    }

    private val nucleusParentThread = Thread {
        Log.d(
            "LOG_TAG",
            "Nucleus parent thread started"
        )
        /** Perform periodic activities here  */
        /** Perform periodic activities here  */
        try {
            // get applicationj writable folder
            val dir = filesDir

            // build greengrass v2 path and create it
            val greengrass = File(dir, "greengrass")
            val greengrassV2 = File(greengrass, "v2")
            greengrassV2.mkdirs()

            // set required properties
            System.setProperty("log.store", "FILE")
            System.setProperty("root", greengrassV2.absolutePath)
            val fakeArgs =
                arrayOf("--setup-system-service", "false")
            GreengrassSetup.main(fakeArgs)

            /* FIXME: Implement right way */while (true) {
                Thread.sleep((30 * 1000).toLong())
            }
/*
            Date now = Calendar.getInstance().getTime();
            SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss yyyy-MM-dd");
            String formattedDate = df.format(now);
            Log.d(LOG_TAG, "Nucleus parent thread is terminated. Timestamp: " + formattedDate);
*/
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        MainActivity.context = applicationContext
        nucleusParentThread.start()
    }
}