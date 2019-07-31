/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.gg2k;

import com.aws.iot.config.*;
import com.aws.iot.config.Node;
import com.aws.iot.config.Topic;
import com.aws.iot.config.Topics;
import com.aws.iot.dependency.*;
import com.aws.iot.util.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;
import javax.inject.*;


public class GGService extends Lifecycle {
    public final Topics config;
    protected final CopyOnWriteArrayList<Lifecycle> explicitDependencies = new CopyOnWriteArrayList<>();
    public GGService(Topics c) {
        config = c;
    }
    @Override public String getName() {
        return config.getFullName();
    }
    @Override public void postInject() {
        super.postInject();
        Node d = config.getChild("dependencies");
        if (d == null)
            d = config.getChild("dependency");
        if (d == null)
            d = config.getChild("requires");
        //            System.out.println("requires: " + d);
        if(d == null){
            //TODO: handle defaultimpl without creating GGService for parent
            d =  config.getChild("defaultimpl");
        }
        if (d instanceof Topics)
            d = pickByOS((Topics) d);
        if (d instanceof Topic) {
            String ds = ((Topic) d).getOnce().toString();
            Matcher m = depParse.matcher(ds);
            while(m.find())
                addDependency(m.group(1), m.group(3));
            if (!m.hitEnd())
                errored("bad dependency syntax", ds);
        }
    }
    public void addDependency(String name, String startWhen) {
        if (startWhen == null)
            startWhen = Lifecycle.State.Running.toString();
        Lifecycle.State x = null;
        if (startWhen != null) {
            int len = startWhen.length();
            if (len > 0) {
                // do "friendly" match
                for (Lifecycle.State s : Lifecycle.State.values())
                    if (startWhen.regionMatches(true, 0, s.name(), 0, len)) {
                        x = s;
                        break;
                    }
                if (x == null)
                    errored("does not match any lifecycle state", startWhen);
            }
        }
        if (x == null)
            x = Lifecycle.State.Running;
        addDependency(name, x);
    }
    public void addDependency(String name, Lifecycle.State startWhen) {
        try {
            Lifecycle d = locate(name);
            if (d != null) {
                explicitDependencies.add(d);
                addDependency(d, startWhen);
            }
            else
                errored("Couldn't locate", name);
        } catch (Throwable ex) {
            errored("Failure adding dependency", ex);
            ex.printStackTrace(System.out);
        }
    }
    private static final Pattern depParse = Pattern.compile(" *([^,:; ]+)(:([^,; ]+))?[,; ]*");
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        try {
            if (config == null)
                sb.append("[nameless]");
            else
                config.appendNameTo(sb);
            if (getState() != State.Running)
                sb.append(':').append(getState().toString());
        } catch (IOException ex) {
            sb.append(ex.toString());
        }
        return sb.toString();
    }
    public Lifecycle locate(String name) throws Throwable {
        return locate(context, name);
    }
    public static Lifecycle locate(Context context, String name) throws Throwable {
        return context.getv(Lifecycle.class, name).computeIfEmpty(v->{
            Configuration c = context.get(Configuration.class);
            Topics t = c.findTopics(Configuration.splitPath(name));
            Lifecycle ret;
            if (t != null) {
                Node n = t.getChild("class");
                if (n != null) {
                    String cn = Coerce.toString(n);
                    try {
                        Class clazz = Class.forName(cn);
                        Constructor ctor = clazz.getConstructor(Topics.class);
                        ret = (GGService) ctor.newInstance(t);
                        if(clazz.getAnnotation(Singleton.class) !=null) {
                            context.put(ret.getClass(), v);
                        }
                    } catch (Throwable ex) {
                        ex.printStackTrace(System.out);
                        ret = errNode(context, name, "creating code-backed service from " + cn, ex);
                    }
                }
                else
                    try {
                        ret = new GenericExternalService(t);
                    } catch (Throwable ex) {
                        ret = errNode(context, name, "Creating generic service", ex);
                    }
                if (ret != null)
                    return ret;
            }
            return errNode(context, name, "No matching definition in system model", null);
        });
    }
    public static GGService errNode(Context context, String name, String message, Throwable ex) {
        try {
            context.get(Log.class).error("Error locating service",name,message,ex);
            GGService ggs = new GenericExternalService(Topics.errorNode(name,
                    "Error locating service " + name + ": " + message
                            + (ex == null ? "" : "\n\t" + ex)));
            return ggs;
        } catch (Throwable ex1) {
            context.get(Log.class).error(name,message,ex);
            return null;
        }
    }
    boolean shouldSkip(Topics n) {
        Node skipif = n.getChild("skipif");
        boolean neg = skipif == null && (skipif = n.getChild("doif")) != null;
        if (skipif instanceof Topic) {
            String expr = String.valueOf(((Topic) skipif).getOnce()).trim();
            if (expr.startsWith("!")) {
                expr = expr.substring(1).trim();
                neg = !neg;
            }
            Matcher m = skipcmd.matcher(expr);
            if (m.matches())
                switch (m.group(1)) {
                    case "onpath":
                        return Exec.which(m.group(2)) != null ^ neg; // XOR ?!?!
                    case "exists":
                        return Files.exists(Paths.get(context.get(GG2K.class).deTilde(m.group(2)))) ^ neg;
                    case "true": return !neg;
                    default:
                        errored("Unknown operator", m.group(1));
                        return false;
                }
            // Assume it's a shell script: test for 0 return code and nothing on stderr
            return neg ^ Exec.successful(expr);
        }
        return false;
    }
    private static final Pattern skipcmd = Pattern.compile("(exists|onpath) +(.+)");
    Node pickByOS(String name) {
        Node n = config.getChild(name);
        if (n instanceof Topics)
            n = pickByOS((Topics) n);
        return n;
    }
    private static final HashMap<String, Integer> ranks = new HashMap<>();
    public static int rank(String s) {
        Integer i = ranks.get(s);
        return i == null ? -1 : i;
    }
    static {
        // figure out what OS we're running and add applicable tags
        // The more specific a tag is, the higher its rank should be
        // TODO: a loopy set of hacks
        ranks.put("all", 0);
        ranks.put("any", 0);
        if (Files.exists(Paths.get("/bin/bash")))
            ranks.put("posix", 3);
        if (Files.exists(Paths.get("/usr/bin/bash")))
            ranks.put("posix", 3);
        if (Files.exists(Paths.get("/proc")))
            ranks.put("linux", 10);
        if (Files.exists(Paths.get("/usr/bin/apt-get")))
            ranks.put("debian", 11);
        if (Exec.isWindows)
            ranks.put("windows", 5);
        if (Files.exists(Paths.get("/usr/bin/yum")))
            ranks.put("fedora", 11);
        String sysver = Exec.sh("uname -a").toLowerCase();
        if (sysver.contains("ubuntu"))
            ranks.put("ubuntu", 20);
        if (sysver.contains("darwin"))
            ranks.put("macos", 20);
        if (sysver.contains("raspbian"))
            ranks.put("raspbian", 22);
        if (sysver.contains("qnx"))
            ranks.put("qnx", 22);
        if (sysver.contains("cygwin"))
            ranks.put("cygwin", 22);
        if (sysver.contains("freebsd"))
            ranks.put("freebsd", 22);
        if (sysver.contains("solaris") || sysver.contains("sunos"))
            ranks.put("solaris", 22);
        try {
            ranks.put(InetAddress.getLocalHost().getHostName(), 99);
        } catch (UnknownHostException ex) {
        }
    }
    Node pickByOS(Topics n) {
        Node bestn = null;
        int bestrank = -1;
        for (Map.Entry<String, Node> me : ((Topics) n).children.entrySet()) {
            int g = rank(me.getKey());
            if (g > bestrank) {
                bestrank = g;
                bestn = me.getValue();
            }
        }
        return bestn;
    }
    protected boolean run(String name, boolean required, IntConsumer background) {
        Node n = pickByOS(name);
        if(n==null) {
            if(required) log().warn("Missing",name,this);
            return !required;
        }
        return run(n, background);
    }
    protected boolean run(Node n, IntConsumer background) {
        return n instanceof Topic ? run((Topic) n, background)
             : n instanceof Topics && run((Topics) n, background);
    }
    @Inject ShellRunner shellRunner;
    protected boolean run(Topic t, IntConsumer background) {
        String cmd = Coerce.toString(t.getOnce());
        return shellRunner.run(t.getFullName(), cmd, background) != ShellRunner.Failed;
    }
    protected boolean run(Topics t, IntConsumer background) {
        if (!shouldSkip(t)) {
            Node script = t.getChild("script");
            if (script instanceof Topic)
                return run((Topic) script, background);
            else {
                errored("Missing script: for ", t.getFullName());
                return false;
            }
        }
        else {
            log().significant("Skipping", t.getFullName());
            return true;
        }
    }
    protected void addDependencies(HashSet<Lifecycle> deps) {
        deps.add(this);
        if (dependencies != null)
            dependencies.keySet().forEach(d -> {
                if (!deps.contains(d) && d instanceof GGService)
                    ((GGService)d).addDependencies(deps);
            });
    }
    @Override public boolean satisfiedBy(HashSet<Lifecycle> ready) {
        return dependencies == null
                || dependencies.keySet().stream().allMatch(l -> ready.contains(l));
    }

}
