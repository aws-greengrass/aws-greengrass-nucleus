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
import java.util.Map;
import java.util.Optional;

/**
 * Docker CLI wrapper that communicates with Docker Engine to execute user commands.
 */
@NoArgsConstructor
public class DefaultDockerClient {
    public static final Logger logger = LogManager.getLogger(DefaultDockerClient.class);

    /**
     * connect error messages.
     */
    private static final String READ_CONNECTION_TIME_OUT = "read: connection timed out";
    private static final String NET_HTTP_TIMEOUT = "net/http";
    private static final String TEMPORARY_FAILURE_IN_NAME_RESOLUTION = "Temporary failure in name resolution";
    private static final String REQUEST_CANCELED = "request canceled";
    private static final String DOCKER_PULL_TIMEOUT = "timeout";
    private static final String NO_SUCH_HOST = "no such host";

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
     * @throws DockerPullException                 unexpected error
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
                if (response.err.contains(READ_CONNECTION_TIME_OUT)
                        || response.err.contains(TEMPORARY_FAILURE_IN_NAME_RESOLUTION)
                        || response.err.toLowerCase().contains(NET_HTTP_TIMEOUT)
                        || response.err.toLowerCase().contains(REQUEST_CANCELED)
                        || response.err.toLowerCase().contains(DOCKER_PULL_TIMEOUT)
                        || response.err.toLowerCase().contains(NO_SUCH_HOST)) {
                    throw new ConnectionException(String.format("Network issue when docker pull - %s", response.err));
                }
                throw new DockerPullException(
                        String.format("Unexpected error while trying to perform docker pull - %s", response.err),
                        response.failureCause);
            }
        } else {
            throw new DockerPullException("Unexpected error while trying to perform docker pull",
                    response.failureCause);
        }
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
