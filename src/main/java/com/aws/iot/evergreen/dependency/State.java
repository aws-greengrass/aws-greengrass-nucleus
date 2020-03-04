/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.dependency;

/**
 * The states in the lifecycle of a service.
 */
public enum State {
    // TODO Not sure I trust this list yet

    /**
     * TODO Remove this?
     * Object does not have a state (not a Lifecycle).
     */
    STATELESS(true, false, false),

    /**
     * Freshly created, probably being injected.
     */
    NEW(true, false, false),

    /**
     * Associated artifacts are installed.
     */
    INSTALLED(true, false, false),

    /**
     * Up and running, operating normally. This is the only state that should
     * ever take a significant amount of time to run.
     */
    RUNNING(true, true, true),

    /**
     * Service is in the process of shutting down.
     */
    STOPPING(true, false, true),

    /**
     * Not running. It may be possible for the enclosing framework to restart
     * it.
     */
    ERRORED(false, false, false),

    /**
     * Shut down, cannot be restarted. Generally the result of an unresolvable error.
     */
    BROKEN(false, false, false),
    /**
     * The service has done it's job and has no more to do. May be restarted
     * (for example, a monitoring task that will be restarted by a timer)
     */
    FINISHED(true, false, true);

    private final boolean happy;
    private final boolean running;
    private final boolean functioningProperly;


    State(boolean happy, boolean running, boolean functioningProperly) {
        // TODO Review with James and Team about do we need this anymore?
        this.happy = happy;
        this.running = running;
        this.functioningProperly = functioningProperly;
    }

    /**
     * Nothing is going wrong, but it may not be fully "up".
     */
    public boolean isHappy() {
        return happy;
    }

    /**
     * Fully up and running with associated service code executing (may be Unstable).
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Fully up and running, all is good.
     */
    public boolean isFunctioningProperly() {
        return functioningProperly;
    }

    public boolean preceeds(State other) {
        return ordinal() < other.ordinal();
    }

    public boolean preceedsOrEqual(State other) {
        return ordinal() <= other.ordinal();
    }
}
