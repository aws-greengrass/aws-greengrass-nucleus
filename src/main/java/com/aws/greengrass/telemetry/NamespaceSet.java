package com.aws.greengrass.telemetry;

import lombok.Getter;

import java.util.HashSet;
import java.util.Set;

public class NamespaceSet {
    @Getter
    private Set<String> namespaces = new HashSet<>();

    public void addNamespace(String namespace) {
        namespaces.add(namespace);
    }
}
