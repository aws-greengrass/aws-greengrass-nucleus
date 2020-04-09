/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import com.aws.iot.evergreen.config.Topic;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.aws.iot.evergreen.util.Utils.isEmpty;

public final class Coerce {
    public static final Object removed = new Object() {
        @Override
        public String toString() {
            return "removed";
        }
    };
    private static final Map<String, Object> specials =
            Utils.immutableMap("true", true, "false", false, "removed", removed, "Inf", Double.POSITIVE_INFINITY,
                    "+Inf", Double.POSITIVE_INFINITY, "-Inf", Double.NEGATIVE_INFINITY, "Nan", Double.NaN, "NaN",
                    Double.NaN, "inf", Double.POSITIVE_INFINITY, "+inf", Double.POSITIVE_INFINITY, "-inf",
                    Double.NEGATIVE_INFINITY, "nan", Double.NaN);
    private static final char[] hex = "0123456789ABCDEF".toCharArray();
    private static final Pattern seperators = Pattern.compile(" *, *");
    private static final Pattern unwrap = Pattern.compile(" *\\[ *(.*) *\\] *");

    private Coerce() {
    }

    /**
     * Convert the object into a boolean value.
     *
     * @param o object
     * @return result.
     */
    public static boolean toBoolean(Object o) {
        if (o instanceof Topic) {
            o = ((Topic) o).getOnce();
        }
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue() != 0;
        }
        if (o != null) {
            switch (o.toString()) {
                case "true":
                case "yes":
                case "on":
                case "t":
                case "y":
                    return true;
                default:
                    return false;
            }
        }
        return false;
    }

    /**
     * Get an object as an integer.
     *
     * @param o object to convert.
     * @return resulting int.
     */
    @SuppressWarnings("checkstyle:emptycatchblock")
    public static int toInt(Object o) {
        if (o instanceof Topic) {
            o = ((Topic) o).getOnce();
        }
        if (o instanceof Boolean) {
            return (Boolean) o ? 1 : 0;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o != null) {
            try {
                CharSequence cs = o instanceof CharSequence ? (CharSequence) o : o.toString();
                return (int) Utils.parseLong(cs);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /**
     * Convert object to double.
     *
     * @param o object to convert.
     * @return the resulting double value.
     */
    @SuppressWarnings("checkstyle:emptycatchblock")
    public static double toDouble(Object o) {
        if (o instanceof Topic) {
            o = ((Topic) o).getOnce();
        }
        if (o instanceof Boolean) {
            return (Boolean) o ? 1 : 0;
        }
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        if (o != null) {
            try {
                return Double.parseDouble(o.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /**
     * Convert an object to string or null if it is null.
     *
     * @param o object to convert.
     * @return resulting string.
     */
    public static String toString(Object o) {
        if (o instanceof Topic) {
            o = ((Topic) o).getOnce();
        }
        return o == null ? null : o.toString();
    }

    /**
     * Convert object to an array of strings.
     *
     * @param o object to convert.
     * @return resulting string array.
     */
    public static String[] toStringArray(Object o) {
        if (o instanceof Topic) {
            o = ((Topic) o).getOnce();
        }
        if (o == null) {
            return new String[0];
        }
        if (o instanceof String[]) {
            return (String[]) o;
        }
        if (o.getClass().isArray()) {
            int len = Array.getLength(o);
            String[] ret = new String[len];
            for (int i = 0; i < len; i++) {
                Object e = Array.get(o, i);
                ret[i] = e == null ? "" : e.toString();
            }
            return ret;
        }
        String body = o.toString();
        Matcher uw = unwrap.matcher(body);
        if (uw.matches()) {
            body = uw.group(1);
        }
        body = body.trim();
        if (isEmpty(body)) {
            return new String[0];
        }
        return seperators.split(body);
    }

    public static <T extends Enum<?>> T toEnum(Class<T> cl, Object o) {
        return toEnum(cl, o, null);
    }

    /**
     * Convert an object to an enum of class clazz with a default value of
     * dflt.
     *
     * @param clazz enum class to convert into.
     * @param o object to be converted.
     * @param dflt default value if the conversion fails.
     * @param <T> Enum type to coerce to.
     * @return enum value or default.
     */
    public static <T extends Enum<?>> T toEnum(Class<T> clazz, Object o, T dflt) {
        if (clazz.isAssignableFrom(o.getClass())) {
            return (T) o;
        }
        T[] values = clazz.getEnumConstants();
        if (o instanceof Number) {
            return values[Math.max(0, Math.min(values.length - 1, ((Number) o).intValue()))];
        }
        String s = Coerce.toString(o);
        int l = s.length();
        for (T v : values) {
            String vs = v.toString();
            if (vs.length() < l) {
                continue;
            }
            if (vs.regionMatches(true, 0, s, 0, l)) {
                return v;
            }
        }
        return dflt;
    }

    /**
     * Convert object into a JSON encoded string.
     *
     * @param o object to convert.
     * @return resulting string.
     */
    @SuppressWarnings("checkstyle:emptycatchblock")
    public static String toQuotedString(Object o) {
        if (o instanceof Topic) {
            o = ((Topic) o).getOnce();
        }
        StringBuilder sb = new StringBuilder();
        try {
            appendQuotedString(o, sb);
        } catch (IOException ignored) {
        }
        return sb.toString();
    }

    /**
     * Convert object to a JSON encoded string, writing output to the appendable.
     *
     * @param o object to convert.
     * @param out appendable to append to.
     * @throws IOException if the append fails.
     */
    public static void appendQuotedString(Object o, Appendable out) throws IOException {
        if (o instanceof Topic) {
            o = ((Topic) o).getOnce();
        }
        out.append('"');
        if (o != null) {
            String s = o.toString();
            int limit = s.length();
            for (int i = 0; i < limit; i++) {
                char c = s.charAt(i);
                if (c < ' ' || c >= 255 || c == 127 || c == '"') {
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
                            break;
                    }
                } else {
                    out.append(c);
                }
            }
        }
        out.append('"');
    }

    /**
     * Convert object to JSON encoded string and write output to the appendable.
     *
     * @param o object to convert.
     * @param out appendable to write to.
     * @throws IOException if the append fails.
     */
    public static void appendParseableString(Object o, Appendable out) throws IOException {
        if (o instanceof Topic) {
            o = ((Topic) o).getOnce();
        }
        if (o == null) {
            out.append("null");
        } else if (o instanceof Boolean || o instanceof Number) {
            out.append(o.toString());
        } else {
            appendQuotedString(o, out);
        }
    }

    /**
     * Convert a string to the appropriate Java object.
     *
     * @param s string to convert
     * @return resulting object or empty string if the input was null.
     */
    @SuppressWarnings({"checkstyle:emptycatchblock"})
    public static Object toObject(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        if (s.charAt(0) == '"') {
            return Utils.dequote(s);
        }
        if (Character.isDigit(s.charAt(0)) && s.indexOf('.') < 0) {
            long l = Utils.parseLong(s);
            int li = (int) l;
            // these returns can't be combined because it would mess with the implicit boxing
            if (l == li) {
                return li;
            } else {
                return l;
            }
        }
        try {
            double d = Double.parseDouble(s);
            int di = (int) d;
            if (di == d) {
                return di;
            }
            long li = (long) d;
            if (li == d) {
                return li;
            }
            return d;
        } catch (NumberFormatException ignored) {
        }
        if ("null".equals(s)) {
            return null;
        }
        Object v = specials.get(s);
        if (v != null) {
            return v;
        }
        return s;
    }

    private static void appendHexString(long n, Appendable out, int ndig) throws IOException {
        if (ndig > 1) {
            appendHexString(n >> 4, out, ndig - 1);
        }
        out.append(hex[((int) n) & 0xF]);
    }
}
