/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Crashable;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;

import java.util.LinkedHashMap;
import java.util.Map;
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
    private final Map<String, Crashable> pendingActions = new LinkedHashMap<>();

    private final Kernel kernel;

    /**
     * Constructor for injection.
     *
     * @param c topics root
     * @param k kernel
     */
    @Inject
    public UpdateSystemSafelyService(Topics c, Kernel k) {
        super(c);
        this.kernel = k;
    }

    /**
     * Add an update action to be performed when the system is in a "safe" state.
     *
     * @param tag    used both as a printable description and a de-duplication key.  eg. If
     *               the action is installing a new config file, the tag should probably be the
     *               URL of the config.  If a key is duplicated by subsequent actions, they
     *               are suppressed.
     * @param action The action to be performed.
     */
    public synchronized void addUpdateAction(String tag, Crashable action) {
        pendingActions.put(tag, action);
        logger.atDebug().setEventType("register-service-update-action").addKeyValue("action", tag).log();
        synchronized (pendingActions) {
            pendingActions.notifyAll();
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    protected synchronized void runUpdateActions() {
        for (Map.Entry<String, Crashable> todo : pendingActions.entrySet()) {
            try {
                todo.getValue().run();
                logger.atDebug().setEventType("service-update-action").addKeyValue("action", todo.getKey()).log();
            } catch (Throwable t) {
                logger.atError().setEventType("service-update-action-error").addKeyValue("action", todo.getKey())
                        .setCause(t).log();
            }
        }
        pendingActions.clear();
        for (EvergreenService s : kernel.orderedDependencies()) {
            s.disruptionCompleted(); // Notify disruption is over
        }
    }

    @SuppressWarnings({"SleepWhileInLoop"})
    @Override
    public void startup() throws InterruptedException {
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
            // TODO: should really use an injected clock to support simulation-time
            //      it's a big project and would affect many parts of the system.
            final long now = System.currentTimeMillis();
            long maxt = now;

            logger.atDebug().setEventType("service-update-pending").addKeyValue("numOfUpdates", pendingActions.size())
                    .log();
            for (EvergreenService s : kernel.orderedDependencies()) {
                long ct = s.whenIsDisruptionOK();
                if (ct > maxt) {
                    maxt = ct;
                }
            }
            if (maxt > now) {
                logger.atDebug().setEventType("service-update-pending").addKeyValue("waitInMS", maxt - now).log();
                Thread.sleep(maxt - now);
            } else {
                logger.atDebug().setEventType("service-update-scheduled").log();
                context.runOnPublishQueueAndWait(() -> {
                    logger.atInfo().setEventType("service-update-start").log();
                    runUpdateActions();
                    logger.atInfo().setEventType("service-update-finish").log();
                });
            }
        }
    }
}
