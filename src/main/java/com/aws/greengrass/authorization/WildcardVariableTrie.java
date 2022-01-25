/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.util.DefaultConcurrentHashMap;

import java.util.HashMap;
import java.util.Map;

/**
 * A Wildcard trie node which contains properties to identify the Node and a map of all it's children.
 * - isTerminal: If the node is a terminal node while adding a resource. It might not necessarily be a leaf node as we
 *   are adding multiple resources having same prefix but terminating on different points.
 * - isTerminalLevel: If the node is the last level before a valid use "#" wildcard (eg: "abc/123/#", 123/ would be the
 *   terminalLevel).
 * - isWildcard: If current Node is a valid glob wildcard (*)
 * - isWildcard: If current Node is a valid MQTT wildcard (#, +)
 * - matchAll: if current node should match everything. Could be MQTTWildcard or a wildcard and will always be a
 *   terminal Node.
 */
public class WildcardVariableTrie {
    protected static final char GLOB_WILDCARD = '*';
    protected static final char MQTT_MULTILEVEL_WILDCARD = '#';
    protected static final char MQTT_SINGLELEVEL_WILDCARD = '+';
    protected static final char MQTT_LEVEL_SEPARATOR = '/';

    private boolean isTerminal;
    private boolean isTerminalLevel;
    private boolean isWildcard;
    private boolean isMQTTWildcard;
    private boolean matchAll;
    private final Map<String, WildcardVariableTrie> children =
            new DefaultConcurrentHashMap<>(WildcardVariableTrie::new);

    /**
     * Add allowed resources for a particular operation.
     * - A new node is created for every occurrence of a wildcard (*, #, +).
     * - Only nodes with valid usage of wildcards are marked with isWildcard or isMQTTWildcard.
     * - Any other characters are grouped together to form a node.
     * - Just a '*' or '#' creates a Node setting matchAll to true and would match all resources
     *
     * @param subject resource pattern
     */
    public void add(String subject) {
        if (subject == null) {
            return;
        }
        if (subject.equals(String.valueOf(GLOB_WILDCARD))) {
            WildcardVariableTrie initial = this.children.get(String.valueOf(GLOB_WILDCARD));
            initial.matchAll = true;
            initial.isTerminal = true;
            initial.isWildcard = true;
            return;
        }
        if (subject.equals(String.valueOf(MQTT_MULTILEVEL_WILDCARD))) {
            WildcardVariableTrie initial = this.children.get(String.valueOf(MQTT_MULTILEVEL_WILDCARD));
            initial.matchAll = true;
            initial.isTerminal = true;
            initial.isMQTTWildcard = true;
            return;
        }
        if (subject.equals(String.valueOf(MQTT_SINGLELEVEL_WILDCARD))) {
            WildcardVariableTrie initial = this.children.get(String.valueOf(MQTT_SINGLELEVEL_WILDCARD));
            initial.isTerminal = true;
            initial.isMQTTWildcard = true;
            return;
        }
        if (subject.startsWith("+/")) {
            WildcardVariableTrie initial = this.children.get(String.valueOf(MQTT_SINGLELEVEL_WILDCARD));
            initial.isMQTTWildcard = true;
            initial.add(subject.substring(1), true);
        }

        add(subject, true);
    }

    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
    private WildcardVariableTrie add(String subject, boolean isTerminal) {
        if (subject.isEmpty()) {
            this.isTerminal |= isTerminal;
            return this;
        }
        WildcardVariableTrie current = this;
        StringBuilder sb = new StringBuilder(subject.length());
        for (int i = 0; i < subject.length(); i++) {

            // Create separate Nodes for wildcards *, # and +
            // Also tag them wildcard if its a valid usage
            if (subject.charAt(i) == GLOB_WILDCARD) {
                current = current.add(sb.toString(), false);
                current = current.children.get(String.valueOf(GLOB_WILDCARD));
                current.isWildcard = true;
                // If the string ends with *, then the wildcard is a terminal
                if (i == subject.length() - 1) {
                    current.isTerminal = isTerminal;
                    return current;
                }
                return current.add(subject.substring(i + 1), true);
            }
            if (subject.charAt(i) == MQTT_MULTILEVEL_WILDCARD) {
                WildcardVariableTrie terminalLevel = current.add(sb.toString(), false);
                current = terminalLevel.children.get(String.valueOf(MQTT_MULTILEVEL_WILDCARD));
                if (i == subject.length() - 1) {
                    current.isTerminal = true;
                    // check if # wildcard usage is valid
                    if (i > 0 && subject.charAt(i - 1) == MQTT_LEVEL_SEPARATOR) {
                        current.isMQTTWildcard = true;
                        current.matchAll = true;
                        terminalLevel.isTerminalLevel = true;
                    }
                    return current;
                }
                return current.add(subject.substring(i + 1), true);
            }
            if (subject.charAt(i) == MQTT_SINGLELEVEL_WILDCARD) {
                current = current.add(sb.toString(), false);
                current = current.children.get(String.valueOf(MQTT_SINGLELEVEL_WILDCARD));
                if (i == subject.length() - 1) {
                    current.isTerminal = true;
                    // check if '+' wildcard usage is valid
                    // if it's used at the last level
                    if (i > 0 && subject.charAt(i - 1) == MQTT_LEVEL_SEPARATOR) {
                        current.isMQTTWildcard = true;
                    }
                    return current;
                }
                // check if '+' wildcard usage is valid
                // if it's used in middle levels
                if (i > 0 && subject.charAt(i - 1) == MQTT_LEVEL_SEPARATOR
                        && subject.charAt(i + 1) == MQTT_LEVEL_SEPARATOR) {
                    current.isMQTTWildcard = true;
                }
                return current.add(subject.substring(i + 1), true);
            }
            sb.append(subject.charAt(i));
        }
        // Handle non-wildcard value
        current = current.children.get(sb.toString());
        current.isTerminal |= isTerminal;
        return current;
    }

    /**
     * Match given string to the corresponding allowed resources trie. MQTT wildcards are processed only if
     * allowMQTT is true.
     *
     * @param str string to match
     * @param allowMQTT If MQTT wildcards are allowed to match
     */
    @SuppressWarnings({"PMD.UselessParentheses", "PMD.CollapsibleIfStatements"})
    public boolean matches(String str, boolean allowMQTT) {
        if (str == null) {
            return true;
        }
        if ((matchAll && isWildcard)
                || (isTerminal && str.isEmpty())
                || (isWildcard && isTerminal && !str.contains(String.valueOf(MQTT_LEVEL_SEPARATOR)))) {
            return true;
        }
        if (allowMQTT && isMQTTWildcard) {
            if (matchAll || (isTerminal && !str.contains(String.valueOf(MQTT_LEVEL_SEPARATOR)))) {
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
            String key = e.getKey();
            WildcardVariableTrie value = e.getValue();

            // Process *, # and + wildcards (only process MQTT wildcards that have valid usages)
            if (key.equals(String.valueOf(GLOB_WILDCARD))
                    || (allowMQTT && value.isMQTTWildcard
                    && (key.equals(String.valueOf(MQTT_SINGLELEVEL_WILDCARD))
                    || key.equals(String.valueOf(MQTT_MULTILEVEL_WILDCARD))))) {
                hasMatch = value.matches(str, allowMQTT);
                continue;
            }

            // Match normal characters
            if (str.startsWith(key)) {
                hasMatch = value.matches(str.substring(key.length()), allowMQTT);
                // Succeed fast
                if (hasMatch) {
                    return true;
                }
            }

            // Check if it's terminalLevel to allow matching of string without "/" in the end
            // eg: Just "abc" should match "abc/#"
            String terminalKey = key.substring(0, key.length() - 1);
            if (allowMQTT && value.isTerminalLevel && str.equals(terminalKey)) {
                return true;
            }

            // If I'm a wildcard, then I need to maybe chomp many characters to match my children
            if (isWildcard) {
                int foundChildIndex = str.indexOf(key);
                int keyLength = key.length();
                if (allowMQTT && value.isTerminalLevel && str.endsWith(terminalKey)) {
                    foundChildIndex = str.indexOf(terminalKey);
                    keyLength = terminalKey.length();
                }
                // Matched characters inside * should not contain a "/"
                if (foundChildIndex >= 0
                        && !str.substring(0,foundChildIndex).contains(String.valueOf(MQTT_LEVEL_SEPARATOR))) {
                    matchingChildren.put(str.substring(foundChildIndex + keyLength), value);
                }
            }
            if (isMQTTWildcard && allowMQTT) {
                int foundChildIndex = str.indexOf(key);
                int keyLength = key.length();
                if (value.isTerminalLevel && str.endsWith(terminalKey)) {
                    foundChildIndex = str.indexOf(terminalKey);
                    keyLength = terminalKey.length();
                }
                // Matched characters inside + should not contain a "/", also next match should have string starting
                // with a "/"
                if (foundChildIndex >= 0
                        && !str.substring(0,foundChildIndex).contains(String.valueOf(MQTT_LEVEL_SEPARATOR))
                        && key.startsWith(String.valueOf(MQTT_LEVEL_SEPARATOR))) {
                    matchingChildren.put(str.substring(foundChildIndex + keyLength), value);
                }
            }
        }
        // Succeed fast
        if (hasMatch) {
            return true;
        }
        if ((isWildcard || isMQTTWildcard) && !matchingChildren.isEmpty()) {
            return matchingChildren.entrySet().stream().anyMatch((e) -> e.getValue().matches(e.getKey(), allowMQTT));
        }

        return false;
    }
}
