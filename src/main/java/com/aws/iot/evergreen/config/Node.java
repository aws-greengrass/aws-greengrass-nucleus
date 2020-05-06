/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.dependency.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

public abstract class Node {
    public final Context context;
    public final Topics parent;
    private final String fnc;
    private final String name;
    protected CopyOnWriteArraySet<Watcher> watchers;
    private boolean parentNeedsToKnow = true; // parent gets notified of changes to this node
    private List<String> path;

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

    public String getName() {
        return name;
    }

    public abstract void appendTo(Appendable a) throws IOException;

    public abstract Object toPOJO();

    public abstract void copyFrom(Node n);

    public <T extends Node> T withParentNeedsToKnow(boolean np) {
        parentNeedsToKnow = np;
        return (T) this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        try {
            appendTo(sb);
        } catch (IOException ignore) {
        }
        return sb.toString();
    }

    abstract void fire(WhatHappened what);

    /**
     * Add a watcher.
     *
     * @param s a watcher to be added
     * @return true if this is a new watcher; false if its a duplicate
     */
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
            for (int laps = 3; laps > 0 && rewrite; --laps) {
                rewrite = false;
                for (Watcher s : watchers) {
                    if (!(s instanceof Validator)) {
                        continue;
                    }
                    Object nv = ((Validator) s).validate(newValue, oldValue);
                    if (!Objects.equals(nv, newValue)) {
                        rewrite = true;
                        newValue = nv;
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
     * Get path of parents.
     *
     * @return list of strings with index 0 being the current node's name
     */
    public List<String> path() {
        if (path != null) {
            return path;
        }

        path = new ArrayList<>();
        path.add(name);

        if (parent != null) {
            path.addAll(parent.path());
        }
        return path;
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

    /**
     * Get the root of the config store.
     *
     * @return Root Topics
     */
    public Topics getRoot() {
        Topics p = parent;
        while (p.parent != null) {
            p = p.parent;
        }
        return p;
    }
}
