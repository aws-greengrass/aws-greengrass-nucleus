/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.managers;

import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public class CmdParser {

    private static final String PATTERN = "[^\\s\"']+|\"([^\"]*)\"|'([^']*)'";

    /**
     * Command parsing.
     *
     * @param cmdLine command as string
     * @return list of params
     */
    public static String[] parse(String cmdLine) {
        List<String> result = new ArrayList<>();
        Pattern regex = Pattern.compile(PATTERN);
        Matcher regexMatcher = regex.matcher(cmdLine);
        while (regexMatcher.find()) {
            if (regexMatcher.group(1) == null) {
                if (regexMatcher.group(2) == null) {
                    result.add(regexMatcher.group());
                } else {
                    result.add(regexMatcher.group(2));
                }
            } else {
                result.add(regexMatcher.group(1));
            }
        }
        return result.toArray(new String[0]);
    }
}
