# FleetStatusService
It is responsible for uploading the statuses of components within the device for all fleets. 
The fleet status service will be gathering state from the device and providing it to the customers. The fleet status service is 
an important feature to provide insight to the customer on the devices which have greengrass V2 running on them and answering 
questions like Is the device running the greengrass application as expected? What happened on the device after the recent deployment,
did a service start failing/flapping after it? etc. Based on these statuses, the customer can take appropriate actions to build their 
infrastructure to take actions on them.
The QoS for FSS would be 1 since we need the information to reach the cloud at least once.

# Startup
1. [***FleetStatusService***](/src/main/java/com/aws/iot/evergreen/fss/FleetStatusService.java) starts as an
evergreen service, which is by default enabled. It starts a timer to update the information about all the components running
in the kernel after a specific interval.

# Shutdown
Service lifecycle is managed by kernel and as part of kernel shutdown it cancels the timer for cadence based FSS data upload.

# Workflow
There are two conditions the Fleet Status Service will upload the status information to the cloud.
1. Event-Triggered
- After deployments.
- If a component goes into BROKEN state in between deployments. 
2. Periodic/Cadence Based
- Default interval is 1 day.

# Sample Configuration
```
services:
  main:
    lifecycle:
  FleetStatusService:
    parameters:
      periodicUpdateIntervalSec: 86400
    lifecycle:
```