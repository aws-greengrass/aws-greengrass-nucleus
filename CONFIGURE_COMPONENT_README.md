# Configure A Component
Each Greengrass V2 component could define its own default configuration which would be used by default.

A deployment, either from cloud or local device, could optionally provide a configuration update instruction to update
the configuration for a deployment's **target components**.

Note: updating configuration is only supported for the **target components** of a deployment.

## 1. Define Default Configuration for a Component
The default configuration is defined in the recipe file.

In YAML, 
```yaml
ComponentConfiguration:
  DefaultConfiguration:
    singleLevelKey: default value of singleLevelKey
    nestedObjectkey:
      leafKey: default value of /nestedObjectkey/leafKey
    listKey:
      - 'item1'
      - 'item2'
    emptyStringKey: ''
    emptyListKey: []
    emptyMapKey: {}
    defaultIsNullKey: null
```

or in JSON,
```json
{
   "ComponentConfiguration": {
      "DefaultConfiguration": {
         "singleLevelKey": "default value of singleLevelKey",
         "nestedObjectkey": {
            "leafKey": "default value of /nestedObjectkey/leafKey"
         },
         "listKey": [
            "item1",
            "item2"
         ],
         "emptyStringKey": "",
         "emptyListKey": [],
         "emptyMapKey": {},
         "defaultIsNullKey": null
      }
   }
}
```

## 2. Update Configuration for a Component
A deployment, either from cloud or local device, could optionally provide a configuration update instruction to update
the configuration for a deployment's **target components**, with the following syntax.


### 2.1 JSON vs YAML
Currently, we only support JSON. We will support YAML as a fast-follow after re:Invent 2020.

### 2.2 Sample
```json
{
  "RESET": [
       "/someOtherKey", "/some/nested/path"
  ],
  "MERGE": {
    "singleLevelKey" : "updated value of singleLevelKey",
    "newSingleLevelKey": "I was not in the default value and could be added.",
    "listKey": ["item3"],
    "nestedObjectkey" : {
      "leafKey": "updated value of /nestedObjectkey/leafKey",
      "newLeafKey": "value of /nestedObjectkey/newLeafKey"
    }
  }
}
```
### 2.3 Syntax
It accepts only **one of each** `RESET` and `MERGE` as top-level keys. The configuration will first perform `RESET` and then perform `MERGE`,
regardless of the order they are given in the JSON Object.

#### 2.3.1 RESET
RESET takes a list of String.
Each string is a [JSON Pointer](https://tools.ietf.org/html/rfc6901).

1. If a default value doesn't exist at this JSON pointer location, then the key/value pair will be removed entirely. 
1. If a default value exists at this JSON pointer location, then the value of configuration will be reset to the default value.

##### RESET doesn't support using index for an Array/List!!
Although JSON pointer supports use indexes to locate an element in an Array/List, **we don't support using
JSON pointer to reset an element in an Array/List for re:Invent 2020**. The reason is that resetting an element of an array
might cause removal for an index, elements shifting in the array, and other nondeterministic results. 

Hence, we've decided to postpone the support for an Array/List to post re:Invent 2020.

##### RESET the entire configuration
An empty JSON pointer string refers to the whole document. To reset the entire configuration, you could simply specify

```json
{
  "RESET":  [""]
}
```

Note that if the empty string appears in the list of `RESET`, then the rest of pointers in the list will be ignored, and the configuration
will just be reset to the default configuration entirely.


##### What happens if I reset to a default value and my default value is null or empty?
Greengrass V2 will reset the configuration to the default value as is, including null or empty values, for example:

1. Default value is an empty List `{"emptyListKey": []}`. The config will be reset to an empty list with JSON pointer `/emptyListKey`.
1. Default value is an empty Map/Object `{"emptyMapKey": {}}`. The config will be reset to an empty map with JSON pointer `/emptyMapKey`.
1. Default value is an empty String `{"emptyStringKey": ""}`. The config will be reset to an empty string with JSON pointer `/emptyStringKey`.
1. Default value is null `{"defaultIsNullKey": null}`. The config will be reset to null with JSON pointer `/defaultIsNullKey`.

#### 2.3.2 MERGE
`MERGE` takes a map object, representing the new configuration.

The given object will be merged to the existing configuration object level by level.

At every level, we go over all the key-value pairs in the new config map, and look up the key in the existing
 configuration:
1. If a key already exists, its value will be overwritten by the new configuration value.
2. If a key doesn't exist, the key-value pair will be added to the existing configuration. Note: a new key could
 be added, even it does not exist in the default configuration.

#### 2.3.3 MERGE - The change between a 'leaf' node and a 'container' node
1. If a config node has children, it's a 'container' node.
1. If a config node has value but not children, it's a 'leaf' node.
1. If during a MERGE, a config node changes between 'leaf' and 'container' in type, the old node will be removed and
 a new one will be created.
 Subscribers of the old node will be copied over to the new node.
 Node subscribers will be notified of a 'removed' event, and parent node subscribers will be notified of a 'childRemoved' event.

##### MERGE doesn't support Array/List append or insertion at index operations!!
Similar to removal, list append and insertion-at-index require handling additional complexity of array index changing, elements
shifting, and other nondeterministic results.

Hence, we've decided to postpone the support for Array/List appends and insertions to post re:Invent 2020.

However, it's still possible to override the entire list.
If you really need to make updates at the element level, think about using a map instead, by giving each element an unique key.

##### Can I merge in a new key-value pair, which was not part of the default configuration?
YES. We are providing the flexibility to do so, so that new configurations could be added during deployments.

##### Can I merge empty values?
YES. You can merge empty String, List, or Object/Map.

##### Can I merge `null` as a value for a key?
YES. When reading the merged configuration, you will be able to find the key with value as `null`.

##### Can I merge in a new value whose type is different from original value's type for the same key?
YES. The new value will be merged in and overrides the old value.
This allows you to potentially change the configuration object's structure. See the following example:

Existing configuration:
```json
{ "myKey": "myValue" }
```

Config update instruction:
```json
{
    "MERGE": {
        "myKey": {
            "nestedKey1": "myValue"
        }
    }
}
```

Result:
```json
{ 
  "myKey": {
       "nestedKey1": "myValue"
   }
}
```

### 2.4 FAQs
#### How can I remove a config value at a location specified by a JSON pointer?

If the default value does not exist for that location, you could RESET with the JSON pointer pointing to that location
and the value will get removed.

However, if a default value does exist, usually it means the the configuration is used in the component recipe or
 application logic.
Hence removing the config value is very risky. In fact, this is the reason why removal is not supported directly.

If you are sure and really want to remove a config value, you could MERGE in a `null` value.
