/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.config;

import java.io.*;
import java.util.*;
import java.util.function.*;

public class Topic extends Node {
    Topic(String n, Topics p) {
        super(n, p);
    }
    public static Topic of(String n, Object v) {
        return new Topic(n,null).dflt(v);
    } 
    private long modtime;
    Object value;
    /**
     * This is the preferred way to get a value from a configuration.
     * Instead of {@code setValue(configValue.getOnce()) }
     * use {@code configValue.get((nv,ov)->setValue(nv)) }
     * This way, every change to the config file will get forwarded to the
     * object.
     *
     * @param s
     */
    public Topic subscribe(Subscriber s) {
        listen(s);
        try {
            s.published(Configuration.WhatHappened.initialized, value, value);
        } catch (Throwable ex) {
            //TODO: do something less stupid
        }
        return this;
    }
    public Topic validate(Validator s) {
        listen(s);
        try {
            Object nv = s.validate(value, value);
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
    public synchronized Topic setValue(long mt, Object nv) {
        final Object ov = value;
        final long omt = modtime;
        if (!Objects.equals(nv, ov) && mt >= omt) {
            nv = validate(Configuration.WhatHappened.changed, nv, ov);
            if (!Objects.equals(nv, ov)) {
                value = nv;
                modtime = mt;
                fire(Configuration.WhatHappened.changed, nv, ov);
                if(parent!=null) parent.publish(this);
            }
        }
        return this;
    }
    public synchronized Topic dflt(Object v) {
        if(value==null) setValue(1, v); // defaults come from the dawn of time
        return this;
    }
    @Override
    public boolean equals(Object o) {
        if (o instanceof Topic) {
            Topic t = (Topic) o;
            return name.equals(t.name) && Objects.equals(value, t.value);
        }
        return false;
    }
    @Override
    public int hashCode() {
        return 43 * Objects.hashCode(name) + Objects.hashCode(this.value);
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
