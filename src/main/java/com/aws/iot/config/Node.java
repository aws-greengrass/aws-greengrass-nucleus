/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.config;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;

public abstract class Node {
    protected Node(String n, Topics p) {
        name = n;
        parent = p;
        fnc = calcFnc();
    }
    protected final String name;
    protected final Topics parent;
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
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        try {
            appendTo(sb);
        } catch (IOException ex) {
        }
        return sb.toString();
    }
    private CopyOnWriteArraySet<Watcher> watchers;
    protected void listen(Watcher s) {
        if (s != null) {
            if (watchers == null)
                watchers = new CopyOnWriteArraySet<>();
            watchers.add(s);
        }
    }
    public void remove(Watcher s) {
        if (watchers != null)
            watchers.remove(s);
    }
    protected Object validate(Configuration.WhatHappened what, Object newValue, Object oldValue) {
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
    protected boolean fire(Configuration.WhatHappened what, Object newValue, Object oldValue) {
        boolean errorFree = true;
        if (watchers != null)
            for (Watcher s : watchers)
                try {
                    if (s instanceof Subscriber)
                        ((Subscriber) s).published(what, newValue, oldValue);
                } catch (Throwable ex) {
                    errorFree = false;
                    Logger.getLogger(Configuration.class.getName()).log(Level.SEVERE, null, ex);
                }
        return errorFree;
    }
    public abstract void deepForEachTopic(Consumer<Topic> f);
    public void remove() {
        if (parent != null)
            parent.remove(this);
    }

}
