package com.aws.greengrass.iot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class IotCloudResponse {
    private byte[] responseBody;
    private int statusCode;

    @Override
    public String toString() {
        return new String(responseBody, StandardCharsets.UTF_8);
    }
}
