package generated.software.amazon.awssdk.iot.greengrass.model;

import com.google.gson.annotations.Expose;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

public class SendConfigurationValidityReportRequest implements EventStreamableJsonMessage {
  public static final SendConfigurationValidityReportRequest VOID;

  static {
    VOID = new SendConfigurationValidityReportRequest() {
      @Override
      public boolean isVoid() {
        return true;
      }
    };
  }

  @Expose(
      serialize = true,
      deserialize = true
  )
  private Optional<ConfigurationValidityReport> configurationValidityReport;

  public SendConfigurationValidityReportRequest() {
    this.configurationValidityReport = Optional.empty();
  }

  public ConfigurationValidityReport getConfigurationValidityReport() {
    if (configurationValidityReport.isPresent()) {
      return configurationValidityReport.get();
    }
    return null;
  }

  public void setConfigurationValidityReport(
      final ConfigurationValidityReport configurationValidityReport) {
    this.configurationValidityReport = Optional.of(configurationValidityReport);
  }

  @Override
  public String getApplicationModelType() {
    return "aws.greengrass#SendConfigurationValidityReportRequest";
  }
}
