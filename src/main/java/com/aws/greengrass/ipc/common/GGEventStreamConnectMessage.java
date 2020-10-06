package com.aws.greengrass.ipc.common;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@JsonSerialize
@NoArgsConstructor
@AllArgsConstructor
public class GGEventStreamConnectMessage {
    String authToken;
}
