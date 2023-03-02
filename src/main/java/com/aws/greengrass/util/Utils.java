/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.config.PlatformResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.io.FilenameUtils;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.Buffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

@SuppressWarnings({"checkstyle:overloadmethodsdeclarationorder", "PMD.AssignmentInOperand"})
public final class Utils {
    public static final Path HOME_PATH = Paths.get(System.getProperty("user.home"));
    private static final char[] rsChars = "abcdefghjklmnpqrstuvwxyz0123456789".toCharArray();
    private static final char[] hex = "0123456789ABCDEF".toCharArray();
    private static final int OCTAL_RADIX = 8;
    private static final int BASE_10 = 10;
    private static final String TRUNCATED_STRING = "...";
    private static final String FILE_URL_PREFIX = "file://";
    private static final Map<Runnable, Boolean> onceMap = new ConcurrentHashMap<>();
    private static SecureRandom random;

    private Utils() {
    }

    /**
     * Tries to close an object if it can.
     *
     * @param closeable object to be closed.
     * @return error if any.
     */
    @SuppressWarnings({"PMD.UnnecessaryLocalBeforeReturn", "PMD.AvoidCatchingThrowable"})
    public static Throwable close(Object closeable) {
        if (closeable instanceof Closeable) {
            try {
                ((Closeable) closeable).close();
                return null;
            } catch (Throwable t) {
                return t;
            }
        } else {
            return null;
        }
    }

    /**
     * Tries to flush an object if it can.
     *
     * @param flushable object to be flushed.
     * @return error if any.
     */
    @SuppressWarnings({"PMD.UnnecessaryLocalBeforeReturn", "PMD.AvoidCatchingThrowable"})
    public static Throwable flush(Object flushable) {
        if (flushable instanceof Flushable) {
            try {
                ((Flushable) flushable).flush();
                return null;
            } catch (Throwable t) {
                return t;
            }
        } else {
            return null;
        }
    }

    /**
     * Returns true if the two strings are different, including <code>null</code>.
     *
     * <p>A change from <code>null</code> to <code>""</code>, or vice versa, is not considered a change.</p>
     *
     * @param oldValue first string
     * @param newValue second string
     * @return true if the two strings are different
     */
    public static boolean stringHasChanged(String oldValue, String newValue) {
        if (Utils.isEmpty(oldValue) && Utils.isEmpty(newValue)) {
            return false;
        }
        if ((oldValue == null) != (newValue == null)) {
            return true;
        }
        return !Objects.equals(oldValue, newValue);
    }

    /**
     * Returns true if the given string is null, empty, or only whitespace.
     *
     * @param s string to check.
     * @return true if it is null, empty, or only whitespace.
     */
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

    public static boolean isNotEmpty(String s) {
        return !isEmpty(s);
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

    public static boolean isEmpty(Collection<?> s) {
        return s == null || s.isEmpty();
    }

    public static <T extends Collection> T nullEmpty(T s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * Get the last cause in the chain of causes (the first cause which happened).
     *
     * @param t throwable to get the cause of.
     * @return Throwable the ultimate cause.
     */
    public static Throwable getUltimateCause(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /**
     * Get the last message from the chain of causes (the message of the first cause which happened).
     *
     * @param t throwable to get the cause of.
     * @return String message.
     */
    public static String getUltimateMessage(Throwable t) {
        if (t == null) {
            return "No Error";
        }
        String msg = getUltimateCause(t).getMessage();
        return isEmpty(msg) ? t.toString() : msg;
    }

    /**
     * Get the chain of exceptions and messages from the chain of causes.
     *
     * @param t throwable.
     * @return String chain of exceptions and messages.
     */
    public static String generateFailureMessage(Throwable t) {
        if (t == null) {
            return "No error, null throwable";
        }
        StringBuilder failureMessage = new StringBuilder(getMessageFromThrowable(t));
        Throwable temp = t;
        while (temp.getCause() != null) {
            temp = temp.getCause();
            failureMessage.append(". ").append(getMessageFromThrowable(temp));
        }
        return failureMessage.toString();
    }

    private static String getMessageFromThrowable(Throwable t) {
        if (isEmpty(t.getMessage())) {
            return t.getClass().getName();
        } else {
            return t.getMessage();
        }
    }

    /**
     * Generate a secure random string of a given length.
     *
     * @param desiredLength length of the output string
     * @return the random string
     */
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
        return HOME_PATH.resolve(s);
    }

    /**
     * Tries to pull the file extension from a path as a string.
     *
     * @param s the file path/name.
     * @return the extension (if any) or else the empty string.
     */
    public static String extension(String s) {
        return FilenameUtils.getExtension(s);
    }

    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification = "Spotbugs false positive")
    public static String namePart(String s) {
        return s == null ? "" : Paths.get(s).getFileName().toString();
    }

    public static CharSequence deepToString(Object o) {
        return deepToString(o, 40);
    }

    /**
     * Tries to convert an object into a string with a max length.
     *
     * @param o         object to convert to a string.
     * @param maxLength maximum length of the returned string.
     * @return string representation of the given object.
     */
    public static CharSequence deepToString(Object o, int maxLength) {
        if (o instanceof CharSequence) {
            return (CharSequence) o;
        }
        StringBuilder sb = new StringBuilder();
        try {
            deepToString(o, sb, maxLength);
        } catch (IOException ignore) {
        }
        return sb;
    }

    /**
     * Un-JSON-encode a string by stripping quotes and escape characters.
     *
     * @param cs JSON encoded string
     * @return unencoded string
     */
    public static String dequote(CharSequence cs) {
        final StringBuilder sb = new StringBuilder();
        int limit = cs.length();
        for (int i = 0; i < limit; i++) {
            char c = cs.charAt(i);
            if (c != '"') {
                if (c == '\\') {
                    try {
                        switch (c = cs.charAt(++i)) {
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
                            case 'u':
                                sb.append((char) Utils.parseLongChecked(cs, i + 1, i + 5, 16));
                                i += 4;
                                break;
                            default:
                                sb.append(c);
                                break;
                        }
                    } catch (NumberFormatException t) {
                        break; // bogus string format: ignore quietly
                    }
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Same as deepToString, but output string as JSON encoded, escaping
     * special characters.
     *
     * @param o         object to encode.
     * @param sb        Appendable object to write the output to.
     * @param maxLength maximum length of the output string.
     * @return output length.
     * @throws IOException if the append fails.
     */
    public static int deepToStringQuoted(Object o, Appendable sb, int maxLength) throws IOException {
        if (o instanceof CharSequence) {
            sb.append('"');
            CharSequence s = (CharSequence) o;
            int l0 = s.length();
            int len = Math.min(l0, Math.max(5, maxLength - 10));
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
                sb.append(TRUNCATED_STRING);
                olen += 3;
            }
            sb.append('"');
            return olen;
        }
        return deepToString(o, sb, maxLength);
    }

    /**
     * Convert an object to a string representation for human readability.
     *
     * @param o         object to convert to string.
     * @param sb        Appendable to write the string into.
     * @param maxLength maximum length of the output.
     * @return actual output length.
     * @throws IOException if the append fails.
     */
    public static int deepToString(Object o, Appendable sb, int maxLength) throws IOException {
        int olen = 0;
        if (o == null) {
            sb.append("null");
            olen += 4;
        } else if (o.getClass().isArray()) {
            int len = Array.getLength(o);
            olen += 2;
            sb.append('[');
            for (int i = 0; i < len; i++) {
                if (olen >= maxLength) {
                    sb.append(TRUNCATED_STRING);
                    olen += 3;
                    break;
                }
                if (i > 0) {
                    sb.append(',');
                    olen++;
                }
                olen += deepToStringQuoted(Array.get(o, i), sb, maxLength - olen);
            }
            sb.append(']');
        } else if (o instanceof Map) {
            sb.append('{');
            olen += 2;
            int slot = 0;
            for (Map.Entry<?, ?> me : ((Map<?, ?>) o).entrySet()) {
                if (olen >= maxLength) {
                    sb.append(TRUNCATED_STRING);
                    olen += 3;
                    break;
                }
                if (slot++ != 0) {
                    sb.append(',');
                    olen++;
                }
                olen += deepToString(me.getKey(), sb, maxLength - olen);
                sb.append(':');
                olen++;
                olen += deepToStringQuoted(me.getValue(), sb, maxLength - olen);
            }
            sb.append('}');
        } else if (o instanceof Iterable && !(o instanceof Path)) {
            sb.append('[');
            olen += 2;
            int slot = 0;
            for (Object obj : (Iterable) o) {
                if (olen >= maxLength) {
                    sb.append(TRUNCATED_STRING);
                    olen += 3;
                    break;
                }
                if (slot++ != 0) {
                    sb.append(',');
                    olen++;
                }
                olen += deepToStringQuoted(obj, sb, maxLength - olen);
            }
            sb.append(']');
        } else {
            String s = o.toString();
            olen += s.length();
            sb.append(s);
        }
        return olen;
    }

    /**
     * Write value to out as hexadecimal of the provided width.
     *
     * @param value value to write as hex.
     * @param width number of hex characters required in the output.
     * @param out   Appendable to write to.
     * @throws IOException if the append fails.
     */
    public static void appendHex(long value, int width, Appendable out) throws IOException {
        while (--width >= 0) {
            out.append(hex[(int) ((value >> (width << 2))) & 0xF]);
        }
    }

    /**
     * Write the given long to the appendable.
     *
     * @param value value to write to the appendable.
     * @param out   Appendable.
     * @throws IOException if the append fails.
     */
    public static void appendLong(long value, Appendable out) throws IOException {
        if (value < 0) {
            out.append('-');
            value = -value;
            if (value < 0) {  // only one number is its own negative
                out.append("9223372036854775808");
                return;
            }
        }
        if (value >= BASE_10) {
            appendLong(value / BASE_10, out);
        }
        out.append((char) ('0' + value % BASE_10));
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

    /**
     * Parse an input string as a long. Throws NumberFormatException if the input
     * is not a long.
     *
     * @param str   input string.
     * @param pos   starting position in the string.
     * @param limit stopping position in the string.
     * @param radix the base of the long.
     * @return the parsed long.
     * @throws NumberFormatException if the input string does not represent a long.
     */
    @SuppressWarnings("PMD.PrematureDeclaration")
    public static long parseLongChecked(CharSequence str, int pos, int limit, int radix) {
        CharBuffer buf = CharBuffer.wrap(str, pos, limit);
        long res = parseLong(buf, radix);
        if (buf.remaining() > 0) {
            throw new NumberFormatException("For input string \"" + str + "\"");
        }
        return res;
    }

    /**
     * Parse the given string into a long.
     *
     * @param str input string.
     * @return long value from the string.
     */
    public static long parseLong(CharBuffer str) {
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
                    if (radix == OCTAL_RADIX) {
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
                                // Stupid cast for jdk 9+
                                ((Buffer) str).position(str.position() - 1);
                                break;
                        }
                    } else {
                        // Stupid cast for jdk 9+
                        ((Buffer) str).position(str.position() - 1);
                    }
                    break scanPrefix;
            }
        }
        long ret = parseLong(str, radix);
        return neg ? -ret : ret;
    }

    /**
     * Parse long from string with a given base.
     *
     * @param str   input string.
     * @param radix base of the long.
     * @return resulting long.
     */
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
                // Stupid cast for jdk 9+
                ((Buffer) str).position(str.position() - 1);
                break;
            }
            ret = ret * radix + digit;
        }
        return ret;
    }

    /**
     * Make an immutable map from provided keys and values.
     *
     * @param k1            first key
     * @param v1            first value
     * @param keyValuePairs remaining keys and values
     * @param <K>           Map key type
     * @param <V>           Map value type
     * @return immutable map with the provided key-values
     * @throws IllegalArgumentException if the key-value pairs are not evenly matched
     */
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

    /**
     * Create all paths.
     *
     * @param paths paths to create
     * @throws IOException if path creation fails
     */
    public static void createPaths(Path... paths) throws IOException {
        for (Path p : paths) {
            if (p.toFile().exists()) {
                continue;
            }
            if (PlatformResolver.isWindows) {
                Files.createDirectories(p);
            } else {
                // This only supports POSIX compliant file permission right now. We will need to
                // change this when trying to support Greengrass in Non-POSIX OS.
                Files.createDirectories(p,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
            }
        }
    }

    /**
     * Clean up a file or a directory recursively.
     *
     * @param filePath path to the file
     * @throws IOException if deletion fails
     */
    public static void deleteFileRecursively(File filePath) throws IOException {
        File[] files = filePath.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteFileRecursively(file);
            }
        }
        Files.deleteIfExists(filePath.toPath());
    }

    /**
     * Flip a Map from key->value to value->List(key).
     *
     * @param sourceMap map to flip
     * @param <K> key type
     * @param <V> value type
     * @return inverted map
     */
    public static <K, V> Map<V, List<K>> inverseMap(Map<K, V> sourceMap) {
        return sourceMap.entrySet().stream().collect(Collectors
                .toMap(Map.Entry::getValue, t -> new ArrayList<>(Collections.singletonList(t.getKey())), (a, b) -> {
                    a.addAll(b);
                    return a;
                }));
    }

    /**
     * Read InputStream to a string.
     * @param is input stream
     * @return string or null if there was an exception
     */
    public static String inputStreamToString(InputStream is) {
        try (InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
             BufferedReader sr = new BufferedReader(isr)) {
            return sr.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return null;
        }
    }

    public static void copyFolderRecursively(Path src, Path des, CopyOption... options) throws IOException {
        copyFolderRecursively(src, des, null, options);
    }

    /**
     * Copy directory tree recursively.
     *
     * @param src            source path
     * @param des            destination path
     * @param shouldCopyFile function called for each path to determine if the copy should be attempted
     * @param options        options specifying how the copy should be done
     * @throws IOException on I/O error
     */
    public static void copyFolderRecursively(Path src, Path des, BiFunction<Path, Path, Boolean> shouldCopyFile,
                                             CopyOption... options) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Utils.createPaths(des.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (shouldCopyFile == null || shouldCopyFile.apply(file, des.resolve(src.relativize(file)))) {
                    Files.copy(file, des.resolve(src.relativize(file)), options);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * CLI params may support file URI. Detect the file URL prefix and read the file content if needed
     * Reference: https://docs.aws.amazon.com/cli/latest/userguide/cli-usage-parameters-file.html
     * @param param a value or a file URL
     * @return the same value or the file content if param is a file URI
     * @throws URISyntaxException if provided file URI has syntax error
     * @throws IOException on I/O Error
     */
    public static String loadParamMaybeFile(String param) throws URISyntaxException, IOException {
        return param.startsWith(FILE_URL_PREFIX) ? new String(Files.readAllBytes(Paths.get(new URI(param))),
                StandardCharsets.UTF_8) : param;
    }

    /**
     * Ensures runnable will be run only once.
     * @param r runnable to be closed.
     */
    public static void once(Runnable r) {
        onceMap.computeIfAbsent(r, (k) -> {
            r.run();
            return true;
        });
    }

    /**
     * Truncate given string to given length.
     *
     * @param s string to truncate.
     * @param l desired length.
     * @return truncated string, or original string if length is greater than string length.
     */
    public static String truncate(String s, int l) {
        return s.substring(0, Math.min(s.length(), l));
    }
}
