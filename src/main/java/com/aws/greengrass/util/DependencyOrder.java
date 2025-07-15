/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import java.util.LinkedHashSet;
import java.util.Set;

public class DependencyOrder<T> {
    private static final Logger logger = LogManager.getLogger(DependencyOrder.class);

    @FunctionalInterface
    public interface DependencyGetter<T> {
        Set<T> getDependencies(T elem);
    }

    /**
     * Resolve the inter-dependency order within a given set of elements.
     *
     * @param pendingDependencies a set of inter-dependent elements
     * @param dependencyGetter function to get all dependency elements of the given element
     * @return unique dependency order
     */
    @SuppressWarnings("PMD.LooseCoupling")
    public LinkedHashSet<T> computeOrderedDependencies(Set<T> pendingDependencies,
                                                       DependencyGetter<T> dependencyGetter) {
        final LinkedHashSet<T> dependencyFound = new LinkedHashSet<>();
        while (!pendingDependencies.isEmpty()) {
            int sz = pendingDependencies.size();
            pendingDependencies.removeIf(pendingService -> {
                if (dependencyFound.containsAll(dependencyGetter.getDependencies(pendingService))) {
                    dependencyFound.add(pendingService);
                    return true;
                }
                return false;
            });
            if (sz == pendingDependencies.size()) {
                // didn't find anything to remove, there must be a cycle
                logger.atError().kv("pendingItems", pendingDependencies).log(
                        "Found potential circular dependencies. Ignoring all pending items");
                break;
            }
        }
        return dependencyFound;
    }
}
