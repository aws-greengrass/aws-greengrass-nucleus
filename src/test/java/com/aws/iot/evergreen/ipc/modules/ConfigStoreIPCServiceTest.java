/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.ipc.modules;

import com.aws.iot.evergreen.builtin.services.configstore.ConfigStoreIPCAgent;
import com.aws.iot.evergreen.ipc.ConnectionContext;
import com.aws.iot.evergreen.ipc.IPCRouter;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.services.common.ApplicationMessage;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreClientOpCodes;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreGenericResponse;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreResponseStatus;
import com.aws.iot.evergreen.ipc.services.configstore.GetConfigurationRequest;
import com.aws.iot.evergreen.ipc.services.configstore.GetConfigurationResponse;
import com.aws.iot.evergreen.ipc.services.configstore.SendConfigurationValidityReportRequest;
import com.aws.iot.evergreen.ipc.services.configstore.SendConfigurationValidityReportResponse;
import com.aws.iot.evergreen.ipc.services.configstore.SubscribeToConfigurationUpdateRequest;
import com.aws.iot.evergreen.ipc.services.configstore.SubscribeToConfigurationUpdateResponse;
import com.aws.iot.evergreen.ipc.services.configstore.SubscribeToValidateConfigurationResponse;
import com.aws.iot.evergreen.ipc.services.configstore.UpdateConfigurationRequest;
import com.aws.iot.evergreen.ipc.services.configstore.UpdateConfigurationResponse;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetSocketAddress;

import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class ConfigStoreIPCServiceTest {
    private static final ObjectMapper CBOR_MAPPER = new CBORMapper();

    @Mock
    private IPCRouter router;

    @Mock
    private ConfigStoreIPCAgent agent;

    private ConnectionContext connectionContext;

    private ConfigStoreIPCService configStoreIPCService;

    @BeforeEach
    public void setup() {
        configStoreIPCService = new ConfigStoreIPCService(router, agent);
        configStoreIPCService.startup();

        connectionContext = new ConnectionContext("ServiceA", new InetSocketAddress(1), router);
    }

    @Test
    public void GIVEN_server_running_WHEN_get_config_request_THEN_get_config_response() throws Exception {
        GetConfigurationRequest request = GetConfigurationRequest.builder().build();
        when(agent.getConfig(any(GetConfigurationRequest.class), any(ConnectionContext.class)))
                .thenReturn(GetConfigurationResponse.builder().build());
        configStoreIPCService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(ConfigStoreClientOpCodes.GET_CONFIG.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        verify(agent).getConfig(request, connectionContext);
    }

    @Test
    public void GIVEN_server_running_WHEN_update_config_request_THEN_update_config_response() throws Exception {
        UpdateConfigurationRequest request = UpdateConfigurationRequest.builder().build();
        when(agent.updateConfig(any(UpdateConfigurationRequest.class), any(ConnectionContext.class)))
                .thenReturn(UpdateConfigurationResponse.builder().build());
        configStoreIPCService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(ConfigStoreClientOpCodes.UPDATE_CONFIG.ordinal())
                        .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext)
                .get();
        verify(agent).updateConfig(request, connectionContext);
    }

    @Test
    public void GIVEN_server_running_WHEN_subscribe_to_config_update_request_THEN_subscribe_to_config_update_response()
            throws Exception {
        SubscribeToConfigurationUpdateRequest request = SubscribeToConfigurationUpdateRequest.builder().build();
        when(agent.subscribeToConfigUpdate(any(SubscribeToConfigurationUpdateRequest.class),
                any(ConnectionContext.class))).thenReturn(SubscribeToConfigurationUpdateResponse.builder().build());
        configStoreIPCService.handleMessage(new FrameReader.Message(ApplicationMessage.builder().version(1)
                .opCode(ConfigStoreClientOpCodes.SUBSCRIBE_TO_ALL_CONFIG_UPDATES.ordinal())
                .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext).get();
        verify(agent).subscribeToConfigUpdate(request, connectionContext);
    }

    @Test
    public void GIVEN_server_running_WHEN_subscribe_to_validate_config_request_THEN_subscribe_to_validate_config_response()
            throws Exception {
        when(agent.subscribeToConfigValidation(any(ConnectionContext.class)))
                .thenReturn(SubscribeToValidateConfigurationResponse.builder().build());
        configStoreIPCService.handleMessage(new FrameReader.Message(ApplicationMessage.builder().version(1)
                .opCode(ConfigStoreClientOpCodes.SUBSCRIBE_TO_CONFIG_VALIDATION.ordinal()).payload("random".getBytes())
                .build().toByteArray()), connectionContext).get();
        verify(agent).subscribeToConfigValidation(connectionContext);
    }

    @Test
    public void GIVEN_server_running_WHEN_send_config_validity_report_request_THEN_send_config_validity_report_response()
            throws Exception {
        SendConfigurationValidityReportRequest request = SendConfigurationValidityReportRequest.builder().build();
        when(agent.handleConfigValidityReport(any(SendConfigurationValidityReportRequest.class),
                any(ConnectionContext.class))).thenReturn(SendConfigurationValidityReportResponse.builder().build());
        configStoreIPCService.handleMessage(new FrameReader.Message(ApplicationMessage.builder().version(1)
                .opCode(ConfigStoreClientOpCodes.SEND_CONFIG_VALIDATION_REPORT.ordinal())
                .payload(CBOR_MAPPER.writeValueAsBytes(request)).build().toByteArray()), connectionContext).get();
        verify(agent).handleConfigValidityReport(request, connectionContext);
    }

    @Test
    public void GIVEN_server_running_WHEN_error_processing_THEN_service_error_response(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, JsonEOFException.class);
        FrameReader.Message responseMessage = configStoreIPCService.handleMessage(new FrameReader.Message(
                ApplicationMessage.builder().version(1).opCode(ConfigStoreClientOpCodes.GET_CONFIG.ordinal())
                        .payload("random".getBytes()).build().toByteArray()), connectionContext).get();
        ConfigStoreGenericResponse response = CBOR_MAPPER
                .readValue(ApplicationMessage.fromBytes(responseMessage.getPayload()).getPayload(),
                        ConfigStoreGenericResponse.class);
        assertEquals(ConfigStoreResponseStatus.InternalError, response.getStatus());
    }
}
