# Setup of Dependency Resolution Benchmark
## Nucleus Config
The benchmark uses the following config files for two different scenarios:
1. `DRBNewConfig.yaml`: GIVEN main without any existing packages, WHEN resolve 2 top-level packages, THEN 13 new packages
 are resolved and
 added.
1. `DRBStatefulConfig.yaml`: GIVEN 7 packages exist on the device (Using <service>.version keyword in config), WHEN
 resolve 2 top-level
 packages, THEN 6 packages remain the same, 1 updated, and 6 new packages added.
## Local Component Store
The test package example is constructed based on the dependency tree of Python packages `awscli` and `boto3`
```
awscli==1.16.144
  - botocore [required: ==1.12.134, installed: 1.12.134]
    - docutils [required: >=0.10, installed: 0.14]
    - jmespath [required: >=0.7.1,<1.0.0, installed: 0.9.4]
    - python-dateutil [required: >=2.1,<3.0.0, installed: 2.8.1]
      - six [required: >=1.5, installed: 1.14.0]
    - urllib3 [required: >=1.20,<1.25, installed: 1.24.2]
  - colorama [required: >=0.2.5,<=0.3.9, installed: 0.3.9]
  - docutils [required: >=0.10, installed: 0.14]
  - PyYAML [required: >=3.10,<=3.13, installed: 3.13]
  - rsa [required: >=3.1.2,<=3.5.0, installed: 3.4.2]
    - pyasn1 [required: >=0.1.3, installed: 0.4.5]
  - s3transfer [required: >=0.2.0,<0.3.0, installed: 0.2.0]
    - botocore [required: >=1.12.36,<2.0.0, installed: 1.12.134]
      - docutils [required: >=0.10, installed: 0.14]
      - jmespath [required: >=0.7.1,<1.0.0, installed: 0.9.4]
      - python-dateutil [required: >=2.1,<3.0.0, installed: 2.8.1]
        - six [required: >=1.5, installed: 1.14.0]
      - urllib3 [required: >=1.20,<1.25, installed: 1.24.2]

boto3==1.9.128
  - botocore [required: >=1.12.128,<1.13.0, installed: 1.12.134]
    - docutils [required: >=0.10, installed: 0.14]
    - jmespath [required: >=0.7.1,<1.0.0, installed: 0.9.4]
    - python-dateutil [required: >=2.1,<3.0.0, installed: 2.8.1]
      - six [required: >=1.5, installed: 1.14.0]
    - urllib3 [required: >=1.20,<1.25, installed: 1.24.2]
  - jmespath [required: >=0.7.1,<1.0.0, installed: 0.9.4]
  - s3transfer [required: >=0.2.0,<0.3.0, installed: 0.2.0]
    - botocore [required: >=1.12.36,<2.0.0, installed: 1.12.134]
      - docutils [required: >=0.10, installed: 0.14]
      - jmespath [required: >=0.7.1,<1.0.0, installed: 0.9.4]
      - python-dateutil [required: >=2.1,<3.0.0, installed: 2.8.1]
        - six [required: >=1.5, installed: 1.14.0]
      - urllib3 [required: >=1.20,<1.25, installed: 1.24.2]
```