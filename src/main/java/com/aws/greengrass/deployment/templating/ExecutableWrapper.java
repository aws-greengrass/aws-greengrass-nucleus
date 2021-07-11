/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import lombok.NonNull;
import org.apache.commons.io.IOUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class ExecutableWrapper {
    private final Path executable;
    private final String paramFile;
    private BufferedReader stderr;
    private BufferedReader stdout;
    private int exitVal;

    public ExecutableWrapper(@NonNull Path executable, @NonNull String paramFile) {
        this.executable = executable;
        this.paramFile = paramFile;
    }

    /**
     * Does transformation.
     * @return the component recipe as a string.
     * @throws TemplateExecutionException if something goes wrong.
     */
    public String transform() throws TemplateExecutionException {
        String command = "java -jar" + executable;
        try {
            Process recipeTransformer = Runtime.getRuntime().exec(command);
            stderr = new BufferedReader(new InputStreamReader(recipeTransformer.getErrorStream()));
            stdout = new BufferedReader(new InputStreamReader(recipeTransformer.getInputStream()));

            // write to stdin
            recipeTransformer.getOutputStream().write(paramFile.getBytes(StandardCharsets.UTF_8));
            recipeTransformer.getOutputStream().flush();

            // wait for execution
            recipeTransformer.waitFor();
            exitVal = recipeTransformer.exitValue();
            if (exitVal != 0) {
                throw new IOException("Failed to execute command \"command\", " + getExecutionLog());
            }

            // read from stdout
            if (stderr.lines().count() != 0) {
                String errorString = IOUtils.toString(stderr);
                throw new TemplateExecutionException("Inner process " + executable + " wrote to stderr:\n"
                        + errorString);
            }

            return IOUtils.toString(stdout);

        } catch (final IOException | InterruptedException e) {
            throw new TemplateExecutionException(e);
        }
    }

    @SuppressWarnings("PMD.AssignmentInOperand")
    private String getExecutionLog() {
        StringBuilder error = new StringBuilder();
        String line;
        try {
            while ((line = stderr.readLine()) != null) {
                error.append('\n').append(line);
            }
        } catch (final IOException e) {
            e.printStackTrace();
        }
        StringBuilder output = new StringBuilder();
        try {
            while ((line = stdout.readLine()) != null) {
                output.append('\n').append(line);
            }
        } catch (final IOException e) {
            e.printStackTrace();
        }
        try {
            stderr.close();
            stdout.close();
        } catch (final IOException e) {
            e.printStackTrace();
        }
        return "exitVal: " + exitVal + ", error: " + error + ", output: " + output;
    }
}
