/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.dependency;

import static com.aws.iot.util.Utils.*;
import java.io.*;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.inject.*;

/**
 * A collection of Objects that work together
 */
public class Context implements Closeable {
    private final ConcurrentHashMap<Object, Value> parts = new ConcurrentHashMap<>();
    {
        parts.put(Context.class, new Value(Context.class, this));
        parts.put(Clock.class, new Value(Clock.class, Clock.systemUTC()));  // can be overwritten
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
    public Value getIfExists(Object tag) {
        return parts.get(tag);
    }
    public <T> Context put(Class<T> cl, T v) {
        parts.compute(cl, (k, ov) -> {
            if (ov == null) ov = new Value(cl, v);
            else ov.put(v);
            return ov;
        });
        return this;
    }
    public <T> Context put(Class<T> cl, Value<T> v) {
        parts.compute(cl, (k, ov) -> {
            if (ov == null) ov = v;
            else ov.put(v.get());
            return ov;
        });
        return this;
    }
    public Context put(String k, Object v) {
        parts.compute(k, (k2, ov) -> {
            if (ov == null) ov = new Value(v.getClass(), v);
            else ov.put(v);
            return ov;
        });
        return this;
    }
    public void forEach(Consumer<Value> f) {
        parts.values().forEach(f);
    }
    private boolean shuttingDown = false;
    public void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        forEach(v -> {
            try {
                Object vv = v.value;
                if (vv instanceof Closeable)
                    ((Closeable) vv).close();
            } catch (Throwable t) {
                t.printStackTrace(System.out);
            }
        });
    }
    @Override
    public void close() throws IOException {
        shutdown();
    }
    // global state change notification
    private CopyOnWriteArrayList<Lifecycle.stateChangeListener> listeners;
    public synchronized void addStateListener(Lifecycle.stateChangeListener l) {
        if(listeners==null) listeners = new CopyOnWriteArrayList<>();
        listeners.add(l);
    }
    public synchronized void removeStateListener(Lifecycle.stateChangeListener l) {
        if(listeners!=null) {
            listeners.remove(l);
            if(listeners.isEmpty()) listeners = null;
        }
    }
    void notify(Lifecycle l, State was) {
        if(listeners!=null)
            listeners.forEach(s->s.stateChanged(l,was));
    }

    public class Value<T> implements Provider<T> {
        volatile T value;
        final Class<T> targetClass;
        Value(Class<T> c, T v) {
            targetClass = c;
            put(v);
        }
        @Override
        public final T get() {
            T v = value;
            if (v != null) return v;
            return get0();
        }
        private synchronized T get0() {
            T v = value;
            if (v != null) return v;
            try {
                Class<T> ccl = targetClass.isInterface()
                        ? (Class<T>) targetClass.getClassLoader().loadClass(targetClass.getName() + "$Default")
                        : targetClass;
                System.out.println(ccl+"  "+deepToString(ccl.getConstructors()));
                Constructor<T> cons = ccl.getDeclaredConstructor();
                cons.setAccessible(true);
//                if(cons.getAnnotation(Singleton.class)!=null) {
//                    System.out.println("Stuffing singleton "+ccl.getSimpleName());
//                    parts.put(ccl, this); // if it's a named singleton, make sure it shows up both ways.
//                }
                return put(cons.newInstance());
            } catch (Throwable ex) {
                ex.printStackTrace(System.out);
                return null;  // TODO noooooo
            }
        }
        public synchronized final T put(T v) {
            if (v == value) return v;
            if (v == null || targetClass.isAssignableFrom(v.getClass())) {
                value = v;
                doInjection();
                return v;
            } else
                throw new IllegalArgumentException(v + " is not assignable to " + targetClass.getSimpleName());
        }
        public synchronized final T computeIfEmpty(Function<Value,T> s) {
            T v = value;
            return v == null ? put(s.apply(this)) : v;
        }
        public boolean isEmpty() {
            return value == null;
        }
        private void doInjection() {
            Object lvalue = value;
//            System.out.println("requestInject " + lvalue);
            if (lvalue == null) return;
            Class cl = lvalue.getClass();
            Lifecycle lo = lvalue instanceof Lifecycle ? (Lifecycle) lvalue : null;
            InjectionActions io = lvalue instanceof InjectionActions ? (InjectionActions) lvalue : null;
            if (lo != null) lo.context = Context.this; // inject context early
            if (io != null)
                try {
                    io.preInject();
                } catch (Throwable e) {
                    if (lo != null) lo.errored("preInject", e);
                    else e.printStackTrace(System.err);  //TODO: be less stupid
                }
            while (cl != null && cl != Object.class) {
                for (Field f : cl.getDeclaredFields()) {
                    Inject a = f.getAnnotation(Inject.class);
//                    System.out.println(f.getName() + " " + (a != null));
                    if (a != null)
                        try {
                            final Named named = f.getAnnotation(Named.class);
                            final String name = nullEmpty(named == null ? null : named.value());
                            Class t = f.getType();
                            Object v;
//                            System.out.println("\tSET");
                            if (t == Provider.class) {
//                                System.out.println("PROVIDER " + t + " " + f + "\n  -> " + f.toGenericString());
//                                Class scl = (Class) ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0];
//                                System.out.println("\tprovides " + scl);
                                v = getv((Class) ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0], name);
                            } else v = Context.this.get(t, name);
                            StartWhen startWhen = f.getAnnotation(StartWhen.class);
                            f.setAccessible(true);
                            f.set(lvalue, v);
//                            System.out.println(cl.getSimpleName() + "." + f.getName() + " = " + v);
                            if (lo != null && v instanceof Lifecycle)
                                lo.addDependency((Lifecycle) v,
                                        startWhen == null ? State.Running
                                                : startWhen.value());
                        } catch (Throwable ex) {
                            ex.printStackTrace(System.err);
                            if (lo != null)
                                lo.errored("Injecting", ex);
                            else
                                System.err.println("Error injecting into " + f + "\n\t" + ex);
                        }
//                    else System.out.println("\tSKIP");
                }
                cl = cl.getSuperclass();
            }
            if (io != null && (lo == null || !lo.errored()))
                try {
                    io.postInject();
                } catch (Throwable e) {
                    if (lo != null) lo.errored("postInject", e);
                    else e.printStackTrace(System.err);  //TODO: be less stupid
                }
        }

    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD})
    public @interface StartWhen {
        State value();
    }
}
