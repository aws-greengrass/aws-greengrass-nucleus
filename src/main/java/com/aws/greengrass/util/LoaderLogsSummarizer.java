/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import java.util.Scanner;

public final class LoaderLogsSummarizer {
    public static final String STARTING_SUBSEQUENCE_REGEX =
            "^Nucleus exited ([0-9])*\\.\\s*(Attempt 2 out of 3|Retrying 2 times)$";
    public static final String ENDING_SUBSEQUENCE_REGEX =
            "^Nucleus exited ([0-9])*\\.\\s*(Attempt 3 out of 3|Retrying 3 times)$";

    private LoaderLogsSummarizer() {
    }

    /**
     * Summarizes loader logs that can be published as part of the deployment status FSS message when deployment fails
     * with NRF.
     *
     * @param blob string blob containing loader logs
     * @return string containing summarized logs
     */
    public static String summarizeLogs(String blob) {
        try (Scanner scanner = new Scanner(blob)) {
            StringBuilder parsedLogsStringBuilder = new StringBuilder();

            // Skip until the last restart failure
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                // process the line
                if (line.matches(STARTING_SUBSEQUENCE_REGEX)) {
                    break;
                }
            }

            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();

                if (line.matches(ENDING_SUBSEQUENCE_REGEX)) {
                    parsedLogsStringBuilder.append(line);
                    break;
                }

                if (line.startsWith("+")) {
                    continue;
                }

                parsedLogsStringBuilder.append(line).append(System.lineSeparator());
            }

            scanner.close();
            return parsedLogsStringBuilder.toString();
        }
    }
}
