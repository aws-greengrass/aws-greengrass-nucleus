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
 * - isWildcard: If current Node is glob wildcard (*)
 */
public class WildcardTrie {
    protected static final String GLOB_WILDCARD = "*";
    protected static final char wildcardChar = GLOB_WILDCARD.charAt(0);

    private boolean isTerminal;
    private boolean isWildcard;
    private final Map<String, WildcardTrie> children =
            new DefaultConcurrentHashMap<>(WildcardTrie::new);

    /**
     * Add allowed resources for a particular operation.
     * - A new node is created for every occurrence of a wildcard *
     * - Any other characters are grouped together to form a node.
     *
     * @param subject resource pattern
     */
    public void add(String subject) {
        if (subject == null) {
            return;
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
            // Create separate Nodes for wildcards * and tag them wildcard
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
            sb.append(currentChar);
        }
        // Handle non-wildcard value
        current = current.children.get(sb.toString());
        current.isTerminal |= isTerminal;
        return current;
    }

    /**
     * Match given string to the corresponding allowed resources trie.
     *
     * @param str string to match.
     */
    @SuppressWarnings({"PMD.UselessParentheses", "PMD.CollapsibleIfStatements"})
    public boolean matches(String str) {
        if (str == null) {
            return true;
        }
        if ((isTerminal && str.isEmpty()) || (isWildcard && isTerminal)) {
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
                hasMatch = value.matches(str);
                continue;
            }

            // Match normal characters
            if (str.startsWith(key)) {
                hasMatch = value.matches(str.substring(key.length()));
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
            return matchingChildren.entrySet().stream().anyMatch((e) -> e.getValue().matches(e.getKey()));
        }

        return false;
    }
}
