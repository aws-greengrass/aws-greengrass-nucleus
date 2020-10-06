package com.aws.greengrass.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import lombok.Getter;

public final class SerializerFactory {
    private static final ObjectMapper JSON_OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    @Getter
    private static final ObjectReader jsonObjectReader = JSON_OBJECT_MAPPER.reader();
    @Getter
    private static final ObjectWriter jsonObjectWriter = JSON_OBJECT_MAPPER.writer();

    private SerializerFactory() {
    }
}
