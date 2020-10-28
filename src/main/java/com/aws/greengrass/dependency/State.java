/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.dependency;

/**
 * The states in the lifecycle of a service.
 */
public enum State {
    // GG_NEEDS_REVIEW: TODO Not sure I trust this list yet

    /**
     * TODO Remove this?
     * Object does not have a state (not a Lifecycle).
     */
    STATELESS(true, false, false, "Stateless"),

    /**
     * Freshly created, probably being injected.
     */
    NEW(true, false, false, "New"),

    /**
     * Associated artifacts are installed.
     */
    INSTALLED(true, false, false, "Installed"),

    /**
     * The service has started, but hasn't report running yet.
     */
    STARTING(true, false, false, "Starting"),

    /**
     * Up and running, operating normally. This is the only state that should
     * ever take a significant amount of time to run.
     */
    RUNNING(true, true, true, "Running"),

    /**
     * Service is in the process of shutting down.
     */
    STOPPING(true, false, true, "Stopping"),

    /**
     * Not running. It may be possible for the enclosing framework to restart
     * it.
     */
    ERRORED(false, false, false, "Errored"),

    /**
     * Shut down, cannot be restarted. Generally the result of an unresolvable error.
     */
    BROKEN(false, false, false, "Broken"),
    /**
     * The service has done it's job and has no more to do. May be restarted
     * (for example, a monitoring task that will be restarted by a timer)
     */
    FINISHED(true, false, true, "Finished");

    private final boolean happy;
    private final boolean running;
    private final boolean functioningProperly;
    private final String name;


    State(boolean happy, boolean running, boolean functioningProperly, String name) {
        // GG_NEEDS_REVIEW: TODO Review with James and Team about do we need this anymore?
        this.happy = happy;
        this.running = running;
        this.functioningProperly = functioningProperly;
        this.name = name;
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

    /**
     * Gets the user-friendly name for a state.
     */
    public String getName() {
        return name;
    }

    public boolean preceeds(State other) {
        return ordinal() < other.ordinal();
    }

    public boolean preceedsOrEqual(State other) {
        return ordinal() <= other.ordinal();
    }

    public boolean isStartable() {
        return this.equals(NEW) || this.equals(INSTALLED) || this.equals(ERRORED) || this.equals(FINISHED);
    }

    public boolean isStoppable() {
        return this.equals(NEW) || this.equals(STARTING) || this.equals(RUNNING);
    }

    public boolean isClosable() {
        return this.equals(ERRORED) || this.equals(BROKEN) || this.equals(FINISHED) || this.equals(NEW);
    }
}
