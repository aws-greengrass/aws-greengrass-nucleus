/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.dependency;


public enum State {
    // TODO The weird error states are not well handled (yet)
    Stateless,      /* Object does not have a state (not a Lifecycle) */
    New,            /* Freshly created, probably being injected */
    Installing,      /* Associated artifacts being installed */
    AwaitingStartup,/* Waiting for some dependency to start Running */
    Running,        /* Up and running, operating normally */
    Unstable,       /* Running, but experiencing problems that the service is
                     * attempting to repair itself */
    Errored,        /* Not running.  It may be possible for the enclosing framework
                     * to restart it. */
    Recovering,     /* In the process of being restarted */
    Shutdown,       /* Shut down, cannot be restarted */
    Finished        /* The service has done it's job and has no more to do.  May
                     * be restarted (for example, by a timer) */
}
