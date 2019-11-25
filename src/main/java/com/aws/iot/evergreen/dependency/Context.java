/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.evergreen.dependency;

import com.aws.iot.evergreen.config.*;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.*;

import static com.aws.iot.evergreen.util.Utils.*;
import java.io.*;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.inject.*;
import javax.inject.Named;

/**
 * A collection of Objects that work together
 */
public class Context implements Closeable {
    private final ConcurrentHashMap<Object, Value> parts = new ConcurrentHashMap<>();
    private final Log log = new Log();  // Some painful meta-circularities make life easier if the log is slightly magical
    {
        parts.put(Context.class, new Value(Context.class, this));
        parts.put(Log.class, new Value(Log.class, log));
    }
    public Log getLog() { return log; }
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
    public <T> T getIfExists(Class<T> cl, String tag) {
        Value v = getvIfExists(tag==null ? cl : tag);
        if(v==null) return null;
        Object o = v.value;
        return o==null || !cl.isAssignableFrom(o.getClass())
            ? null
            : (T) o;
    }
    public Value getvIfExists(Object tag) {
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
            Object vv = v.value;
            try {
                if (vv instanceof Closeable && vv!=log)
                    ((Closeable) vv).close();
            } catch (Throwable t) {
                log.error("Failed to shutdown",Coerce.toString(vv),t);
            }
        });
        Utils.close(log);
    }
    @Override
    public void close() throws IOException {
        shutdown();
    }
    // global state change notification
    private CopyOnWriteArrayList<EvergreenService.GlobalStateChangeListener> listeners;
    public synchronized void addGlobalStateChangeListener(EvergreenService.GlobalStateChangeListener l) {
        if(listeners==null) listeners = new CopyOnWriteArrayList<>();
        listeners.add(l);
    }
    public synchronized void removeGlobalStateChangeListener(EvergreenService.GlobalStateChangeListener l) {
        if(listeners!=null) {
            listeners.remove(l);
            if(listeners.isEmpty()) listeners = null;
        }
    }
    public void globalNotifyStateChanged(EvergreenService l, State was) {
        if(listeners!=null)
            listeners.forEach(s->s.globalServiceStateChanged(l, was));
    }
    
    public void setAllStates(State ms) {
        forEach(f->{
            Object v = f.get();
            if(v instanceof EvergreenService) {
                ((EvergreenService)v).setState(ms);
            }
        });
    }

    public class Value<T> implements Provider<T> {
        public volatile T value;
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
//                System.out.println(ccl+"  "+deepToString(ccl.getConstructors()));
                Constructor<T> cons = null;
                for(Constructor<T> c:(Constructor<T>[])ccl.getConstructors()) {
//                    System.out.println("Examine "+c.getParameterCount()+" "+c.toGenericString());
                    if(c.getParameterCount()==0) cons = c;
                    else if(c.isAnnotationPresent(Inject.class)) {
                        cons = c;
                        break;
                    }
                }
                if(cons==null)
                    throw new NoSuchMethodException("No usable injection constructor for "+ccl);
                cons.setAccessible(true);
                int np = cons.getParameterCount();
                if(np==0)
                    return put(cons.newInstance());
//                System.out.println("Injecting args into "+cons.toGenericString());
                Object[] args = new Object[np];
                Class[] types = cons.getParameterTypes();
                for(int i = 0; i<np; i++) {
                    Class T = types[i];
                    if(T== Topics.class) {
                        ImplementsService svc = ccl.getAnnotation(ImplementsService.class);
                        if(svc!=null) {
                            String nm = svc.name();
                            if(nm!=null) {
                                args[i] = Context.this.get(Configuration.class).lookupTopics(nm);
                                continue;
                            }
                        }
                        args[i] = Topics.errorNode(Context.this, "message", "Synthetic args");
                    }
                    else args[i] = Context.this.get(T);
                }
//                System.out.println("**Construct "+Utils.deepToString(cons, 90)+" "+Utils.deepToString(args, 90));
                return put(cons.newInstance(args));
            } catch (Throwable ex) {
                log.error("Can't create instance of",targetClass,ex);
                throw new IllegalArgumentException("Can't create instance of "+targetClass.getName(), ex);
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
            EvergreenService asService = lvalue instanceof EvergreenService ? (EvergreenService) lvalue : null;
            InjectionActions injectionActions = lvalue instanceof InjectionActions ? (InjectionActions) lvalue : null;
            if (asService != null) asService.context = Context.this; // inject context early
            if (injectionActions != null)
                try {
                    injectionActions.preInject();
                } catch (Throwable e) {
                    if (asService != null) asService.errored("preInject", e);
                    else getLog().error("preInject",cl,e);
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
//                            System.out.println(cl.getSimpleName() + "." + f.getName() + " ...");
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
//                            System.out.println("   "+cl.getSimpleName() + "." + f.getName() + " = " + v);
                            if (asService != null && v instanceof EvergreenService)
                                asService.addDependency((EvergreenService) v,
                                        startWhen == null ? State.Running
                                                : startWhen.value());
                        } catch (Throwable ex) {
                            if (asService != null)
                                asService.errored("Injecting", ex);
                            else
                                log.error("Error injecting into", f, ex);
                        }
//                    else System.out.println("\tSKIP");
                }
                cl = cl.getSuperclass();
            }
            if (injectionActions != null && (asService == null || !asService.errored()))
                try {
                    injectionActions.postInject();
                } catch (Throwable e) {
                    if (asService != null) asService.errored("postInject", e);
                    else log.error("postInject", value.getClass(),e);
                }
        }

    }
    
    private BlockingDeque<Runnable> serialized
            = new LinkedBlockingDeque<>();
    public void runOnPublishQueue(Runnable r) { serialized.add(r); }
    public void queuePublish(Topic t) {
        runOnPublishQueue(() -> {
            t.fire(WhatHappened.changed);
            if (t.parent != null) t.parent.publish(t);
        });
    }
    {
        new Thread() {
            {
                setName("Serialized listener processor");
                setPriority(Thread.MAX_PRIORITY - 1);
//                setDaemon(true);
            }
            @Override
            public void run() {
                while (true) try {
                    Runnable task = serialized.takeFirst();
                    try {
                        task.run();
                    } catch (Throwable t) {
                        log.error("subscription listener errored",task.getClass(),t);
                    }
                } catch(InterruptedException e){}
            }
        }.start();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD})
    public @interface StartWhen {
        State value();
    }
}
