# M1 Demo Branch
Please push all the demo related code/recipe/README to the related folder.             

## Stream Manager

**Very important!** In order for Stream Manager to integrate with TES and get creds from TES, make sure:
1. Stream Manager does not inherit any AWS credentials through environment variables
2. You do not have ~/.aws/credentials 

There is a Stream Manager component defined in the `packages` folder. The component requires the Stream Manager jar.
Currently, the jar in the `artifacts` folder is a 0 byte placeholder. You can get the actual jar from this link and
replace the placeholder.

https://devcentral.amazon.com/ac/brazil/package-master/package/view/AWSGreengrassGreenlake%3B1.0.1521.0%3BAL2012%3BDEV.STD.PTHREAD%3Bsuperjar

The Stream Manager recipe declares TES as its dependency. The following conditions are needed in order for the Stream
Manager component to work:
1. There is a recipe file for TES. 
2. `iotRoleAlias` (a TES property) needs to be set.
3. `system` properties such as `certificateFilePath` and `privateKeyPath` needs to be set.

All these conditions should be fulfilled by the setup script. Ping fufranci@ for an example of how to set this up
manually.

Example `config.yaml`. Note the settings under `system`. Also note that you must define `iotRoleAlias`.
```
---
services:
  main:
    lifecycle:
      run: |-
        echo "Hello World"
  TokenExchangeService:
    iotRoleAlias: "smeg"
system:
  # To config manually, see: https://docs.aws.amazon.com/iot/latest/developerguide/authorizing-direct-aws.html
  thingName: “smeg_Core”
  certificateFilePath: "/Users/fufranci/workspaces/evergreen/StreamManagerOnEvergreenDemo/stream_manager/certs/e4974bab6d.cert.pem"
  privateKeyPath: "/Users/fufranci/workspaces/evergreen/StreamManagerOnEvergreenDemo/stream_manager/certs/e4974bab6d.private.key"
  rootCaPath: "/Users/fufranci/workspaces/evergreen/StreamManagerOnEvergreenDemo/stream_manager/certs/root.ca.pem"
  iotDataEndpoint: "fufranci"
  iotCredEndpoint: "https://c3pysqolcbrvr1.credentials.iot.us-west-2.amazonaws.com"
  awsRegion: "us-west-2"
  # mqttClientEndpoint:
```

To understand how to setup TES manually, including how to find out your `iotCredEndpoint`, please see: 
https://docs.aws.amazon.com/iot/latest/developerguide/authorizing-direct-aws.html
