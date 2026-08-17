/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker;

import com.aws.greengrass.componentmanager.plugins.docker.exceptions.ConnectionException;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.DockerImageDeleteException;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.DockerImageQueryException;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.DockerLoginException;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.DockerPullException;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.DockerServiceUnavailableException;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.InvalidImageOrAccessDeniedException;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.UnknownDockerPullException;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.UserNotAuthorizedForDockerException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.platforms.Platform;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Docker CLI wrapper that communicates with Docker Engine to execute user commands.
 */
@NoArgsConstructor
public class DefaultDockerClient {
    public static final Logger logger = LogManager.getLogger(DefaultDockerClient.class);

    /**
     * Errors indicating that a docker pull failed at the network transport layer rather than being rejected by the
     * registry. Such a failure is recoverable once connectivity is restored, so it is retried indefinitely.
     *
     * <p>Two shapes are matched. Go renders a {@code net.OpError} as
     * {@code "<op> <net> <source>-><addr>: <cause>"}, so matching on the op prefix classifies a failure at the TCP
     * layer regardless of which cause follows, including causes not seen before. Only {@code dial tcp} was matched
     * previously, which covers a connection that fails to be established; a connection that dies mid-transfer
     * instead reports {@code read tcp} or {@code write tcp}. The remaining entries are transport-level causes,
     * matched on their own because docker and containerd also surface them without an op prefix, for example when
     * wrapping an error raised while copying an image layer.
     */
    private static final List<String> CONNECTION_ERRORS = Collections.unmodifiableList(Arrays.asList(
            // Go net.OpError op prefixes for the transport operations
            "dial tcp",
            "read tcp",
            "write tcp",
            // Transport-level causes
            "connection reset by peer",
            "connection refused",
            "connection aborted",
            "broken pipe",
            "network is unreachable",
            "network is down",
            "host is unreachable",
            "no route to host",
            "read: connection timed out",
            "i/o timeout",
            "unexpected eof",
            "\": eof",
            // TLS transport failures. Only wire-level failures are listed. An error saying the peer rejected our
            // certificate, or that we do not trust theirs, is a configuration problem a retry cannot fix, so
            // "x509:", "bad certificate" and "handshake failure" are deliberately absent.
            "tls: use of closed connection",
            "bad record mac",
            "tls: internal error",
            // HTTP/2 transport failures, matched on the package prefix for the same reason "net/http" is. A stream
            // error carries no such prefix, so it is matched separately.
            "http2:",
            "stream error: stream id",
            // Transport failures that containerd surfaces while copying an image layer, having discarded the
            // net.OpError that produced them
            "file already closed",
            // Timeouts and cancellations, which all mean the request did not complete rather than that the registry
            // rejected it
            "net/http",
            "timeout",
            "request canceled",
            "context canceled",
            "context deadline exceeded",
            // Name resolution failures
            "no such host",
            "temporary failure in name resolution",
            "server misbehaving"));

    /**
     * Errors that a retry cannot recover from, so a docker pull failing with one of these should fail fast.
     */
    private static final List<String> NON_RETRYABLE_ERRORS = Collections.unmodifiableList(Arrays.asList(
            "manifest unknown",
            "no matching manifest for",
            "invalid reference format",
            "no space left on device",
            "authentication required",
            "requested access to the resource is denied"));

    /**
     * Sanity check for installation.
     *
     * @return if docker is installed on the host
     */
    public boolean dockerInstalled() {
        CliResponse response = runDockerCmd("docker -v");
        return response.exit.isPresent() && response.exit.get() == 0;
    }

    /**
     * Login to given docker registry.
     *
     * @param registry Registry to log into, with credentials encapsulated
     * @throws DockerLoginException                error in authenticating with the registry
     * @throws UserNotAuthorizedForDockerException when current user is not authorized to use docker
     * @throws DockerServiceUnavailableException   an error that can be potentially fixed through retries
     */
    public void login(Registry registry)
            throws DockerLoginException, UserNotAuthorizedForDockerException, DockerServiceUnavailableException {
        Map<String, String> credEnvMap = new HashMap<>();
        credEnvMap.put("dockerUsername", registry.getCredentials().getUsername());
        credEnvMap.put("dockerPassword", registry.getCredentials().getPassword());

        Platform platform = Platform.getInstance();
        String loginCommand = String.format("docker login %s -u %s -p %s", registry.getEndpoint(),
                platform.formatEnvironmentVariableCmd("dockerUsername"),
                platform.formatEnvironmentVariableCmd("dockerPassword"));
        CliResponse response = runDockerCmd(loginCommand, credEnvMap);

        Optional<UserNotAuthorizedForDockerException> userAuthorizationError = checkUserAuthorizationError(response);
        if (userAuthorizationError.isPresent()) {
            throw userAuthorizationError.get();
        }

        if (response.exit.isPresent()) {
            if (response.exit.get() == 0) {
                return;
            } else {
                if (response.getOut().contains("Service Unavailable")) {
                    // This error can be thrown when disconnected/issue with docker cloud service, or when the docker
                    // engine has issues or proxy config is bad etc. Not entirely reliable to determine retry behavior
                    throw new DockerServiceUnavailableException(
                            String.format("Error logging into the registry using credentials - %s", response.err));
                }
                throw new DockerLoginException(
                        String.format("Error logging into the registry using credentials - %s", response.err));
            }
        } else {
            throw new DockerLoginException("Unexpected error while trying to perform docker login",
                    response.failureCause);
        }
    }

    /**
     * Pull given docker image.
     *
     * @param image Image to download
     * @throws DockerServiceUnavailableException   an error that can be potentially fixed through retries
     * @throws InvalidImageOrAccessDeniedException an error indicating incorrect image specification or auth issues with
     *                                             the registry
     * @throws UserNotAuthorizedForDockerException when current user is not authorized to use docker
     * @throws ConnectionException                 network error
     * @throws DockerPullException                 an error that a retry cannot recover from, or, as
     *                                             {@link UnknownDockerPullException}, an unrecognized error that may
     *                                             be transient
     */
    public void pullImage(Image image) throws DockerServiceUnavailableException, InvalidImageOrAccessDeniedException,
            UserNotAuthorizedForDockerException, DockerPullException, ConnectionException {
        CliResponse response = runDockerCmd(String.format("docker pull %s", image.getImageFullName()));

        Optional<UserNotAuthorizedForDockerException> userAuthorizationError = checkUserAuthorizationError(response);
        if (userAuthorizationError.isPresent()) {
            throw userAuthorizationError.get();
        }

        if (response.exit.isPresent()) {
            if (response.exit.get() == 0) {
                return;
            } else {
                if (response.getOut().contains("Service Unavailable")) {
                    // This error can be thrown when disconnected/issue with docker cloud service, or when the docker
                    // engine has issues or proxy config is bad etc. Not entirely reliable to determine retry behavior
                    throw new DockerServiceUnavailableException(
                            String.format("Error pulling docker image - %s", response.err));
                }
                if (response.getOut().contains("repository does not exist or may require 'docker login'")) {
                    throw new InvalidImageOrAccessDeniedException(
                            String.format("Invalid image or login - %s", response.err));
                }
                if (isConnectionError(response.err)) {
                    throw new ConnectionException(String.format("Network issue when docker pull - %s", response.err));
                }
                if (isNonRetryableError(response.err)) {
                    throw new DockerPullException(
                            String.format("Unexpected error while trying to perform docker pull - %s", response.err),
                            response.failureCause);
                }
                // The error is not recognized as either a network error or a non-retryable one. Assume it may be
                // transient and let the caller apply a small, bounded number of retries rather than failing the
                // deployment on the first attempt.
                throw new UnknownDockerPullException(
                        String.format("Unexpected error while trying to perform docker pull - %s", response.err),
                        response.failureCause);
            }
        } else {
            throw new DockerPullException("Unexpected error while trying to perform docker pull",
                    response.failureCause);
        }
    }

    /**
     * Check if a docker CLI error indicates a network-level failure, which is recoverable once connectivity
     * is restored and so is retried indefinitely.
     *
     * @param err stderr emitted by the docker CLI
     * @return true if the error is a known network error
     */
    static boolean isConnectionError(String err) {
        return containsAny(err, CONNECTION_ERRORS);
    }

    /**
     * Check if a docker CLI error is one that a retry cannot recover from, such as a missing image or a full disk.
     *
     * @param err stderr emitted by the docker CLI
     * @return true if the error is known to be non-retryable
     */
    static boolean isNonRetryableError(String err) {
        return containsAny(err, NON_RETRYABLE_ERRORS);
    }

    private static boolean containsAny(String err, List<String> lowerCaseNeedles) {
        String lowerCaseErr = err.toLowerCase(Locale.ROOT);
        return lowerCaseNeedles.stream().anyMatch(lowerCaseErr::contains);
    }

    /**
     * Check if an image exists locally.
     *
     * @param image image to check locally
     * @throws DockerServiceUnavailableException   an error that can be potentially fixed through retries
     * @throws UserNotAuthorizedForDockerException when current user is not authorized to use docker
     * @throws DockerImageQueryException unexpected error
     */
    public boolean imageExistsLocally(Image image) throws DockerServiceUnavailableException,
            UserNotAuthorizedForDockerException, DockerImageQueryException {

        CliResponse response = runDockerCmd(String.format("docker images -q %s", image.getImageFullName()));

        Optional<UserNotAuthorizedForDockerException> userAuthorizationError = checkUserAuthorizationError(response);
        if (userAuthorizationError.isPresent()) {
            throw userAuthorizationError.get();
        }

        if (response.exit.isPresent() && response.exit.get() == 0) {
            return StringUtils.isNotBlank(response.getOut());
        } else {
            throw new DockerImageQueryException(
                        String.format("Unexpected error while trying to perform docker images -q %s", response.err),
                        response.failureCause);
        }
    }

    private CliResponse runDockerCmd(String cmd) {
        return runDockerCmd(cmd, Collections.emptyMap());
    }

    private CliResponse runDockerCmd(String cmd, Map<String, String> envs) {
        Throwable cause = null;
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        Optional<Integer> exit = Optional.empty();
        try (Exec exec = Platform.getInstance().createNewProcessRunner()) {
            exec.withExec(cmd.split(" ")).withShell().withOut(output::append).withErr(error::append);
            for (Map.Entry<String, String> env : envs.entrySet()) {
                exec.setenv(env.getKey(), env.getValue());
            }
            exit = exec.exec();
        } catch (InterruptedException e) {
            Arrays.stream(e.getSuppressed()).forEach((t) -> {
                logger.atError().setCause(e).log("interrupted");
            });
            cause = e;
        } catch (IOException e) {
            cause = e;
        }
        return new CliResponse(exit, output.toString(), error.toString(), cause);
    }

    private Optional<UserNotAuthorizedForDockerException> checkUserAuthorizationError(CliResponse response) {
        UserNotAuthorizedForDockerException error = null;
        if (response.exit.isPresent() && response.exit.get() != 0 && response.err
                .contains("Got permission denied while trying to connect to the Docker daemon socket")) {
            error = new UserNotAuthorizedForDockerException("User not authorized to use docker, if you're "
                    + "not running greengrass as root, please add the user you're running with to docker group "
                    + "and redo the deployment");
        }
        return Optional.ofNullable(error);
    }

    /**
     * Use docker command to delete the docker image.
     *
     * @param image docker image to delete
     * @throws DockerImageDeleteException if error is encountered
     */
    public void deleteImage(Image image) throws DockerImageDeleteException {
        CliResponse response = runDockerCmd(String.format("docker rmi %s", image.getImageFullName()));
        if (response.exit.isPresent() && response.exit.get() == 0) {
            return;
        } else {
            throw new DockerImageDeleteException(
                    String.format("Unexpected error while trying to perform docker rmi - %s", response.err),
                    response.failureCause);
        }
    }

    @Getter
    @AllArgsConstructor
    private static class CliResponse {
        Optional<Integer> exit;
        String out;
        String err;
        Throwable failureCause;
    }
}
