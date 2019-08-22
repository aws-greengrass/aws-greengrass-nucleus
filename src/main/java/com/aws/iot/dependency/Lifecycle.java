/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.dependency;

import static com.aws.iot.dependency.State.*;
import com.aws.iot.util.*;
import static com.aws.iot.util.Utils.*;
import java.io.*;
import java.util.HashSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Implements an object that goes through lifecycle phases.
 */
public class Lifecycle implements Closeable, InjectionActions {
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
    public boolean isRunningInternally() {
        Future b = backingTask;
        return b!=null && !b.isDone();
    }
    private boolean errorHandlerErrored; // cheezy hack to avoid repeating error handlers
    public synchronized void setState(State s) {
        final State prev = state;
        if (s == prev)
            return;
//        System.out.println(getName()+" "+prev+"->"+s);
        state = s;
        if(prev.isRunning() && !s.isRunning()) { // transition from running to not running
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
        }
        try {
            switch(s) {
                case Installing:
                    install();
                    break;
                case AwaitingStartup:
                    awaitingStartup();
                    break;
                case Starting:
                    backingTask = context.get(ExecutorService.class).submit(() -> {
                        try {
                            startup();
                        } catch (Throwable t) {
                            errored("Failed starting up", t);
                        }
                    });
                    break;
                case Running:
                    if(prev != Unstable) {
                        recheckOthersDependencies();
                        backingTask = context.get(ExecutorService.class).submit(() -> {
                            try {
                                run();
                            } catch (Throwable t) {
                                errored("Failed starting up", t);
                            }
                        });
                    }
                    break;
                case Errored:
                    try {
                        if(!errorHandlerErrored) handleError();
                    } catch (Throwable t) {
                        errorHandlerErrored = true;
                        errored("Error handler failed", t);
                    }
                    break;
            }
        } catch(Throwable t) {
            errored("Transitioning from "+prev+" to "+s, t);
        }
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
        return !state.isHappy() || error != null;
    }
    @Override public void postInject() {
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
        if(!hasDependencies() && !errored()) setState(Starting);
    }
    /**
     * Called when all dependencies are Running. If there are no dependencies,
     * it is called right after postInject.  The service doesn't transition to Running
     * until *after* this state is complete.
     */
    protected void startup() {
        if(!errored()) setState(State.Running);
    }
    /**
     * Called when all dependencies are Running. If there are no dependencies,
     * it is called right after postInject.
     */
    protected void run() {
        if(!errored()) setState(State.Finished);
    }
    /**
     * Called when a running service encounters an error.
     */
    protected void handleError() {
    }
    /**
     * Called when the object's state leaves Running.
     * To shutdown a service, use <tt>setState(Shutdown)</dd>
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
    public String getName() { return getClass().getSimpleName(); }
    public Context getContext() { return context; }
    protected void addDependency(Lifecycle v, State when) {
        if (dependencies == null)
            dependencies = new ConcurrentHashMap<>();
//        System.out.println(getName()+" depends on "+v.getName());
        dependencies.put(v, when);
    }
    private boolean hasDependencies() {
//        if(dependencies==null) {
//            System.out.println(getName()+": no dependencies");
//            return false;
//        } else {
//            dependencies.entrySet().stream().forEach(ls -> {
//                System.out.println(getName() +"/"+ getState()+" :: "+ls.getKey().getName()+"/"+ls.getKey().getState());
//            });
//        }
        return dependencies != null
                && (dependencies.entrySet().stream().anyMatch(ls -> ls.getKey().getState().preceeds(ls.getValue())));
    }
    public void forAllDependencies(Consumer<? super Lifecycle> f) {
        if(dependencies!=null) dependencies.keySet().forEach(f);
    }
    private CopyOnWriteArrayList<stateChangeListener> listeners;
    public synchronized void addStateListener(stateChangeListener l) {
        if(listeners==null) listeners = new CopyOnWriteArrayList<>();
        listeners.add(l);
    }
    public synchronized void removeStateListener(stateChangeListener l) {
        if(listeners!=null) {
            listeners.remove(l);
            if(listeners.isEmpty()) listeners = null;
        }
    }
    private void notify(Lifecycle l, State was) {
        if(listeners!=null)
            listeners.forEach(s->s.stateChanged(l,was));
        context.notify(l,was);
    }
    public boolean satisfiedBy(HashSet<Lifecycle> ready) { return true; }
    private void recheckOthersDependencies() {
        if (context != null) {
            final AtomicBoolean changed = new AtomicBoolean(true);
            while (changed.get()) {
                changed.set(false);
                context.forEach(v -> {
                    Object vv= v.value;
                    if(vv instanceof Lifecycle) {
                        Lifecycle l = (Lifecycle) vv;
                        if (l.state == State.AwaitingStartup) {
                            if (!l.hasDependencies()) {
                                l.setState(State.Starting);
                                changed.set(true);
                            }
                        }
                    }
                });
            }
        }
    }
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String s) { status = s; }
    public interface stateChangeListener {
        void stateChanged(Lifecycle l, State was);
    }
}
