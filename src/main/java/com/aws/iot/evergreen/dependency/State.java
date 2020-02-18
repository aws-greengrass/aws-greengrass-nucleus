/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.dependency;

/**
 * The states in the lifecycle of a service.
 */
public enum State {
    // TODO Not sure I trust this list yet

    /**
     * Object does not have a state (not a Lifecycle).
     */
    STATELESS(true, false, false),

    /**
     * Freshly created, probably being injected.
     */
    NEW(true, false, false),

    /**
     * Associated artifacts are being installed. TODO: How to handle the download
     * phase of installation is a topic of debate.  For now, Downloading isn't a state
     * since it can (and should) be done in the background by a MIN_PRIORITY thread.
     */
    @Deprecated
    INSTALLING(true, false, false),

    /**
     * Associated artifacts are installed.
     */
    INSTALLED(true, false, false),

    /**
     * Waiting for some dependency to start RUNNING
     */
    @Deprecated
    AWAITING_STARTUP(true, false, false),
    /**
     * Executed when all dependencies are satisfied. When this step is completed
     * the service will be RUNNING.
     */
    @Deprecated
    STARTING(true, false, false),
    /**
     * Up and running, operating normally. This is the only state that should
     * ever take a significant amount of time to run.
     */
    RUNNING(true, true, true),
    //    /**
    //     * RUNNING, but experiencing problems that the service is attempting to
    //     * repair itself
    //     */
    //    Unstable(false, true, false),

    /**
     *
     */
    STOPPING(true, false, false),

    /**
     * Not running. It may be possible for the enclosing framework to restart
     * it.
     */
    ERRORED(false, false, false),
    //    /**
    //     * In the process of being restarted
    //     */
    //    Recovering(false, false, false),
    /**
     * Shut down, cannot be restarted.  Generally the result of an unresolvable error.
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

    State(boolean h, boolean r, boolean p) {
        // TODO Review with James and team
        // Do we need these anymore?
        happy = h;
        running = r;
        functioningProperly = p;
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
