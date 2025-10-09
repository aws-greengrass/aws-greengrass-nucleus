/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.LockFactory;
import com.aws.greengrass.util.LockScope;
import com.aws.greengrass.util.Utils;

import java.nio.CharBuffer;
import java.util.TimeZone;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import javax.annotation.Nullable;

import static com.aws.greengrass.util.Utils.parseLong;
//import java.util.concurrent.TimeUnit; not flexible enough :-(

/**
 * Support for services that are periodic. For now, it's very simplistic.
 */
public final class Periodicity {
    /**
     * Just using raw milliseconds: finer precision isn't realistic at this point.
     */
    private final Topic interval;
    private final Topic phase;
    private final Topic fuzz;
    private final GreengrassService service;
    private ScheduledFuture<?> future;

    private static final float DEFAULT_FUZZ_FACTOR = 0.5f;
    private final Lock lock = LockFactory.newReentrantLock(this);

    private Periodicity(Topic i, Topic f, Topic p, GreengrassService s) {
        // f is a random "fuzz factor" to add some noise to the phase offset so that
        // if (for example) there are many devices doing periodic reports,they don't all
        // do it at the same time
        interval = i == null ? Topic.of(s.context, "interval", TimeUnit.MINUTES.toMillis(5)) : i;
        fuzz = f == null ? Topic.of(s.context, "fuzz", 0.5) : f;
        phase = p == null ? Topic.of(s.context, "phase", 0) : p;
        service = s;
    }

    /**
     * Get Periodicity for an GreengrassService based on its config.
     *
     * @param s service to get periodicity of
     * @return the periodicity (or null)
     */
    @Nullable
    public static Periodicity of(GreengrassService s) {
        Node n = s.getServiceConfig().getChild("periodic");
        if (n == null) {
            return null;
        }
        n.withParentNeedsToKnow(false);
        try {
            Periodicity ret;
            ScheduledExecutorService ses = s.getContext().get(ScheduledExecutorService.class);
            Runnable action = () -> {
                if (s.inState(State.FINISHED)) {
                    s.requestStart();
                }
            };
            if (n instanceof Topic) {
                Topic t = (Topic) n;
                ret = new Periodicity(t, null, null, s);
                t.subscribe((a, b) -> ret.start(ses, action));
            } else if (n instanceof Topics) {
                Topics params = (Topics) n;
                ret = new Periodicity(params.findLeafChild("interval"), params.findLeafChild("fuzz"),
                        params.findLeafChild("phase"), s);
                params.subscribe((what, child) -> ret.start(ses, action));
            } else {
                return null;
            }
            return ret;
        } catch (NumberFormatException t) {
            s.logger.atError("service-invalid-config")
                    .setCause(t)
                    .kv("parameter", Utils.deepToString(n))
                    .kv(GreengrassService.SERVICE_NAME_KEY, s.getName())
                    .log("Unparseable periodic parameter");
            s.serviceErrored(t);
        }
        return null;
    }

    @SuppressWarnings("PMD.DefaultLabelNotLastInSwitchStmt")
    static long parseInterval(String v) {
        CharBuffer p = CharBuffer.wrap(v);
        long n = parseLong(p);
        String u = p.toString().trim().toLowerCase();
        // TimeUnit tu; Bugger: doesn't support weeks
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
        }
        return n * tu;
    }

    private void start(ScheduledExecutorService ses, Runnable r) {
        try (LockScope ls = LockScope.lock(lock)) {
            Future<?> f = future;
            if (f != null) {
                f.cancel(false);
            }
            long now = System.currentTimeMillis();
            long timeIntervalMillis = parseInterval(Coerce.toString(interval));
            long phase = parseInterval(Coerce.toString(this.phase));
            float fuzzFactor; // The fraction of the interval to "fuzz" the start time
            try {
                fuzzFactor = Float.parseFloat(Coerce.toString(fuzz));
                if (fuzzFactor < 0) {
                    fuzzFactor = 0;
                }
                if (fuzzFactor > 1) {
                    fuzzFactor = 1;
                }
            } catch (NumberFormatException t) {
                service.logger.atWarn()
                        .addKeyValue("factor", Coerce.toString(fuzz))
                        .setCause(t)
                        .addKeyValue("default", DEFAULT_FUZZ_FACTOR)
                        .log("Error parsing fuzz factor. Using default");
                fuzzFactor = DEFAULT_FUZZ_FACTOR;
            }

            // make cycle phase be relative to the local time zone
            long myT = now / timeIntervalMillis * timeIntervalMillis + phase + TimeZone.getDefault().getOffset(now);
            if (fuzzFactor > 0) {
                myT += (long) (fuzzFactor * Math.random() * timeIntervalMillis);
            }
            while (myT <= now + 1) {
                myT += timeIntervalMillis;
            }
            future = ses.scheduleAtFixedRate(r, myT - now, timeIntervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Shutdown the periodic task.
     */
    public void shutdown() {
        try (LockScope ls = LockScope.lock(lock)) {
            Future<?> f = future;
            if (f != null && (future.isDone() || future.isCancelled())) {
                f.cancel(true);
            }
        }
    }

}
