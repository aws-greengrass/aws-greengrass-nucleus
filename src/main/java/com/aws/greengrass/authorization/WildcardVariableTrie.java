/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.util.DefaultConcurrentHashMap;

import java.util.HashMap;
import java.util.Map;

public class WildcardVariableTrie {
    protected static final String GLOB_WILDCARD = "*";
    protected static final String MQTT_WILDCARD = "#";
    private boolean isTerminal;
    private boolean isGlobWildcard;
    private boolean isNull;
    private boolean matchAll;
    private final Map<String, WildcardVariableTrie> children =
            new DefaultConcurrentHashMap<>(WildcardVariableTrie::new);

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
        // '*' alone allows all resources including multiple levels
        if (subject.equals(GLOB_WILDCARD)) {
            add(subject, true, true);
        }
        subject = subject.replaceAll("/\\+/", "/*/");
        add(subject, true, false);
    }

    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
    private WildcardVariableTrie add(String subject, boolean isTerminal, boolean matchAll) {
        if (subject.isEmpty()) {
            return this;
        }
        // '*' alone allows all resources including multiple levels
        if (matchAll) {
            this.matchAll = true;
            return this;
        }
        WildcardVariableTrie current = this;
        StringBuilder sb = new StringBuilder(subject.length());
        for (int i = 0; i < subject.length(); i++) {
            // Create node for a wildcard
            if (subject.charAt(i) == '*') {
                current = current.add(sb.toString(), false, false);
                current = current.children.get(GLOB_WILDCARD);
                current.isGlobWildcard = true;
                // If the string ends with *, then the wildcard is a terminal
                if (i == subject.length() - 1) {
                    current.isTerminal = isTerminal;
                    return current;
                }
                return current.add(subject.substring(i + 1), true, false);
            }
            // Create a node for MQTT wildcard, should be terminal and preceded with '/'
            if (i > 0 && subject.charAt(i) == '#' && subject.charAt(i - 1) == '/' && i == (subject.length() - 1)) {
                current = current.add(sb.toString(), false, false);
                current = current.children.get(MQTT_WILDCARD);
                current.isTerminal = true;
                return current;
            }
            sb.append(subject.charAt(i));
        }
        // Handle non-wildcard value
        current = current.children.get(sb.toString());
        current.isTerminal |= isTerminal;
        return current;
    }

    /**
     * Match given string to the allowed resources sub-trie
     *
     * @param str string to match
     */
    public boolean matches(String str) {
        if (isNull && str == null) {
            return true;
        }
        if (matchAll || isTerminal && str.isEmpty()) {
            return true;
        }
        if (isGlobWildcard && isTerminal && !str.contains("/")) {
            return true;
        }

        boolean hasMatch = false;
        Map<String, WildcardVariableTrie> matchingChildren = new HashMap<>();
        for (Map.Entry<String, WildcardVariableTrie> e : children.entrySet()) {
            // Succeed fast
            if (hasMatch) {
                return true;
            }
            if (e.getKey().equals(GLOB_WILDCARD)) {
                hasMatch = e.getValue().matches(str);
                continue;
            }
            if (e.getKey().equals(MQTT_WILDCARD)) {
                // Succeed fast, we don't care after this
                return true;
            }
            String key = e.getKey();
            if (str.startsWith(key)) {
                hasMatch = e.getValue().matches(str.substring(key.length()));
                // Succeed fast
                if (hasMatch) {
                    return true;
                }
            }
            // If I'm a wildcard, then I need to maybe chomp many characters to match my children
            if (isGlobWildcard) {
                int foundChildIndex = str.indexOf(key);
                // Matched characters inside * should not contain a "/"
                if (foundChildIndex >= 0 && !str.substring(0,foundChildIndex).contains("/")) {
                    matchingChildren.put(str.substring(foundChildIndex + key.length()), e.getValue());
                }
            }
        }
        // Succeed fast
        if (hasMatch) {
            return true;
        }
        if (isGlobWildcard && !matchingChildren.isEmpty()) {
            return matchingChildren.entrySet().stream().anyMatch((e) -> e.getValue().matches(e.getKey()));
        }

        return false;
    }
}
