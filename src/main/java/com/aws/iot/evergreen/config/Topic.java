/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.dependency.Context;
import java.io.*;
import java.util.*;
import java.util.function.*;

public class Topic extends Node {
    Topic(Context c, String n, Topics p) {
        super(c, n, p);
    }
    public static Topic of(Context c, String n, Object v) {
        return new Topic(c, n, null).dflt(v);
    }
    private long modtime;
    private Object value;
    /**
     * This is the preferred way to get a value from a configuration. Instead of {@code setValue(configValue.getOnce())
     * }
     * use {@code configValue.get((nv,ov)->setValue(nv)) }
     * This way, every change to the config file will get forwarded to the
     * object.
     *
     * @param s
     */
    public Topic subscribe(Subscriber s) {
        if(listen(s)) try {
                s.published(WhatHappened.initialized, this);
            } catch (Throwable ex) {
                //TODO: do something less stupid
            }
        return this;
    }
    public Topic validate(Validator s) {
        if(listen(s)) try {
            if(value!=null) value = s.validate(value, null);
        } catch (Throwable ex) {
            //TODO: do something less stupid
        }
        return this;
    }
    public long getModtime() {
        return modtime;
    }
    /**
     * This should rarely be used. Instead, use subscribe(Subscriber)
     */
    // @Deprecated
    public Object getOnce() {
        return value;
    }
    public void appendValueTo(Appendable a) throws IOException {
        a.append(String.valueOf(value));
    }
    @Override
    public void appendTo(Appendable a) throws IOException {
        appendNameTo(a);
        a.append(':');
        appendValueTo(a);
    }
    public Topic setValue(Object nv) {
        return setValue(System.currentTimeMillis(), nv);
    }
    public synchronized Topic setValue(long proposedModtime, final Object proposed) {
//        context.getLog().note("proposing change to "+getFullName()+": "+value+" => "+proposed);
//        System.out.println("setValue: " + getFullName() + ": " + value + " => " + proposed);
//        if(proposed==Errored)
//            new Exception("setValue to Errored").printStackTrace();
        final Object currentValue = value;
        final long currentModtime = modtime;
        if (Objects.equals(proposed, currentValue) || proposedModtime < currentModtime)
            return this;
        final Object validated = validate(proposed, currentValue);
        if (Objects.equals(validated, currentValue)) return this;
        value = validated;
        modtime = proposedModtime;
//        context.getLog().note("seen change to "+getFullName()+": "+currentValue+" => "+validated);
        context.queuePublish(this);
        return this;
    }
    @Override
    public void fire(WhatHappened what) {
        if (watchers != null)
            for (Watcher s : watchers)
                try {
                    if (s instanceof Subscriber)
                        ((Subscriber) s).published(what, this);
                } catch (Throwable ex) {
                    /* TODO if a subscriber fails, we should do more than just log a
                       message.  Possibly unsubscribe it if the fault is persistent */
                    context.getLog().error(getFullName(),ex);
                }
        if(parent!=null && !isTransParent()) parent.childChanged(what, this);
    }
    @Override public void copyFrom(Node n) {
        if (n instanceof Topic)
            setValue(((Topic) n).modtime, ((Topic) n).value);
        else
            throw new IllegalArgumentException("copyFrom: " + 
                    (n==null ? "NULL" : n.getFullName())
                    + " is already a container, not a leaf");
    }
    public synchronized Topic dflt(Object v) {
        if (value == null) setValue(1, v); // defaults come from the dawn of time
        return this;
    }
    @Override
    public boolean equals(Object o) {
        if (o instanceof Topic) {
            Topic t = (Topic) o;
            return name.equals(t.name);
        }
        return false;
    }
    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }
    @Override
    public void deepForEachTopic(Consumer<Topic> f) {
        f.accept(this);
    }
    @Override
    public Object toPOJO() {
        return value;
    }

}
