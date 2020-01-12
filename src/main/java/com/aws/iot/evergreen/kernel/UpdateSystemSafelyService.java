/* Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.*;
import com.aws.iot.evergreen.util.Log;
import java.util.*;
import java.util.concurrent.*;
import javax.inject.*;

/**
 * Handles requests to update the system's configuration during safe times.
 * (or anything else that's disruptive and shouldn't be done until the system
 * is in a "safe" state).
 * 
 * It maintains two lists: one is a list of actions that will be executed when the
 * system is next "disruptable".  This is typically code that is going to install an update.
 * 
 * The other is a list of functions that are called to check if the system is "disruptable".
 * For example, a TV might not be disruptable if it is being used, or a robot if it is
 * in motion.
 * 
 * If the update service is periodic, update actions will only be processed at that time.
 * Otherwise, it the update will be processed immediately, assuming that all disruptability
 * checks pass.
 */
@ImplementsService(name = "update", autostart = true)
@Singleton
public class UpdateSystemSafelyService extends EvergreenService {
    @Inject Log log;
    private final LinkedHashMap<String, Crashable> pendingActions = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<DisruptableCheck> disrutableChecks = new CopyOnWriteArrayList<>();

    public UpdateSystemSafelyService(Topics c) {
        super(c);
    }
    public void addDisrutableCheck(DisruptableCheck d) {
        disrutableChecks.add(d);
    }
    public void removeDisrutableCheck(DisruptableCheck d) {
        disrutableChecks.remove(d);
    }
    /**
     * 
     * @param tag used both as a printable description and a de-duplication key.  eg. If
     *          the action is installing a new config file, the tag should probably be the
     *          URL of the config.  If a key is duplicated by subsequent actions, they
     *          are suppressed.
     * @param action The action to be performed.
     */
    public synchronized void addUpdateAction(String tag, Crashable action) {
        pendingActions.put(tag, action);
        log.note(getName(), "Adding update action", tag);
        if (!isPeriodic()) setState(State.Running);
    }
    protected synchronized void runUpdateActions() {
        for (Map.Entry<String, Crashable> todo : pendingActions.entrySet())
            try {
                todo.getValue().run();
            } catch (Throwable t) {
                log.error(getName(), "Error processing system update", todo.getKey(), t);
            }
        pendingActions.clear();
        for (DisruptableCheck c : disrutableChecks) c.disruptableCheck(false); // Notify disruption is over
    }
    @SuppressWarnings("SleepWhileInLoop")
    @Override public void run() {
        // run() is invoked on it's own thread
        log.note(getName(), "Checking for updates");
        while (!pendingActions.isEmpty()) {
            final long now = System.currentTimeMillis();
            long maxt = now;

            log.note(getName(), "updates pending:", pendingActions.size());
            for (DisruptableCheck c : disrutableChecks) {
                long ct = c.disruptableCheck(true);
                if (ct > maxt) maxt = ct;
            }
            if (maxt > now) try {
                log.note(getName(), "Holding for", maxt - now, "millisÏ");
                Thread.sleep(maxt - now);
            } catch (InterruptedException ex) {
            }
            else {
                log.note(getName(), "Queueing update actions");
                context.runOnPublishQueueAndWait(() -> {
                    log.note(getName(), "Starting safe-time update");
                    runUpdateActions();
                    log.note(getName(), "Finished read-phase of safe-time update");
                });
                log.note(getName(), "Back on run Q safe-time update");
            }
        }
        super.run();
    }

    public static interface DisruptableCheck {
        /**
         * 
         * @param disruptionPending true iff a disruption is pending.  After a disruption,
         *      disruptableCheck is called with this being false to signal to the handler
         *      that it's OK to start activity
         * @return Estimated time that this handler will be willing to be disrupted. If
         *      the returned value is less than now (System.currentTimeMillis()) the handler
         *      is granting permission to be disrupted.  Otherwise, it will be asked again
         *      sometime later.
         */
        public long disruptableCheck(boolean disruptionPending);
    }
}
