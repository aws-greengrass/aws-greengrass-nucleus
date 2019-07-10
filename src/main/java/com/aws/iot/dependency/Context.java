/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.dependency;

import static com.aws.iot.util.Utils.*;
import java.io.*;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * A collection of Objects that work together
 */
public class Context implements Closeable {
    private final ConcurrentHashMap<Object, Object> parts = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Object> injectQueue = new ConcurrentLinkedQueue<>();
    {
        parts.put(Context.class, this);
        parts.put(Clock.class, Clock.systemUTC());  // can be overwritten
    }
    public <T> T get(Class<T> cl) {
        return get0(cl, cl);
    }
    public <T> T get(Class<T> cl, String tag) {
        return get0(cl, isEmpty(tag) ? cl : tag);
    }
    private <T> T get0(Class<T> cl, Object tag) {
        Object v = parts.computeIfAbsent(tag, c -> {
            try {
                Class<T> ccl = cl.isInterface()
                        ? (Class<T>)cl.getClassLoader().loadClass(cl.getName()+"$Default")
                        : cl;
                Constructor<T> cons = ccl.getDeclaredConstructor();
                cons.setAccessible(true);
                T rv = cons.newInstance();
                requestInject(rv);
                return rv;
            } catch (Throwable ex) {
                ex.printStackTrace(System.out);
                return ex;
            }
        });
        inject();  // Must be outside computeIfAbsent
        if (v != null && cl.isAssignableFrom(v.getClass()))
            return (T) v;
        if (v == null || v instanceof Throwable) {
//            get(Log.class).error("Error creating value",v,cl.getName(),tag);
            if (v instanceof Error)
                throw (Error) v;
            throw new IllegalArgumentException("Error creating value " + cl, (Throwable) v);
        }
        throw new IllegalArgumentException("Mismatched types: " + cl + " " + v.getClass());
    }
    public <T> T newInstance(Class<T> cl) throws Throwable {
        T v = newInstance0(cl);
        inject();
        return v;
    }
    private <T> T newInstance0(Class<T> cl) throws Throwable {
        Constructor<T> cons = cl.getDeclaredConstructor();
        cons.setAccessible(true);
        T v = cons.newInstance();
        requestInject(v);
        return (T) v;
    }
    public Object getIfExists(Object tag) {
        return parts.get(tag);
    }
    public <T> Context put(Class<T> cl, T v) {
        parts.compute(cl, (k, ov) -> {
            if (ov != null && ov != v)
                throw new IllegalArgumentException("Instance already present in Context: " + k);
            return v;
        });
        return this;
    }
    public Context put(String k, Object v) {
        parts.compute(k, (k2, ov) -> {
            if (ov != null && ov != v)
                throw new IllegalArgumentException("Instance already present in Context: " + k2);
            requestInject(v);
            inject();
            return v;
        });
        return this;
    }
    public <T> T computeIfAbsent(Object key, Function<Object,T> computeValue) {
        return (T)parts.computeIfAbsent(key, computeValue);
    }
    public void forEach(Consumer<Object> f) {
        parts.values().forEach(f);
    }
    private boolean shuttingDown = false;
    public void shutdown() {
        if(shuttingDown) return;
        shuttingDown = true;
        forEach(v -> {
            try {
                if (v instanceof Lifecycle)
                    ((Lifecycle) v).setState(Lifecycle.State.Shutdown);
                else if (v instanceof Closeable)
                    ((Closeable) v).close();
            } catch (Throwable t) {
                t.printStackTrace(System.out);
            }
        });
    }
    private final WeakHashMap<Object,Object> alreadyInjected = new WeakHashMap<>();
    public void requestInject(Object o) {
        if(!alreadyInjected.containsKey(o))
            injectQueue.add(o);
    }
    public synchronized void inject() {
        Object o;
        while ((o = injectQueue.poll()) != null) {
            if(alreadyInjected.containsKey(o)) continue;
            alreadyInjected.put(o, Boolean.TRUE);
            Class cl = o.getClass();
            Lifecycle lo = o instanceof Lifecycle ? (Lifecycle) o : null;
            if (lo != null)
                try {
                    lo.context = this; // inject context early
                    lo.preInject();
                } catch (Throwable e) {
                    lo.errored("preInject", e);
                }
            while (cl != null && cl != Object.class) {
                for (Field f : cl.getDeclaredFields()) {
                    Dependency a = f.getAnnotation(Dependency.class);
                    if (a != null)
                        try {
                            Object v = get(f.getType(), a.value());
                            StartWhen startWhen = f.getAnnotation(StartWhen.class);
                            f.setAccessible(true);
                            f.set(o, v);
                            if (lo != null && v instanceof Lifecycle)
                                lo.addDependency((Lifecycle)v,
                                        startWhen==null ? Lifecycle.State.Running
                                                        : startWhen.value());
                        } catch (Throwable ex) {
                            if (lo != null)
                                lo.errored("Injecting", ex);
                        }
                }
                cl = cl.getSuperclass();
            }
            if (lo != null && !lo.errored())
                try {
                    lo.postInject();
                    lo.setState(Lifecycle.State.AwaitingStartup);
                } catch (Throwable e) {
                    lo.errored("postInject", e);
                }
        }
    }
    @Override
    public void close() throws IOException {
        shutdown();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD})
    public @interface Dependency {
        String value() default "";
    }
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD})
    public @interface StartWhen {
        Lifecycle.State value();
    }
}
