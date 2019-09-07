/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.gg2k;

import com.aws.iot.util.*;
import static com.aws.iot.util.Utils.close;
import java.io.*;
import java.net.URL;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * This is a super-simple templating engine. It's really just variable
 * expansion. If folks start getting delusions of turning this into something
 * grand, it may make more sense to just switch to Velocity
 */
public class EZTemplates {
    public CharSequence rewrite(CharSequence in) {
        Matcher m = scriptVar.matcher(in);
        StringBuffer sb = null;
        while (m.find()) {
            Object result = eval(m.group(1));
            if(result==null) result = m.group(0);
            if (sb == null) sb = new StringBuffer();
            m.appendReplacement(sb, Coerce.toString(result));
        }
        if (sb == null) return in;
        m.appendTail(sb);
        return sb;
    }
    private static final Pattern scriptVar = Pattern.compile("\\$\\[([^\\[\\]\\n]+)\\]");
    public CharSequence rewrite(URL in) throws IOException {
        return rewrite(toCS(in));
    }
    public CharSequence rewrite(InputStream in) throws IOException {
        return rewrite(toCS(in));
    }
    public CharSequence rewrite(Reader in) throws IOException {
        return rewrite(toCS(in));
    }
    public static CharSequence toCS(URL in) throws IOException {
        return toCS(in.openStream());
    }
    public static CharSequence toCS(InputStream in) throws IOException {
        return toCS(new BufferedReader(new InputStreamReader(in)));
    }
    @SuppressWarnings("ThrowableResultIgnored")
    public static CharSequence toCS(Reader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0)
            sb.append((char) c);
        close(in);
        return sb;
    }
    public static void writeTo(CharSequence cs, Path dest) throws IOException {
        CommitableWriter cw = CommitableWriter.of(dest);
        cw.append(cs);
        cw.commit();
    }
    public void rewrite(URL in, Path dest) throws IOException {
        writeTo(rewrite(toCS(in)), dest);
    }
    public void rewrite(InputStream in, Path dest) throws IOException {
        writeTo(rewrite(toCS(in)), dest);
    }
    public void rewrite(Reader in, Path dest) throws IOException {
        writeTo(rewrite(toCS(in)), dest);
    }

    public interface Evaluator {
        public Object evaluate(String expr);
    }
    private final CopyOnWriteArraySet<Evaluator> evaluators = new CopyOnWriteArraySet<>();
    public void addEvaluator(Evaluator e) {
        evaluators.add(e);
    }
    public void removeEvaluator(Evaluator e) {
        evaluators.remove(e);
    }
    public Object eval(String expr) {
        Object result;
        for (Evaluator e : evaluators)
            if ((result = e.evaluate(expr)) != null) return result;
        return null;

    }

//            Path dest = nukk;
//            try (BufferedReader in = new BufferedReader(new InputStreamReader(resource.openStream()));
//                    CommitableWriter out = CommitableWriter.of(dest)) {
//                String s;
//                StringBuffer sb = new StringBuffer();
//                while((s = in.readLine())!=null) {
//                    Matcher m = scriptVar.matcher(s);
//                    sb.setLength(0);
//                    while (m.find()) {
//                        String rep;
//                        switch(m.group(1)) {
//                            case "root": rep = rootPath.toString(); break;
//                            case "work": rep = workPath.toString(); break;
//                            case "bin": rep = clitoolPath.toString(); break;
//                            case "config": rep = configPath.toString(); break;
//                            default: rep = m.group(0); break;
//                        }
//                        m.appendReplacement(sb, rep);
//                    }
//                    m.appendTail(sb);
//                    out.write(sb.toString());
//                    out.write('\n');
//                }
//                out.commit();
}
