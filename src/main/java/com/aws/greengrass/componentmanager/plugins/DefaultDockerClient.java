/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.aws.greengrass.componentmanager.plugins.exceptions.DockerLoginException;
import com.aws.greengrass.componentmanager.plugins.exceptions.DockerServiceUnavailableException;
import com.aws.greengrass.componentmanager.plugins.exceptions.InvalidImageOrAccessDeniedException;
import com.aws.greengrass.componentmanager.plugins.exceptions.UserNotAuthorizedForDockerException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Exec;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

/**
 * Docker CLI wrapper that communicates with Docker Engine to execute user commands.
 */
@NoArgsConstructor
public class DefaultDockerClient {
    public static final Logger logger = LogManager.getLogger(DefaultDockerClient.class);

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
     * @return if docker login was successful
     * @throws DockerLoginException                error in authenticating with the registry
     * @throws UserNotAuthorizedForDockerException when current user is not authorized to use docker
     * @throws DockerServiceUnavailableException   an error that can be potentially fixed through retries
     * @throws IOException                         unexpected error
     */
    public boolean login(Registry registry)
            throws DockerLoginException, UserNotAuthorizedForDockerException, DockerServiceUnavailableException,
            IOException {
        // TODO : [Blocker] Ensure credentials in command to be run do not get logged
        CliResponse response = runDockerCmd(String.format("docker login %s -u %s -p %s", registry.getEndpoint(),
                registry.getCredentials().getUsername(), registry.getCredentials().getPassword()));

        Optional userAuthorizationError = checkUserAuthorizationError(response);
        if (userAuthorizationError.isPresent()) {
            throw (UserNotAuthorizedForDockerException) userAuthorizationError.get();
        }

        if (response.exit.isPresent()) {
            if (response.exit.get() == 0) {
                return true;
            } else {
                if (response.getOut().contains("Service Unavailable")) {
                    // This error can be thrown when disconnected/issue with docker cloud service, or when the docker
                    // engine has issues or proxy config is bad etc. Not entirely reliable to determine retry behavior
                    throw new DockerServiceUnavailableException(
                            String.format("Error logging into the registry using credentials - %s", response.out));
                }
                throw new DockerLoginException(
                        String.format("Error logging into the registry using credentials - %s", response.out));
            }
        } else {
            throw new IOException("Unexpected error while trying to perform docker login", response.failureCause);
        }
    }

    /**
     * Pull given docker image.
     *
     * @param image Image to download
     * @return if docker pull was successful
     * @throws DockerServiceUnavailableException   an error that can be potentially fixed through retries
     * @throws InvalidImageOrAccessDeniedException an error indicating incorrect image specification or auth issues with
     *                                             the registry
     * @throws UserNotAuthorizedForDockerException when current user is not authorized to use docker
     * @throws IOException                         unexpected error
     */
    public boolean pullImage(Image image) throws DockerServiceUnavailableException, InvalidImageOrAccessDeniedException,
            UserNotAuthorizedForDockerException, IOException {
        CliResponse response = runDockerCmd(String.format("docker pull %s", image.getImageFullName()));

        Optional userAuthorizationError = checkUserAuthorizationError(response);
        if (userAuthorizationError.isPresent()) {
            throw (UserNotAuthorizedForDockerException) userAuthorizationError.get();
        }

        if (response.exit.isPresent()) {
            if (response.exit.get() == 0) {
                return true;
            } else {
                if (response.getOut().contains("Service Unavailable")) {
                    // This error can be thrown when disconnected/issue with docker cloud service, or when the docker
                    // engine has issues or proxy config is bad etc. Not entirely reliable to determine retry behavior
                    throw new DockerServiceUnavailableException(
                            String.format("Error logging into the registry using credentials - %s", response.out));
                }
                if (response.getOut().contains("repository does not exist or may require 'docker login'")) {
                    throw new InvalidImageOrAccessDeniedException(
                            String.format("Invalid image or login - %s", response.out));
                }
                throw new IOException("Unexpected error while trying to perform docker login", response.failureCause);
            }
        } else {
            throw new IOException("Unexpected error while trying to perform docker login", response.failureCause);
        }
    }

    private CliResponse runDockerCmd(String cmd) {
        Throwable cause = null;
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        Optional<Integer> exit = Optional.empty();
        try (Exec exec = new Exec()) {
            exit = exec.withExec(cmd.split(" ")).withShell().withOut(output::append).withErr(error::append).exec();
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

    @Getter
    @AllArgsConstructor
    private static class CliResponse {
        Optional<Integer> exit;
        String out;
        String err;
        Throwable failureCause;
    }
}
