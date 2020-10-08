# Configure A Component
Each Greengrass V2 component could define its own default configuration. A deployment, either from cloud or local, would
use the default configuration if there is additional no configuration update.

Optionally, a 

## Define Default Configuration
The default configuration is defined in the recipe file. e.g.
```yaml
ComponentConfiguration:
  DefaultConfiguration:
    singleLevelKey: default value of singleLevelKey
    path:
      leafKey: default value of /path/leafKey
    listKey:
      - 'item1'
      - 'item2'
    emptyStringKey: ''
    emptyListKey: []

```

## Update Component Configuration

### JSON vs YAML
Currently, we only support JSON. We will support YAML as a fast-follow after reInvent 2020.

### Sample
```json

{
  "MERGE": {
    "singleLevelKey" : "updated value of singleLevelKey",
    "newSingleLevelKey": "value of newSingleLevelKey",
    "listKey": ["item3"],
    "path" : {
      "leafKey": "updated value of /path/leafKey",
      "newLeafKey": "value of /path/newLeafKey"
    }
  },
    
  "RESET": [
    "/newSingleLevelKey", "/path"
  ]
}
```
### Syntax
It accepts only `MERGE` and `RESET` as top-level keys. 

### RESET
RESET takes a list of String.
Each string is a JSON Pointer: https://tools.ietf.org/html/rfc6901.

1. If a default value exists at this JSON pointer location, then the value will be reset, including explicit null value.
1. If a default value doesn't exist at this JSON pointer location, then the key/value pair will be removed entirely. 

#### MERGE
`MERGE` takes an object, representing new configuration that should be merging in.

The given object is recursively merged to the existing configuration object. 

At any level, 

1. if a key already exists, then the value will be overridden by the value that is merging in.
2. If a key doesn't exist, then key-value pair that is merging in will be added.


