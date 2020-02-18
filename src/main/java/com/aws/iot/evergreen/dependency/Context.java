/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.dependency;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Log;
import com.aws.iot.evergreen.util.Utils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import static com.aws.iot.evergreen.util.Utils.isEmpty;
import static com.aws.iot.evergreen.util.Utils.nullEmpty;

/**
 * A collection of Objects that work together.
 */
@SuppressFBWarnings(value = "SC_START_IN_CTOR", justification = "Starting thread in constructor is what we want")
public class Context implements Closeable {
    private final ConcurrentHashMap<Object, Value> parts = new ConcurrentHashMap<>();
    private final Log log = new Log();  // Some painful meta-circularities make life easier if the log is slightly
    // magical
    private boolean shuttingDown = false;
    // global state change notification
    private CopyOnWriteArrayList<EvergreenService.GlobalStateChangeListener> listeners;
    private BlockingDeque<Runnable> serialized = new LinkedBlockingDeque<>();
    private final Thread publishThread = new Thread() {
        {
            setName("Serialized listener processor");
            setPriority(Thread.MAX_PRIORITY - 1);
            //                setDaemon(true);
        }

        @SuppressWarnings({"checkstyle:emptycatchblock"})
        @Override
        public void run() {
            while (true) {
                try {
                    Runnable task = serialized.takeFirst();
                    try {
                        task.run();
                    } catch (Throwable t) {
                        log.error("subscription listener errored", task.getClass(), t);
                    }
                } catch (InterruptedException ignored) {
                }
            }
        }
    };

    {
        parts.put(Context.class, new Value(Context.class, this));
        parts.put(Log.class, new Value(Log.class, log));
    }

    {
        publishThread.start();
    }

    public Log getLog() {
        return log;
    }

    public <T> T get(Class<T> cl) {
        return getv0(cl, cl).get();
    }

    public <T> T get(Class<T> cl, String tag) {
        return getv0(cl, isEmpty(tag) ? cl : tag).get();
    }

    public <T> Value<T> getv(Class<T> cl) {
        return getv0(cl, cl);
    }

    public <T> Value<T> getv(Class<T> cl, String tag) {
        return getv0(cl, isEmpty(tag) ? cl : tag);
    }

    private <T> Value<T> getv0(Class<T> cl, Object tag) {
        //        if(cl!=tag && cl.getAnnotation(Singleton.class)!=null) {
        //            Value<T> v = getv0(cl,cl);
        //            return parts.computeIfAbsent(tag, c->v);
        //        }
        return parts.computeIfAbsent(tag, c -> new Value(cl, null));
    }

    public <T> T newInstance(Class<T> cl) throws Throwable {
        return new Value<>(cl, null).get();
    }

    /**
     * Get the class with the provided tag, if it exists.
     *
     * @param cl class to lookup
     * @param tag tag of the instance of the class to get
     * @return null if it could not be found, returns the class otherwise
     */
    public <T> T getIfExists(Class<T> cl, String tag) {
        Value v = getvIfExists(tag == null ? cl : tag);
        if (v == null) {
            return null;
        }
        Object o = v.value;
        return o == null || !cl.isAssignableFrom(o.getClass()) ? null : (T) o;
    }

    public Value getvIfExists(Object tag) {
        return parts.get(tag);
    }

    /**
     * Put a class into the Context.
     *
     * @param cl type of class to be stored
     * @param v instance of class to store
     * @return this
     */
    public <T> Context put(Class<T> cl, T v) {
        parts.compute(cl, (k, ov) -> {
            if (ov == null) {
                ov = new Value(cl, v);
            } else {
                ov.put(v);
            }
            return ov;
        });
        return this;
    }

    /**
     * Put a class into the Context.
     *
     * @param cl type of class to be stored
     * @param v value instance of class to store
     * @return this
     */
    public <T> Context put(Class<T> cl, Value<T> v) {
        parts.compute(cl, (k, ov) -> {
            if (ov == null) {
                ov = v;
            } else {
                ov.put(v.get());
            }
            return ov;
        });
        return this;
    }

    /**
     * Put object into the context with a provided tag.
     *
     * @param tag tag
     * @param v value
     * @return this
     */
    public Context put(String tag, Object v) {
        parts.compute(tag, (k2, ov) -> {
            if (ov == null) {
                ov = new Value(v.getClass(), v);
            } else {
                ov.put(v);
            }
            return ov;
        });
        return this;
    }

    public void forEach(Consumer<Value> f) {
        parts.values().forEach(f);
    }

    /**
     * Shutdown this context, closing all closeable classes stored in this context.
     */
    public void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        forEach(v -> {
            Object vv = v.value;
            try {
                if (vv instanceof Closeable && vv != log) {
                    ((Closeable) vv).close();
                }
            } catch (Throwable t) {
                log.error("Failed to shutdown", Coerce.toString(vv), t);
            }
        });
        Utils.close(log);
    }

    @Override
    public void close() throws IOException {
        shutdown();
    }

    /**
     * Add a global state change listener.
     *
     * @param l listener to add
     */
    public synchronized void addGlobalStateChangeListener(EvergreenService.GlobalStateChangeListener l) {
        if (listeners == null) {
            listeners = new CopyOnWriteArrayList<>();
        }
        listeners.add(l);
    }

    /**
     * Remove a global state change listener.
     *
     * @param l listener to remove
     */
    public synchronized void removeGlobalStateChangeListener(EvergreenService.GlobalStateChangeListener l) {
        if (listeners != null) {
            listeners.remove(l);
            if (listeners.isEmpty()) {
                listeners = null;
            }
        }
    }

    /**
     * Serially send an event to the global state change listeners.
     *
     * @param changedService the service which had a state change
     * @param previousState the previous state of the service
     */
    public synchronized void globalNotifyStateChanged(EvergreenService service, final State prevState,
                                                      final State activeState) {
        if (listeners != null) {
            listeners.forEach(s -> s.globalServiceStateChanged(service, prevState, activeState));
        }
    }

    /**
     * Set the state of every service.
     *
     * @param newState new state
     */
    public void addDesiredStatesForAll(State desiredState) {
        forEach(f -> {
            Object v = f.get();
            if (v instanceof EvergreenService) {
                ((EvergreenService) v).addDesiredState(desiredState);
            }
        });
    }

    public void runOnPublishQueue(Runnable r) {
        serialized.add(r);
    }

    /**
     * Run a Crashable function on the publish queue and wait for it to finish execution.
     *
     * @param r Crashable
     * @return Throwable resulting from running the Crashable (if any)
     */
    public Throwable runOnPublishQueueAndWait(Crashable r) {
        AtomicReference<Throwable> ret = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        runOnPublishQueue(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                ret.set(t);
                getLog().error("runOnPublishQueueAndWait", t);
            }
            ready.countDown();
        });
        if (!onPublishThread()) {
            try {
                ready.await();
            } catch (InterruptedException ex) {
                ret.set(ex);
            }
        }
        return ret.get();
    }

    public void queuePublish(Topic t) {
        runOnPublishQueue(() -> t.fire(WhatHappened.changed));
    }

    private boolean onPublishThread() {
        return Thread.currentThread() == publishThread;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD})
    public @interface StartWhen {
        /**
         * What state to start the service.
         */
        State value();
    }

    public class Value<T> implements Provider<T> {
        final Class<T> targetClass;
        public volatile T value;
        @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "No need to be sync")
        private boolean injectionCompleted;

        Value(Class<T> c, T v) {
            targetClass = c;
            put(v);
        }

        @Override
        public final T get() {
            T v = value;
            if (v != null && injectionCompleted) {
                return v;
            }
            return get0();
        }

        private synchronized T get0() {
            T v = value;
            if (v != null) {
                return v;
            }
            try {
                Class<T> ccl = targetClass.isInterface() ? (Class<T>) targetClass.getClassLoader()
                        .loadClass(targetClass.getName() + "$Default") : targetClass;
                //                System.out.println(ccl+"  "+deepToString(ccl.getConstructors()));
                Constructor<T> cons = null;
                for (Constructor<T> c : (Constructor<T>[]) ccl.getConstructors()) {
                    //                    System.out.println("Examine "+c.getParameterCount()+" "+c.toGenericString());
                    if (c.getParameterCount() == 0) {
                        cons = c;
                    } else if (c.isAnnotationPresent(Inject.class)) {
                        cons = c;
                        break;
                    }
                }
                if (cons == null) {
                    throw new NoSuchMethodException("No usable injection constructor for " + ccl);
                }
                cons.setAccessible(true);
                int np = cons.getParameterCount();
                if (np == 0) {
                    return put(cons.newInstance());
                }
                //                System.out.println("Injecting args into "+cons.toGenericString());
                Object[] args = new Object[np];
                Class[] types = cons.getParameterTypes();
                for (int i = 0; i < np; i++) {
                    Class type = types[i];
                    if (type == Topics.class) {
                        ImplementsService svc = ccl.getAnnotation(ImplementsService.class);
                        if (svc != null) {
                            String nm = svc.name();
                            args[i] = Context.this.get(Configuration.class).lookupTopics(nm);
                            continue;
                        }
                        args[i] = Topics.errorNode(Context.this, "message", "Synthetic args");
                    } else {
                        args[i] = Context.this.get(type);
                    }
                }
                //                System.out.println("**Construct "+Utils.deepToString(cons, 90)+" "+Utils
                //                .deepToString(args, 90));
                return put(cons.newInstance(args));
            } catch (Throwable ex) {
                log.error("Can't create instance of", targetClass, ex);
                throw new IllegalArgumentException("Can't create instance of " + targetClass.getName(), ex);
            }
        }

        /**
         * Put a new value.
         *
         * @param v new value
         * @return new value
         */
        public final synchronized T put(T v) {
            if (v == value) {
                return v;
            }
            if (v == null || targetClass.isAssignableFrom(v.getClass())) {
                injectionCompleted = false;
                value = v;
                doInjection(v);
                injectionCompleted = true;
                return v; // only assign after injection is complete
            } else {
                throw new IllegalArgumentException(v + " is not assignable to " + targetClass.getSimpleName());
            }
        }

        public final synchronized T computeIfEmpty(Function<Value, T> s) {
            T v = value;
            return v == null ? put(s.apply(this)) : v;
        }

        public boolean isEmpty() {
            return value == null;
        }

        private void doInjection(Object lvalue) {
            //            System.out.println("requestInject " + lvalue);
            if (lvalue == null) {
                return;
            }
            Class cl = lvalue.getClass();
            EvergreenService asService = lvalue instanceof EvergreenService ? (EvergreenService) lvalue : null;
            InjectionActions injectionActions = lvalue instanceof InjectionActions ? (InjectionActions) lvalue : null;
            if (asService != null) {
                asService.context = Context.this; // inject context early
            }
            if (injectionActions != null) {
                try {
                    injectionActions.preInject();
                } catch (Throwable e) {
                    if (asService != null) {
                        asService.errored("preInject", e);
                    } else {
                        getLog().error("preInject", cl, e);
                    }
                }
            }
            while (cl != null && cl != Object.class) {
                for (Field f : cl.getDeclaredFields()) {
                    Inject a = f.getAnnotation(Inject.class);
                    //                    System.out.println(f.getName() + " " + (a != null));
                    if (a != null) {
                        try {
                            final Named named = f.getAnnotation(Named.class);
                            final String name = nullEmpty(named == null ? null : named.value());
                            Class t = f.getType();
                            Object v;
                            //                            System.out.println(cl.getSimpleName() + "." + f.getName() +
                            //                            " .
                            //                            ..");
                            //                            System.out.println("\tSET");
                            if (t == Provider.class) {
                                //                                System.out.println("PROVIDER " + t + " " + f + "\n
                                //                                ->
                                //                                " + f.toGenericString());
                                //                                Class scl = (Class) ((ParameterizedType) f
                                //                                .getGenericType()).getActualTypeArguments()[0];
                                //                                System.out.println("\tprovides " + scl);
                                v = getv((Class) ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0],
                                        name);
                            } else {
                                v = Context.this.get(t, name);
                            }
                            StartWhen startWhen = f.getAnnotation(StartWhen.class);
                            f.setAccessible(true);
                            f.set(lvalue, v);
                            //                            System.out.println("   "+cl.getSimpleName() + "." + f
                            //                            .getName()
                            //                            + " = " + v);
                            if (asService != null && v instanceof EvergreenService) {
                                asService.addDependency((EvergreenService) v, startWhen == null ? State.RUNNING :
                                        startWhen.value());
                            }
                        } catch (Throwable ex) {
                            if (asService != null) {
                                asService.errored("Injecting", ex);
                            } else {
                                log.error("Error injecting into", f, ex);
                            }
                        }
                    }
                    //                    else System.out.println("\tSKIP");
                }
                cl = cl.getSuperclass();
            }
            if (injectionActions != null && (asService == null || !asService.errored())) {
                try {
                    injectionActions.postInject();
                } catch (Throwable e) {
                    if (asService != null) {
                        asService.errored("postInject", e);
                    } else {
                        log.error("postInject", value.getClass(), e);
                    }
                }
            }
        }

    }
}
