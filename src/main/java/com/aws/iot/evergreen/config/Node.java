/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.dependency.Context;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public abstract class Node {
    protected Node(Context c, String n, Topics p) {
        context = c;
        name = n;
        parent = p;
        fnc = calcFnc();
    }
    public final Context context;
    public final String name;
    public final Topics parent;
    private boolean parentNeedsToKnow = true; // parent gets notified of changes to this node
    private final String fnc;
    public boolean appendNameTo(Appendable a) throws IOException {
        if (name == null)
            return false;
        if (parent != null && parent.appendNameTo(a))
            a.append('.');
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
        return (T)this;
    }
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        try {
            appendTo(sb);
        } catch (IOException ex) {
        }
        return sb.toString();
    }
    protected CopyOnWriteArraySet<Watcher> watchers;
    abstract void fire(WhatHappened what);
    /* returns true if this is a new listener; false if its a duplicate */
    protected boolean listen(Watcher s) {
        if (s != null) {
            if (watchers == null)
                watchers = new CopyOnWriteArraySet<>();
            return watchers.add(s);
        }
        return false;
    }
    public void remove(Subscriber s) {
        if (watchers != null)
            watchers.remove(s);
    }
    protected Object validate(Object newValue, Object oldValue) {
        if (watchers != null) {
            boolean rewrite = true;
            // Try to make all the validators happy, but not infinitely
            for (int laps = 3; --laps >= 0 && rewrite;) {
                rewrite = false;
                for (Watcher s : watchers)
                    if (s instanceof Validator) {
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
    public void remove() {
        if (parent != null)
            parent.remove(this);
    }
    public boolean childOf(String n) {
        return n.equals(name) || parent!=null && parent.childOf(n);
    }
    /**
     * @return false iff changes to this node should be ignored by it's parent
     * (ie. it's completely handled locally)
     */
    public boolean parentNeedsToKnow() {
        return parentNeedsToKnow;
    }

}
