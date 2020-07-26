package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import com.aws.iot.evergreen.util.Exec;
import com.aws.iot.evergreen.util.platforms.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static com.aws.iot.evergreen.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShellRunnerTest extends EGServiceTestUtil {

    @Mock
    private Topic uniqueId;

    @Mock
    private Kernel kernel;

    @TempDir
    protected Path tempDir;

    private EvergreenService evergreenService;

    @BeforeEach
    void beforeEach() {
        Topics config = initializeMockedConfig();
        Topics serviceRuntimeTopics = mock(Topics.class);

        when(config.lookupTopics(EvergreenService.RUNTIME_STORE_NAMESPACE_TOPIC)).thenReturn(serviceRuntimeTopics);
        when(serviceRuntimeTopics.findLeafChild(SERVICE_UNIQUE_ID_KEY)).thenReturn(uniqueId);
        when(kernel.getWorkPath()).thenReturn(tempDir);
        evergreenService = new EvergreenService(config);
    }

    @Test
    void GIVEN_shell_command_WHEN_setup_THEN_sets_exec_cwd_to_work_path_with_service() throws Exception {
        try (Context context = new Context()) {
            context.put(Kernel.class, kernel);
            final ShellRunner shellRunner = context.get(ShellRunner.class);
            try (Exec exec = shellRunner.setup("note", "echo hi", evergreenService)) {
                assertEquals(kernel.getWorkPath().resolve(evergreenService.getName()).toFile().toString(),
                        exec.cwd().toString());
            }
        }
    }

    @Test
    void GIVEN_shell_command_WHEN_run_in_foreground_THEN_succeeds() throws Exception {
        try (Context context = new Context()) {
            context.put(Kernel.class, kernel);
            final ShellRunner shellRunner = context.get(ShellRunner.class);
            try (Exec exec = shellRunner.setup("note", "echo hi", evergreenService)) {
                boolean ok = shellRunner.successful(exec, "note", null, evergreenService);
                assertTrue(ok);
                assertFalse(exec.isRunning());
            }
        }
    }

    @Test
    void GIVEN_shell_command_WHEN_run_in_background_THEN_succeeds() throws Exception {
        final AtomicInteger exitCode = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        IntConsumer background = (value) -> {
            exitCode.set(value);
            latch.countDown();
        };

        try (Context context = new Context()) {
            context.put(Kernel.class, kernel);
            final ShellRunner shellRunner = context.get(ShellRunner.class);
            try (Exec exec = shellRunner.setup("note", "echo 0", evergreenService)) {
                boolean ok = shellRunner.successful(exec, "note", background, evergreenService);
                assertTrue(ok);
                assertTrue(latch.await(2, TimeUnit.SECONDS));
                assertEquals(0, exitCode.get());
                assertFalse(exec.isRunning());
            }
        }
    }

    @Test
    void GIVEN_shell_command_that_doesnt_exist_WHEN_run_in_foreground_THEN_fails() throws Exception {
        try (Context context = new Context()) {
            context.put(Kernel.class, kernel);
            final ShellRunner shellRunner = context.get(ShellRunner.class);
            try (Exec exec = shellRunner.setup("note", "there_is_no_such_program", evergreenService)) {
                boolean ok = shellRunner.successful(exec, "note", null, evergreenService);
                assertFalse(ok);
                assertFalse(exec.isRunning());
            }
        }
    }

    @Test
    void GIVEN_shell_command_that_doesnt_exist_WHEN_run_in_background_THEN_fails() throws Exception {
        final AtomicInteger exitCode = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        IntConsumer background = (value) -> {
            exitCode.set(value);
            latch.countDown();
        };

        try (Context context = new Context()) {
            context.put(Kernel.class, kernel);
            final ShellRunner shellRunner = context.get(ShellRunner.class);
            try (Exec exec = shellRunner.setup("note", "there_is_no_such_program", evergreenService)) {
                boolean ok = shellRunner.successful(exec, "note", background, evergreenService);
                assertTrue(ok); // when runs in background, always return true
                assertTrue(latch.await(2, TimeUnit.SECONDS));
                assertEquals(Platform.getInstance().exitCodeWhenCommandDoesNotExist(), exitCode.get());
                assertFalse(exec.isRunning());
            }
        }
    }
}
