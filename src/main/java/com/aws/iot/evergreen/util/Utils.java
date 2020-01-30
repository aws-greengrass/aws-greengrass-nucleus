/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.CharBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class Utils {
    public static final Path homePath = Paths.get(System.getProperty("user.home"));
    private static final char[] rsChars = "abcdefghjklmnpqrstuvwxyz0123456789".toCharArray();
    private static final char[] hex = "0123456789ABCDEF".toCharArray();
    private static SecureRandom random;

    private Utils() {
    }

    public static Throwable close(Object c) {
        if (c instanceof Closeable) {
            try {
                ((Closeable) c).close();
                return null;
            } catch (Throwable t) {
                return t;
            }
        } else {
            return null;
        }
    }

    public static Throwable flush(Object c) {
        if (c instanceof Flushable) {
            try {
                ((Flushable) c).flush();
                return null;
            } catch (Throwable t) {
                return t;
            }
        } else {
            return null;
        }
    }

    public static boolean isEmpty(String s) {
        if (s == null) {
            return true;
        }
        int len = s.length();
        for (int i = 0; i < len; i++) {
            if (!Character.isSpaceChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static String nullEmpty(String s) {
        return isEmpty(s) ? null : s;
    }

    public static boolean isEmpty(CharSequence s) {
        return s == null || s.length() == 0;
    }

    public static CharSequence nullEmpty(CharSequence s) {
        return s == null || s.length() == 0 ? null : s;
    }

    public static boolean isEmpty(Collection s) {
        return s == null || s.isEmpty();
    }

    public static <T extends Collection> T nullEmpty(T s) {
        return s == null || s.isEmpty() ? null : s;
    }

    public static Throwable getUltimateCause(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    public static String getUltimateMessage(Throwable t) {
        if (t == null) {
            return "No Error";
        }
        String msg = getUltimateCause(t).getMessage();
        return isEmpty(msg) ? t.toString() : msg;
    }

    public static String generateRandomString(int desiredLength) {
        SecureRandom r;
        if ((r = random) == null) {
            r = random = new SecureRandom();
        }
        StringBuilder sb = new StringBuilder(desiredLength);
        byte[] srb = new byte[desiredLength];
        r.nextBytes(srb);
        while (--desiredLength >= 0) {
            sb.append(rsChars[srb[desiredLength] & 31]);
        }
        return sb.toString();
    }

    public static Path homePath(String s) {
        return homePath.resolve(s);
    }

    public static String extension(String s) {
        if (s != null) {
            int dp = s.lastIndexOf('.');
            if (dp > s.lastIndexOf('/')) {
                return s.substring(dp + 1).toLowerCase();
            }
        }
        return "";
    }

    public static String namePart(String s) {
        return s == null ? "" : s.substring(s.lastIndexOf('/') + 1);
    }

    public static String deepToNonEmptyString(Object obj) {
        if (obj == null) {
            return "null";
        }
        String string = deepToString(obj).toString().trim();
        return string.isEmpty() ? "\"\"" : string;
    }

    public static CharSequence deepToString(Object o) {
        return deepToString(o, 40);
    }

    public static CharSequence deepToString(Object o, int len) {
        if (o instanceof CharSequence) {
            return (CharSequence) o;
        }
        StringBuilder sb = new StringBuilder();
        try {
            deepToString(o, sb, len);
        } catch (IOException ex) {
        }
        return sb;
    }

    public static String dequote(CharSequence cs) {
        final StringBuilder sb = new StringBuilder();
        int limit = cs.length();
        for (int i = 0; i < limit; i++) {
            char c = cs.charAt(i);
            if (c != '"') {
                if (c != '\\') {
                    sb.append(c);
                } else {
                    try {
                        switch (c = cs.charAt(++i)) {
                            default:
                                sb.append(c);
                                break;
                            case 'b':
                                sb.append('\b');
                                break;
                            case 'n':
                                sb.append('\n');
                                break;
                            case 'r':
                                sb.append('\r');
                                break;
                            case 't':
                                sb.append('\t');
                                break;
                            case 'u': {
                                sb.append((char) Utils.parseLongChecked(cs, i + 1, i + 5, 16));
                                i += 4;
                            }
                        }
                    } catch (Throwable t) {
                        break; // bogus string format: ignore quietly
                    }
                }
            }
        }
        return sb.toString();
    }

    public static int deepToStringQuoted(Object o, Appendable sb, int maxlen) throws IOException {
        if (o instanceof CharSequence) {
            sb.append('"');
            CharSequence s = (CharSequence) o;
            int l0 = s.length();
            int len = Math.min(l0, Math.max(5, maxlen - 10));
            int olen = 2;
            for (int i = 0; i < len; i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '\n':
                        sb.append("\\n");
                        olen += 2;
                        break;
                    case '\t':
                        sb.append("\\t");
                        olen += 2;
                        break;
                    default:
                        if (c < ' ' || c >= 0xFF || c == 0x7F || c == '"') {
                            sb.append("\\u");
                            appendHex(c, 4, sb);
                            olen += 6;
                        } else {
                            sb.append(c);
                            olen++;
                        }
                        break;
                }
            }
            if (len < l0) {
                sb.append("...");
                olen += 3;
            }
            sb.append('"');
            return olen;
        }
        return deepToString(o, sb, maxlen);
    }

    public static int deepToString(Object o, Appendable sb, int maxlen) throws IOException {
        int olen = 0;
        if (o == null) {
            sb.append("null");
            olen += 4;
        } else if (o.getClass().isArray()) {
            int len = Array.getLength(o);
            olen += 2;
            sb.append('[');
            for (int i = 0; i < len; i++) {
                if (olen >= maxlen) {
                    sb.append("...");
                    olen += 3;
                    break;
                }
                if (i > 0) {
                    sb.append(',');
                    olen++;
                }
                olen += deepToStringQuoted(Array.get(o, i), sb, maxlen - olen);
            }
            sb.append(']');
        } else if (o instanceof Map) {
            sb.append('{');
            olen += 2;
            int slot = 0;
            for (Map.Entry<?, ?> me : ((Map<?, ?>) o).entrySet()) {
                if (olen >= maxlen) {
                    sb.append("...");
                    olen += 3;
                    break;
                }
                if (slot++ != 0) {
                    sb.append(',');
                    olen++;
                }
                olen += deepToString(me.getKey(), sb, maxlen - olen);
                sb.append(':');
                olen++;
                olen += deepToStringQuoted(me.getValue(), sb, maxlen - olen);
            }
            sb.append('}');
        } else if (o instanceof Iterable && !(o instanceof Path)) {
            sb.append('[');
            olen += 2;
            int slot = 0;
            for (Object obj : (Iterable) o) {
                if (olen >= maxlen) {
                    sb.append("...");
                    olen += 3;
                    break;
                }
                if (slot++ != 0) {
                    sb.append(',');
                    olen++;
                }
                olen += deepToStringQuoted(obj, sb, maxlen - olen);
            }
            sb.append(']');
        } else {
            String s = o.toString();
            olen += s.length();
            sb.append(s);
        }
        return olen;
    }

    public static void appendHex(long v, int w, Appendable out) throws IOException {
        while (--w >= 0) {
            out.append(hex[(int) ((v >> (w << 2))) & 0xF]);
        }
    }

    public static void appendLong(long v, Appendable out) throws IOException {
        if (v < 0) {
            out.append('-');
            v = -v;
            if (v < 0) {  // only one number is its own negative
                out.append("9223372036854775808");
                return;
            }
        }
        if (v >= 10) {
            appendLong(v / 10, out);
        }
        out.append((char) ('0' + v % 10));
    }

    public static long parseLong(CharSequence str) {
        return str == null ? 0 : parseLong(CharBuffer.wrap(str));
    }

    public static long parseLong(CharSequence str, int pos, int limit) {
        return parseLong(CharBuffer.wrap(str, pos, limit));
    }

    public static long parseLong(CharSequence str, int pos, int limit, int radix) {
        return parseLong(CharBuffer.wrap(str, pos, limit), radix);
    }

    public static long parseLongChecked(CharSequence str, int pos, int limit, int radix) {
        CharBuffer buf = CharBuffer.wrap(str, pos, limit);
        long res = parseLong(buf, radix);
        if (buf.remaining() > 0) {
            throw new NumberFormatException("For input string \"" + str + "\"");
        }
        return res;
    }

    public static long parseLong(CharBuffer str) {
        long ret = 0;
        boolean neg = false;
        int radix = 10;
        char c;
        scanPrefix:
        while (true) {
            if (str.remaining() <= 0) {
                return 0;
            }
            c = str.get();
            switch (c) {
                case ' ':
                case '+':
                    break;
                case '-':
                    neg = !neg;
                    break;
                case '0':
                    radix = 8;
                    break;
                default:
                    if (radix == 8) {
                        switch (c) {
                            case 'x':
                            case 'X':
                                radix = 16;
                                break;
                            case 'b':
                            case 'B':
                                radix = 2;
                                break;
                            default:
                                str.position(str.position() - 1);
                        }
                    } else {
                        str.position(str.position() - 1);
                    }
                    break scanPrefix;
            }
        }
        ret = parseLong(str, radix);
        return neg ? -ret : ret;
    }

    public static long parseLong(CharBuffer str, int radix) {
        long ret = 0;
        while (str.remaining() > 0) {
            int digit;
            int c = str.get();
            if ('0' <= c && c <= '9') {
                digit = c - '0';
            } else if ('a' <= c && c <= 'f') {
                digit = c - 'a' + 10;
            } else if ('A' <= c && c <= 'F') {
                digit = c - 'A' + 10;
            } else {
                digit = 99;
            }
            if (digit >= radix) {
                str.position(str.position() - 1);
                break;
            }
            ret = ret * radix + digit;
        }
        return ret;
    }

    public static <K, V> Map<K, V> immutableMap(K k1, V v1, Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Expected even number of arguments");
        }
        HashMap<K, V> map = new HashMap<>();
        map.put(k1, v1);
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put((K) keyValuePairs[i], (V) keyValuePairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

}
