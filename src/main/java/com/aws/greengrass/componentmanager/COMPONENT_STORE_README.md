# Nucleus Component Store

## Recipe Folder
Folder root: `{componentStoreRoot}/recipes`

### Recipe File

#### File Name 

**{ComponentNameHash}@{Semver}.recipe.yaml**

The file name needs to be cross-platform safe. See below for details for each part.

1. ComponentNameHash
    1. Hash = Base64.getUrlEncoder().withoutPadding((SHA_256(ComponentName))). 
    1. Padding is omitted to avoid confusion.
    1. Hash does lose the ability to convert it back, but we only need and foresee one-way conversion of file name
     for querying purposes. 
1. Semver
    1. We don't hash semver because we will need to sort with Semver Standard.
    1. Semver could only contain `[0-9A-Za-z]`, `-`, and `.`.
    
1. `@`: Delimiter between ComponentNameHash and Semver
    1. Tend to be cross-platform safe, at least for Linux and Windows.
    1. Meaningful.
    
1. Suffix: `.recipe.yaml`
    
#### File format type: YAML.
Since human will need to read this for debugging and development, YAML is more concise and readable, comparing to
JSON.

### Recipe Metadata File
TBD

## Artifact Folder
TODO