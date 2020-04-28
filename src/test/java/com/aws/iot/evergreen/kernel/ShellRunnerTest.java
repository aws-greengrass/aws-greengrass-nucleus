package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import com.aws.iot.evergreen.util.Exec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static com.aws.iot.evergreen.ipc.AuthHandler.SERVICE_UNIQUE_ID_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class ShellRunnerTest extends EGServiceTestUtil {

    @Mock
    private Topic uniqueId;

    @Mock
    private Kernel kernel;

    private EvergreenService evergreenService;

    @BeforeEach
    void beforeEach() {
        Topics config = initializeMockedConfig();
        when(config.findLeafChild(SERVICE_UNIQUE_ID_KEY)).thenReturn(uniqueId);
        when(kernel.getWorkPath()).thenReturn(Paths.get(System.getProperty("user.dir")));
        evergreenService = new EvergreenService(config);
    }

    @Test
    void testForeground() throws Exception {
        try (Context context = new Context()) {
            context.put(Kernel.class, kernel);
            final ShellRunner shellRunner = context.get(ShellRunner.class);
            try (Exec exec = shellRunner.setup("note", "echo hi", evergreenService)) {
                boolean ok = shellRunner.successful(exec, "note", null, evergreenService);
                assertTrue(ok);
            }
        }
    }

    @Test
    void testBackground() throws Exception {
        final AtomicInteger exitCode = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        IntConsumer background = (value) -> {
            exitCode.set(value);
            latch.countDown();
        };

        try (Context context = new Context()) {
            context.put(Kernel.class, kernel);
            final ShellRunner shellRunner = context.get(ShellRunner.class);
            try (Exec exec = shellRunner.setup("note", "sleep 5", evergreenService)) {
                boolean ok = shellRunner.successful(exec, "note", background, evergreenService);
                assertTrue(ok);
                assertTrue(latch.await(10, TimeUnit.SECONDS));
                assertEquals(0, exitCode.get());
            }
        }
    }
}
