# TokenExchangeService is responsible for vending AWS credentials for device certificates, so that components running
on Evergreen can seamlessly communicate with cloud using AWS clients.

# Startup
1. [***TokenExchangeService***](/src/main/java/com/aws/iot/evergreen/tes/TokenExchangeService.java) starts as an
evergreen service, which is by default disabled. After starting it starts up HTTP server at custom port which can
vend credentials at url "/2016-11-01/credentialprovider/"

# Shutdown
Service lifecycle is managed by kernel and as part of kernel shutdown it stops the server.