package com.aws.iot.evergreen.iot.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Setter
public class IotCloudResponse {
    private String responseBody;
    private int statusCode;

}
