/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.util.DefaultConcurrentHashMap;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

public class WildcardVariableTrie {
    protected static final String GLOB_WILDCARD = "*";
    protected static final String MQTT_WILDCARD = "#";
    private boolean isTerminal;
    private boolean isGlobWildcard;
    private boolean isVariable;
    private boolean isNull;
    private boolean matchAll;
    private final Map<String, WildcardVariableTrie> children =
            new DefaultConcurrentHashMap<>(WildcardVariableTrie::new);

    public WildcardVariableTrie() {
    }

    /**
     * Add allowed resources for a particular operation.
     *
     * @param subject resource pattern
     */
    public void add(String subject) {
        if (subject == null) {
            isNull = true;
            return;
        }
        subject = subject.replaceAll("/\\+/", "/*/");
        add(subject, true);
    }

    private WildcardVariableTrie add(String subject, boolean isTerminal) {
        if (subject.isEmpty()) {
            return this;
        }
        // '*' alone allows all resources including multiple levels
        if (subject.equals(GLOB_WILDCARD)) {
            this.matchAll = true;
            return this;
        }
        WildcardVariableTrie current = this;
        StringBuilder sb = new StringBuilder(subject.length());
        for (int i = 0; i < subject.length(); i++) {
            // Maybe create node for a wildcard
            if (subject.charAt(i) == '*') {
                current = current.add(sb.toString(), false);
                current = current.children.get(GLOB_WILDCARD);
                current.isGlobWildcard = true;
                // If the string ends with *, then the wildcard is a terminal
                if (i == subject.length() - 1) {
                    current.isTerminal = isTerminal;
                    return current;
                }
                return current.add(subject.substring(i + 1), true);
            }
            // Create a node for MQTT wildcard, should be terminal and proceeded with '/'
            if (i > 0 && subject.charAt(i) == '#' && subject.charAt(i - 1) == '/' && i == (subject.length() - 1)) {
                current = current.add(sb.toString(), false);
                current = current.children.get(MQTT_WILDCARD);
                current.isTerminal = isTerminal;
                return current;
            }
            // Maybe create node for a variable
            if (subject.charAt(i) == '$') {
                String variableValue = getVariable(subject.substring(i));
                if (variableValue != null) {
                    current = current.add(sb.toString(), false);
                    current = current.children.get(variableValue);
                    current.isVariable = true;
                    if (subject.endsWith(variableValue)) {
                        current.isTerminal = isTerminal;
                        return current;
                    }
                    return current.add(subject.substring(i + variableValue.length()), true);
                }
            }
            sb.append(subject.charAt(i));
        }
        // Handle non-wildcard and non-variable value
        current = current.children.get(sb.toString());
        current.isTerminal |= isTerminal;
        return current;
    }

    @Nullable
    private String getVariable(String str) {
        // Minimum possible variable would be ${a}
        if (str.length() < 4) {
            return null;
        }
        // Make sure we match the format ${<variable text>}
        if (str.charAt(0) == '$' && str.charAt(1) == '{') {
            int variableEndIndex = str.indexOf('}');
            if (variableEndIndex > 2) {
                return str.substring(0, variableEndIndex + 1);
            }
        }
        return null;
    }

    /**
     * Get resources for combination of destination, principal and operation.
     * Also returns resources covered by permissions with * operation/principal.
     *
     * @param str string to match
     * @param variables map of vars
     *
     */
    public boolean matches(String str, Map<String, String> variables) {
        if (isNull && str == null) {
            return true;
        }
        if (matchAll || (isTerminal && str.isEmpty())) {
            return true;
        }
        if (isGlobWildcard && isTerminal) {
            if (!str.contains("/")) {
                return true;
            }
        }

        boolean hasMatch = false;
        Map<String, WildcardVariableTrie> matchingChildren = new HashMap<>();
        for (Map.Entry<String, WildcardVariableTrie> e : children.entrySet()) {
            // Succeed fast
            if (hasMatch) {
                return true;
            }
            if (e.getKey().equals(GLOB_WILDCARD)) {
                hasMatch = e.getValue().matches(str, variables);
                continue;
            }
            if (e.getKey().equals(MQTT_WILDCARD)) {
                // Succeed fast, we don't care after this
                return true;
            }
            String key = e.getKey();
            if (e.getValue().isVariable) {
                key = variables.getOrDefault(key, key);
            }
            if (str.startsWith(key)) {
                hasMatch = e.getValue().matches(str.substring(key.length()), variables);
                // Succeed fast
                if (hasMatch) {
                    return true;
                }
            }
            // If I'm a wildcard, then I need to maybe chomp many characters to match my children
            if (isGlobWildcard) {
                int foundChildIndex = str.indexOf(key);
                // If the matched characters inside * had a "/"
                // Continue forward as we don't support / inside *
                if (foundChildIndex >= 0) {
                    if (str.substring(0,foundChildIndex).contains("/")) {
                        continue;
                    }
                    if (e.getValue().isTerminal && str.endsWith(key)) {
                        return true;
                    }
                    matchingChildren.put(str.substring(foundChildIndex + key.length()), e.getValue());
                }
            }
        }
        // Succeed fast
        if (hasMatch) {
            return true;
        }
        if (isGlobWildcard && !matchingChildren.isEmpty()) {
            return matchingChildren.entrySet().stream().anyMatch((e) -> e.getValue().matches(e.getKey(), variables));
        }

        return false;
    }
}
