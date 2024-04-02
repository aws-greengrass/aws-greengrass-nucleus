/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.dependency;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.lifecyclemanager.GlobalStateChangeListener;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.CrashableFunction;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.util.Utils.isEmpty;
import static com.aws.greengrass.util.Utils.nullEmpty;

/**
 * A collection of Objects that work together.
 */
@SuppressFBWarnings(value = "SC_START_IN_CTOR", justification = "Starting thread in constructor is what we want")
public class Context implements Closeable {
    private static final Logger logger = LogManager.getLogger(Context.class);
    private static final String classKeyword = "class";
    private final ConcurrentHashMap<Object, Value> parts = new ConcurrentHashMap<>();
    private final BlockingDeque<Runnable> serialized = new LinkedBlockingDeque<>();
    private final Thread publishThread = new Thread() {
        {
            setName("Serialized listener processor");
            setPriority(Thread.MAX_PRIORITY - 1);
            //                setDaemon(true);
        }

        @SuppressWarnings("PMD.AvoidCatchingThrowable")
        @Override
        public void run() {
            while (!requestPublishThreadStop.get()) {
                try {
                    Runnable task = serialized.takeFirst();
                    task.run();
                } catch (InterruptedException ie) {
                    return;
                } catch (Throwable t) {
                    logger.atError().setEventType("run-on-publish-queue-error").setCause(t).log();
                }
            }
        }
    };
    private static final Crashable doNothing = () -> {};
    // magical
    private boolean shuttingDown = false;
    // global state change notification
    private CopyOnWriteArrayList<GlobalStateChangeListener> listeners;
    private final AtomicBoolean requestPublishThreadStop = new AtomicBoolean();

    public Context() {
        parts.put(Context.class, new Value(Context.class, this));
        publishThread.start();
    }

    /**
     * Removed an entry with the provided tag.
     *
     * @param tag key to be removed
     * @return Value that was removed
     */
    public Value remove(Object tag) {
        return parts.remove(tag);
    }

    public <T> T get(Class<T> clazz) {
        return getValue(clazz, clazz).get();
    }

    public <T> T get(Class<T> clazz, String tag) {
        return getValue(clazz, isEmpty(tag) ? clazz : tag).get();
    }

    public <T> Value<T> getValue(Class<T> clazz) {
        return getValue(clazz, clazz);
    }

    public <T> Value<T> getValue(Class<T> clazz, String tag) {
        return getValue(clazz, isEmpty(tag) ? clazz : tag);
    }

    private <T> Value<T> getValue(Class<T> clazz, Object tag) {
        return parts.computeIfAbsent(tag, c -> new Value(clazz, null));
    }

    public <T> T newInstance(Class<T> cl) {
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
        Object o = v.object;
        return o == null || !cl.isAssignableFrom(o.getClass()) ? null : (T) o;
    }

    public Value getvIfExists(Object tag) {
        return parts.get(tag);
    }

    /**
     * Put a class into the Context.
     *
     * @param clazz  type of class to be stored
     * @param object instance of class to store
     * @param <T>    the class type to put
     * @return this
     */
    public <T> Context put(Class<T> clazz, T object) {
        parts.compute(clazz, (tagObj, originalValue) -> {
            if (originalValue == null) {
                originalValue = new Value(clazz, object);
            } else {
                originalValue.putAndInjectFields(object);
            }
            return originalValue;
        });
        return this;
    }

    /**
     * Put a class into the Context.
     *
     * @param clazz type of class to be stored
     * @param value value instance of class to store
     * @param <T>   the class type to put
     * @return this
     */
    public <T> Context put(Class<T> clazz, Value<T> value) {
        parts.put(clazz, value);
        return this;
    }

    /**
     * Put object into the context with a provided tag.
     *
     * @param tag    tag
     * @param object value
     * @return this
     */
    public Context put(String tag, Object object) {
        parts.compute(tag, (tagObject, originalValue) -> {
            if (originalValue == null) {
                originalValue = new Value(object.getClass(), object);
            } else {
                originalValue.putAndInjectFields(object);
            }
            return originalValue;
        });
        return this;
    }

    /**
     * Shutdown this context, closing all closeable classes stored in this context.
     */
    public void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;

        parts.values().forEach(value -> {
            Object object = value.object;
            try {
                if (object instanceof Closeable) {
                    ((Closeable) object).close();
                    logger.atDebug("context-shutdown").kv(classKeyword, Coerce.toString(object)).log();
                }
                if (object instanceof ExecutorService) {
                    logger.atDebug("context-shutdown").kv(classKeyword, Coerce.toString(object))
                            .kv("executorInterruptedRunnables", ((ExecutorService) object).shutdownNow()).log();
                }
            } catch (IOException t) {
                logger.atError("context-shutdown-error", t).kv(classKeyword, Coerce.toString(object)).log();
            }
        });

        // Request stop without actually interrupting the publish thread
        requestPublishThreadStop.set(true);
        // Add something into the queue to be sure that takeFirst returns
        runOnPublishQueue(() -> {});
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
     * @param oldState       the old state of the service
     * @param newState       the new state of the service
     */
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public synchronized void globalNotifyStateChanged(GreengrassService changedService, final State oldState,
                                                      final State newState) {
        if (listeners != null) {
            listeners.forEach(s -> {
                try {
                    s.globalServiceStateChanged(changedService, oldState, newState);
                } catch (Throwable t) {
                    logger.atError().log("Error publishing service state change", t);
                }
            });
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

    @SuppressWarnings("checkstyle:MissingJavadocMethod")
    public void waitForPublishQueueToClear() {
        // Run on the publish queue until it is empty. When the queue is empty that doesn't mean that
        // all jobs have finished processing though, so we run it once again at the end.
        do {
            runOnPublishQueueAndWait(doNothing);
        } while (!serialized.isEmpty());
        runOnPublishQueueAndWait(doNothing);
    }

    private boolean onPublishThread() {
        return Thread.currentThread() == publishThread;
    }

    /**
     * Use to manually inject dependencies into the fields of a class using the values in the current Context. Use with
     * caution because you should not inject fields into a class multiple times as it will trigger the pre and post
     * lifecycle methods each time.
     *
     * @param object Object to inject fields into
     */
    @SuppressFBWarnings("DP_DO_INSIDE_DO_PRIVILEGED")
    @SuppressWarnings({"PMD.AvoidCatchingThrowable"})
    public void injectFields(Object object) {
        if (object == null) {
            return;
        }
        Class clazz = object.getClass();
        String className = clazz.getName();
        logger.atTrace("class-injection-start").kv(classKeyword, className).log();

        GreengrassService asService = object instanceof GreengrassService ? (GreengrassService) object : null;
        InjectionActions injectionActions = object instanceof InjectionActions ? (InjectionActions) object : null;
        if (asService != null) {
            asService.context = this; // inject context early
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
                if (a != null) {
                    try {
                        final Named named = f.getAnnotation(Named.class);
                        final String name = nullEmpty(named == null ? null : named.value());
                        Class t = f.getType();
                        Object v;
                        if (t == Provider.class) {
                            v = getValue((Class) ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0],
                                    name);
                        } else {
                            v = this.get(t, name);

                            // if v is a GreengrassService, then make sure to save it into
                            // the context tagged with its service name so that GreengrassService.locate
                            // will be able to find it when it looks for it by name (and not by class)
                            if (v instanceof GreengrassService) {
                                this.getValue(GreengrassService.class, ((GreengrassService) v).getName())
                                        .putAndInjectFields((GreengrassService) v);
                            }
                        }
                        ServiceDependencyType dependencyType = f.getAnnotation(ServiceDependencyType.class);
                        f.setAccessible(true);
                        f.set(object, v);
                        if (asService != null && v instanceof GreengrassService) {
                            asService.addOrUpdateDependency((GreengrassService) v,
                                    dependencyType == null ? DependencyType.HARD : dependencyType.value(), true);
                        }
                        logger.atTrace("class-inject-complete").kv(classKeyword, f.getName()).log();
                    } catch (Throwable ex) {
                        logger.atError("class-inject-error", ex).kv(classKeyword, f.getName()).log();
                        if (asService != null) {
                            asService.serviceErrored(ex);
                        }
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        if (injectionActions != null && (asService == null || !asService.isErrored())) {
            try {
                injectionActions.postInject();
                logger.atTrace("class-post-inject-complete").kv(classKeyword, object.getClass()).log();
            } catch (Throwable e) {
                logger.atError("class-post-inject-error", e).kv(classKeyword, object.getClass()).log();
                if (asService != null) {
                    asService.serviceErrored(e);
                }
            }
        }

        logger.atTrace("class-injection-complete").kv(classKeyword, className).log();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD})
    public @interface ServiceDependencyType {
        /**
         * What state to start the service.
         */
        DependencyType value();
    }

    public class Value<T> implements Provider<T> {
        private final Class<T> targetClass;
        private volatile T object;
        @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "No need to be sync")
        private boolean injectionCompleted;

        Value(Class<T> clazz, T object) {
            targetClass = clazz;
            putAndInjectFields(object);
        }

        @Override
        public final T get() {
            if (object != null && injectionCompleted) {
                return object;
            }
            return constructObjectWithInjection();
        }

        /**
         * Put a new object instance and inject fields with pre and post actions, if the new object is not equal to
         * current one.
         *
         * @param newObject the new object instance
         * @return new object with fields injected
         */
        final T putAndInjectFields(T newObject) {
            synchronized (Context.this) {
                synchronized (this) {
                    if (Objects.equals(newObject, object)) {
                        return newObject;
                    }
                    if (newObject == null || targetClass.isAssignableFrom(newObject.getClass())) {
                        injectionCompleted = false;
                        object = newObject;
                        Context.this.injectFields(newObject);
                        injectionCompleted = true;
                        return newObject; // only assign after injection is complete

                    } else {
                        throw new IllegalArgumentException(
                                newObject + " is not assignable to " + targetClass.getSimpleName());
                    }
                }
            }
        }

        @SuppressWarnings("PMD.AvoidCatchingThrowable")
        private T constructObjectWithInjection() {
            synchronized (Context.this) {
                synchronized (this) {
                    if (object != null) {
                        return object;
                    }

                    try {
                        Class<T> clazz = targetClass;

                        if (targetClass.isInterface()) {
                            // For interface, we only support binding the inner
                            // "Default" class as implementation class for now
                            clazz = (Class<T>) targetClass.getClassLoader().loadClass(
                                    targetClass.getName() + "$Default");
                        }

                        Constructor<T> pickedConstructor = pickConstructor(clazz);
                        pickedConstructor.setAccessible(true);

                        int paramCount = pickedConstructor.getParameterCount();
                        if (paramCount == 0) {
                            // no arg constructor
                            return putAndInjectFields(pickedConstructor.newInstance());
                        }

                        Object[] args = getOrCreateArgInstances(clazz, pickedConstructor, paramCount);
                        return putAndInjectFields(pickedConstructor.newInstance(args));
                    } catch (Throwable ex) {
                        throw new IllegalArgumentException("Can't create instance of " + targetClass.getName(), ex);
                    }
                }
            }
        }

        private Object[] getOrCreateArgInstances(Class<T> clazz, Constructor<T> pickedConstructor, int argCount) {
            Object[] args = new Object[argCount];
            Class[] argTypes = pickedConstructor.getParameterTypes();
            Annotation[][] argAnnotations = pickedConstructor.getParameterAnnotations();

            for (int i = 0; i < argCount; i++) {
                Class argClazz = argTypes[i];

                if (argClazz == Topics.class) {
                    ImplementsService service = clazz.getAnnotation(ImplementsService.class);
                    if (service != null) {
                        String serviceName = service.name();
                        args[i] = Context.this.get(Configuration.class)
                                .lookupTopics(SERVICES_NAMESPACE_TOPIC, serviceName);
                        continue;
                    }
                    args[i] = Topics.errorNode(Context.this, "message", "Synthetic args");
                } else {
                    String name = null;

                    for (Annotation annotation : argAnnotations[i]) {
                        if (annotation instanceof Named) {
                            name = nullEmpty(((Named) annotation).value());
                        }
                    }

                    if (name == null) {
                        args[i] = Context.this.get(argClazz);
                    } else {
                        args[i] = Context.this.get(argClazz, name);
                    }
                }
            }
            return args;
        }

        private Constructor<T> pickConstructor(Class<T> clazz) throws NoSuchMethodException {
            // Use constructor with @Inject if exists
            for (Constructor<T> constructor : (Constructor<T>[]) clazz.getDeclaredConstructors()) {
                if (constructor.isAnnotationPresent(Inject.class)) {
                    return constructor;
                }
            }

            // fall back to no arg constructor
            for (Constructor<T> constructor : (Constructor<T>[]) clazz.getDeclaredConstructors()) {
                if (constructor.getParameterCount() == 0) {
                    return constructor;
                }
            }

            throw new NoSuchMethodException("No usable injection constructor for " + clazz);
        }

        /**
         * Computes and return T if object instance is null. TODO revisit to see if there is a better way because the
         * mapping function usage is weird.
         *
         * @param mappingFunction maps from Value to T
         * @param <E>             CheckedException
         * @return the current (existing or computed) object instance
         * @throws E when mapping function throws checked exception
         */
        public final <E extends Exception> T computeObjectIfEmpty(
                CrashableFunction<Value, T, E> mappingFunction) throws E {
            synchronized (Context.this) {
                synchronized (this) {
                    if (object != null) {
                        return object;
                }

                    return putAndInjectFields(mappingFunction.apply(this));
                }
            }
        }

        public boolean isEmpty() {
            return object == null;
        }
    }
}
