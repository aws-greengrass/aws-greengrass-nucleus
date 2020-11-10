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
    path:
      leafKey: default value of /path/leafKey
    listKey:
      - 'item1'
      - 'item2'
    emptyStringKey: ''
    emptyListKey: []
    emptyMap: {}
    defaultIsNullKey: null
```

or in JSON,
```json
{
   "ComponentConfiguration": {
      "DefaultConfiguration": {
         "singleLevelKey": "default value of singleLevelKey",
         "nestedObjectkey": {
            "leafKey": "default value of /path/leafKey"
         },
         "listKey": [
            "item1",
            "item2"
         ],
         "emptyStringKey": "",
         "emptyListKey": [],
         "emptyMap": {},
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
      "leafKey": "updated value of /path/leafKey",
      "newLeafKey": "value of /path/newLeafKey"
    }
  }
}
```
### 2.3 Syntax
It accepts only **one of each** `RESET` and `MERGE` as top-level keys. The configuration will first perform `RESET` and then perform `MERGE`,
regardless of the order they are given in the JSON Object.

#### 2.3.1 RESET
RESET takes a list of String.
Each string is a JSON Pointer: https://tools.ietf.org/html/rfc6901.

1. If a default value doesn't exist at this JSON pointer location, then the key/value pair will be removed entirely. 
1. If a default value exists at this JSON pointer location, then the value of configuration will be reset to the default value.

##### RESET doesn't support using index for an Array/List!!
Although JSON pointer supports use indexes to locate an element in an Array/List, **we don't support use
JSON pointer to reset an element in an Array/List for re:Invent 2020**. The reason is that resetting an element of an array
might cause removal for an index, elements shifting in the array, and other indeterministic results. 

Hence, we've decided to postpone the support for an Array/List to post re:Invent 2020.

##### RESET the entire configuration
Empty String is JSON pointer's way to refer to the whole document. So you could simply do

```json
{
  "RESET":  [""]
}
```

Note that if the empty string appears in the list of `RESET`, then the rest of pointers in the list will be ignored, and the configuration
will just be reset to the default configuration entirely.


##### What happens if I reset to a default value and my default value is null or empty?
In general, Greengrass V2 will reset to the default value as is, instead of dropping null or empty values, including:

1. Default value has an empty List. ex. `{"emptyListKey": []}`. An empty list will be reset with JSON pointer: `/emptyListKey`.
1. Default value has an empty Map/Object. `{"emptyMapKey": {}}`. An empty map will be reset with JSON pointer: `/emptyMapKey`.
1. Default value has an empty String. `{"emptyStringKey": """}`. An empty String will be reset with JSON pointer: `/emptyStringKey`.
1. Default value has a null. `{"defaultIsNullKey":null}`. A null will be reset with JSON pointer: `/defaultIsNullKey`.

#### 2.3.2 MERGE
`MERGE` takes an object, representing new configuration that should be merging in.

The given object is merged to the existing configuration object level by level. 

At every level,
1. if a key already exists, then the value will be overridden by the value that is merging in.
2. If a key doesn't exist, then key-value pair that is merging in will be added. Note a key that is not existed in the default value,
could also be added.

#### 2.3.3 MERGE - The change between a 'leaf' node and a 'container node'
1. If a config node has children, it's a 'container' node.
1. If a config node has value while not having children, it's a 'leaf' node.
1. If during a MERGE, a node type changed between 'leaf' and 'container', the old node will be removed and new node will be created.
 Subscribers of the old node will be copied over to new node.
 Node subscribers will be notified of 'removed' event, and parent node subscribers will be notified of 'childRemoved' event.

##### MERGE doesn't support Array/List append or insertion at index operations!!
Similar to removal, list append and insertion index require handling addtional complexity of array index changing, elements
shifting, and other indeterministic results.

Hence, we've decided to postpone the support for an Array/List to post re:Invent 2020.

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

ex.

Existing 
```json
{ "myKey": "myValue" }
```

Update:
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
#### How can I remove a value at a location specified by a JSON pointer?

If the default value does not exist for that location, you could RESET with the JSON pointer pointing to that location
and the value will get removed.

However, if a default value does exist, usually it means the Component Owner would use it in their recipe or logic.
Hence removing the value is very risky. In fact, this is the reason why removal is not supported directly.

If you are sure and really want to remove a value, you could MERGE in a `null` value.