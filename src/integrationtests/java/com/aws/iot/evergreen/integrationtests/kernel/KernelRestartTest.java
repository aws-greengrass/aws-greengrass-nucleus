/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.kernel;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.integrationtests.BaseITCase;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KernelRestartTest extends BaseITCase {

    private Kernel kernel;

    @Test
    void GIVEN_kernel_launch_cleanly_and_shutdown_WHEN_kernel_restarts_with_same_root_dir_THEN_it_is_successful() {
        // note that this test is mainly to verify system plugins restart fine with tlog

        // GIVEN
        kernel = new Kernel().parseArgs();
        kernel.launch();
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        kernel.shutdown();

        // WHEN
        kernel = new Kernel().parseArgs().launch();

        // THEN
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
    }

    @Test
    void GIVEN_kernel_shuts_down_WHEN_kernel_restarts_with_same_root_dir_THEN_it_should_get_back_to_prev_state()
            throws ServiceLoadException {
        // GIVEN
        kernel = new Kernel()
                .parseArgs("-i", this.getClass().getResource("kernel_restart_initial_config.yaml").toString());
        kernel.launch();

        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("service_1")::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("service_2")::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("service_2").getConfig().find("setenv", "key1").getOnce(), is(equalTo("value1")));

        kernel.shutdown();

        // WHEN
        kernel = new Kernel().parseArgs().launch();

        // THEN
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("service_1")::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("service_2")::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("service_2").getConfig().find("setenv", "key1").getOnce(), is(equalTo("value1")));

    }


    @Test
    void GIVEN_kernel_shuts_down_WHEN_kernel_restarts_with_a_new_config_THEN_it_should_start_with_the_new_config()
            throws ServiceLoadException {
        // GIVEN
        kernel = new Kernel()
                .parseArgs("-i", this.getClass().getResource("kernel_restart_initial_config.yaml").toString());
        kernel.launch();

        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("service_1")::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("service_2")::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("service_2").getConfig().find("setenv", "key1").getOnce(), is(equalTo("value1")));

        kernel.shutdown();

        // WHEN
        kernel = new Kernel().parseArgs("-i", this.getClass().getResource("kernel_restart_new_config.yaml").toString())
                .launch();

        // THEN
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));

        // service 3 is added
        assertThat(kernel.locate("service_3")::getState, eventuallyEval(is(State.FINISHED)));

        // service 2's setenv is updated
        assertThat(kernel.locate("service_2")::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("service_2").getConfig().find("setenv", "key1").getOnce(), is(equalTo("new_value1")));

        // service 1 is removed
        assertThrows(ServiceLoadException.class, () -> kernel.locate("service_1"));
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }
}
