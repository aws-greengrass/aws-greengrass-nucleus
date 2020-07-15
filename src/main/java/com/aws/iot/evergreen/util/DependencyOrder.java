/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.util;

import java.util.LinkedHashSet;
import java.util.Set;

public class DependencyOrder<T> {
    @FunctionalInterface
    public interface DependencyGetter<E> {
        Set<E> getDependencies(E elem);
    }

    /**
     * Resolve the inter-dependency order within a given set of elements.
     *
     * @param pendingDependencies a set of inter-dependent elements
     * @param dependencyGetter function to get all dependency elements of the given element
     * @return
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
                break;
            }
        }
        return dependencyFound;
    }
}
