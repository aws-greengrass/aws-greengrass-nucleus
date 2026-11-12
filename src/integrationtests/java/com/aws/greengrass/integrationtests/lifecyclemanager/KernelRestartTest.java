/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.lifecyclemanager;

import com.aws.greengrass.dependency.State;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.util.Coerce;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KernelRestartTest extends BaseITCase {
    private static final Duration TIMEOUT = Duration.ofSeconds(30L);
    private Kernel kernel;

    @Test
    void GIVEN_kernel_launch_cleanly_and_shutdown_WHEN_kernel_restarts_with_same_root_dir_THEN_it_is_successful() {
        // note that this test is mainly to verify system plugins restart fine with tlog

        // GIVEN
        kernel = new Kernel();
        kernel.parseArgs();
        kernel.launch();
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED), TIMEOUT));
        kernel.shutdown();

        // WHEN
        kernel = new Kernel();
        kernel.parseArgs().launch();

        // THEN
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED), TIMEOUT));
    }

    @Test
    void GIVEN_kernel_shuts_down_WHEN_kernel_restarts_with_same_root_dir_THEN_it_should_get_back_to_prev_state()
            throws Exception {
        // GIVEN
        kernel = new Kernel();
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("kernel_restart_initial_config.yaml"));
        kernel.launch();

        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED), TIMEOUT));
        assertThat(kernel.locate("service_1")::getState, eventuallyEval(is(State.FINISHED), TIMEOUT));
        assertThat(kernel.locate("service_2")::getState, eventuallyEval(is(State.FINISHED), TIMEOUT));
        assertThat(kernel.locate("service_2").getConfig().find("setenv", "key1").getOnce(), is(equalTo("value1")));
        kernel.shutdown();
        // WHEN
        kernel = new Kernel();
        kernel.parseArgs().launch();
        // THEN
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED), TIMEOUT));
        assertThat(kernel.locate("service_1")::getState, eventuallyEval(is(State.FINISHED), TIMEOUT));
        assertThat(kernel.locate("service_2")::getState, eventuallyEval(is(State.FINISHED), TIMEOUT));
        assertThat(kernel.locate("service_2").getConfig().find("setenv", "key1").getOnce(), is(equalTo("value1")));
    }


    @Test
    void GIVEN_kernel_shuts_down_WHEN_kernel_restarts_with_a_new_config_THEN_it_should_start_with_the_new_config()
            throws Exception {
        // GIVEN
        kernel = new Kernel();
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("kernel_restart_initial_config.yaml"));
        kernel.launch();

        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED), TIMEOUT));
        assertThat(kernel.locate("service_1")::getState, eventuallyEval(is(State.FINISHED), TIMEOUT));
        assertThat(kernel.locate("service_2")::getState, eventuallyEval(is(State.FINISHED), TIMEOUT));
        assertThat(kernel.locate("service_2").getConfig().find("setenv", "key1").getOnce(), is(equalTo("value1")));

        kernel.shutdown();

        // WHEN
        // start Nucleus with parseArgs input so previous config tlog will be ignored.
        kernel = new Kernel();
        kernel.parseArgs("-i",
                this.getClass().getResource("kernel_restart_new_config.yaml").toString());
        kernel.launch();

        // THEN
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED), TIMEOUT));

        // service 3 is added
        assertThat(kernel.locate("service_3")::getState, eventuallyEval(is(State.FINISHED), TIMEOUT));

        // service 2's setenv is updated
        assertThat(kernel.locate("service_2")::getState, eventuallyEval(is(State.FINISHED), TIMEOUT));
        assertThat(kernel.locate("service_2").getConfig().find("setenv", "key1").getOnce(), is(equalTo("new_value1")));

        // service 1 is removed
        assertThrows(ServiceLoadException.class, () -> kernel.locate("service_1"),
                "actual kernel config: " + kernel.getConfig().toPOJO());
    }

    @Test
    void GIVEN_config_tlog_exceeds_threshold_WHEN_kernel_restarts_THEN_compacted_and_config_preserved()
            throws Exception {
        // GIVEN a running kernel with a low boot-compaction threshold and an oversized config.tlog.
        kernel = new Kernel();
        kernel.parseArgs();
        kernel.launch();
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED), TIMEOUT));

        Path configTlog = kernel.getNucleusPaths().configPath().resolve("config.tlog");

        // Persist a low threshold (so the restart boot reads it back) and bloat the tlog with many
        // last-writer-wins writes to one topic: many tlog lines, but a tiny effective config.
        kernel.getContext().runOnPublishQueueAndWait(() -> {
            kernel.getConfig().lookup("services", "aws.greengrass.Nucleus", "configuration",
                    "bootConfigTlogCompactionThresholdBytes").withValue(20_000L);
            for (int i = 0; i < 2000; i++) {
                kernel.getConfig().lookup("e2eCompactionProbe", "counter").withValue(i);
            }
        });
        kernel.getContext().waitForPublishQueueToClear();

        long bloatedSize = Files.size(configTlog);
        assertThat(bloatedSize, greaterThan(20_000L));
        kernel.shutdown();

        // WHEN the kernel restarts with the same root dir, boot compaction rewrites the oversized tlog.
        kernel = new Kernel();
        kernel.parseArgs().launch();
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED), TIMEOUT));

        // THEN config.tlog was compacted and the probe value survived the compaction round-trip.
        assertThat(Files.size(configTlog), lessThan(bloatedSize));
        assertThat(Coerce.toInt(kernel.getConfig().find("e2eCompactionProbe", "counter")), is(equalTo(1999)));
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }
}
