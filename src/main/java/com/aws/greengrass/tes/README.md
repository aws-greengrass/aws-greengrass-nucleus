# TokenExchangeService
It is responsible for vending AWS credentials for device certificates, so that components running
on Greengrass can seamlessly communicate with cloud using AWS clients.

# Startup
1. [***TokenExchangeService***](/src/main/java/com/aws/greengrass/tes/TokenExchangeService.java) starts as a
greengrass service, which is by default disabled. After startup, it queries AWS credentials corresponding to
your roleAlias and starts up HTTP server at custom port which vends credentials at url "/2016-11-01
/credentialprovider/".

# Shutdown
Service lifecycle is managed by kernel and as part of kernel shutdown it stops the server.

# Sample Configuration
```
services:
  main:
    lifecycle:
    dependencies:
      - TokenExchangeService
  aws.greengrass.Nucleus:
    parameters:
      awsRegion: "us-east-1"
      certificateFilePath: "root/thingCert.crt"
      iotCredEndpoint: "c13im2gfya04ip.credentials.iot.us-east-1.amazonaws.com"
      iotDataEndpoint: "aun2g37imm74n-ats.iot.us-east-1.amazonaws.com"
      privateKeyPath: "root/privKey.key"
      rootCaPath: "root/rootCA.pem"
      thingName: "tes_thing"
      iotRoleAlias: "tes_alias"
  TokenExchangeService:
    parameters:
      port: 2020
    lifecycle:
```
