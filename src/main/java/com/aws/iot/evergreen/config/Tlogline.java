package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.util.Coerce;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.IOException;
import java.io.Writer;
import java.util.regex.Pattern;

import static com.aws.iot.evergreen.util.Utils.appendLong;
import static com.aws.iot.evergreen.util.Utils.parseLong;

@AllArgsConstructor
@Getter
class Tlogline {
    private static final Pattern logLine =
            Pattern.compile("^([0-9]+),(\\[.*]),(changed|removed),([^\n]*)\n*$", Pattern.DOTALL);

    long timestamp;
    String[] topicPath;
    WhatHappened action;
    Object value;

    static class InvalidLogException extends Exception {
        static final long serialVersionUID = -3387516993124229948L;

        InvalidLogException(String message) {
            super(message);
        }
    }

    void outputTo(Writer out) throws IOException {
        appendLong(timestamp, out);
        out.append(',');
        Coerce.appendParseableString(topicPath, out);
        out.append(',');
        out.append(this.action.toString());
        out.append(',');
        Coerce.appendParseableString(this.value, out);
        out.append('\n');
    }

    static Tlogline fromStringInput(String l) throws InvalidLogException {
        if (l == null) {
            throw new InvalidLogException("logline can't be null");
        }
        java.util.regex.Matcher m = logLine.matcher(l);
        if (!m.matches()) {
            throw new InvalidLogException("unrecognized log format: " + l);
        }
        long timestamp = parseLong(m.group(1));
        String topicString = m.group(2);
        WhatHappened action = WhatHappened.valueOf(m.group(3).toLowerCase());

        Object value = null;
        if (action == WhatHappened.changed) {
            value = Coerce.toObject(m.group(4));
        }

        return new Tlogline(timestamp, Coerce.toStringArray(topicString), action, value);
    }

}
