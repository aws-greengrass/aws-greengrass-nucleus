/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.gg2k;

import com.aws.iot.util.*;
import static com.aws.iot.util.Utils.close;
import java.io.*;
import java.net.URL;
import java.nio.file.*;
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
        Files.deleteIfExists(dest);
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
}
