/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.lifecyclemanager.GreengrassService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

public final class DependerFinder {
    private DependerFinder() {
    }

    /**
     * Finds all services which are dependers of target services, directly or indirectly
     * This method performs a breadth-first search, starting from the target services and traversing through
     * all hard dependencies.
     * @param targetServices the set of services that we want to find their dependers
     * @return a set of all services that depend on the target services, including the target services
     */
    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
    public static Set<GreengrassService> findTargetServicesDependers(final Set<GreengrassService> targetServices) {
        Queue<GreengrassService> dependers = new LinkedList<>(targetServices);

        // Breadth-first search to find all dependent services, staring from broken services
        while (!dependers.isEmpty()) {
            GreengrassService currentService = dependers.poll();
            for (GreengrassService depender : currentService.getHardDependers()) {
                // Ensure dependers haven't been processed
                if (targetServices.add(depender)) {
                    dependers.offer(depender);
                }
            }
        }
        return targetServices;
    }

}
