/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.authorization.AuthorizationHandler.ResourceLookupPolicy;
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
 * - isMQTTWildcard: If current Node is a valid MQTT wildcard (#, +)
 * - matchAll: if current node should match everything. Could be MQTTWildcard or a wildcard and will always be a
 *   terminal Node.
 */
public class WildcardTrie {
    protected static final String GLOB_WILDCARD = "*";
    protected static final String MQTT_MULTILEVEL_WILDCARD = "#";
    protected static final String MQTT_SINGLELEVEL_WILDCARD = "+";
    protected static final String MQTT_LEVEL_SEPARATOR = "/";
    protected static final char wildcardChar = GLOB_WILDCARD.charAt(0);
    protected static final char multiLevelWildcardChar = MQTT_MULTILEVEL_WILDCARD.charAt(0);
    protected static final char singleLevelWildcardChar = MQTT_SINGLELEVEL_WILDCARD.charAt(0);
    protected static final char levelSeparatorChar = MQTT_LEVEL_SEPARATOR.charAt(0);

    private boolean isTerminal;
    private boolean isTerminalLevel;
    private boolean isWildcard;
    private boolean isMQTTWildcard;
    private boolean matchAll;
    private final Map<String, WildcardTrie> children =
            new DefaultConcurrentHashMap<>(WildcardTrie::new);

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
        if (subject.equals(GLOB_WILDCARD)) {
            WildcardTrie initial = this.children.get(GLOB_WILDCARD);
            initial.matchAll = true;
            initial.isTerminal = true;
            initial.isWildcard = true;
            return;
        }
        if (subject.equals(MQTT_MULTILEVEL_WILDCARD)) {
            WildcardTrie initial = this.children.get(MQTT_MULTILEVEL_WILDCARD);
            initial.matchAll = true;
            initial.isTerminal = true;
            initial.isMQTTWildcard = true;
            return;
        }
        if (subject.equals(MQTT_SINGLELEVEL_WILDCARD)) {
            WildcardTrie initial = this.children.get(MQTT_SINGLELEVEL_WILDCARD);
            initial.isTerminal = true;
            initial.isMQTTWildcard = true;
            return;
        }
        if (subject.startsWith(MQTT_SINGLELEVEL_WILDCARD + MQTT_LEVEL_SEPARATOR)) {
            WildcardTrie initial = this.children.get(MQTT_SINGLELEVEL_WILDCARD);
            initial.isMQTTWildcard = true;
            initial.add(subject.substring(1), true);
        }

        add(subject, true);
    }

    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
    private WildcardTrie add(String subject, boolean isTerminal) {
        if (subject.isEmpty()) {
            this.isTerminal |= isTerminal;
            return this;
        }
        int subjectLength = subject.length();
        WildcardTrie current = this;
        StringBuilder sb = new StringBuilder(subjectLength);
        for (int i = 0; i < subjectLength; i++) {
            char currentChar = subject.charAt(i);
            // Create separate Nodes for wildcards *, # and +
            // Also tag them wildcard if its a valid usage
            if (currentChar == wildcardChar) {
                current = current.add(sb.toString(), false);
                current = current.children.get(GLOB_WILDCARD);
                current.isWildcard = true;
                // If the string ends with *, then the wildcard is a terminal
                if (i == subjectLength - 1) {
                    current.isTerminal = isTerminal;
                    return current;
                }
                return current.add(subject.substring(i + 1), true);
            }
            if (currentChar == multiLevelWildcardChar) {
                WildcardTrie terminalLevel = current.add(sb.toString(), false);
                current = terminalLevel.children.get(MQTT_MULTILEVEL_WILDCARD);
                if (i == subjectLength - 1) {
                    current.isTerminal = true;
                    // check if # wildcard usage is valid
                    if (i > 0 && subject.charAt(i - 1) == levelSeparatorChar) {
                        current.isMQTTWildcard = true;
                        current.matchAll = true;
                        terminalLevel.isTerminalLevel = true;
                    }
                    return current;
                }
                return current.add(subject.substring(i + 1), true);
            }
            if (currentChar == singleLevelWildcardChar) {
                current = current.add(sb.toString(), false);
                current = current.children.get(MQTT_SINGLELEVEL_WILDCARD);
                if (i == subjectLength - 1) {
                    current.isTerminal = true;
                    // check if '+' wildcard usage is valid
                    // if it's used at the last level
                    if (i > 0 && subject.charAt(i - 1) == levelSeparatorChar) {
                        current.isMQTTWildcard = true;
                    }
                    return current;
                }
                // check if '+' wildcard usage is valid
                // if it's used in middle levels
                if (i > 0 && subject.charAt(i - 1) == levelSeparatorChar
                        && subject.charAt(i + 1) == levelSeparatorChar) {
                    current.isMQTTWildcard = true;
                }
                return current.add(subject.substring(i + 1), true);
            }
            sb.append(currentChar);
        }
        // Handle non-wildcard value
        current = current.children.get(sb.toString());
        current.isTerminal |= isTerminal;
        return current;
    }

    /**
     * Match given string to the corresponding allowed resources trie. MQTT wildcards are not processed.
     *
     * @param str string to match.
     */
    @SuppressWarnings({"PMD.UselessParentheses", "PMD.CollapsibleIfStatements"})
    public boolean matchesStandard(String str) {
        if (str == null) {
            return true;
        }
        if ((isWildcard && isTerminal) || (isTerminal && str.isEmpty())) {
            return true;
        }

        boolean hasMatch = false;
        Map<String, WildcardTrie> matchingChildren = new HashMap<>();
        for (Map.Entry<String, WildcardTrie> e : children.entrySet()) {
            // Succeed fast
            if (hasMatch) {
                return true;
            }
            String key = e.getKey();
            WildcardTrie value = e.getValue();

            // Process * wildcards
            if (key.equals(GLOB_WILDCARD)) {
                hasMatch = value.matchesStandard(str);
                continue;
            }

            // Match normal characters
            if (str.startsWith(key)) {
                hasMatch = value.matchesStandard(str.substring(key.length()));
                // Succeed fast
                if (hasMatch) {
                    return true;
                }
            }

            // If I'm a wildcard, then I need to maybe chomp many characters to match my children
            if (isWildcard) {
                int foundChildIndex = str.indexOf(key);
                int keyLength = key.length();
                while (foundChildIndex >= 0) {
                    matchingChildren.put(str.substring(foundChildIndex + keyLength), value);
                    foundChildIndex = str.indexOf(key, foundChildIndex + 1);
                }
            }
        }
        // Succeed fast
        if (hasMatch) {
            return true;
        }
        if (isWildcard && !matchingChildren.isEmpty()) {
            return matchingChildren.entrySet().stream().anyMatch((e) -> e.getValue().matchesStandard(e.getKey()));
        }

        return false;
    }

    /**
     * Match given string to the corresponding allowed resources trie. MQTT wildcards are processed only if
     * its a valid usage, otherwise treated as normal characters.
     *
     * @param str string to match
     */
    @SuppressWarnings({"PMD.UselessParentheses", "PMD.CollapsibleIfStatements"})
    public boolean matchesMQTT(String str) {
        if (str == null) {
            return true;
        }
        if ((isWildcard && isTerminal) || (isTerminal && str.isEmpty())) {
            return true;
        }
        if (isMQTTWildcard) {
            if (matchAll || (isTerminal && (str.indexOf(MQTT_LEVEL_SEPARATOR) == -1))) {
                return true;
            }
        }

        boolean hasMatch = false;
        Map<String, WildcardTrie> matchingChildren = new HashMap<>();
        for (Map.Entry<String, WildcardTrie> e : children.entrySet()) {
            // Succeed fast
            if (hasMatch) {
                return true;
            }
            String key = e.getKey();
            WildcardTrie value = e.getValue();

            // Process *, # and + wildcards (only process MQTT wildcards that have valid usages)
            if (key.equals(GLOB_WILDCARD) || value.isMQTTWildcard && (key.equals(MQTT_SINGLELEVEL_WILDCARD)
                    || key.equals(MQTT_MULTILEVEL_WILDCARD))) {
                hasMatch = value.matchesMQTT(str);
                continue;
            }

            // Match normal characters
            if (str.startsWith(key)) {
                hasMatch = value.matchesMQTT(str.substring(key.length()));
                // Succeed fast
                if (hasMatch) {
                    return true;
                }
            }

            // Check if it's terminalLevel to allow matching of string without "/" in the end
            //      "abc/#" should match "abc".
            //      "abc/*xy/#" should match "abc/12xy"
            String terminalKey = key.substring(0, key.length() - 1);
            if (value.isTerminalLevel) {
                if (str.equals(terminalKey)) {
                    return true;
                }
                if (str.endsWith(terminalKey)) {
                    key = terminalKey;
                }
            }

            int keyLength = key.length();
            // If I'm a wildcard, then I need to maybe chomp many characters to match my children
            if (isWildcard) {
                int foundChildIndex = str.indexOf(key);
                while (foundChildIndex >= 0 && foundChildIndex < str.length()) {
                    matchingChildren.put(str.substring(foundChildIndex + keyLength), value);
                    foundChildIndex = str.indexOf(key, foundChildIndex + 1);
                }
            }
            // If I'm a MQTT wildcard (specifically + as # is already covered),
            // then I need to maybe chomp many characters to match my children
            if (isMQTTWildcard) {
                int foundChildIndex = str.indexOf(key);
                // Matched characters inside + should not contain a "/"
                while (foundChildIndex >= 0
                        && foundChildIndex < str.length()
                        && (str.substring(0,foundChildIndex).indexOf(MQTT_LEVEL_SEPARATOR) == -1)) {
                    matchingChildren.put(str.substring(foundChildIndex + keyLength), value);
                    foundChildIndex = str.indexOf(key, foundChildIndex + 1);
                }
            }
        }
        // Succeed fast
        if (hasMatch) {
            return true;
        }
        if ((isWildcard || isMQTTWildcard) && !matchingChildren.isEmpty()) {
            return matchingChildren.entrySet().stream().anyMatch((e) -> e.getValue().matchesMQTT(e.getKey()));
        }

        return false;
    }

    public boolean matches(String str, ResourceLookupPolicy lookupPolicy) {
        return lookupPolicy == ResourceLookupPolicy.MQTT_STYLE ? matchesMQTT(str)
                : matchesStandard(str);
    }
}
