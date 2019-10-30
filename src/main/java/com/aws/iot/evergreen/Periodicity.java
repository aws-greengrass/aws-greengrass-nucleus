/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.evergreen;

import com.aws.iot.evergreen.config.*;
import com.aws.iot.evergreen.dependency.*;
import com.aws.iot.evergreen.util.*;
import static com.aws.iot.evergreen.util.Utils.parseLong;
import java.nio.CharBuffer;
import java.util.TimeZone;
import java.util.concurrent.*;
//import java.util.concurrent.TimeUnit; not flexible enouth :-(

/**
 * Support for serices that are periodic. For now, it's very simplistic
 */
public class Periodicity {
    /**
     * Just using raw milliseconds: finer precision isn't realistic at this
     * point
     */
    private final Topic interval, phase, fuzz;
    private ScheduledFuture future;
    private final EvergreenService service;
    public static Periodicity of(EvergreenService s) {
        Node n = s.config.getChild("periodic");
        if (n == null) return null;
        try {
            Periodicity ret;
            ScheduledExecutorService ses = s.getContext().get(ScheduledExecutorService.class);
            Runnable action = ()->{
                if (s.inState(State.Finished))
                    s.setState(State.Running);
            };
            if (n instanceof Topic) {
                Topic t = (Topic) n;
                ret = new Periodicity(t, null, null, s);
                t.subscribe((a,b)->ret.start(ses, action));
            } else if (n instanceof Topics) {
                Topics params = (Topics) n;
                ret = new Periodicity(params.findLeafChild("interval"),
                        params.findLeafChild("fuzz"),
                        params.findLeafChild("phase"),
                        s);
                params.subscribe(child->ret.start(ses, action));
            } else return null;
            return ret;
        } catch (Throwable t) {
            s.errored("Unparseable periodic parameter: " + Utils.deepToString(n), t);
        }
        return null;
    }
    public static Periodicity of(Node n, EvergreenService s) throws Throwable {
        return null;
    }
    private Periodicity(Topic i, Topic f, Topic p, EvergreenService s) throws IllegalArgumentException {
        // f is a random "fuzz factor" to add some noise to the phase offset so that
        // if (for example) there are many devices doing periodic reports,they don't all
        // do it at the same time
        interval = i != null ? i : Topic.of(s.context,"interval", TimeUnit.MINUTES.toMillis(5));
        fuzz = f != null ? f : Topic.of(s.context,"fuzz", 0.5);
        phase = p != null ? p : Topic.of(s.context,"phase", 0);
        service = s;
    }
    public synchronized void start(ScheduledExecutorService ses, Runnable r) {
        Future f = future;
        if (f != null)
            f.cancel(false);
        long now = System.currentTimeMillis();
        long ΔT = parseInterval(Coerce.toString(interval)),
                ϕ = parseInterval(Coerce.toString(phase));
        float ε;  // The fraction of the interval to "fuzz" the start time
        try {
            ε = Float.parseFloat(Coerce.toString(fuzz));
            if (ε < 0) ε = 0;
            if (ε > 1) ε = 1;
        } catch (Throwable t) {
            service.log().warn("Error parsing fuzz factor: " + Coerce.toString(fuzz), t);
            ε = 0.5f;
        }
        long myT = now / ΔT * ΔT + ϕ
                + TimeZone.getDefault().getOffset(now);  // make cycle phase be relative to the local time zone
        if (ε > 0) myT += (long) (ε * Math.random() * ΔT);
        while (myT <= now + 1)
            myT += ΔT;
        future = ses.scheduleAtFixedRate(r, myT - now, ΔT, TimeUnit.MILLISECONDS);
    }
    public void shutdown() {
        Future f = future;
        if(f!=null) {
            f.cancel(true);
            future = null;
        }
    }
    // TODO: use of parseInterval to parse the phase offset is wholly inadequate: it should
    // allow for all sorts of complexity, like being relative to local time (eg. 2am)
    public static long parseInterval(String v) {
        CharBuffer p = CharBuffer.wrap(v);
        long n = parseLong(p);
        String u = p.toString().trim().toLowerCase();
//        TimeUnit tu;   Bugger: doesn't support weeks
        long tu;
        switch (u) {
            case "ms":
            case "millis":
            case "milliseconds":
                tu = 1;
                break;
            case "":
            case "s":
            case "seconds":
            case "second":
                tu = 1000;
                break;
            default:
            case "m":
            case "minutes":
            case "minute":
                tu = 1000 * 60;
                break;
            case "h":
            case "hours":
            case "hour":
                tu = 1000 * 60 * 60;
                break;
            case "d":
            case "days":
            case "day":
                tu = 1000 * 60 * 60 * 24;
                break;
            case "w":
            case "weeks":
            case "week":
                tu = 1000 * 60 * 60 * 24 * 7;
                break;
            // Should do months
        }
        return n * tu;
    }
    public static long parseTime(String v) {
        return 100 + System.currentTimeMillis();  // TODO: replace this total hack
    }

}
