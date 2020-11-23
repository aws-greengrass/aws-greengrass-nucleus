/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.builtin.services.lifecycle.LifecycleIPCEventStreamAgent;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.dependency.State;
import software.amazon.awssdk.aws.greengrass.model.DeferComponentUpdateRequest;
import software.amazon.awssdk.aws.greengrass.model.PostComponentUpdateEvent;
import software.amazon.awssdk.aws.greengrass.model.PreComponentUpdateEvent;

import java.time.Clock;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handles requests to update the system's configuration during disruptable times.
 * (or anything else that's disruptive and shouldn't be done until the system
 * is in a "disruptable" state).
 *
 * <p>It maintains a list of actions that will be executed when the
 * system is next "disruptable". This is typically code that is going to install an update.
 *
 * <p>If the update service is periodic, update actions will only be processed at that time.
 * Otherwise, it the update will be processed immediately, assuming that all disruptability
 * checks pass.
 */
@ImplementsService(name = "UpdateSystemPolicyService", autostart = true)
@Singleton
public class UpdateSystemPolicyService extends GreengrassService {
    // String identifies the action, the pair consist of timeout and an action. The timeout
    // represents the value in seconds the kernel will wait for components to respond to
    // an precomponent update event
    private final Map<String, UpdateAction> pendingActions = new LinkedHashMap<>();
    private final AtomicBoolean runningUpdateActions = new AtomicBoolean(false);

    @Inject
    private LifecycleIPCEventStreamAgent lifecycleIPCAgent;

    @Inject
    private Clock clock;

    /**
     * Constructor for injection.
     *
     * @param topics topics root
     */
    @Inject
    public UpdateSystemPolicyService(Topics topics) {
        super(topics);
    }

    /**
     * Add an update action to be performed when the system is in a "disruptable" state.
     *
     * @param tag          used both as a printable description and a de-duplication key.  eg. If the action is
     *                     installing a new config file, the tag should probably be the URL of the config.  If a key is
     *                     duplicated by subsequent actions, they are suppressed.
     * @param updateAction Update action to be performed.
     */
    public synchronized void addUpdateAction(String tag, UpdateAction updateAction) {
        pendingActions.put(tag, updateAction);
        logger.atInfo().setEventType("register-service-update-action").addKeyValue("action", tag).log();
        synchronized (pendingActions) {
            pendingActions.notifyAll();
        }
    }

    public synchronized Set<String> getPendingActions() {
        return new HashSet<>(pendingActions.keySet());
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    protected synchronized void runUpdateActions() {
        runningUpdateActions.set(true);
        for (Map.Entry<String, UpdateAction> todo : pendingActions.entrySet()) {
            try {
                todo.getValue().getAction().run();
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
        return pendingActions.containsKey(tag);
    }

    /**
     * Discard a pending action if update actions are not already running.
     *
     * @param  tag tag to identify an update action
     * @return true if all update actions are pending and requested action could be discarded,
     *         false if update actions were already in progress
     */
    public boolean discardPendingUpdateAction(String tag) {
        if (runningUpdateActions.get()) {
            return false;
        }
        return pendingActions.remove(tag) != null;
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

            boolean ggcRestarting = false;
            for (UpdateAction action : pendingActions.values()) {
                if (action.isGgcRestart()) {
                    ggcRestarting = true;
                    break;
                }
            }

            PreComponentUpdateEvent preComponentUpdateEvent = new PreComponentUpdateEvent();
            preComponentUpdateEvent.setIsGgcRestarting(ggcRestarting);
            String deploymentId = pendingActions.values().stream().map(UpdateAction::getDeploymentId).findFirst().get();
            preComponentUpdateEvent.setDeploymentId(deploymentId);
            List<Future<DeferComponentUpdateRequest>> deferRequestFutures =
                    lifecycleIPCAgent.sendPreComponentUpdateEvent(preComponentUpdateEvent);

            long timeToReCheck = getTimeToReCheck(getMaxTimeoutInMillis(), deploymentId, deferRequestFutures);
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
     If multiple updates are present, get the max time-out. As of now, kernel does not process multiple
     deployments at the same time and pendingActions will have only one action to run at a time.
     */
    private long getMaxTimeoutInMillis() {
        Optional<Integer> maxTimeoutInSec =
                pendingActions.values().stream().map(UpdateAction::getTimeout).max(Integer::compareTo);
        return TimeUnit.SECONDS.toMillis(maxTimeoutInSec.get());
    }

    private long getTimeToReCheck(long timeout, String deploymentId,
                                  List<Future<DeferComponentUpdateRequest>> deferRequestFutures)
            throws InterruptedException {
        final long currentTimeMillis = clock.millis();
        long maxTimeToReCheck = currentTimeMillis;
        while ((clock.millis() - currentTimeMillis) < timeout && !deferRequestFutures.isEmpty()) {
            Iterator<Future<DeferComponentUpdateRequest>> iterator = deferRequestFutures.iterator();
            while (iterator.hasNext()) {
                Future<DeferComponentUpdateRequest> fut = iterator.next();
                if (fut.isDone()) {
                    try {
                        DeferComponentUpdateRequest deferRequest = fut.get();
                        if (deploymentId.equals(deferRequest.getDeploymentId())) {
                            long timeToRecheck = currentTimeMillis + deferRequest.getRecheckAfterMs();
                            if (timeToRecheck > maxTimeToReCheck) {
                                maxTimeToReCheck = timeToRecheck;
                                logger.atInfo().setEventType("service-update-deferred")
                                        .log("deferred for {} millis with message {}",
                                                deferRequest.getRecheckAfterMs(),
                                                deferRequest.getMessage());
                            }
                        } else {
                            logger.atWarn().log("Deferral request is not for the action which is pending");
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
