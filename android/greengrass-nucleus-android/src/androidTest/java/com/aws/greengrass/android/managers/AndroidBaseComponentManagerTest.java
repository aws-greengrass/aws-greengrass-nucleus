package com.aws.greengrass.android.managers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import androidx.test.platform.app.InstrumentationRegistry;

import com.aws.greengrass.android.AndroidContextProvider;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.android.AndroidCallable;
import com.aws.greengrass.util.platforms.android.AndroidPlatform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

public class AndroidBaseComponentManagerTest {

    private Logger logger;
    private AndroidPlatform platform;
    private final String packageName = "PackageName";

    @BeforeEach
    public void setup() {
        logger = LogManager.getLogger(AndroidBaseComponentManagerTest.class);

        AndroidContextProvider contextProvider = () -> InstrumentationRegistry.getInstrumentation().getTargetContext();
        platform = (AndroidPlatform) Platform.getInstance();
        platform.setAndroidAPIs(status -> {
                },
                new AndroidBasePackageManager(contextProvider),
                new AndroidBaseComponentManager(contextProvider));
    }

    @Test
    void GIVEN_exec_WHEN_running_command_closed_THEN_success() {
        String command = "#run_service PackageName.ClassName StartIntentName";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentRunner(command, packageName, logger));
        String command2 = "#run_service .ClassName StartIntentName";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentRunner(command2, packageName, logger));
        String command3 = "#run_service";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentRunner(command3, packageName, logger));
        String command4 = "#run_service .ClassName";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentRunner(command4, packageName, logger));
    }

    @Test
    void GIVEN_exec_WHEN_running_command_closed_THEN_success2() {
        String command = "#run_service getComponentStarter wrong";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentRunner(command, packageName, logger));
        String command2 = "";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentRunner(command2, packageName, logger));
        String command3 = "#startup_service";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentRunner(command3, packageName, logger));
    }

    @Test
    void GIVEN_exec_WHEN_running_command_closed_THEN_success3() {
        String command = "#startup_service PackageName.ClassName StartIntentName";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentStarter(command, packageName, logger));
        String command2 = "#startup_service .ClassName StartIntentName";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentStarter(command2, packageName, logger));
        String command3 = "#startup_service";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentStarter(command3, packageName, logger));
        String command4 = "#startup_service .ClassName";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentStarter(command4, packageName, logger));
    }

    @Test
    void GIVEN_exec_WHEN_running_command_closed_THEN_success4() {
        String command = "#startup_service PackageName.ClassName StartIntentName wrong";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentStarter(command, packageName, logger));
        String command2 = "";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentStarter(command2, packageName, logger));
        String command3 = "#run_service";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentStarter(command3, packageName, logger));
    }

    @Test
    void GIVEN_exec_WHEN_running_command_closed_THEN_success5() {
        String command = "#shutdown_service PackageName.ClassName";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentStopper(command, packageName, logger));
        String command2 = "#shutdown_service .ClassName";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentStopper(command2, packageName, logger));
        String command3 = "#shutdown_service";
        assertDoesNotThrow((Executable) () -> platform.getAndroidComponentManager().getComponentStopper(command3, packageName, logger));
    }

    @Test
    void GIVEN_exec_WHEN_running_command_closed_THEN_success6() {
        String command = "#shutdown_service PackageName.ClassName wrong";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentStopper(command, packageName, logger));
        String command2 = "";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentStopper(command2, packageName, logger));
        String command3 = "#run_service";
        assertThrows(RuntimeException.class,
                (Executable) () -> platform.getAndroidComponentManager().getComponentStopper(command3, packageName, logger));
    }

    @Test
    void GIVEN_exec_WHEN_running_command_closed_THEN_success7() {
        String command = "#run_service PackageName.ClassName StartIntentName";
        AndroidCallable androidCallable = platform.getAndroidComponentManager().getComponentRunner(command, packageName, logger);
        assertThrows(RuntimeException.class, (Executable) androidCallable::call);
    }

    @Test
    void GIVEN_exec_WHEN_running_command_closed_THEN_success8() {
        String command = "#run_service PackageName.ClassName StartIntentName";
        AndroidCallable androidCallable = platform.getAndroidComponentManager().getComponentRunner(command, packageName, logger);
        assertThrows(RuntimeException.class, (Executable) androidCallable::call);
    }

    @Test
    void GIVEN_exec_WHEN_running_command_closed_THEN_success9() {
        String command = "#startup_service PackageName.ClassName StartIntentName";
        AndroidCallable androidCallable = platform.getAndroidComponentManager().getComponentStarter(command, packageName, logger);
        assertThrows(RuntimeException.class, (Executable) androidCallable::call);
    }

    @Test
    void GIVEN_exec_WHEN_running_command_closed_THEN_success10() {
        String command = "#shutdown_service PackageName.ClassName";
        AndroidCallable androidCallable = platform.getAndroidComponentManager().getComponentStopper(command, packageName, logger);
        assertDoesNotThrow((Executable) androidCallable::call);
    }
}
