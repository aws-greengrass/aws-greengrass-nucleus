package com.aws.iot.evergreen.packagemanager.models;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.vdurmont.semver4j.Semver;

import java.io.IOException;

// Force semver to serialize as a single string to match recipe format
public class SemverSerializer extends JsonSerializer<Semver> {
    @Override
    public void serialize(Semver semver, JsonGenerator generator, SerializerProvider provider)
            throws IOException {
        generator.writeString(semver.toString());
    }
}
