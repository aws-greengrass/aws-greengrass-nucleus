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
    protected static final String WILDCARD = "*";
    private boolean isTerminal;
    private boolean isWildcard;
    private boolean isVariable;
    private boolean isNull;
    private final Map<String, WildcardVariableTrie> children = new DefaultConcurrentHashMap<>(WildcardVariableTrie::new);

    public WildcardVariableTrie() {
    }

    public void add(String subject) {
        if (subject == null) {
            isNull = true;
            return;
        }
        add(subject, true);
    }

    private WildcardVariableTrie add(String subject, boolean isTerminal) {
        if (subject.isEmpty()) {
            return this;
        }
        WildcardVariableTrie current = this;
        StringBuilder sb = new StringBuilder(subject.length());
        for (int i = 0; i < subject.length(); i++) {
            // Maybe create node for a wildcard
            if (subject.charAt(i) == '*') {
                current = current.add(sb.toString(), false);
                current = current.children.get(WILDCARD);
                current.isWildcard = true;
                // If the string ends with *, then the wildcard is a terminal
                if (i == subject.length() - 1) {
                    current.isTerminal = isTerminal;
                    return current;
                }
                return current.add(subject.substring(i + 1), true);
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

    private String toString(int level) {
        StringBuilder sb = new StringBuilder();
        children.forEach((k, v) -> {
            sb.append("\n");
            for (int i = 0; i < level; i++) {
                sb.append("  ");
            }
            sb.append('"');
            sb.append(k);
            sb.append("\" ");
            if (v.isTerminal) {
                sb.append("|term");
            }
            if (v.isVariable) {
                sb.append("|var");
            }
            if (v.isWildcard) {
                sb.append("|wild");
            }
            sb.append(v.toString(level + 1));
        });
        return sb.toString();
    }

    @Override
    public String toString() {
        return toString(0);
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

    public boolean matches(String str, Map<String, String> variables) {
        if (isNull && str == null) {
            return true;
        }
        if ((isWildcard && isTerminal) || (isTerminal && str.isEmpty())) {
            return true;
        }

        boolean hasMatch = false;
        Map<String, WildcardVariableTrie> matchingChildren = new HashMap<>();
        for (Map.Entry<String, WildcardVariableTrie> e : children.entrySet()) {
            // Succeed fast
            if (hasMatch) {
                return true;
            }
            if (e.getKey().equals(WILDCARD)) {
                hasMatch = e.getValue().matches(str, variables);
                continue;
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
            if (isWildcard) {
                int foundChildIndex = str.indexOf(key);
                if (foundChildIndex >= 0) {
                    if (e.getValue().isTerminal && str.endsWith(key)) {
                        return true;
                    }
                    matchingChildren.put(str.substring(foundChildIndex), e.getValue());
                }
            }
        }
        // Succeed fast
        if (hasMatch) {
            return true;
        }
        if (isWildcard && !matchingChildren.isEmpty()) {
            return matchingChildren.entrySet().stream().anyMatch((e) -> e.getValue().matches(e.getKey(), variables));
        }

        return false;
    }
}
