/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.gg2k;

public class GenericExternalService extends GGService {
    public GenericExternalService(com.aws.iot.config.Topics c) {
        super(c);
    }
    @Override
    public void install() {
        log.note("install", this);
        run("install", false, null);
    }
    @Override
    public void awaitingStartup() {
        log.note("awaitingStartup", this);
        run("awaitingStartup", false, null);
    }
    @Override
    public void startup() {
        log.note("startup", this);
        run("startup", false, exit -> {
            if (exit == 0) {
                setState(State.Shutdown);
                log.note("Finished", GenericExternalService.this);
            } else {
                setState(State.Shutdown);
                log.error("Failed", exit, this);
            }
        });
    }
    @Override
    public void shutdown() {
        log.note("shutdown", this);
        run("shutdown", false, null);
    }

}
