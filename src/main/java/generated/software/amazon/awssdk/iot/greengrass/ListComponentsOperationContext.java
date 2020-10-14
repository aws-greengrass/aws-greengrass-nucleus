package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;

import generated.software.amazon.awssdk.iot.greengrass.model.ListComponentsRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.ListComponentsResponse;
import software.amazon.eventstream.iot.EventStreamRPCServiceModel;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class ListComponentsOperationContext implements OperationModelContext<ListComponentsRequest, ListComponentsResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  @Override
  public String getOperationName() {
    return GreengrassCoreIPCServiceModel.LIST_COMPONENTS;
  }

  @Override
  public Class<ListComponentsRequest> getRequestTypeClass() {
    return ListComponentsRequest.class;
  }

  @Override
  public Class<ListComponentsResponse> getResponseTypeClass() {
    return ListComponentsResponse.class;
  }

  @Override
  public String getRequestApplicationModelType() {
    return ListComponentsRequest.APPLICATION_MODEL_TYPE;
  }

  @Override
  public String getResponseApplicationModelType() {
    return ListComponentsResponse.APPLICATION_MODEL_TYPE;
  }

  @Override
  public Optional<Class<EventStreamJsonMessage>> getStreamingRequestTypeClass() {
    return Optional.empty();
  }

  @Override
  public Optional<Class<EventStreamJsonMessage>> getStreamingResponseTypeClass() {
    return Optional.empty();
  }

  public Optional<String> getStreamingRequestApplicationModelType() {
    return Optional.empty();
  }

  @Override
  public Optional<String> getStreamingResponseApplicationModelType() {
    return Optional.empty();
  }
}
