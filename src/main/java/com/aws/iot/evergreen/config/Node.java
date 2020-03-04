/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.dependency.Context;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

public abstract class Node {
    public final Context context;
    public final String name;
    public final Topics parent;
    private final String fnc;
    protected CopyOnWriteArraySet<Watcher> watchers;
    private boolean parentNeedsToKnow = true; // parent gets notified of changes to this node

    protected Node(Context c, String n, Topics p) {
        context = c;
        name = n;
        parent = p;
        fnc = calcFnc();
    }

    /**
     * Append node's name to the appendable.
     *
     * @param a appendable to write the name into
     * @return false if name is null, true otherwise
     * @throws IOException if the append fails
     */
    public boolean appendNameTo(Appendable a) throws IOException {
        if (name == null) {
            return false;
        }
        if (parent != null && parent.appendNameTo(a)) {
            a.append('.');
        }
        a.append(name);
        return true;
    }

    private String calcFnc() {
        try {
            StringBuilder sb = new StringBuilder();
            appendNameTo(sb);
            return sb.toString();
        } catch (IOException ex) {
            return ex.toString();
        }
    }

    public String getFullName() {
        return fnc;
    }

    public abstract void appendTo(Appendable a) throws IOException;

    public abstract Object toPOJO();

    public abstract void copyFrom(Node n);

    public <T extends Node> T setParentNeedsToKnow(boolean np) {
        parentNeedsToKnow = np;
        return (T) this;
    }

    @SuppressWarnings({"checkstyle:emptycatchblock"})
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        try {
            appendTo(sb);
        } catch (IOException ignored) {
        }
        return sb.toString();
    }

    abstract void fire(WhatHappened what);

    /* returns true if this is a new listener; false if its a duplicate */
    protected boolean listen(Watcher s) {
        if (s != null) {
            if (watchers == null) {
                watchers = new CopyOnWriteArraySet<>();
            }
            return watchers.add(s);
        }
        return false;
    }

    /**
     * Remove a subscriber to stop being called for updates.
     *
     * @param s subscriber to remove
     */
    public void remove(Subscriber s) {
        if (watchers != null) {
            watchers.remove(s);
        }
    }

    /**
     * Remove this node from its parent.
     */
    public void remove() {
        if (parent != null) {
            parent.remove(this);
        }
    }

    protected Object validate(Object newValue, Object oldValue) {
        if (watchers != null) {
            boolean rewrite = true;
            // Try to make all the validators happy, but not infinitely
            for (int laps = 3; --laps >= 0 && rewrite; ) {
                rewrite = false;
                for (Watcher s : watchers) {
                    if (s instanceof Validator) {
                        Object nv = ((Validator) s).validate(newValue, oldValue);
                        if (!Objects.equals(nv, newValue)) {
                            rewrite = true;
                            newValue = nv;
                        }
                    }
                }
            }
        }
        return newValue;
    }

    public abstract void deepForEachTopic(Consumer<Topic> f);

    /**
     * Check if this node is a child of a node with the given name.
     *
     * @param n name to check for
     * @return true if this node is a child of a node named n
     */
    public boolean childOf(String n) {
        return n.equals(name) || parent != null && parent.childOf(n);
    }

    /**
     * Get if parents will be notified for changes.
     *
     * @return false iff changes to this node should be ignored by it's parent
     *     (ie. it's completely handled locally)
     */
    public boolean parentNeedsToKnow() {
        return parentNeedsToKnow;
    }

}
