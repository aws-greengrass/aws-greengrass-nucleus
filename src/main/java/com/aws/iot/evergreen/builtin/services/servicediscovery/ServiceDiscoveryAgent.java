package com.aws.iot.evergreen.builtin.services.servicediscovery;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.ipc.services.common.GeneralResponse;
import com.aws.iot.evergreen.ipc.services.servicediscovery.LookupResourceRequest;
import com.aws.iot.evergreen.ipc.services.servicediscovery.RegisterResourceRequest;
import com.aws.iot.evergreen.ipc.services.servicediscovery.RemoveResourceRequest;
import com.aws.iot.evergreen.ipc.services.servicediscovery.Resource;
import com.aws.iot.evergreen.ipc.services.servicediscovery.ServiceDiscoveryResponseStatus;
import com.aws.iot.evergreen.ipc.services.servicediscovery.UpdateResourceRequest;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.util.LockScope;
import com.aws.iot.evergreen.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import javax.inject.Inject;

/**
 * Class to handle the business logic for Service Discovery including CRUD operations.
 */
public class ServiceDiscoveryAgent implements InjectionActions {
    public static final String REGISTERED_RESOURCES = "registered-resources";
    public static final String SERVICE_DISCOVERY_RESOURCE_CONFIG_KEY = "resources";
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Inject
    private Configuration config;

    @Inject
    private Log log;

    @Inject
    private Kernel kernel;

    public ServiceDiscoveryAgent() {
    }

    private static String resourceToPath(Resource r) {
        List<String> ll = new LinkedList<>();
        if (r.getName() != null) {
            ll.add(r.getName());
        }
        if (r.getServiceSubtype() != null) {
            ll.add(r.getServiceSubtype() + "._sub");
        }
        if (r.getServiceType() != null) {
            ll.add(r.getServiceType());
        }
        ll.add("_" + r.getServiceProtocol().name().toLowerCase());
        if (r.getDomain() != null) {
            ll.add(r.getDomain());
        }
        return String.join(".", ll);
    }

    private static boolean matchResourceFields(Resource input, Resource validateAgainst) {
        return nullOrEqual(input.getName(), validateAgainst.getName()) && nullOrEqual(input.getServiceType(),
                validateAgainst.getServiceType()) && nullOrEqual(input.getServiceSubtype(),
                validateAgainst.getServiceSubtype()) && nullOrEqual(input.getServiceProtocol(),
                validateAgainst.getServiceProtocol()) && nullOrEqual(input.getDomain(), validateAgainst.getDomain());
    }

    private static boolean nullOrEqual(Object input, Object validateAgainst) {
        if (input == null) {
            return true;
        }
        return Objects.equals(input, validateAgainst);
    }

    /**
     * Register a resource with Service Discovery. Will throw if the resource is already registered.
     *
     * @param request
     * @param serviceName
     * @return
     */
    public GeneralResponse<Resource, ServiceDiscoveryResponseStatus> registerResource(RegisterResourceRequest request,
                                                                                      String serviceName) {
        String resourcePath = resourceToPath(request.getResource());
        GeneralResponse<Resource, ServiceDiscoveryResponseStatus> response = new GeneralResponse<>();

        // TODO input validation. https://sim.amazon.com/issues/P32540011

        boolean pathIsReserved = kernel.orderedDependencies().parallelStream()
                .map(service -> config.findResolvedTopic(service.getName(), SERVICE_DISCOVERY_RESOURCE_CONFIG_KEY))
                .filter(Objects::nonNull).anyMatch(t -> {
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
            response.setErrorMessage(
                    String.format("Service %s is not allowed to register %s", serviceName, resourcePath));
            return response;
        }

        try (LockScope scope = LockScope.lock(lock.writeLock())) {
            if (isRegistered(resourcePath)) {
                response.setError(ServiceDiscoveryResponseStatus.AlreadyRegistered);
                response.setErrorMessage(String.format("%s already exists", resourcePath));
                return response;
            }

            SDAResource sdaResource =
                    SDAResource.builder().resource(request.getResource()).publishedToDNSSD(request.isPublishToDNSSD())
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
    public GeneralResponse<Void, ServiceDiscoveryResponseStatus> updateResource(UpdateResourceRequest request,
                                                                                String serviceName) {
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
                response.setErrorMessage(
                        String.format("Service %s is not allowed to update %s", serviceName, resourcePath));
                return response;
            }

            // update resource (only some fields are updatable)
            resource.getResource().setTxtRecords(request.getResource().getTxtRecords());
            resource.getResource().setUri(request.getResource().getUri());
            resource.setPublishedToDNSSD(request.isPublishToDNSSD());

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
    public GeneralResponse<Void, ServiceDiscoveryResponseStatus> removeResource(RemoveResourceRequest request,
                                                                                String serviceName) {
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
                response.setErrorMessage(
                        String.format("Service %s is not allowed to remove %s", serviceName, resourcePath));
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
    public GeneralResponse<List<Resource>, ServiceDiscoveryResponseStatus> lookupResources(
            LookupResourceRequest request, String serviceName) {
        String resourcePath = resourceToPath(request.getResource());
        GeneralResponse<List<Resource>, ServiceDiscoveryResponseStatus> response = new GeneralResponse<>();

        // TODO: input validation. https://sim.amazon.com/issues/P32540011
        response.setError(ServiceDiscoveryResponseStatus.Success);
        List<Resource> matchingResources = new ArrayList<>();

        try (LockScope scope = LockScope.lock(lock.readLock())) {
            // Try a direct lookup
            response.setResponse(matchingResources);
            if (isRegistered(resourcePath)) {
                matchingResources
                        .add(((SDAResource) config.find(REGISTERED_RESOURCES, resourcePath).getOnce()).getResource());
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

    private List<Resource> findMatchingResourcesInMap(LookupResourceRequest request) {
        // Just use a dumb linear search since we probably don't have *that* many resources.
        // Can definitely be optimized in future.
        return config.lookupTopics(REGISTERED_RESOURCES).children.values().stream()
                .map(node -> ((SDAResource) ((Topic) node).getOnce()).getResource())
                .filter(r -> matchResourceFields(request.getResource(), r)).collect(Collectors.toList());
    }
}
