/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.builtin.services.lifecycle.DeferUpdateRequest;
import com.aws.iot.evergreen.builtin.services.lifecycle.LifecycleIPCAgent;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Crashable;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.ipc.services.lifecycle.PostComponentUpdateEvent;
import com.aws.iot.evergreen.ipc.services.lifecycle.PreComponentUpdateEvent;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handles requests to update the system's configuration during safe times.
 * (or anything else that's disruptive and shouldn't be done until the system
 * is in a "safe" state).
 *
 * <p>It maintains a list of actions that will be executed when the
 * system is next "disruptable".  This is typically code that is going to install an update.
 *
 * <p>If the update service is periodic, update actions will only be processed at that time.
 * Otherwise, it the update will be processed immediately, assuming that all disruptability
 * checks pass.
 */
@ImplementsService(name = "SafeSystemUpdate", autostart = true)
@Singleton
public class UpdateSystemSafelyService extends EvergreenService {
    private final Map<DeploymentDocument, Crashable> pendingActions = new LinkedHashMap<>();
    private final AtomicBoolean runningUpdateActions = new AtomicBoolean(false);

    @Inject
    private LifecycleIPCAgent lifecycleIPCAgent;

    @Inject
    private Clock clock;

    /**
     * Constructor for injection.
     *
     * @param c topics root
     */
    @Inject
    public UpdateSystemSafelyService(Topics c) {
        super(c);
    }

    /**
     * Add an update action to be performed when the system is in a "safe" state.
     *
     * @param deploymentDocument used both as a printable description and a de-duplication key.
     *                           If the same deployment document is submitted it is ignored
     * @param action             The action to be performed.
     */
    public synchronized void addUpdateAction(DeploymentDocument deploymentDocument, Crashable action) {
        pendingActions.put(deploymentDocument, action);
        logger.atInfo().setEventType("register-service-update-action")
                .addKeyValue("action", deploymentDocument.getDeploymentId()).log();
        synchronized (pendingActions) {
            pendingActions.notifyAll();
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    protected synchronized void runUpdateActions() {
        runningUpdateActions.set(true);
        for (Map.Entry<DeploymentDocument, Crashable> todo : pendingActions.entrySet()) {
            try {
                todo.getValue().run();
                logger.atDebug().setEventType("service-update-action").addKeyValue("action", todo.getKey()).log();
            } catch (Throwable t) {
                logger.atError().setEventType("service-update-action-error").addKeyValue("action", todo.getKey())
                        .setCause(t).log();
            }
        }
        pendingActions.clear();
        lifecycleIPCAgent.sendPostComponentUpdateEvent(new PostComponentUpdateEvent());
        runningUpdateActions.set(false);
    }

    /**
     * Check if a pending action with the tag currently exists.
     *
     * @param tag tag to identify an update action
     * @return true if there is a pending action for specified tag
     */
    public boolean hasPendingUpdateAction(String tag) {
        return pendingActions.keySet().stream()
                .anyMatch(deploymentDocument -> deploymentDocument.getDeploymentId().equals(tag));
    }

    /**
     * Discard a pending action if update actions are not already running.
     *
     * @param tag tag to identify an update action
     * @return true if all update actions are pending and requested action could be discarded,
     *         false if update actions were already in progress so it's not safe to discard the requested action
     */
    public boolean discardPendingUpdateAction(String tag) {
        if (runningUpdateActions.get()) {
            return false;
        }
        Optional<DeploymentDocument> document = pendingActions.keySet().stream()
                .filter(deploymentDocument -> deploymentDocument.getDeploymentId().equals(tag))
                .findFirst();
        if (document.isPresent()) {
            pendingActions.remove(document.get());
        }
        return true;
    }

    @SuppressWarnings({"SleepWhileInLoop"})
    @Override
    protected void startup() throws InterruptedException {
        // startup() is invoked on it's own thread
        reportState(State.RUNNING);

        while (!State.FINISHED.equals(getState())) {
            synchronized (pendingActions) {
                if (pendingActions.isEmpty()) {
                    pendingActions.wait(10_000);
                    continue;
                }
            }
            logger.atInfo().setEventType("get-available-service-update").log();
            logger.atDebug().setEventType("service-update-pending").addKeyValue("numOfUpdates", pendingActions.size())
                    .log();

            //TODO: set isGgcRestarting to true if the updates involves kernel restart
            PreComponentUpdateEvent preComponentUpdateEvent = PreComponentUpdateEvent.builder()
                    .isGgcRestarting(false).build();
            List<Future<DeferUpdateRequest>> deferRequestFutures = new ArrayList<>();
            lifecycleIPCAgent.sendPreComponentUpdateEvent(preComponentUpdateEvent, deferRequestFutures);

            long timeToReCheck = getTimeToReCheck(getMaxTimeoutInMillis(), deferRequestFutures);
            if (timeToReCheck > 0) {
                logger.atDebug().setEventType("service-update-pending").addKeyValue("waitInMS", timeToReCheck).log();
                Thread.sleep(timeToReCheck);
            } else {
                lifecycleIPCAgent.discardDeferComponentUpdateFutures();
                logger.atDebug().setEventType("service-update-scheduled").log();
                try {
                    context.get(ExecutorService.class).submit(() -> {
                        logger.atInfo().setEventType("service-update-start").log();
                        runUpdateActions();
                        logger.atInfo().setEventType("service-update-finish").log();
                    }).get();
                } catch (ExecutionException e) {
                    logger.atError().setEventType("service-update-error")
                            .log("Run update actions errored", e);
                }
            }
        }
    }

    /*
     If multiple updates are present, get the max time-out. Currently
     */
    private long getMaxTimeoutInMillis() {
        Optional<Long> maxTimeoutInSec = pendingActions.keySet().stream()
                .map(deploymentDocument -> deploymentDocument.getComponentUpdatePolicy().getTimeout())
                .max(Long::compareTo);
        return TimeUnit.SECONDS.toMillis(maxTimeoutInSec.get());
    }

    private long getTimeToReCheck(long timeout, List<Future<DeferUpdateRequest>> deferRequestFutures)
            throws InterruptedException {
        final long currentTimeMillis = clock.millis();
        long maxTimeToReCheck = currentTimeMillis;
        while ((clock.millis() - currentTimeMillis) < timeout && !deferRequestFutures.isEmpty()) {
            Iterator<Future<DeferUpdateRequest>> iterator = deferRequestFutures.iterator();
            while (iterator.hasNext()) {
                Future<DeferUpdateRequest> fut = iterator.next();
                if (fut.isDone()) {
                    try {
                        DeferUpdateRequest deferRequest = fut.get();
                        long timeToRecheck = currentTimeMillis + deferRequest.getRecheckTimeInMs();
                        if (timeToRecheck > maxTimeToReCheck) {
                            maxTimeToReCheck = timeToRecheck;
                            logger.atInfo().setEventType("service-update-deferred")
                                    .log("deferred by {} for {} millis with message {}",
                                            deferRequest.getMessage(), deferRequest.getRecheckTimeInMs(),
                                            deferRequest.getMessage());
                        }
                    } catch (ExecutionException e) {
                        logger.error("Failed to process component update request", e);
                    }
                    iterator.remove();
                }
            }
            Thread.sleep(TimeUnit.SECONDS.toMillis(1));
        }
        return maxTimeToReCheck - currentTimeMillis;
    }
}
