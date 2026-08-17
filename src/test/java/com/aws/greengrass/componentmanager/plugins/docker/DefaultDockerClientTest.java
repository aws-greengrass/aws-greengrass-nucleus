/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({GGExtension.class})
class DefaultDockerClientTest {

    @ParameterizedTest
    @ValueSource(strings = {
            // Connection never established. These matched before this change.
            "Error response from daemon: Get \"https://registry-1.docker.io/v2/\": dial tcp: lookup registry-1"
                    + ".docker.io: no such host",
            "Error response from daemon: Get \"https://1234.dkr.ecr.us-east-1.amazonaws.com/v2/\": dial tcp 1.2.3"
                    + ".4:443: connect: connection refused",
            "Error response from daemon: Get \"https://1234.dkr.ecr.us-east-1.amazonaws.com/v2/\": read tcp 10.0.0"
                    + ".1:52044->1.2.3.4:443: read: connection timed out",
            "Get \"https://registry-1.docker.io/v2/\": net/http: TLS handshake timeout",
            "Error response from daemon: Get \"https://registry-1.docker.io/v2/\": request canceled while waiting "
                    + "for connection",
            "dial tcp: lookup 1234.dkr.ecr.us-east-1.amazonaws.com: Temporary failure in name resolution",
            // Established connection dying mid-transfer. The first of these is the error reported by a device whose
            // LTE connection failed over during a pull; before this change it matched nothing and so failed the
            // deployment on the first attempt.
            "Error response from daemon: Get \"https://1234.dkr.ecr.us-east-1.amazonaws.com/v2/vapr/manifests/sha256"
                    + ":d35a4457caa9e9bb60dc03a45b3fd9c0d7d242b06c1b0e36410cb5ed3b594050\": read tcp 172.28.1.230"
                    + ":52044->34.204.60.241:443: read: connection reset by peer",
            "Error response from daemon: Get \"https://registry-1.docker.io/v2/\": write tcp 10.0.0.1:52044->1.2.3"
                    + ".4:443: write: broken pipe",
            "Error response from daemon: Get \"https://registry-1.docker.io/v2/\": EOF",
            "failed to copy: httpReadSeeker: failed open: unexpected EOF",
            // Routing lost, typically while an interface is being torn down or brought up
            "Error response from daemon: Get \"https://1234.dkr.ecr.us-east-1.amazonaws.com/v2/\": dial tcp 1.2.3"
                    + ".4:443: connect: network is unreachable",
            "read tcp 10.0.0.1:52044->1.2.3.4:443: read: no route to host",
            // Wire-level TLS failures, which do not arrive as a net.OpError
            "Error response from daemon: Get \"https://registry-1.docker.io/v2/\": remote error: tls: bad record MAC",
            "Error response from daemon: Get \"https://registry-1.docker.io/v2/\": tls: use of closed connection",
            "Error response from daemon: Get \"https://registry-1.docker.io/v2/\": remote error: tls: internal error",
            // HTTP/2 transport failures
            "Error response from daemon: Get \"https://registry-1.docker.io/v2/\": http2: server sent GOAWAY and "
                    + "closed the connection",
            "Error response from daemon: Get \"https://registry-1.docker.io/v2/\": http2: client connection lost",
            "failed to copy: stream error: stream ID 5; INTERNAL_ERROR; received from peer",
            // Transport failures containerd surfaces after discarding the net.OpError
            "failed to copy: read |0: file already closed",
            "Error response from daemon: Get \"https://registry-1.docker.io/v2/\": context canceled",
            // A cause at the TCP layer that docker has not been observed emitting before is still classified by its
            // op prefix
            "Error response from daemon: Get \"https://registry-1.docker.io/v2/\": read tcp 10.0.0.1:52044->1.2.3"
                    + ".4:443: read: some errno not seen before"})
    void GIVEN_network_error_WHEN_classified_THEN_treated_as_connection_error(String err) {
        assertTrue(DefaultDockerClient.isConnectionError(err));
        // A network error must never also be claimed as non-retryable, since isConnectionError is evaluated first
        // and the two classifications must not disagree
        assertFalse(DefaultDockerClient.isNonRetryableError(err));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Error response from daemon: manifest for alpine:doesnotexist not found: manifest unknown: manifest "
                    + "unknown",
            "Error response from daemon: no matching manifest for linux/arm64/v8 in the manifest list entries",
            "invalid reference format",
            "failed to register layer: write /usr/lib/foo: no space left on device",
            "Error response from daemon: Head \"https://registry-1.docker.io/v2/library/alpine/manifests/latest\": "
                    + "unauthorized: authentication required",
            "Error response from daemon: denied: requested access to the resource is denied"})
    void GIVEN_known_non_retryable_error_WHEN_classified_THEN_treated_as_non_retryable(String err) {
        assertTrue(DefaultDockerClient.isNonRetryableError(err));
        assertFalse(DefaultDockerClient.isConnectionError(err));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "failed to copy: httpReadSeeker: failed open: unexpected status code 503",
            "Error response from daemon: failed to resolve reference: unexpected commit digest",
            "some error string docker has not emitted before"})
    void GIVEN_unrecognized_error_WHEN_classified_THEN_neither_connection_nor_non_retryable(String err) {
        assertFalse(DefaultDockerClient.isConnectionError(err));
        assertFalse(DefaultDockerClient.isNonRetryableError(err));
    }
}
