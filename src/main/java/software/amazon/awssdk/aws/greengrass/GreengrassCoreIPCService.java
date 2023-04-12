/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCServiceHandler;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCServiceModel;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class GreengrassCoreIPCService extends EventStreamRPCServiceHandler {
  public static final String SERVICE_NAMESPACE = "aws.greengrass";

  protected static final Set<String> SERVICE_OPERATION_SET;

  public static final String SUBSCRIBE_TO_IOT_CORE = SERVICE_NAMESPACE + "#SubscribeToIoTCore";

  public static final String RESUME_COMPONENT = SERVICE_NAMESPACE + "#ResumeComponent";

  public static final String PUBLISH_TO_IOT_CORE = SERVICE_NAMESPACE + "#PublishToIoTCore";

  public static final String SUBSCRIBE_TO_CONFIGURATION_UPDATE = SERVICE_NAMESPACE + "#SubscribeToConfigurationUpdate";

  public static final String DELETE_THING_SHADOW = SERVICE_NAMESPACE + "#DeleteThingShadow";

  public static final String PUT_COMPONENT_METRIC = SERVICE_NAMESPACE + "#PutComponentMetric";

  public static final String RETRIEVE_SHARED_LOCK = SERVICE_NAMESPACE + "#RetrieveSharedLock";

  public static final String EXTEND_SHARED_LOCK = SERVICE_NAMESPACE + "#ExtendSharedLock";

  public static final String DEFER_COMPONENT_UPDATE = SERVICE_NAMESPACE + "#DeferComponentUpdate";

  public static final String SUBSCRIBE_TO_VALIDATE_CONFIGURATION_UPDATES = SERVICE_NAMESPACE + "#SubscribeToValidateConfigurationUpdates";

  public static final String GET_CONFIGURATION = SERVICE_NAMESPACE + "#GetConfiguration";

  public static final String SUBSCRIBE_TO_TOPIC = SERVICE_NAMESPACE + "#SubscribeToTopic";

  public static final String GET_COMPONENT_DETAILS = SERVICE_NAMESPACE + "#GetComponentDetails";

  public static final String GET_CLIENT_DEVICE_AUTH_TOKEN = SERVICE_NAMESPACE + "#GetClientDeviceAuthToken";

  public static final String RETRIEVE_SHARED_PROPERTY = SERVICE_NAMESPACE + "#RetrieveSharedProperty";

  public static final String PUBLISH_TO_TOPIC = SERVICE_NAMESPACE + "#PublishToTopic";

  public static final String SUBSCRIBE_TO_CERTIFICATE_UPDATES = SERVICE_NAMESPACE + "#SubscribeToCertificateUpdates";

  public static final String VERIFY_CLIENT_DEVICE_IDENTITY = SERVICE_NAMESPACE + "#VerifyClientDeviceIdentity";

  public static final String CREATE_SHARED_LOCK = SERVICE_NAMESPACE + "#CreateSharedLock";

  public static final String AUTHORIZE_CLIENT_DEVICE_ACTION = SERVICE_NAMESPACE + "#AuthorizeClientDeviceAction";

  public static final String LIST_COMPONENTS = SERVICE_NAMESPACE + "#ListComponents";

  public static final String CREATE_DEBUG_PASSWORD = SERVICE_NAMESPACE + "#CreateDebugPassword";

  public static final String GET_THING_SHADOW = SERVICE_NAMESPACE + "#GetThingShadow";

  public static final String UPDATE_CLUSTER_STATE = SERVICE_NAMESPACE + "#UpdateClusterState";

  public static final String SEND_CONFIGURATION_VALIDITY_REPORT = SERVICE_NAMESPACE + "#SendConfigurationValidityReport";

  public static final String UPDATE_THING_SHADOW = SERVICE_NAMESPACE + "#UpdateThingShadow";

  public static final String UPDATE_CONFIGURATION = SERVICE_NAMESPACE + "#UpdateConfiguration";

  public static final String VALIDATE_AUTHORIZATION_TOKEN = SERVICE_NAMESPACE + "#ValidateAuthorizationToken";

  public static final String SUBSCRIBE_TO_CLUSTER_STATE_EVENTS = SERVICE_NAMESPACE + "#SubscribeToClusterStateEvents";

  public static final String RESTART_COMPONENT = SERVICE_NAMESPACE + "#RestartComponent";

  public static final String GET_LOCAL_DEPLOYMENT_STATUS = SERVICE_NAMESPACE + "#GetLocalDeploymentStatus";

  public static final String GET_SECRET_VALUE = SERVICE_NAMESPACE + "#GetSecretValue";

  public static final String QUERY_SHARED_PROPERTIES = SERVICE_NAMESPACE + "#QuerySharedProperties";

  public static final String UNLOCK_SHARED_PROPERTY = SERVICE_NAMESPACE + "#UnlockSharedProperty";

  public static final String UPDATE_STATE = SERVICE_NAMESPACE + "#UpdateState";

  public static final String PUBLISH_SHARED_PROPERTY = SERVICE_NAMESPACE + "#PublishSharedProperty";

  public static final String LIST_NAMED_SHADOWS_FOR_THING = SERVICE_NAMESPACE + "#ListNamedShadowsForThing";

  public static final String DELETE_SHARED_PROPERTY = SERVICE_NAMESPACE + "#DeleteSharedProperty";

  public static final String SUBSCRIBE_TO_COMPONENT_UPDATES = SERVICE_NAMESPACE + "#SubscribeToComponentUpdates";

  public static final String LIST_LOCAL_DEPLOYMENTS = SERVICE_NAMESPACE + "#ListLocalDeployments";

  public static final String STOP_COMPONENT = SERVICE_NAMESPACE + "#StopComponent";

  public static final String PAUSE_COMPONENT = SERVICE_NAMESPACE + "#PauseComponent";

  public static final String CREATE_LOCAL_DEPLOYMENT = SERVICE_NAMESPACE + "#CreateLocalDeployment";

  public static final String CANCEL_LOCAL_DEPLOYMENT = SERVICE_NAMESPACE + "#CancelLocalDeployment";

  static {
    SERVICE_OPERATION_SET = new HashSet();
    SERVICE_OPERATION_SET.add(SUBSCRIBE_TO_IOT_CORE);
    SERVICE_OPERATION_SET.add(RESUME_COMPONENT);
    SERVICE_OPERATION_SET.add(PUBLISH_TO_IOT_CORE);
    SERVICE_OPERATION_SET.add(SUBSCRIBE_TO_CONFIGURATION_UPDATE);
    SERVICE_OPERATION_SET.add(DELETE_THING_SHADOW);
    SERVICE_OPERATION_SET.add(PUT_COMPONENT_METRIC);
    SERVICE_OPERATION_SET.add(RETRIEVE_SHARED_LOCK);
    SERVICE_OPERATION_SET.add(EXTEND_SHARED_LOCK);
    SERVICE_OPERATION_SET.add(DEFER_COMPONENT_UPDATE);
    SERVICE_OPERATION_SET.add(SUBSCRIBE_TO_VALIDATE_CONFIGURATION_UPDATES);
    SERVICE_OPERATION_SET.add(GET_CONFIGURATION);
    SERVICE_OPERATION_SET.add(SUBSCRIBE_TO_TOPIC);
    SERVICE_OPERATION_SET.add(GET_COMPONENT_DETAILS);
    SERVICE_OPERATION_SET.add(GET_CLIENT_DEVICE_AUTH_TOKEN);
    SERVICE_OPERATION_SET.add(RETRIEVE_SHARED_PROPERTY);
    SERVICE_OPERATION_SET.add(PUBLISH_TO_TOPIC);
    SERVICE_OPERATION_SET.add(SUBSCRIBE_TO_CERTIFICATE_UPDATES);
    SERVICE_OPERATION_SET.add(VERIFY_CLIENT_DEVICE_IDENTITY);
    SERVICE_OPERATION_SET.add(CREATE_SHARED_LOCK);
    SERVICE_OPERATION_SET.add(AUTHORIZE_CLIENT_DEVICE_ACTION);
    SERVICE_OPERATION_SET.add(LIST_COMPONENTS);
    SERVICE_OPERATION_SET.add(CREATE_DEBUG_PASSWORD);
    SERVICE_OPERATION_SET.add(GET_THING_SHADOW);
    SERVICE_OPERATION_SET.add(UPDATE_CLUSTER_STATE);
    SERVICE_OPERATION_SET.add(SEND_CONFIGURATION_VALIDITY_REPORT);
    SERVICE_OPERATION_SET.add(UPDATE_THING_SHADOW);
    SERVICE_OPERATION_SET.add(UPDATE_CONFIGURATION);
    SERVICE_OPERATION_SET.add(VALIDATE_AUTHORIZATION_TOKEN);
    SERVICE_OPERATION_SET.add(SUBSCRIBE_TO_CLUSTER_STATE_EVENTS);
    SERVICE_OPERATION_SET.add(RESTART_COMPONENT);
    SERVICE_OPERATION_SET.add(GET_LOCAL_DEPLOYMENT_STATUS);
    SERVICE_OPERATION_SET.add(GET_SECRET_VALUE);
    SERVICE_OPERATION_SET.add(QUERY_SHARED_PROPERTIES);
    SERVICE_OPERATION_SET.add(UNLOCK_SHARED_PROPERTY);
    SERVICE_OPERATION_SET.add(UPDATE_STATE);
    SERVICE_OPERATION_SET.add(PUBLISH_SHARED_PROPERTY);
    SERVICE_OPERATION_SET.add(LIST_NAMED_SHADOWS_FOR_THING);
    SERVICE_OPERATION_SET.add(DELETE_SHARED_PROPERTY);
    SERVICE_OPERATION_SET.add(SUBSCRIBE_TO_COMPONENT_UPDATES);
    SERVICE_OPERATION_SET.add(LIST_LOCAL_DEPLOYMENTS);
    SERVICE_OPERATION_SET.add(STOP_COMPONENT);
    SERVICE_OPERATION_SET.add(PAUSE_COMPONENT);
    SERVICE_OPERATION_SET.add(CREATE_LOCAL_DEPLOYMENT);
  }

  private final Map<String, Function<OperationContinuationHandlerContext, ? extends ServerConnectionContinuationHandler>> operationSupplierMap;

  public GreengrassCoreIPCService() {
    this.operationSupplierMap = new HashMap();
  }

  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  public void setSubscribeToIoTCoreHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractSubscribeToIoTCoreOperationHandler> handler) {
    operationSupplierMap.put(SUBSCRIBE_TO_IOT_CORE, handler);
  }

  public void setResumeComponentHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractResumeComponentOperationHandler> handler) {
    operationSupplierMap.put(RESUME_COMPONENT, handler);
  }

  public void setPublishToIoTCoreHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractPublishToIoTCoreOperationHandler> handler) {
    operationSupplierMap.put(PUBLISH_TO_IOT_CORE, handler);
  }

  public void setSubscribeToConfigurationUpdateHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractSubscribeToConfigurationUpdateOperationHandler> handler) {
    operationSupplierMap.put(SUBSCRIBE_TO_CONFIGURATION_UPDATE, handler);
  }

  public void setDeleteThingShadowHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractDeleteThingShadowOperationHandler> handler) {
    operationSupplierMap.put(DELETE_THING_SHADOW, handler);
  }

  public void setPutComponentMetricHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractPutComponentMetricOperationHandler> handler) {
    operationSupplierMap.put(PUT_COMPONENT_METRIC, handler);
  }

  public void setRetrieveSharedLockHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractRetrieveSharedLockOperationHandler> handler) {
    operationSupplierMap.put(RETRIEVE_SHARED_LOCK, handler);
  }

  public void setExtendSharedLockHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractExtendSharedLockOperationHandler> handler) {
    operationSupplierMap.put(EXTEND_SHARED_LOCK, handler);
  }

  public void setDeferComponentUpdateHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractDeferComponentUpdateOperationHandler> handler) {
    operationSupplierMap.put(DEFER_COMPONENT_UPDATE, handler);
  }

  public void setSubscribeToValidateConfigurationUpdatesHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractSubscribeToValidateConfigurationUpdatesOperationHandler> handler) {
    operationSupplierMap.put(SUBSCRIBE_TO_VALIDATE_CONFIGURATION_UPDATES, handler);
  }

  public void setGetConfigurationHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractGetConfigurationOperationHandler> handler) {
    operationSupplierMap.put(GET_CONFIGURATION, handler);
  }

  public void setSubscribeToTopicHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractSubscribeToTopicOperationHandler> handler) {
    operationSupplierMap.put(SUBSCRIBE_TO_TOPIC, handler);
  }

  public void setGetComponentDetailsHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractGetComponentDetailsOperationHandler> handler) {
    operationSupplierMap.put(GET_COMPONENT_DETAILS, handler);
  }

  public void setGetClientDeviceAuthTokenHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractGetClientDeviceAuthTokenOperationHandler> handler) {
    operationSupplierMap.put(GET_CLIENT_DEVICE_AUTH_TOKEN, handler);
  }

  public void setRetrieveSharedPropertyHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractRetrieveSharedPropertyOperationHandler> handler) {
    operationSupplierMap.put(RETRIEVE_SHARED_PROPERTY, handler);
  }

  public void setPublishToTopicHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractPublishToTopicOperationHandler> handler) {
    operationSupplierMap.put(PUBLISH_TO_TOPIC, handler);
  }

  public void setSubscribeToCertificateUpdatesHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractSubscribeToCertificateUpdatesOperationHandler> handler) {
    operationSupplierMap.put(SUBSCRIBE_TO_CERTIFICATE_UPDATES, handler);
  }

  public void setVerifyClientDeviceIdentityHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractVerifyClientDeviceIdentityOperationHandler> handler) {
    operationSupplierMap.put(VERIFY_CLIENT_DEVICE_IDENTITY, handler);
  }

  public void setCreateSharedLockHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractCreateSharedLockOperationHandler> handler) {
    operationSupplierMap.put(CREATE_SHARED_LOCK, handler);
  }

  public void setAuthorizeClientDeviceActionHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractAuthorizeClientDeviceActionOperationHandler> handler) {
    operationSupplierMap.put(AUTHORIZE_CLIENT_DEVICE_ACTION, handler);
  }

  public void setListComponentsHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractListComponentsOperationHandler> handler) {
    operationSupplierMap.put(LIST_COMPONENTS, handler);
  }

  public void setCreateDebugPasswordHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractCreateDebugPasswordOperationHandler> handler) {
    operationSupplierMap.put(CREATE_DEBUG_PASSWORD, handler);
  }

  public void setGetThingShadowHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractGetThingShadowOperationHandler> handler) {
    operationSupplierMap.put(GET_THING_SHADOW, handler);
  }

  public void setUpdateClusterStateHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractUpdateClusterStateOperationHandler> handler) {
    operationSupplierMap.put(UPDATE_CLUSTER_STATE, handler);
  }

  public void setSendConfigurationValidityReportHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractSendConfigurationValidityReportOperationHandler> handler) {
    operationSupplierMap.put(SEND_CONFIGURATION_VALIDITY_REPORT, handler);
  }

  public void setUpdateThingShadowHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractUpdateThingShadowOperationHandler> handler) {
    operationSupplierMap.put(UPDATE_THING_SHADOW, handler);
  }

  public void setUpdateConfigurationHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractUpdateConfigurationOperationHandler> handler) {
    operationSupplierMap.put(UPDATE_CONFIGURATION, handler);
  }

  public void setValidateAuthorizationTokenHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractValidateAuthorizationTokenOperationHandler> handler) {
    operationSupplierMap.put(VALIDATE_AUTHORIZATION_TOKEN, handler);
  }

  public void setSubscribeToClusterStateEventsHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractSubscribeToClusterStateEventsOperationHandler> handler) {
    operationSupplierMap.put(SUBSCRIBE_TO_CLUSTER_STATE_EVENTS, handler);
  }

  public void setRestartComponentHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractRestartComponentOperationHandler> handler) {
    operationSupplierMap.put(RESTART_COMPONENT, handler);
  }

  public void setGetLocalDeploymentStatusHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractGetLocalDeploymentStatusOperationHandler> handler) {
    operationSupplierMap.put(GET_LOCAL_DEPLOYMENT_STATUS, handler);
  }

  public void setGetSecretValueHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractGetSecretValueOperationHandler> handler) {
    operationSupplierMap.put(GET_SECRET_VALUE, handler);
  }

  public void setQuerySharedPropertiesHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractQuerySharedPropertiesOperationHandler> handler) {
    operationSupplierMap.put(QUERY_SHARED_PROPERTIES, handler);
  }

  public void setUnlockSharedPropertyHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractUnlockSharedPropertyOperationHandler> handler) {
    operationSupplierMap.put(UNLOCK_SHARED_PROPERTY, handler);
  }

  public void setUpdateStateHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractUpdateStateOperationHandler> handler) {
    operationSupplierMap.put(UPDATE_STATE, handler);
  }

  public void setPublishSharedPropertyHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractPublishSharedPropertyOperationHandler> handler) {
    operationSupplierMap.put(PUBLISH_SHARED_PROPERTY, handler);
  }

  public void setListNamedShadowsForThingHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractListNamedShadowsForThingOperationHandler> handler) {
    operationSupplierMap.put(LIST_NAMED_SHADOWS_FOR_THING, handler);
  }

  public void setDeleteSharedPropertyHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractDeleteSharedPropertyOperationHandler> handler) {
    operationSupplierMap.put(DELETE_SHARED_PROPERTY, handler);
  }

  public void setSubscribeToComponentUpdatesHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractSubscribeToComponentUpdatesOperationHandler> handler) {
    operationSupplierMap.put(SUBSCRIBE_TO_COMPONENT_UPDATES, handler);
  }

  public void setListLocalDeploymentsHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractListLocalDeploymentsOperationHandler> handler) {
    operationSupplierMap.put(LIST_LOCAL_DEPLOYMENTS, handler);
  }

  public void setStopComponentHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractStopComponentOperationHandler> handler) {
    operationSupplierMap.put(STOP_COMPONENT, handler);
  }

  public void setPauseComponentHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractPauseComponentOperationHandler> handler) {
    operationSupplierMap.put(PAUSE_COMPONENT, handler);
  }

  public void setCreateLocalDeploymentHandler(
      Function<OperationContinuationHandlerContext, GeneratedAbstractCreateLocalDeploymentOperationHandler> handler) {
    operationSupplierMap.put(CREATE_LOCAL_DEPLOYMENT, handler);
  }

  public void setCancelLocalDeploymentHandler(
          Function<OperationContinuationHandlerContext, GeneratedAbstractCancelLocalDeploymentOperationHandler> handler) {
    operationSupplierMap.put(CANCEL_LOCAL_DEPLOYMENT, handler);
  }

  @Override
  public Set<String> getAllOperations() {
    return SERVICE_OPERATION_SET;
  }

  @Override
  public boolean hasHandlerForOperation(String operation) {
    return operationSupplierMap.containsKey(operation);
  }

  @Override
  public Function<OperationContinuationHandlerContext, ? extends ServerConnectionContinuationHandler> getOperationHandler(
      String operation) {
    return operationSupplierMap.get(operation);
  }

  public void setOperationHandler(String operation,
      Function<OperationContinuationHandlerContext, ? extends ServerConnectionContinuationHandler> handler) {
    operationSupplierMap.put(operation, handler);
  }
}
