/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class DeploymentQueueTest {
    private static final DeploymentDocument TEST_DEPLOYMENT_DOCUMENT = new DeploymentDocument();
    private static final String TEST_DEPLOYMENT_ID_1 = "deployment-1";
    private static final String TEST_DEPLOYMENT_ID_2 = "deployment-2";
    private static final String TEST_DEPLOYMENT_ID_3 = "deployment-3";

    /**
     * Default deployment with id=1
     */
    private final Deployment TEST_DEPLOYMENT_1 = new Deployment(
            TEST_DEPLOYMENT_DOCUMENT,
            Deployment.DeploymentType.LOCAL,
            TEST_DEPLOYMENT_ID_1,
            Deployment.DeploymentStage.DEFAULT);

    /**
     * Default deployment with id=2
     */
    private final Deployment TEST_DEPLOYMENT_2 = new Deployment(
            TEST_DEPLOYMENT_DOCUMENT,
            Deployment.DeploymentType.LOCAL,
            TEST_DEPLOYMENT_ID_2,
            Deployment.DeploymentStage.DEFAULT);

    /**
     * Default deployment with id=3
     */
    private final Deployment TEST_DEPLOYMENT_3 = new Deployment(
            TEST_DEPLOYMENT_DOCUMENT,
            Deployment.DeploymentType.LOCAL,
            TEST_DEPLOYMENT_ID_3,
            Deployment.DeploymentStage.DEFAULT);

    /**
     * Bootstrap deployment with id=1
     */
    private final Deployment TEST_DEPLOYMENT_1_BOOTSTRAP = new Deployment(
            TEST_DEPLOYMENT_DOCUMENT,
            Deployment.DeploymentType.LOCAL,
            TEST_DEPLOYMENT_ID_1,
            Deployment.DeploymentStage.BOOTSTRAP);

    /**
     * Bootstrap deployment with id=2
     */
    private final Deployment TEST_DEPLOYMENT_2_BOOTSTRAP = new Deployment(
            TEST_DEPLOYMENT_DOCUMENT,
            Deployment.DeploymentType.LOCAL,
            TEST_DEPLOYMENT_ID_2,
            Deployment.DeploymentStage.BOOTSTRAP);

    /**
     * Bootstrap deployment with id=3
     */
    private final Deployment TEST_DEPLOYMENT_3_BOOTSTRAP = new Deployment(
            TEST_DEPLOYMENT_DOCUMENT,
            Deployment.DeploymentType.LOCAL,
            TEST_DEPLOYMENT_ID_3,
            Deployment.DeploymentStage.BOOTSTRAP);

    /**
     * Cancelled deployment with id=1
     */
    private final Deployment TEST_DEPLOYMENT_1_CANCELLED = new Deployment(
            Deployment.DeploymentType.LOCAL,
            TEST_DEPLOYMENT_ID_1,
            true);

    /**
     * Cancelled deployment with id=2
     */
    private final Deployment TEST_DEPLOYMENT_2_CANCELLED = new Deployment(
            Deployment.DeploymentType.LOCAL,
            TEST_DEPLOYMENT_ID_2,
            true);

    /**
     * Cancelled deployment with id=3
     */
    private final Deployment TEST_DEPLOYMENT_3_CANCELLED = new Deployment(
            Deployment.DeploymentType.LOCAL,
            TEST_DEPLOYMENT_ID_3,
            true);

    /**
     * Shadow deployment with id=1
     */
    private final Deployment TEST_DEPLOYMENT_1_SHADOW = new Deployment(
            TEST_DEPLOYMENT_DOCUMENT,
            Deployment.DeploymentType.SHADOW,
            TEST_DEPLOYMENT_ID_1,
            Deployment.DeploymentStage.DEFAULT);

    /**
     * Shadow deployment with id=2
     */
    private final Deployment TEST_DEPLOYMENT_2_SHADOW = new Deployment(
            TEST_DEPLOYMENT_DOCUMENT,
            Deployment.DeploymentType.SHADOW,
            TEST_DEPLOYMENT_ID_2,
            Deployment.DeploymentStage.DEFAULT);

    /**
     * Shadow deployment with id=3
     */
    private final Deployment TEST_DEPLOYMENT_3_SHADOW = new Deployment(
            TEST_DEPLOYMENT_DOCUMENT,
            Deployment.DeploymentType.SHADOW,
            TEST_DEPLOYMENT_ID_3,
            Deployment.DeploymentStage.DEFAULT);

    private DeploymentQueue deploymentQueue;
    private boolean result;
    private Deployment pollResult;

    @BeforeEach
    void setup() {
        deploymentQueue = new DeploymentQueue();
    }

    @Test
    void GIVEN_deployment_queue_WHEN_offer_deployments_THEN_queue_contains_deployments_in_fifo_order() {
        assertThat(deploymentQueue.isEmpty(), is(true));
        assertThat(deploymentQueue.toArray(), empty());
        result = deploymentQueue.offer(TEST_DEPLOYMENT_1);
        assertThat(result, is(true));
        assertThat(deploymentQueue.isEmpty(), is(false));
        assertThat(deploymentQueue.toArray(), contains(TEST_DEPLOYMENT_1));
        result = deploymentQueue.offer(TEST_DEPLOYMENT_2);
        assertThat(result, is(true));
        assertThat(deploymentQueue.isEmpty(), is(false));
        assertThat(deploymentQueue.toArray(), contains(TEST_DEPLOYMENT_1, TEST_DEPLOYMENT_2));
        result = deploymentQueue.offer(TEST_DEPLOYMENT_3);
        assertThat(result, is(true));
        assertThat(deploymentQueue.isEmpty(), is(false));
        assertThat(deploymentQueue.toArray(), contains(TEST_DEPLOYMENT_1, TEST_DEPLOYMENT_2, TEST_DEPLOYMENT_3));
    }

    @Test
    void GIVEN_deployment_queue_WHEN_remove_deployments_THEN_elements_removed_in_fifo_order() {
        deploymentQueue.offer(TEST_DEPLOYMENT_1);
        deploymentQueue.offer(TEST_DEPLOYMENT_2);
        deploymentQueue.offer(TEST_DEPLOYMENT_3);
        pollResult = deploymentQueue.poll();
        assertThat(pollResult, is(TEST_DEPLOYMENT_1));
        assertThat(deploymentQueue.toArray(), contains(TEST_DEPLOYMENT_2, TEST_DEPLOYMENT_3));
        pollResult = deploymentQueue.poll();
        assertThat(pollResult, is(TEST_DEPLOYMENT_2));
        assertThat(deploymentQueue.toArray(), contains(TEST_DEPLOYMENT_3));
        pollResult = deploymentQueue.poll();
        assertThat(pollResult, is(TEST_DEPLOYMENT_3));
        assertThat(deploymentQueue.isEmpty(), is(true));
        assertThat(deploymentQueue.toArray(), empty());
    }

    @Test
    void GIVEN_deployment_queue_WHEN_offer_null_deployment_THEN_throw_exception() {
        assertThrows(NullPointerException.class, () -> {
            deploymentQueue.offer(null);
        });
    }

    @Test
    void GIVEN_deployment_queue_WHEN_poll_empty_queue_THEN_get_null() {
        assertThat(deploymentQueue.isEmpty(), is(true));
        assertThat(deploymentQueue.toArray(), empty());
        assertNull(deploymentQueue.poll());
        assertNull(deploymentQueue.poll());
    }

    @Test
    void GIVEN_deployment_queue_WHEN_offer_duplicate_deployments_THEN_queue_is_not_modified() {
        deploymentQueue.offer(TEST_DEPLOYMENT_1);
        deploymentQueue.offer(TEST_DEPLOYMENT_2);
        deploymentQueue.offer(TEST_DEPLOYMENT_3);

        result = deploymentQueue.offer(TEST_DEPLOYMENT_1);
        assertThat(result, is(false));
        assertThat(deploymentQueue.toArray(), contains(TEST_DEPLOYMENT_1, TEST_DEPLOYMENT_2, TEST_DEPLOYMENT_3));

        result = deploymentQueue.offer(TEST_DEPLOYMENT_2);
        assertThat(result, is(false));
        assertThat(deploymentQueue.toArray(), contains(TEST_DEPLOYMENT_1, TEST_DEPLOYMENT_2, TEST_DEPLOYMENT_3));

        result = deploymentQueue.offer(TEST_DEPLOYMENT_3);
        assertThat(result, is(false));
        assertThat(deploymentQueue.toArray(), contains(TEST_DEPLOYMENT_1, TEST_DEPLOYMENT_2, TEST_DEPLOYMENT_3));
    }

    @Test
    void GIVEN_deployment_queue_WHEN_offer_bootstrap_deployments_THEN_queue_replaces_default_deployments() {
        deploymentQueue.offer(TEST_DEPLOYMENT_1);
        deploymentQueue.offer(TEST_DEPLOYMENT_2);
        deploymentQueue.offer(TEST_DEPLOYMENT_3);

        result = deploymentQueue.offer(TEST_DEPLOYMENT_1_BOOTSTRAP);
        assertThat(result, is(true));
        assertThat(deploymentQueue.toArray(),
                contains(TEST_DEPLOYMENT_1_BOOTSTRAP, TEST_DEPLOYMENT_2, TEST_DEPLOYMENT_3));

        result = deploymentQueue.offer(TEST_DEPLOYMENT_2_BOOTSTRAP);
        assertThat(result, is(true));
        assertThat(deploymentQueue.toArray(),
                contains(TEST_DEPLOYMENT_1_BOOTSTRAP, TEST_DEPLOYMENT_2_BOOTSTRAP, TEST_DEPLOYMENT_3));

        result = deploymentQueue.offer(TEST_DEPLOYMENT_3_BOOTSTRAP);
        assertThat(result, is(true));
        assertThat(deploymentQueue.toArray(),
                contains(TEST_DEPLOYMENT_1_BOOTSTRAP, TEST_DEPLOYMENT_2_BOOTSTRAP, TEST_DEPLOYMENT_3_BOOTSTRAP));
    }

    @Test
    void GIVEN_deployment_queue_WHEN_offer_cancelled_deployments_THEN_queue_replaces_existing_deployments() {
        deploymentQueue.offer(TEST_DEPLOYMENT_1);
        deploymentQueue.offer(TEST_DEPLOYMENT_2_BOOTSTRAP);
        deploymentQueue.offer(TEST_DEPLOYMENT_3);

        result = deploymentQueue.offer(TEST_DEPLOYMENT_1_CANCELLED);
        assertThat(result, is(true));
        assertThat(deploymentQueue.toArray(),
                contains(TEST_DEPLOYMENT_1_CANCELLED, TEST_DEPLOYMENT_2_BOOTSTRAP, TEST_DEPLOYMENT_3));

        result = deploymentQueue.offer(TEST_DEPLOYMENT_2_CANCELLED);
        // a bootstrap deployment cannot be cancelled
        assertThat(result, is(false));
        assertThat(deploymentQueue.toArray(),
                contains(TEST_DEPLOYMENT_1_CANCELLED, TEST_DEPLOYMENT_2_BOOTSTRAP, TEST_DEPLOYMENT_3));

        result = deploymentQueue.offer(TEST_DEPLOYMENT_3_CANCELLED);
        assertThat(result, is(true));
        assertThat(deploymentQueue.toArray(),
                contains(TEST_DEPLOYMENT_1_CANCELLED, TEST_DEPLOYMENT_2_BOOTSTRAP, TEST_DEPLOYMENT_3_CANCELLED));
    }

    @Test
    void GIVEN_deployment_queue_WHEN_offer_shadow_deployments_THEN_at_most_one_shadow_deployment_enqueued() {
        deploymentQueue.offer(TEST_DEPLOYMENT_1);
        deploymentQueue.offer(TEST_DEPLOYMENT_2);
        result = deploymentQueue.offer(TEST_DEPLOYMENT_1_SHADOW);
        assertThat(result, is(true));
        assertThat(deploymentQueue.toArray(),
                contains(TEST_DEPLOYMENT_1, TEST_DEPLOYMENT_2, TEST_DEPLOYMENT_1_SHADOW));
        deploymentQueue.offer(TEST_DEPLOYMENT_3);
        assertThat(deploymentQueue.toArray(),
                contains(TEST_DEPLOYMENT_1, TEST_DEPLOYMENT_2, TEST_DEPLOYMENT_1_SHADOW, TEST_DEPLOYMENT_3));

        result = deploymentQueue.offer(TEST_DEPLOYMENT_1_SHADOW);
        assertThat(result, is(true));
        assertThat(deploymentQueue.toArray(),
                contains(TEST_DEPLOYMENT_1, TEST_DEPLOYMENT_2, TEST_DEPLOYMENT_1_SHADOW, TEST_DEPLOYMENT_3));

        result = deploymentQueue.offer(TEST_DEPLOYMENT_2_SHADOW);
        assertThat(result, is(true));
        assertThat(deploymentQueue.toArray(),
                contains(TEST_DEPLOYMENT_1, TEST_DEPLOYMENT_2, TEST_DEPLOYMENT_2_SHADOW, TEST_DEPLOYMENT_3));

        result = deploymentQueue.offer(TEST_DEPLOYMENT_3_SHADOW);
        assertThat(result, is(true));
        assertThat(deploymentQueue.toArray(),
                contains(TEST_DEPLOYMENT_1, TEST_DEPLOYMENT_2, TEST_DEPLOYMENT_3_SHADOW, TEST_DEPLOYMENT_3));

        deploymentQueue.poll();
        deploymentQueue.poll();
        pollResult = deploymentQueue.poll();
        assertThat(pollResult, is(TEST_DEPLOYMENT_3_SHADOW));
        assertThat(deploymentQueue.toArray(), contains(TEST_DEPLOYMENT_3));
    }

    @Test
    void GIVEN_deployment_queue_WHEN_modify_queue_concurrently_from_two_threads_THEN_queue_is_consistent()
            throws InterruptedException {

        // Offer 2000 unique elements concurrently with 2 threads
        Consumer offerOneThousandDeployments = (threadName) -> {
            for (int i = 0; i < 1000; i++) {
                deploymentQueue.offer(new Deployment(
                        TEST_DEPLOYMENT_DOCUMENT,
                        Deployment.DeploymentType.LOCAL,
                        "deployment-" + threadName + i,
                        Deployment.DeploymentStage.DEFAULT));
            }
        };
        Thread thread1 = new Thread(() -> {
            offerOneThousandDeployments.accept("thread1");
        });
        Thread thread2 = new Thread(() -> {
            offerOneThousandDeployments.accept("thread2");
        });
        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
        assertThat(deploymentQueue.toArray(), hasSize(2000));

        // Remove 1998 elements concurrently with 2 threads
        Runnable removeNineHundredNinetyNineDeployments = () -> {
            for (int i = 0; i < 999; i++) {
                deploymentQueue.poll();
            }
        };
        thread1 = new Thread(removeNineHundredNinetyNineDeployments);
        thread2 = new Thread(removeNineHundredNinetyNineDeployments);
        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
        assertThat(deploymentQueue.toArray(), hasSize(2));
    }

}
