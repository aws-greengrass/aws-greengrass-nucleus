package com.aws.iot.evergreen.builtin.services.servicediscovery;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.ipc.IPCService;
import com.aws.iot.evergreen.ipc.Ipc;
import com.aws.iot.evergreen.ipc.impl.ServiceDiscoveryImpl;
import com.aws.iot.evergreen.ipc.services.common.GeneralResponse;
import com.aws.iot.evergreen.ipc.services.servicediscovery.ServiceDiscoveryResponseStatus;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.util.LockScope;
import com.aws.iot.evergreen.util.Log;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Class to handle the business logic for Service Discovery including CRUD operations.
 */
@ImplementsService(name = "servicediscovery", autostart = true)
public class ServiceDiscoveryAgent extends EvergreenService implements InjectionActions {
    public static final String REGISTERED_RESOURCES = "registered-resources";
    public static final String SERVICE_DISCOVERY_RESOURCE_CONFIG_KEY = "resources";
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Inject
    private Configuration config;

    @Inject
    private Log log;

    @Inject
    private Kernel kernel;

    public ServiceDiscoveryAgent(Topics c) {
        super(c);
        IPCService.registerService(new ServiceDiscoveryImpl(this));
    }

    /**
     * Register a resource with Service Discovery. Will throw if the resource is already registered.
     *
     * @param request
     * @param serviceName
     * @return
     */
    public GeneralResponse<Ipc.Resource, ServiceDiscoveryResponseStatus> registerResource(Ipc.RegisterResourceRequest request, String serviceName) {
        String resourcePath = resourceToPath(request.getResource());
        GeneralResponse<Ipc.Resource, ServiceDiscoveryResponseStatus> response = new GeneralResponse<>();

        // TODO input validation. https://sim.amazon.com/issues/P32540011

        boolean pathIsReserved =
                kernel.orderedDependencies().parallelStream()
                        .map(service -> config.findResolvedTopic(service.getName(), SERVICE_DISCOVERY_RESOURCE_CONFIG_KEY))
                        .filter(Objects::nonNull)
                        .anyMatch(t -> {
                            Object o = t.getOnce();
                            if (o instanceof Collection) {
                                String name = t.name;
                                Topics p = t.parent;
                                while (p.name != null) {
                                    name = p.name;
                                    p = p.parent;
                                }
                                return ((Collection) o).contains(resourcePath) && !serviceName.equals(name);
                            }
                            return false;
                        });

        if (pathIsReserved) {
            response.setError(ServiceDiscoveryResponseStatus.ResourceNotOwned);
            response.setErrorMessage(String.format("Service %s is not allowed to register %s", serviceName, resourcePath));
            return response;
        }

        try (LockScope scope = LockScope.lock(lock.writeLock())) {
            if (isRegistered(resourcePath)) {
                response.setError(ServiceDiscoveryResponseStatus.AlreadyRegistered);
                response.setErrorMessage(String.format("%s already exists", resourcePath));
                return response;
            }

            SDAResource sdaResource = SDAResource.builder()
                    .resource(request.getResource())
                    .publishedToDNSSD(request.getPublishToDNSSD())
                    .owningService(serviceName).build();
            config.lookup(REGISTERED_RESOURCES, resourcePath).setValue(sdaResource);

            response.setError(ServiceDiscoveryResponseStatus.Success);
            response.setResponse(request.getResource());
            return response;
        }
    }

    /**
     * Update an already existing resource. The update will only update the URI, TXT Records, and whether
     * it is published to DNS-SD or not.
     *
     * @param request
     * @param serviceName
     * @return
     */
    public GeneralResponse<Void, ServiceDiscoveryResponseStatus> updateResource(Ipc.UpdateResourceRequest request, String serviceName) {
        String resourcePath = resourceToPath(request.getResource());
        GeneralResponse<Void, ServiceDiscoveryResponseStatus> response = new GeneralResponse<>();

        // TODO input validation. https://sim.amazon.com/issues/P32540011

        try (LockScope scope = LockScope.lock(lock.writeLock())) {
            if (!isRegistered(resourcePath)) {
                response.setError(ServiceDiscoveryResponseStatus.ResourceNotFound);
                response.setErrorMessage(String.format("%s was not found", resourcePath));
                return response;
            }

            SDAResource resource = (SDAResource) config.find(REGISTERED_RESOURCES, resourcePath).getOnce();
            if (!resource.getOwningService().equals(serviceName)) {
                response.setError(ServiceDiscoveryResponseStatus.ResourceNotOwned);
                response.setErrorMessage(String.format("Service %s is not allowed to update %s", serviceName, resourcePath));
                return response;
            }

            // update resource (only some fields are updatable)
            Ipc.Resource.Builder updater = resource.getResource().toBuilder()
                    .clearTxtRecords()
                    .putAllTxtRecords(request.getResource().getTxtRecordsMap())
                    .setUri(request.getResource().getUri());
            resource.setPublishedToDNSSD(request.getPublishToDNSSD());
            resource.setResource(updater.build());

            response.setError(ServiceDiscoveryResponseStatus.Success);
            return response;
        }
    }

    /**
     * Remove an existing resource.
     *
     * @param request
     * @param serviceName
     * @return
     */
    public GeneralResponse<Void, ServiceDiscoveryResponseStatus> removeResource(Ipc.RemoveResourceRequest request, String serviceName) {
        String resourcePath = resourceToPath(request.getResource());
        GeneralResponse<Void, ServiceDiscoveryResponseStatus> response = new GeneralResponse<>();

        // TODO input validation. https://sim.amazon.com/issues/P32540011

        try (LockScope scope = LockScope.lock(lock.writeLock())) {
            if (!isRegistered(resourcePath)) {
                response.setError(ServiceDiscoveryResponseStatus.ResourceNotFound);
                response.setErrorMessage(String.format("%s was not found", resourcePath));
                return response;
            }

            SDAResource resource = (SDAResource) config.find(REGISTERED_RESOURCES, resourcePath).getOnce();
            if (!resource.getOwningService().equals(serviceName)) {
                response.setError(ServiceDiscoveryResponseStatus.ResourceNotOwned);
                response.setErrorMessage(String.format("Service %s is not allowed to remove %s", serviceName, resourcePath));
                return response;
            }

            config.find(REGISTERED_RESOURCES, resourcePath).remove();
            response.setError(ServiceDiscoveryResponseStatus.Success);
            return response;
        }
    }

    /**
     * Lookup resources. Returns a list of matching resources. Any null field in the input request
     * will be treated as a wildcard, so any value will match it.
     *
     * @param request
     * @param serviceName
     * @return
     */
    public GeneralResponse<List<Ipc.Resource>, ServiceDiscoveryResponseStatus> lookupResources(Ipc.LookupResourcesRequest request, String serviceName) {
        String resourcePath = resourceToPath(request.getResource());
        GeneralResponse<List<Ipc.Resource>, ServiceDiscoveryResponseStatus> response = new GeneralResponse<>();

        // TODO: input validation. https://sim.amazon.com/issues/P32540011
        response.setError(ServiceDiscoveryResponseStatus.Success);
        List<Ipc.Resource> matchingResources = new ArrayList<>();

        try (LockScope scope = LockScope.lock(lock.readLock())) {
            // Try a direct lookup
            response.setResponse(matchingResources);
            if (isRegistered(resourcePath)) {
                matchingResources.add(((SDAResource) config.find(REGISTERED_RESOURCES, resourcePath).getOnce()).getResource());
                return response;
            }

            // Exact match not found, try a fuzzy search
            matchingResources.addAll(findMatchingResourcesInMap(request));

            return response;
        }
    }

    private boolean isRegistered(String resourcePath) {
        return config.find(REGISTERED_RESOURCES, resourcePath) != null;
    }

    private List<Ipc.Resource> findMatchingResourcesInMap(Ipc.LookupResourcesRequest request) {
        // Just use a dumb linear search since we probably don't have *that* many resources.
        // Can definitely be optimized in future.
        return config.lookupTopics(REGISTERED_RESOURCES).children.values().stream()
                .map(node -> ((SDAResource) ((Topic) node).getOnce()).getResource())
                .filter(r -> matchResourceFields(request.getResource(), r))
                .collect(Collectors.toList());
    }

    private static String resourceToPath(Ipc.Resource r) {
        List<String> ll = new LinkedList<>();
        if (!r.getName().isEmpty())
        ll.add(r.getName());
        if (!r.getServiceSubtype().isEmpty())
        ll.add(r.getServiceSubtype() + "._sub");
        if (!r.getServiceType().isEmpty())
        ll.add(r.getServiceType());
        ll.add("_" + r.getServiceProtocol().name().toLowerCase());
        if (!r.getDomain().isEmpty())
        ll.add(r.getDomain());
        return String.join(".", ll);
    }

    private static boolean matchResourceFields(Ipc.Resource input, Ipc.Resource validateAgainst) {
        return nullEmptyOrEqual(input.getName(), validateAgainst.getName())
                && nullEmptyOrEqual(input.getServiceType(), validateAgainst.getServiceType())
                && nullEmptyOrEqual(input.getServiceSubtype(), validateAgainst.getServiceSubtype())
                && nullEmptyOrEqual(input.getServiceProtocol(), validateAgainst.getServiceProtocol())
                && nullEmptyOrEqual(input.getDomain(), validateAgainst.getDomain());
    }

    private static boolean nullEmptyOrEqual(Object input, Object validateAgainst) {
        if (input == null || (input instanceof String && ((String) input).isEmpty())) {
            return true;
        }
        return Objects.equals(input, validateAgainst);
    }
}
