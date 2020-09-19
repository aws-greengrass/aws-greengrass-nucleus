package com.aws.greengrass.telemetry;

import lombok.Getter;

import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;

public class NamespaceSet {
    @Getter
    private Set<String> namespaces = new HashSet<>();

    @Inject
    public NamespaceSet() {
        super();
    }

    public void addNamespace(String namespace) {
        namespaces.add(namespace);
    }
}
