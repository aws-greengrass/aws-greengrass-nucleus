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
import com.aws.iot.evergreen.util.Log;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ServiceDiscoveryAgent implements InjectionActions {
    public static final String REGISTERED_RESOURCES = "registered-resources";

    public static final String SERVICE_DISCOVERY_RESOURCE_CONFIG_KEY = "resources";

    @Inject
    private Configuration config;

    @Inject
    private Log log;

    @Inject
    private Kernel kernel;

    public ServiceDiscoveryAgent() {
    }

    public synchronized GeneralResponse<Resource, ServiceDiscoveryResponseStatus> registerResource(RegisterResourceRequest request, String serviceName) {
        String resourcePath = resourceToPath(request.getResource());
        GeneralResponse<Resource, ServiceDiscoveryResponseStatus> response = new GeneralResponse<>();

        // TODO input validation

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
        if (isRegistered(resourcePath)) {
            response.setError(ServiceDiscoveryResponseStatus.AlreadyRegistered);
            response.setErrorMessage(String.format("%s already exists", resourcePath));
            return response;
        }

        // Save resource
        SDAResource sdaResource = SDAResource.builder()
                .resource(request.getResource())
                .publishedToDNSSD(request.isPublishToDNSSD())
                .owningService(serviceName).build();
        config.lookup(REGISTERED_RESOURCES, resourcePath).setValue(sdaResource);

        response.setError(ServiceDiscoveryResponseStatus.Success);
        response.setResponse(request.getResource());
        return response;
    }

    private boolean isRegistered(String resourcePath) {
        return config.find(REGISTERED_RESOURCES, resourcePath) != null;
    }

    public synchronized GeneralResponse<Void, ServiceDiscoveryResponseStatus> updateResource(UpdateResourceRequest request, String serviceName) {
        String resourcePath = resourceToPath(request.getResource());
        GeneralResponse<Void, ServiceDiscoveryResponseStatus> response = new GeneralResponse<>();

        // TODO input validation

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
        resource.getResource().setTxtRecords(request.getResource().getTxtRecords());
        resource.getResource().setUri(request.getResource().getUri());
        resource.setPublishedToDNSSD(request.isPublishToDNSSD());

        response.setError(ServiceDiscoveryResponseStatus.Success);
        return response;
    }

    public synchronized GeneralResponse<Void, ServiceDiscoveryResponseStatus> removeResource(RemoveResourceRequest request, String serviceName) {
        String resourcePath = resourceToPath(request.getResource());
        GeneralResponse<Void, ServiceDiscoveryResponseStatus> response = new GeneralResponse<>();

        // TODO input validation

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

        // Remove from master list
        config.find(REGISTERED_RESOURCES, resourcePath).remove();
        response.setError(ServiceDiscoveryResponseStatus.Success);
        return response;
    }

    public GeneralResponse<List<Resource>, ServiceDiscoveryResponseStatus> lookupResources(LookupResourceRequest request, String serviceName) {
        String resourcePath = resourceToPath(request.getResource());
        GeneralResponse<List<Resource>, ServiceDiscoveryResponseStatus> response = new GeneralResponse<>();

        // TODO: input validation
        response.setError(ServiceDiscoveryResponseStatus.Success);
        List<Resource> matchingResources = new ArrayList<>();

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

    private List<Resource> findMatchingResourcesInMap(LookupResourceRequest request) {
        // Just use a dumb linear search since we probably don't have *that* many resources.
        // Can definitely be optimized in future.
        return config.lookupTopics(REGISTERED_RESOURCES).children.values().stream()
                .map(node -> ((SDAResource) ((Topic) node).getOnce()).getResource())
                .filter(r -> matchResourceFields(request.getResource(), r))
                .collect(Collectors.toList());
    }

    private static String resourceToPath(Resource r) {
        List<String> ll = new LinkedList<>();
        if (r.getName() != null)
        ll.add(r.getName());
        if (r.getServiceSubtype() != null)
        ll.add(r.getServiceSubtype() + "._sub");
        if (r.getServiceType() != null)
        ll.add(r.getServiceType());
        ll.add("_" + r.getServiceProtocol().name().toLowerCase());
        if (r.getDomain() != null)
        ll.add(r.getDomain());
        return String.join(".", ll);
    }

    private static boolean matchResourceFields(Resource input, Resource validateAgainst) {
        return nullOrEqual(input.getName(), validateAgainst.getName())
                && nullOrEqual(input.getServiceType(), validateAgainst.getServiceType())
                && nullOrEqual(input.getServiceSubtype(), validateAgainst.getServiceSubtype())
                && nullOrEqual(input.getServiceProtocol(), validateAgainst.getServiceProtocol())
                && nullOrEqual(input.getDomain(), validateAgainst.getDomain());
    }

    private static boolean nullOrEqual(Object input, Object validateAgainst) {
        if (input == null) {
            return true;
        }
        return Objects.equals(input, validateAgainst);
    }
}
