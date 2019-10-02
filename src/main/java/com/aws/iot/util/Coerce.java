/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.util;

import static com.aws.iot.util.Utils.*;
import com.aws.iot.config.Topic;
import java.io.*;
import java.util.*;

public class Coerce {
    private Coerce() {
    }
    public static boolean toBoolean(Object o) {
        if(o instanceof Topic) o = ((Topic)o).getOnce();
        if (o instanceof Boolean)
            return (Boolean) o;
        if (o instanceof Number)
            return ((Number) o).intValue() != 0;
        if (o != null)
            switch (o.toString()) {
                case "true":
                case "yes":
                case "on":
                case "t":
                case "y":
                    return true;
            }
        return false;
    }
    public static int toInt(Object o) {
        if(o instanceof Topic) o = ((Topic)o).getOnce();
        if (o instanceof Boolean)
            return (Boolean) o ? 1 : 0;
        if (o instanceof Number)
            return ((Number) o).intValue();
        if (o != null)
            try {
                CharSequence cs = o instanceof CharSequence ? (CharSequence) o : o.toString();
                return (int) Utils.parseLong(cs);
            } catch (NumberFormatException nfe) {
            }
        return 0;
    }
    public static double toDouble(Object o) {
        if(o instanceof Topic) o = ((Topic)o).getOnce();
        if (o instanceof Boolean)
            return (Boolean) o ? 1 : 0;
        if (o instanceof Number)
            return ((Number) o).doubleValue();
        if (o != null)
            try {
                return Double.parseDouble(o.toString());
            } catch (NumberFormatException nfe) {
            }
        return 0;
    }
    public static String toString(Object o) {
        if(o instanceof Topic) o = ((Topic)o).getOnce();
        return o == null ? null : o.toString();
    }
    public static <T extends Enum> T toEnum(Class<T> cl, Object o) {
        if(cl.isAssignableFrom(o.getClass())) return (T)o;
        T[] values = cl.getEnumConstants();
        if(o instanceof Number)
            return values[Math.max(0, Math.min(values.length-1,((Number)o).intValue()))];
        String s = Coerce.toString(o);
        int l = s.length();
        for(T v:values) {
            String vs = v.toString();
            if(vs.length()<l) continue;
            if(vs.regionMatches(true, 0, s, 0, l)) return v;
        }
        return null;
    }
    public static String toQuotedString(Object o) {
        if(o instanceof Topic) o = ((Topic)o).getOnce();
        StringBuilder sb = new StringBuilder();
        try {
            toQuotedString(o, sb);
        } catch (IOException ex) {
        }
        return sb.toString();
    }
    public static void toQuotedString(Object o, Appendable out) throws IOException {
        if(o instanceof Topic) o = ((Topic)o).getOnce();
        out.append('"');
        if (o != null) {
            String s = o.toString();
            int limit = s.length();
            for (int i = 0; i < limit; i++) {
                char c = s.charAt(i);
                if (c < ' ' || c >= 0377 || c == 0177 || c == '"')
                    switch (c) {
                        case '\n':
                            out.append("\\n");
                            break;
                        case '\r':
                            out.append("\\r");
                            break;
                        case '\t':
                            out.append("\\t");
                            break;
                        default:
                            out.append("\\u");
                            appendHexString(c, out, 4);
                    }
                else
                    out.append(c);
            }
        }
        out.append('"');
    }
    public static void toParseableString(Object o, Appendable out) throws IOException {
        if(o instanceof Topic) o = ((Topic)o).getOnce();
        if (o == null)
            out.append("null");
        else if (o instanceof Boolean || o instanceof Number)
            out.append(o.toString());
        else
            toQuotedString(o, out);
    }
    public static Object toObject(String s) {
        if (s == null || s.length() == 0)
            return "";
        if (s.charAt(0) == '"')
            return dequote(s);
        if (Character.isDigit(s.charAt(0)) && s.indexOf('.') < 0) {
            long l = parseLong(s);
            int li = (int) l;
            // these returns can't be combined because it would mess with the implicit boxing
            if (l == li)
                return li;
            else
                return l;
        }
        try {
            double d = Double.parseDouble(s);
            int di = (int) d;
            if (di == d)
                return di;
            long li = (long) d;
            if (li == d)
                return li;
            return d;
        } catch (Throwable t) {
        }
        if ("null".equals(s))
            return null;
        Object v = specials.get(s);
        if (v != null)
            return v;
        return s;
    }
    public static final Object removed = new Object() {
        @Override
        public String toString() {
            return "removed";
        }
    };
    private static final Map<String,Object> specials = Utils.immutableMap(
            "true", true,
            "false", false,
            "removed", removed,
            "Inf", Double.POSITIVE_INFINITY,
            "+Inf", Double.POSITIVE_INFINITY,
            "-Inf", Double.NEGATIVE_INFINITY,
            "Nan", Double.NaN,
            "NaN", Double.NaN,
            "inf", Double.POSITIVE_INFINITY,
            "+inf", Double.POSITIVE_INFINITY,
            "-inf", Double.NEGATIVE_INFINITY,
            "nan", Double.NaN
    );
    private static void appendHexString(long n, Appendable out, int ndig) throws IOException {
        if (ndig > 1)
            appendHexString(n >> 4, out, ndig - 1);
        out.append(hex[((int) n) & 0xF]);
    }
    private static final char[] hex = "0123456789ABCDEF".toCharArray();
}
