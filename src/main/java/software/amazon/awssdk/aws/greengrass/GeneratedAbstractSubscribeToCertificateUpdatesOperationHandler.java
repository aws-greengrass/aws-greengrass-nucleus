/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import software.amazon.awssdk.aws.greengrass.model.CertificateUpdateEvent;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToCertificateUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToCertificateUpdatesResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractSubscribeToCertificateUpdatesOperationHandler
        extends
            OperationContinuationHandler<SubscribeToCertificateUpdatesRequest, SubscribeToCertificateUpdatesResponse, EventStreamJsonMessage, CertificateUpdateEvent> {
    protected GeneratedAbstractSubscribeToCertificateUpdatesOperationHandler(
            OperationContinuationHandlerContext context) {
        super(context);
    }

    @Override
    public OperationModelContext<SubscribeToCertificateUpdatesRequest, SubscribeToCertificateUpdatesResponse, EventStreamJsonMessage, CertificateUpdateEvent> getOperationModelContext() {
        return GreengrassCoreIPCServiceModel.getSubscribeToCertificateUpdatesModelContext();
    }
}
