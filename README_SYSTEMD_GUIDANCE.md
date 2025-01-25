We have identified a service reliability issue affecting new installations of AWS IoT Greengrass Nucleus Java v2.14.0. This issue may impact service stability during system restarts and only affects fresh installations on new Greengrass devices. 

Please Note:
  - This issue does NOT affect existing Greengrass installations that have upgraded to v2.14.0 
  - Previous versions of Greengrass Nucleus are NOT affected
  - Only Linux devices using the default systemd service file from v2.14.0 are affected

### How to fix it:

Run the following command to revert the systemd service file to the way it was in 2.13.x and earlier:

```
sudo sed -i 's|ExecStart=/bin/sh -c "\(.*\) >> .*/logs/loader.log 2>&1"|ExecStart=/bin/sh \1|' /etc/systemd/system/greengrass.service
```

Apply the changes:

```
sudo systemctl daemon-reload
sudo systemctl restart greengrass
```
