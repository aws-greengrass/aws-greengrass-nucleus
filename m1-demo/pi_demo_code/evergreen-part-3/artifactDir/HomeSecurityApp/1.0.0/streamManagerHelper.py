import asyncio
import uuid
import time

# from botocore import client
from greengrasssdk.stream_manager import StrategyOnFull, Persistence, ExportDefinition, StreamManagerException, \
    KinesisConfig, IoTAnalyticsConfig
from greengrasssdk.stream_manager.streammanagerclient import StreamManagerClient
from greengrasssdk.stream_manager.streammanagerclient import MessageStreamDefinition

class StreamUploader:

    def __init__(self):
        time.sleep(5)
        self.client = StreamManagerClient()

    def createStreamWithKinesisExport(self, streamName):
        try:
            self.client.create_message_stream(MessageStreamDefinition(
                name=streamName,  # Required.
                strategy_on_full=StrategyOnFull.OverwriteOldestData,  # Required.
                export_definition=ExportDefinition(  # Optional. Choose where/how the stream is exported to the AWS Cloud.
                    kinesis=[KinesisConfig(
                        identifier=str(uuid.uuid4()),
                        kinesis_stream_name=streamName,
                        batch_size=5,
                        priority=1
                    )]
                )
            ))
        except StreamManagerException:
            pass
        # Properly handle errors.
        except ConnectionError or asyncio.TimeoutError:
            pass

    def createStreamWithIotAnalyticsExport(self, channelName):
        try:
            self.client.create_message_stream(MessageStreamDefinition(
                name=channelName,  # Required.
                strategy_on_full=StrategyOnFull.OverwriteOldestData,  # Required.
                export_definition=ExportDefinition(  # Optional. Choose where/how the stream is exported to the AWS Cloud.
                    iot_analytics=[IoTAnalyticsConfig(
                        identifier=str(uuid.uuid4()),
                        iot_channel=channelName,
                        batch_size=1,
                        priority=1
                    )]
                )
            ))
        except StreamManagerException:
            pass
        # Properly handle errors.
        except ConnectionError or asyncio.TimeoutError:
            pass

    def appendToStream(self, streamName, data):
        sequenceNumber = self.client.append_message(stream_name=streamName, data=data)
        print("appended the message to the stream")
        print(sequenceNumber)

