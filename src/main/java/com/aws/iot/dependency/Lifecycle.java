/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.dependency;

import static com.aws.iot.dependency.Lifecycle.State.*;
import com.aws.iot.util.*;
import static com.aws.iot.util.Utils.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Implements an object that goes through lifecycle phases.
 */
public class Lifecycle implements Closeable {
    public enum State {
        // TODO The weird error states are not well handled (yet)
        Stateless, New, Installed, AwaitingStartup, Running, Unstable, Errored, Recovering, Shutdown
    };
    private State state = State.New;
    private Throwable error;
    protected ConcurrentHashMap<Lifecycle, State> dependencies;
    private Future backingTask;
    protected Context context;
    public static State getState(Lifecycle o) {
        return o.state;
    }
    public State getState() {
        return state;
    }
    public Log log() { return context.get(Log.class); }
    /** TODO: Most of the lifecycle FSM is here, and in this iteration it is wildly
     *  inadequate.  Needs much work. */
    private boolean bumpState() {
        switch (state) { // this is the state we're *leaving*
            case New:
                install();
                break;
            case Installed:
                try {
                    awaitingStartup();
                } catch (Throwable t) {
                    errored("Failed awaiting startup", t);
                }
                break;
            case AwaitingStartup:
                backingTask = context.get(ExecutorService.class).submit(() -> {
                try {
                    startup();
                } catch (Throwable t) {
                    errored("Failed starting up", t);
                }
                });
                break;
            case Running:
                try {
                    shutdown();
                    Future b = backingTask;
                    if(b!=null) {
                        backingTask = null;
                        b.cancel(true);
                    }
                } catch (Throwable t) {
                    errored("Failed shutting down", t);
                }
                break;
            default:
                return false;
        }
        State ds = State.values()[state.ordinal() + 1];
        if (ds == State.Running)
            if (hasDependencies())
                return false;  // can't mark something running if it's got dependencies
            else {
                state = State.Running;
                recheckOthersDependencies();
            }
        else
            state = ds;
        return true;
    }
    private boolean errorHandlerErrored; // cheezy hack to avoid repeating error handlers
    public void setState(State s) {
        if (s == state)
            return;
        if (s.ordinal() > State.Running.ordinal()) {
            if (state == State.Running)
                try {
                    shutdown();
                } catch (Throwable t) {
                    errored("Shutdown handler failed", t);
                }
            state = s;
            if (s == State.Errored)
                try {
                    if(!errorHandlerErrored) handleError();
                } catch (Throwable t) {
                    errorHandlerErrored = true;
                    errored("Error handler failed", t);
                }
        } else if (s.ordinal() <= State.Running.ordinal())
            while (s.ordinal() > state.ordinal() && bumpState()) {
            }
        if (state == State.AwaitingStartup && !hasDependencies())
            setState(State.Running);
    }
    public static State getState(Object o) {
        return o instanceof Lifecycle ? ((Lifecycle) o).state : State.Stateless;
    }
    static final void setState(Object o, State st) {
        if (o instanceof Lifecycle)
            ((Lifecycle) o).setState(st);
    }
    public void errored(String message, Throwable e) {
        e = getUltimateCause(e);
        error = e;
        errored(message, (Object)e);
    }
    public void errored(String message, Object e) {
        if(context==null) {
            System.err.println("ERROR EARLY IN BOOT\n\t"+message+" "+e);
            if(e instanceof Throwable) ((Throwable)e).printStackTrace(System.err);
        }
        else log().error(this,message,e);
        setState(State.Errored);
    }
    public boolean errored() {
        return state == State.Errored || error != null;
    }
    /**
     * Called after the constructor, but before dependency injection
     */
    protected void preInject() {
    }
    /**
     * Called after dependency injection, but before dependencies are all
     * Running
     */
    protected void postInject() {
    }
    /**
     * Called when this service is known to be needed to make sure that required
     * additional software is installed.
     */
    protected void install() {
    }
    /**
     * Called when this service is known to be needed, and is AwaitingStartup.
     * This is a good place to do any preconfiguration.  It is seperate from "install"
     * because there are situations (like factory preflight setup) where there's a
     * certain amount of setup to be done, but we're not actually going to start the app.
     */
    protected void awaitingStartup() {
    }
    /**
     * Called when all dependencies are Running. If there are no dependencies,
     * it is called right after postInject.
     */
    protected void startup() {
    }
    /**
     * Called when a running service encounters an error.
     */
    protected void handleError() {
    }
    /**
     * Called when the object's state leaves Running
     */
    protected void shutdown() {
    }
    /**
     * Sets the state to Shutdown
     */
    @Override
    public void close() {
        setState(State.Shutdown);
    }
    protected void addDependency(Lifecycle v, State when) {
        if (dependencies == null)
            dependencies = new ConcurrentHashMap<>();
        dependencies.put(v, when);
    }
    private boolean hasDependencies() {
        return dependencies != null
                && (dependencies.entrySet().stream().anyMatch((ls) -> (ls.getKey().getState().ordinal() < ls.getValue().ordinal())));
    }
    private void recheckOthersDependencies() {
        if (context != null) {
            final AtomicBoolean changed = new AtomicBoolean(true);
            while (changed.get()) {
                changed.set(false);
                context.forEach(v -> {
                    if (getState(v) == State.AwaitingStartup) {
                        Lifecycle l = (Lifecycle) v;
                        if (!l.hasDependencies()) {
                            l.setState(State.Running);
                            changed.set(true);
                        }
                    }
                });
            }
        }
//        System.out.print("Pending: ");
//        context.forEach(v -> {
//            if (getState(v) == State.AwaitingStartup) {
//                Lifecycle l = (Lifecycle) v;
//                System.out.print(l.lid);
//            }
//        });
//        System.out.println();
    }
}
