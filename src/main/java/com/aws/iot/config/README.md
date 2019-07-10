# Config files

This is a start on support for simple config files in the style of https://quip-amazon.com/NwzPAaC28fmf 

Think of it as a hierarchy of key/value pair maps - much like JSON's data model.
K/V pairs all have timestamps.  When setting a value, the operation is ignored if the new timestamp predates the current timestamp.  A config file can
be serialized in many formats (JSON, YAML, ...) but the most important is as a time-stamped log file.

If the log file is replayed, the config is reconstructed.  An important property
is that the log file can be shuffled arbitrarily and the result will be the same.
An important use-case for this property is when two clones of this config are disconnected
(no longer cross-updating).  Updates made while disconnected can be reconstructed by
replaying each other's logs upon reconnection.  This "hack" depends on the two systems having sufficiently well synchronized clocks.

One feature that's missing is that arrays are not supported, only primitives and maps.
Even though arrays are a standard feature of JSON, they present problems when logging
mutations in a way that can be replayed in any order.  I haven't yet figured out a way
to do it that I like.

The basic API looks like this:
```java
var where = Path.of("somewhere.tlog");
var c = ConfigurationReader.createFrom(where);
ConfigurationWriter.logTo(c,where);
c.lookup("services","greenlake","verbose").subscribe((newValue,oldvalue)->
    myVerbose = Coerce.toBoolean(newValue));
c.lookup("services","greenlake","verbose").setValue((true);
c.lookupTopics("services").forEachTopicSet(s->System.out.println("Found service "+s.name));
```
Textual reading and writing can be handled by using the Jackson-JR library:

*Read YAML:*
```java
	config.mergeMap(timestamp, (Map)JSON.std
 		.with(new YAMLFactory())
 		.anyFrom(inputStream));
```
*Write YAML:*
```java
	JSON.std.with(new YAMLFactory())
        .write(config.toPOJO(), System.out);
```
*Write pretty JSON:*
```java
JSON.std.with(PRETTY_PRINT_OUTPUT)
        .write(config.toPOJO(), System.out);
```
If you omit the `.with(...)` clause, you get the default format, which is JSON.

Enumerating subnodes is easy too.  Suppose you wanted to list all the platforms in
a GG-SG config file:
```java
config.findTopics("platforms").forEachTopicSet(n -> System.out.println(n.name));
```

For code samples, look at ConfigurationTest.java


### To Do:
- [x] Readers for json/yaml/... ?
- [x] Writers for "
- [ ] More iterators
- [x] Partial path lookup
- [ ] Race audit
- [ ] Finish node removal support
- [ ] More tests!