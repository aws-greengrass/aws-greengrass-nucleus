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

## One way of visualizing it

Given the following configuration in YAML format,

```yaml
main:
    requires: myService
    run: echo MAIN
```

You could visualize it as:

![Alt text](Topics_and_topic_sample.svg)


With the above simple example in mind, a more generic version is the following:

![Alt text](Topics_and_topic_concept.svg)

## Limitations
One feature that's missing is that arrays are not supported, only primitives and maps.
Even though arrays are a standard feature of JSON, they present problems when logging
mutations in a way that can be replayed in any order.  I haven't yet figured out a way
to do it that I like.

## Code with configuration
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

For code samples, look at [ConfigurationTest](https://github.com/aws/aws-greengrass-kernel/blob/master/src/test/java/com/aws/iot/evergreen/config/ConfigurationTest.java).

### subscribe() vs getOnce()
While you can think of this mechanism as a conventional config file, it's better to think of it as a lightweight publish/subscribe mechanism and use that viewpoint to make it possible for code to be reactive to on-the-fly configuration changes, rather than depending on rebooting the system to pick up configuration changes.

For example, it is common for services to have a variable to control the level of detail in diagnostic traces.  It's useful, in a system under test, to be able to change this in order to help in diagnosing problems.  In a conventional system, it's common to write something like this:
```java
	setTraceLevel(config.get("tracelevel", defaultValue));
```
This works, but it requires a reboot to change the trace level.  If there's a notification mechanism, this can look like:
```java
	setTraceLevel(config.get("tracelevel", defaultValue));
	config.subscribe("tracelevel",v->setTraceLevel(v));
```
This removes the must-reboot requirement, but the fact that there are two similar calls to setTraceLevel is kinda boilerplate-ish.  There's duplication and the possibility of differences causing bugs.  The right pattern in Evergreen looks like this:
```java
config.lookup("tracelevel").dflt(defaultValue)
      .subscribe((why, newValue, newValue) -> setTraceLevel(newValue));
```
The important weirdness here is that the `subscribe()` method invokes the listener *right away*, so that the only `setTraceLevel()` is the one in the handler.  There is no possibility of inconsistent code, and the `set` code is always executed, therefore always tested.
