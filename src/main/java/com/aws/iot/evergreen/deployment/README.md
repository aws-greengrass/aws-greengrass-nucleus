# Models
1. [***DeploymentDocument***](/src/main/java/com/aws/iot/evergreen/deployment/model/DeploymentDocument.java) 
Represents the json document which contains the desired state for the device 
2. [***Deployment***](/src/main/java/com/aws/iot/evergreen/deployment/model/Deployment.java) Represents creation of a
 new desired state for the device. This can come from multiple sources like IotJobs, device shadow or local CLI. Each
  Deployment is uniquely identified

# Startup
1. [***DeploymentService***](/src/main/java/com/aws/iot/evergreen/deployment/DeploymentService.java) starts as an 
evergreen service and initializes [***IotJobsHelper***](/src/main/java/com/aws/iot/evergreen/deployment/IotJobsHelper) and initiates the connection to AWS Iot via the 
*IotJobsHelper*
2. *IotJobsHelper* connects to AWS Iot cloud and subscribes to the Iot jobs mqtt topics. *IotJobsHelper* and 
*DeploymentService* communicate via a Queue.
 
# Workflow
## Successful deployment via IotJobs
1. [***IotJobsHelper***](/src/main/java/com/aws/iot/evergreen/deployment/IotJobsHelper) receives notification when
    - A new job is created in cloud.
    - A job is completed (FAILED/SUCCEEDED)
    - A job is cancelled 
    The notification contains the list of QUEUED and IN_PROGRESS jobs according to status in cloud
2. Upon receiving the notification, if there are any QUEUED jobs in the list then *IotJobsHelper* requests for the 
**next** *pending* job document. *pending* can mean jobs which are either IN_PROGRESS or QUEUED. **next** represents 
the chronological order of creation of jobs in cloud. Cancellation of a job in cloud will remove the job from the 
list. If a device is processing a job (with job id "1"), it means the status of this job is IN_PROGRESS. If the 
device requests for next pending job at this time then it will receive job with JobId "1"  
3. Upon receiving a QUEUED job document helper creates the *Deployment* object and passes it to *DeploymentService*
4. *DeploymentService* polls the queue and upon receiving a Deployment, starts processing the deployment and stores 
the id of the deployment it is currently processing
5. *DeploymentService* waits for the deployment to get completed and then updates the status of the job in the cloud.
 Service only processes one deployment at a time

## Cancellation of deployment via IotJobs
1. For cancellation of any job which was not yet processed by device does not require any action from device
2. If a job "Job1" is in progress and user cancels "Job1" from cloud. Device will receive the notification and will 
request the next pending job and will get either the next job in list or an empty list (if nothing is there)
3. Device then identifies that the current job "Job1" has been cancelled and attempts to cancel the ongoing "Job1" 
  

  
