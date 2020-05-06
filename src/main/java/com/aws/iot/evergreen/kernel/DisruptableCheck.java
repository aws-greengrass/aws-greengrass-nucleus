/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

public interface DisruptableCheck {
    /**
     * Inform a listener that a disruption is pending to find out when a disruption
     * is acceptable.
     *
     * @return Estimated time when this handler will be willing to be disrupted,
     *     expressed as milliseconds since the epoch. If
     *     the returned value is less than now (System.currentTimeMillis()) the handler
     *     is granting permission to be disrupted.  Otherwise, it will be asked again
     *     sometime later.
     */
    long whenIsDisruptionOK();

    /**
     * After a disruption, this is called to signal to the handler that the
     * disruption is over and it's OK to start activity.
     */
    void disruptionCompleted();
}
