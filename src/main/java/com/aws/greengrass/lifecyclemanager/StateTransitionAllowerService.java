/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.dependency.State;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;

public final class StateTransitionAllowerService {
    private final Set<StateTransitionAllower> stateTransitionAllowerSet = Collections.synchronizedSet(new HashSet<>());

    @Inject
    public StateTransitionAllowerService() {
    }

    public void registerStateTransitionAllower(StateTransitionAllower allower) {
        stateTransitionAllowerSet.add(allower);
    }

    public void deregisterStateTransitionAllower(StateTransitionAllower allower) {
        stateTransitionAllowerSet.remove(allower);
    }

    /**
     * Check if the state transition is allowed.
     *
     * @param service the service which wants to change state
     * @param from state to transition from
     * @param to state to transition to
     * @return false if the state transition is not allowed.
     */
    public boolean isStateTransitionAllowed(GreengrassService service, State from, State to) {
        for (StateTransitionAllower allower : stateTransitionAllowerSet) {
            if (!allower.allowStateTransition(service, from, to)) {
                return false;
            }
        }
        return true;
    }

    @FunctionalInterface
    public interface StateTransitionAllower {
        boolean allowStateTransition(GreengrassService service, State oldState, State newState);
    }
}
