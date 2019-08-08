/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.dependency;

/** The states in the lifecycle of a service */
public enum State {
    // TODO Not sure I trust this list yet
    
    /**
     * Object does not have a state (not a Lifecycle)
     */
    Stateless(true, false),
    /**
     * Freshly created, probably being injected
     */
    New(true, false),
    /**
     * Associated artifacts are being installed. TODO: This should probably be
     * preceded by a new state: PreparingToInstall which can run while the
     * service is running, and should do downloads in preparation to
     * installation.
     */
    Installing(true, false),
    /**
     * Waiting for some dependency to start Running
     */
    AwaitingStartup(true, false),
    /**
     * Executed when all dependencies are satisfied. When this step is completed
     * the service will be Running.
     */
    Starting(true, false),
    /**
     * Up and running, operating normally. This is the only state that should
     * ever take a significant amount of time to run.
     */
    Running(true, true),
    /**
     * Running, but experiencing problems that the service is attempting to
     * repair itself
     */
    Unstable(false, true),
    /**
     * Not running. It may be possible for the enclosing framework to restart
     * it.
     */
    Errored(false, false),
    /**
     * In the process of being restarted
     */
    Recovering(false, false),
    /**
     * Shut down, cannot be restarted.  Generally the result of an unresolvable error.
     */
    Shutdown(false, false),
    /**
     * The service has done it's job and has no more to do. May be restarted
     * (for example, a monitoring task that will be restarted by a timer)
     */
    Finished(true, false);
    
    private final boolean happy;
    private final boolean running;
    private State(boolean h, boolean r) {
        happy = h;
        running = r;
    }
    public boolean isHappy() {
        return happy;
    }
    public boolean isRunning() {
        return running;
    }
    public boolean preceeds(State other) {
        return ordinal()<other.ordinal();
    }
}
