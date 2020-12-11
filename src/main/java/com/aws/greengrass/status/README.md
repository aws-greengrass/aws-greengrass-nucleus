# Fleet Status Service
It is responsible for uploading the statuses of components within the device for all fleets. 
The fleet status service will be gathering state from the device and providing it to the customers. The fleet status service is
an important feature to provide insight to the customer on the devices which have greengrass V2 running on them and
answering questions like:
  > Is the device running the greengrass application as expected?
  > What happened on the device after the recent deployment, did a service start failing/flapping after it?

Based on these statuses, the customers can build their infrastructure to take appropriate actions.
The QoS for FSS is 1 since we need the information to reach the cloud at least once.

There is an initial jitter added for each device to emit the periodic updates. This is to ensure not all the devices within the fleet
update their fleet statuses at the same time.

Since there is a limit of 128 KB per message on IoT Core, the fleet status service will chunk the message to make sure
 that each message does not exceed the max size of 128 KB.

# Startup
1. [***FleetStatusService***](FleetStatusService.java) starts as a greengrass service, and is by default enabled. It
starts a timer to update the information about all the components running
in the Nucleus after a specific interval.

# Shutdown
Service lifecycle is managed by Nucleus. As part of Nucleus shutdown, FSS cancels the timer for cadence based data
 upload.

# Workflow
There are two conditions the Fleet Status Service will upload the status information to the cloud.
1. Event-Triggered
- After deployments.
- If a component goes into BROKEN state in between deployments. 
2. Periodic/Cadence Based
- Default interval is 1 day.

# Sample Configuration
> Note: this configuration cannot be updated via deployments.
```
services:
  main:
    dependencies:
      - FleetStatusService
  FleetStatusService:
    configuration:
      periodicUpdateIntervalSec: 86400
```
