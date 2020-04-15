/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.dependency;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.GlobalStateChangeListener;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.CrashableFunction;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.Objects;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
    private static final Logger logger = LogManager.getLogger(Context.class);
    private static final String classKeyword = "class";
    // magical
    private boolean shuttingDown = false;
    // global state change notification
    private CopyOnWriteArrayList<GlobalStateChangeListener> listeners;
    private final BlockingDeque<Runnable> serialized = new LinkedBlockingDeque<>();
    private final Thread publishThread = new Thread() {
        {
            setName("Serialized listener processor");
            setPriority(Thread.MAX_PRIORITY - 1);
            //                setDaemon(true);
        }

        @SuppressWarnings({"checkstyle:emptycatchblock", "PMD.AvoidCatchingThrowable"})
        @Override
        public void run() {
            while (true) {
                try {
                    Runnable task = serialized.takeFirst();
                    task.run();
                } catch (InterruptedException ie) {
                    logger.atWarn().log("Interrupted while running tasks. Publish thread will exit now.");
                    return;
                } catch (Throwable t) {
                    logger.atError().setEventType("run-on-publish-queue-error").setCause(t).log();
                }
            }
        }
    };

    public Context() {
        parts.put(Context.class, new Value(Context.class, this));
        publishThread.start();
    }

    /**
     * Removed an entry with the provided tag.
     * @param tag key to be removed
     * @return true is success, false if tag not found
     */
    public boolean remove(Object tag) {
        return parts.remove(tag) != null;
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
     * @param cl  class to lookup
     * @param tag tag of the instance of the class to get
     * @param <T> the class type to lookup
     * @return null if it could not be found, returns the class otherwise
     */
    public <T> T getIfExists(Class<T> cl, String tag) {
        Value v = getvIfExists(tag == null ? cl : tag);
        if (v == null) {
            return null;
        }
        Object o = v.targetObject;
        return o == null || !cl.isAssignableFrom(o.getClass()) ? null : (T) o;
    }

    public Value getvIfExists(Object tag) {
        return parts.get(tag);
    }

    /**
     * Put a class into the Context.
     *
     * @param cl  type of class to be stored
     * @param v   instance of class to store
     * @param <T> the class type to put
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
     * @param cl  type of class to be stored
     * @param v   value instance of class to store
     * @param <T> the class type to put
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
     * @param v   value
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
            Object vv = v.targetObject;
            try {
                if (vv instanceof Closeable) {
                    ((Closeable) vv).close();
                    logger.atDebug("context-shutdown").kv(classKeyword, Coerce.toString(vv)).log();
                }
            } catch (IOException t) {
                logger.atError("context-shutdown-error", t).kv(classKeyword, Coerce.toString(vv)).log();
            }
        });
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
    public synchronized void addGlobalStateChangeListener(GlobalStateChangeListener l) {
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
    public synchronized void removeGlobalStateChangeListener(GlobalStateChangeListener l) {
        if (listeners != null) {
            listeners.remove(l);
        }
    }

    /**
     * Serially send an event to the global state change listeners.
     *
     * @param changedService the service which had a state change
     * @param oldState  the old state of the service
     * @param newState the new state of the service
     */
    public synchronized void globalNotifyStateChanged(EvergreenService changedService, final State oldState,
                                                      final State newState) {
        if (listeners != null) {
            listeners.forEach(s -> s.globalServiceStateChanged(changedService, oldState, newState));
        }
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
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public Throwable runOnPublishQueueAndWait(Crashable r) {
        AtomicReference<Throwable> ret = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        runOnPublishQueue(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                ret.set(t);
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
        public volatile T targetObject;
        @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "No need to be sync")
        private boolean injectionCompleted;

        Value(Class<T> clazz, T object) {
            targetClass = clazz;
            put(object);
        }

        @Override
        public final T get() {
            if (targetObject != null && injectionCompleted) {
                return targetObject;
            }
            return constructObject();
        }

        @SuppressWarnings({"PMD.AvoidCatchingThrowable"})
        private synchronized T constructObject() {
            T object = targetObject;
            if (object != null) {
                return object;
            }
            try {
                Class<T> clazz = targetClass.isInterface() ? (Class<T>) targetClass.getClassLoader()
                        .loadClass(targetClass.getName() + "$Default") : targetClass;
                //                System.out.println(ccl+"  "+deepToString(ccl.getConstructors()));
                Constructor<T> pickedConstructor = null;
                for (Constructor<T> constructor : (Constructor<T>[]) clazz.getConstructors()) {
                    //                    System.out.println("Examine "+c.getParameterCount()+" "+c.toGenericString());
                    if (constructor.getParameterCount() == 0) {
                        pickedConstructor = constructor;
                    } else if (constructor.isAnnotationPresent(Inject.class)) {
                        pickedConstructor = constructor;
                        break;
                    }
                }
                if (pickedConstructor == null) {
                    throw new NoSuchMethodException("No usable injection constructor for " + clazz);
                }
                pickedConstructor.setAccessible(true);
                int np = pickedConstructor.getParameterCount();
                if (np == 0) {
                    return put(pickedConstructor.newInstance());
                }
                //                System.out.println("Injecting args into "+cons.toGenericString());
                Object[] args = new Object[np];
                Class[] types = pickedConstructor.getParameterTypes();

                Annotation[][] argAnnotations = pickedConstructor.getParameterAnnotations();

                for (int i = 0; i < np; i++) {
                    Class type = types[i];
                    if (type == Topics.class) {
                        ImplementsService service = clazz.getAnnotation(ImplementsService.class);
                        if (service != null) {
                            String serviceName = service.name();
                            args[i] = Context.this.get(Configuration.class).lookupTopics(serviceName);
                            continue;
                        }
                        args[i] = Topics.errorNode(Context.this, "message", "Synthetic args");
                    } else {

                        String name = null;

                        for (Annotation annotation: argAnnotations[i]) {
                            if (annotation instanceof Named) {
                                name = nullEmpty(((Named) annotation).value());
                            }
                        }

                        if (name != null) {
                            args[i] = Context.this.get(type, name);
                        } else {
                            args[i] = Context.this.get(type);
                        }
                    }
                }
                //                System.out.println("**Construct "+utils.deepToString(cons, 90)+" "+utils
                //                .deepToString(args, 90));
                return put(pickedConstructor.newInstance(args));
            } catch (Throwable ex) {
                throw new IllegalArgumentException("Can't create instance of " + targetClass.getName(), ex);
            }
        }

        /**
         * Put a new object instance and perform injection actions.
         *
         * @param object the object instance
         * @return new value
         */
        public final synchronized T put(T object) {
            if (Objects.equals(object, targetObject)) {
                return object;
            }
            if (object == null || targetClass.isAssignableFrom(object.getClass())) {
                injectionCompleted = false;
                targetObject = object;
                doInjection(object);
                injectionCompleted = true;
                return object; // only assign after injection is complete
            } else {
                throw new IllegalArgumentException(object + " is not assignable to " + targetClass.getSimpleName());
            }
        }

        /**
         * Computes and return T if object instance is null
         * @param mappingFunction maps from Value to T
         * @return the current (existing or computed) object instance
         */
        public final synchronized <E extends Exception> T computeObjectIfEmpty(CrashableFunction<Value, T, E> mappingFunction) throws E {
            if (targetObject != null) {
                return targetObject;
            }

            return put(mappingFunction.apply(this));
        }

        public boolean isEmpty() {
            return targetObject == null;
        }

        @SuppressWarnings({"PMD.AvoidCatchingThrowable"})
        private void doInjection(Object object) {
            //            System.out.println("requestInject " + lvalue);
            if (object == null) {
                return;
            }
            Class clazz = object.getClass();
            String className = clazz.getName();
            logger.atTrace("class-injection-start").kv(classKeyword, className).log();

            EvergreenService asService = object instanceof EvergreenService ? (EvergreenService) object : null;
            InjectionActions injectionActions = object instanceof InjectionActions ? (InjectionActions) object : null;
            if (asService != null) {
                asService.context = Context.this; // inject context early
            }
            if (injectionActions != null) {
                try {
                    injectionActions.preInject();
                    logger.atTrace("class-pre-inject-complete").kv(classKeyword, className).log();
                } catch (Throwable e) {
                    logger.atError("class-pre-inject-error", e).kv(classKeyword, className).log();
                    if (asService != null) {
                        asService.serviceErrored(e);
                    }
                }
            }
            while (clazz != null && clazz != Object.class) {
                for (Field f : clazz.getDeclaredFields()) {
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

                                // if v is an EvergreenService, then make sure to save it into
                                // the context tagged with its service name so that EvergreenService.locate
                                // will be able to find it when it looks for it by name (and not by class)
                                if (v instanceof EvergreenService) {
                                    Context.this.getv(EvergreenService.class, ((EvergreenService) v).getName())
                                            .put((EvergreenService) v);
                                }
                            }
                            StartWhen startWhen = f.getAnnotation(StartWhen.class);
                            f.setAccessible(true);
                            f.set(object, v);
                            //                            System.out.println("   "+cl.getSimpleName() + "." + f
                            //                            .getName()
                            //                            + " = " + v);
                            if (asService != null && v instanceof EvergreenService) {
                                asService.addOrUpdateDependency((EvergreenService) v,
                                        startWhen == null ? State.RUNNING : startWhen.value(), true);
                            }
                            logger.atTrace("class-inject-complete").kv(classKeyword, f.getName()).log();
                        } catch (Throwable ex) {
                            logger.atError("class-inject-error", ex).kv(classKeyword, f.getName()).log();
                            if (asService != null) {
                                asService.serviceErrored(ex);
                            }
                        }
                    }
                    //                    else System.out.println("\tSKIP");
                }
                clazz = clazz.getSuperclass();
            }
            if (injectionActions != null && (asService == null || !asService.isErrored())) {
                try {
                    injectionActions.postInject();
                    logger.atTrace("class-post-inject-complete").kv(classKeyword, targetObject.getClass()).log();
                } catch (Throwable e) {
                    logger.atError("class-post-inject-error", e).kv(classKeyword, targetObject.getClass()).log();
                    if (asService != null) {
                        asService.serviceErrored(e);
                    }
                }
            }

            logger.atTrace("class-injection-complete").kv(classKeyword, className).log();
        }

    }
}
