package com.aws.iot.evergreen.builtin.services.servicediscovery;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.ipc.services.servicediscovery.LookupResourceRequest;
import com.aws.iot.evergreen.ipc.services.servicediscovery.LookupResourceResponse;
import com.aws.iot.evergreen.ipc.services.servicediscovery.RegisterResourceRequest;
import com.aws.iot.evergreen.ipc.services.servicediscovery.RegisterResourceResponse;
import com.aws.iot.evergreen.ipc.services.servicediscovery.RemoveResourceRequest;
import com.aws.iot.evergreen.ipc.services.servicediscovery.Resource;
import com.aws.iot.evergreen.ipc.services.servicediscovery.ServiceDiscoveryGenericResponse;
import com.aws.iot.evergreen.ipc.services.servicediscovery.ServiceDiscoveryResponseStatus;
import com.aws.iot.evergreen.ipc.services.servicediscovery.UpdateResourceRequest;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.util.LockScope;

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
     * @param registerResourceRequest incoming request
     * @param serviceName             name of the calling service
     * @return
     */
    public RegisterResourceResponse registerResource(RegisterResourceRequest registerResourceRequest,
                                                     String serviceName) {
        String resourcePath = resourceToPath(registerResourceRequest.getResource());
        RegisterResourceResponse registerResourceResponse = new RegisterResourceResponse();

        // TODO input validation. https://sim.amazon.com/issues/P32540011

        boolean pathIsReserved = kernel.orderedDependencies().parallelStream()
                .map(service -> config.find(service.getName(), SERVICE_DISCOVERY_RESOURCE_CONFIG_KEY))
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
            registerResourceResponse.setResponseStatus(ServiceDiscoveryResponseStatus.ResourceNotOwned);
            registerResourceResponse.setErrorMessage(
                    String.format("Service %s is not allowed to register %s", serviceName, resourcePath));
            return registerResourceResponse;
        }

        try (LockScope scope = LockScope.lock(lock.writeLock())) {
            if (isRegistered(resourcePath)) {
                registerResourceResponse.setResponseStatus(ServiceDiscoveryResponseStatus.AlreadyRegistered);
                registerResourceResponse.setErrorMessage(String.format("%s already exists", resourcePath));
                return registerResourceResponse;
            }

            SDAResource sdaResource = SDAResource.builder().resource(registerResourceRequest.getResource())
                    .publishedToDNSSD(registerResourceRequest.isPublishToDNSSD()).owningService(serviceName).build();
            config.lookup(REGISTERED_RESOURCES, resourcePath).setValue(sdaResource);

            registerResourceResponse.setResponseStatus(ServiceDiscoveryResponseStatus.Success);
            registerResourceResponse.setResource(registerResourceRequest.getResource());
            return registerResourceResponse;
        }
    }

    /**
     * Update an already existing resource. The update will only update the URI, TXT Records, and whether
     * it is published to DNS-SD or not.
     *
     * @param updateResourceRequest incoming request
     * @param serviceName           calling service name
     */
    public ServiceDiscoveryGenericResponse updateResource(UpdateResourceRequest updateResourceRequest,
                                                          String serviceName) {
        String resourcePath = resourceToPath(updateResourceRequest.getResource());
        ServiceDiscoveryGenericResponse serviceDiscoveryGenericResponse = new ServiceDiscoveryGenericResponse();

        // TODO input validation. https://sim.amazon.com/issues/P32540011

        try (LockScope scope = LockScope.lock(lock.writeLock())) {
            if (!isRegistered(resourcePath)) {
                serviceDiscoveryGenericResponse.setResponseStatus(ServiceDiscoveryResponseStatus.ResourceNotFound);
                serviceDiscoveryGenericResponse.setErrorMessage(String.format("%s was not found", resourcePath));
                return serviceDiscoveryGenericResponse;
            }

            SDAResource resource = (SDAResource) config.find(REGISTERED_RESOURCES, resourcePath).getOnce();
            if (!resource.getOwningService().equals(serviceName)) {
                serviceDiscoveryGenericResponse.setResponseStatus(ServiceDiscoveryResponseStatus.ResourceNotOwned);
                serviceDiscoveryGenericResponse.setErrorMessage(
                        String.format("Service %s is not allowed to update %s", serviceName, resourcePath));
                return serviceDiscoveryGenericResponse;
            }

            // update resource (only some fields are updatable)
            resource.getResource().setTxtRecords(updateResourceRequest.getResource().getTxtRecords());
            resource.getResource().setUri(updateResourceRequest.getResource().getUri());
            resource.setPublishedToDNSSD(updateResourceRequest.isPublishToDNSSD());

            serviceDiscoveryGenericResponse.setResponseStatus(ServiceDiscoveryResponseStatus.Success);
            return serviceDiscoveryGenericResponse;
        }
    }

    /**
     * Remove an existing resource.
     *
     * @param removeResourceRequest incoming removeResourceRequest
     * @param serviceName           calling service name
     */
    public ServiceDiscoveryGenericResponse removeResource(RemoveResourceRequest removeResourceRequest,
                                                          String serviceName) {
        String resourcePath = resourceToPath(removeResourceRequest.getResource());
        ServiceDiscoveryGenericResponse serviceDiscoveryGenericResponse = new ServiceDiscoveryGenericResponse();

        // TODO input validation. https://sim.amazon.com/issues/P32540011

        try (LockScope scope = LockScope.lock(lock.writeLock())) {
            if (!isRegistered(resourcePath)) {
                serviceDiscoveryGenericResponse.setResponseStatus(ServiceDiscoveryResponseStatus.ResourceNotFound);
                serviceDiscoveryGenericResponse.setErrorMessage(String.format("%s was not found", resourcePath));
                return serviceDiscoveryGenericResponse;
            }

            SDAResource resource = (SDAResource) config.find(REGISTERED_RESOURCES, resourcePath).getOnce();
            if (!resource.getOwningService().equals(serviceName)) {
                serviceDiscoveryGenericResponse.setResponseStatus(ServiceDiscoveryResponseStatus.ResourceNotOwned);
                serviceDiscoveryGenericResponse.setErrorMessage(
                        String.format("Service %s is not allowed to remove %s", serviceName, resourcePath));
                return serviceDiscoveryGenericResponse;
            }

            config.find(REGISTERED_RESOURCES, resourcePath).remove();
            serviceDiscoveryGenericResponse.setResponseStatus(ServiceDiscoveryResponseStatus.Success);
            return serviceDiscoveryGenericResponse;
        }
    }

    /**
     * Lookup resources. Returns a list of matching resources. Any null field in the input lookupResourceRequest
     * will be treated as a wildcard, so any value will match it.
     *
     * @param lookupResourceRequest incoming lookupResourceRequest
     * @param serviceName           calling service name
     */
    public LookupResourceResponse lookupResources(LookupResourceRequest lookupResourceRequest, String serviceName) {
        String resourcePath = resourceToPath(lookupResourceRequest.getResource());
        LookupResourceResponse lookupResourceResponse = new LookupResourceResponse();

        // TODO: input validation. https://sim.amazon.com/issues/P32540011
        lookupResourceResponse.setResponseStatus(ServiceDiscoveryResponseStatus.Success);
        List<Resource> matchingResources = new ArrayList<>();

        try (LockScope scope = LockScope.lock(lock.readLock())) {
            // Try a direct lookup
            lookupResourceResponse.setResources(matchingResources);
            if (isRegistered(resourcePath)) {
                matchingResources
                        .add(((SDAResource) config.find(REGISTERED_RESOURCES, resourcePath).getOnce()).getResource());
                return lookupResourceResponse;
            }

            // Exact match not found, try a fuzzy search
            matchingResources.addAll(findMatchingResourcesInMap(lookupResourceRequest));
            return lookupResourceResponse;
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
