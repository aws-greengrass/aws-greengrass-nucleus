# Token Exchange Service
It is responsible for vending AWS credentials for device certificates, so that components running
on Greengrass can seamlessly communicate with cloud using AWS clients.

# Startup
1. [***TokenExchangeService***](TokenExchangeService.java) starts as a
greengrass service, and is by default disabled. After startup, it queries AWS credentials corresponding to
your roleAlias and starts up HTTP server at custom port which vends credentials at url "/2016-11-01
/credentialprovider/".

# Shutdown
Service lifecycle is managed by Nucleus. As part of Nucleus shutdown, TES server stops.

# Sample Configuration
```
services:
  main:
    dependencies:
      - TokenExchangeService
  aws.greengrass.Nucleus:
    configuration:
      awsRegion: "us-east-1"
      iotCredEndpoint: "xxxxxx.credentials.iot.us-east-1.amazonaws.com"
      iotDataEndpoint: "xxxxxx-ats.iot.us-east-1.amazonaws.com"
      iotRoleAlias: "tes_alias"
  TokenExchangeService:
    configuration:
      port: 2020
```
