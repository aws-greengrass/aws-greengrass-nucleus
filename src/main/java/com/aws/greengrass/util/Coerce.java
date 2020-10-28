/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.config.Topic;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

import static com.aws.greengrass.util.Utils.isEmpty;

public final class Coerce {
    private static final Pattern SEPARATORS = Pattern.compile(" *, *");
    private static final Pattern unwrap = Pattern.compile(" *\\[ *(.*) *\\] *");
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
                case "Y":
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
            } catch (NumberFormatException ignore) {
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
            } catch (NumberFormatException ignore) {
            }
        }
        return 0;
    }

    /**
     * Convert object to long.
     *
     * @param o object to convert.
     * @return the resulting long value.
     */
    public static long toLong(Object o) {
        if (o instanceof Topic) {
            o = ((Topic) o).getOnce();
        }
        if (o instanceof Boolean) {
            return (Boolean) o ? 1 : 0;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o != null) {
            try {
                return Long.parseLong(o.toString());
            } catch (NumberFormatException ignore) {
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
    @Nullable
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
        return SEPARATORS.split(body);
    }

    /**
     * Convert object to a list of strings.
     *
     * @param o object to convert.
     * @return resulting list.
     */
    public static List<String> toStringList(Object o) {
        return Arrays.asList(toStringArray(o));
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
        if (o == null) {
            return dflt;
        }
        if (o instanceof Topic) {
            o = ((Topic) o).getOnce();
            if (o == null) {
                return dflt;
            }
        }
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
        try {
            out.append(MAPPER.writeValueAsString(o) + '\n');
        } catch (JsonProcessingException e) {
            throw new IOException(e);
        }
    }

    /**
     * Convert a string to the appropriate Java object.
     *
     * @param s string to convert
     * @return resulting object or empty string if the input was null.
     * @throws JsonProcessingException if it failed to read the JSON.
     */
    public static Object toObject(String s) throws JsonProcessingException {
        if (isEmpty(s)) {
            return "";
        }
        return toObject(s, new TypeReference<Object>() {});
    }

    /**
     * Convert a string to the appropriate Java object.
     *
     * @param s string to convert
     * @param t type to convert to
     * @param <T> type
     * @return resulting object or empty string if the input was null.
     * @throws JsonProcessingException if it failed to read the JSON.
     */
    public static <T> T toObject(String s, TypeReference<T> t) throws JsonProcessingException {
        return MAPPER.readValue(s, t);
    }
}
