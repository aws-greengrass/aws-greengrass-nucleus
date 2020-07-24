/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import lombok.Getter;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

public class MultiInstanceEvergreenService extends EvergreenService {
    protected static final int BASE_INSTANCE_ID = 0;
    public static final String INSTANCES_NAMESPACE_KEY = "instances";
    protected AtomicInteger lastInstanceId;
    @Getter
    private final int instanceId;
    private Map<Integer, MultiInstanceEvergreenService> instances;

    MultiInstanceEvergreenService(Topics topics, int instanceId) {
        super(topics, topics.lookupTopics(RUNTIME_STORE_NAMESPACE_TOPIC, INSTANCES_NAMESPACE_KEY,
                String.valueOf(instanceId)));
        this.instanceId = instanceId;

        // Only allocate these objects when they're needed, which is when this is the base instance
        if (instanceId == BASE_INSTANCE_ID) {
            lastInstanceId = new AtomicInteger(BASE_INSTANCE_ID);
            instances = new ConcurrentHashMap<>();
        }
        logger.dfltKv("serviceInstance", String.valueOf(instanceId));
    }

    /**
     * Create a new sub-instance of this service. Must be called on the "base" service instance. Subclasses can choose
     * to override this method if they have a different constructor than just {@link Topics} and {@code int}.
     *
     * @return the newly created instance
     * @throws ServiceLoadException thrown if not called on the base instance, or if constructing the new instance
     *                              fails
     */
    public MultiInstanceEvergreenService createNewInstance() throws ServiceLoadException {
        if (instanceId != BASE_INSTANCE_ID) {
            throw new ServiceLoadException("New instances may only be created from the base instance");
        }
        try {
            int id = lastInstanceId.incrementAndGet();
            Constructor<? extends MultiInstanceEvergreenService> constructor =
                    getClass().getDeclaredConstructor(Topics.class, int.class);
            constructor.setAccessible(true);
            MultiInstanceEvergreenService newService = constructor.newInstance(config, id);
            context.injectFields(newService);
            context.get(Kernel.class).clearODcache(); // Must clear the cache because we've essentially added a new
            // dependency; the newly created service
            instances.put(id, newService);
            return newService;
        } catch (NoSuchMethodException | InstantiationException
                | IllegalAccessException | InvocationTargetException e) {
            throw new ServiceLoadException("Unable to create new instance of " + getClass().getName(), e);
        }
    }

    /**
     * Get an instance by ID.
     *
     * @param instanceId ID to lookup
     * @return instance or null if not found
     */
    @Nullable
    public MultiInstanceEvergreenService getInstance(int instanceId) {
        if (instances == null) {
            return null;
        }
        return instances.get(instanceId);
    }

    /**
     * Remove an instance by ID. Does not shutdown or close the instance, so ensure that the service is shutdown prior
     * to dropping references to it, otherwise it will just be lost.
     *
     * @param instanceId ID to lookup
     * @return instance or null if not found
     */
    @Nullable
    public MultiInstanceEvergreenService removeInstance(int instanceId) {
        if (instances == null) {
            return null;
        }
        return instances.remove(instanceId);
    }

    @Override
    public String getName() {
        if (instanceId == BASE_INSTANCE_ID) {
            return super.getName();
        }
        return String.format("%s-%d", super.getName(), instanceId);
    }

    // Override so that it adds all instances of the service into the dependency set
    @Override
    protected void putDependenciesIntoSet(Set<EvergreenService> deps) {
        if (instances != null) {
            deps.addAll(instances.values());
        }
        super.putDependenciesIntoSet(deps);
    }
}
