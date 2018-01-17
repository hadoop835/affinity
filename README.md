# Design Goals 

 - library for building stateful, scalable Data APIs on top of streaming foundation
 - can be attached to stream-processing pipelines based around kafka and participate
   either as a producer/source or consumer/materializer of state
 - fault-tolerance build on top of a distributed-log
 - horizontally scalable and fully asynchronous
 - zero-downtime possible


# Architecture


 ![Cluster Architecture](doc/affinity.png)

 - akka http as the main interface with websocket layer
 - akka for asynchronous communication and consistency guarantees
 - zookeeper for distributed coordination
 - RocksDb, MemDb, SimpleMap implementations
 - kafka as a fault-tolerant change log storage

## Basic principles

Internally, as well as externally Affinity is a fully event-driven system.
On the outside it offers two interfaces: Stream and Http which  are the entry
to the system:
 - Stream Interface can be used to simply ingest/process external stream
 - Http Interface can be used to define HTTP methods to access the data inside

Both of these interfaces communicate with the internal system only through
akka messages which can be simple ! Tell flows, ? Ask flows or ?? Ack
flows - these a strongly typed versions of Ask with retries, provided by the core module
all the way to complex internal event chains which may result in zero or
more events being fired back to the external systems.

Underneath the Http and Stream Gateway layer there is a common Akka Gateway
that represents all of the Keyspaces as simple actors each managing a set
of partitions - same Keyspace can be referenced by any number of nodes
with a full consistency guaranteed.

Applications typically extend the common Gateway to create traits of functionality
around a keyspace and then mixes them into a higher order traits where orchestrated
behaviours can be implemented. The final composite logic is attached to either
a StreamGateway, HttpGateway or both.

Actors that represent Keyspaces can be referenced in any of the Gateway traits and take
care of the routing of the messages which is designed to mimic the default partitioning
scheme of apache kafka - this is not necessary for correct functioning of the system as
the partitioning scheme is completely embedded within the Affinity universe but it helps
to know that an external kafka producer can be used with its default partitioner to create
a topic which will be completely compatible with affinity's view of the data - this helps
with migrations and other miscellaneous ops.

Keyspace Actor routes all request to Partition Actors which implement the logic over
the data partition - each Keyspace is therefore a dynamic Akka Router which
maintains a copy of the active Partition Actors using internal instance of Coordinator
(see distributed coordination section below).

Gateways are hence the orchestration layer while Keyspaces are completely
constrained to the partition scope. (There is an experimintal piece of code around
lightweight transactions that can be wrapped around orchestrated logic which use reversible
Instructions to compensate failed operations but this is probably going to be abandonned
as for it to operate consistently distributed locks would have to be used)

### The Http Layer

In the Http Gateway, the HTTP Interface is completely async done with Akka Http.
HTTP Handlers participate in handling the incoming HTTP Requests by chaining the
actor handle: Receive method of the GatewayHttp trait.

Handlers translate requests into Akka Messages which may be routed to a partition
or handled directly in the gateway or by orchestrating an internal event flow that
must conclude eventuall in fulfilling the response promise.

WebSockets can be attached to Key-Value entities which then receive automatically
all changes to that entity and the concept can be also extended to push notification
via external delivery system like Google FCM or Apple APN.


## Serialization

Serialization is very important in any distributed data processing system.
Affinity builds around Avro and extends the idea of seamless serialization
and deserialization at all levels of the entire stack - kafka streams,
state stores, akka communication and even spark rdds that are mapped directly
on top of the underlying streams use the same serialization mechanism.
This mechanism is almost completely transparent and allows the application
to work with pure scala case classes rather then generic or generated avro
classes. The schemas and the binary formats produced are however fully
compatible with generic avro records and the data serialized by affinity
can be read by any standard avro client library. When the binary output
is written to kafka topics, affinity serializers can register these schemas
in a central registry, including the standard kafka avro schema registry made
by Confluent with matching binary format so that the deserializer that
ships with schema registry can be used to read the data. Therefore the
serialization abstraction is fairly complicated and central piece.
At its heart is an AvroRecord class which can infer schemas about case
classes and convert between binary, json, avro and case class instances.
At the moment this is done at runtime by reflection but this will be
 replaced by a macro based compile-time version while preserving the
 higher layers that take care of reusability across all system components
 from akka to kafka, rocksdb and spark, etc. Since the schema inference
 and is done by reflection during runtime a series of caches is used to
 give performance character comparable to serializing and deserializing
 generic avro records but the need for these should disappear with a
 macro-based approach.

## Distributed coordination

Akka Cluster that comes with Akka is not used, instead a custom cluster
management is implemented using a coordinator that allows for dynamic participation
by multiple actor systems

Each Keyspace manages a  set of Partition actors. Keyspace has a precisely
defined number of partitions but each partition can be served by multiple
instances, typically on different nodes, at the same time. If there are multiple
Partition Actors for the same physical partition Coordinator uses distributed
logic to choose one of them as master and the others become standby.

On becoming a Master, the Partition Actor stops consuming (tailing) the the
underlying topic, because the master receives all the writes, its in-memory
state is consistent and it only publishes to the kafka for future bootstrap
and keeping other standby(s) for the partitions up to date.

On becoming a Standby, the Partition Actor resumes consuming the underlying topic
and stops receiving until it again becomes a master. Standby is not a read replica
at the moment but it could be an option but the systems tend to be quite simple
and still scale well horizontally by the means of partitioning rather then
opening the door for vertical concurrency.

## State Management

All data is stored in the physical storage in the form
of change log, e.g. Kafka compacted topic. Each physical partition
can have multiple active API Partitions, one of which is 
always a master and the remainder are standby(s).

As mentioned elsewhere, Keyspace is composed of Partitions and each
Partition can contain multilple state stores whose partitioning scheme will
be aligned with that of the entire overarching Keyspace, however each
state can define specific details about how to store the data, what type
of memstore to use, what TTL to observe and more.

Each State within a Partition of a Keyspace has a first-class TTL support
on a per-key basis and also offers subscribtion at the key level - this
is for example used by the WebSocket support described below.

For kafka 0.11 and higher there is an AdminClient that is used to
   automatically create and re-configure the underlying topics, including
   the compaction, replication, retention and other aspects derived from
   the overarching Keyspace properties and individual State properties.

In the most consistent setup, master takes all reads and writes 
and records each write in the storage change log while reads
come directly from the in-memory data set.
After storage has accepted the write, master updates its own 
in-memory state - this way the level of data loss acceptance can
be controlled by simply configuring the appropriate ack level
in the underlying storage.

Standby(s) tail the changelog continuously and keep their 
in-memory state up-to-date. In in the event of master failure, 
one of the standby(s) is picked to be the new master. This way
zero-downtime is possible for both failures as well as upgrades.

In cases where eventual read consistency is sufficient, standby(s) 
can also be used as read replicas (this is currently not implemented
but the design is expecting this to come in future).

### Note on consistency of global state stores

The State described above applies to Keyspaces - these are represented
by a Keyspace actor that routes all messages to all of its Partition
actors. Since actors are single-threaded and there is only one master
actor per partition the integrity of reads and writes is guaranteed.

Sometimes it is necessary to use global state rather than partitioned
keyspace. In this case all gateways that hold reference to a global
state see the same data. It is a useful concept, similar to broadcast
variables in batch processors but because affinity is provides
a read-write api on top of its stores, the global state consistency
is not as strong as that of partitioned keyspace because there is not
a single actor guarding it.

# Configuration

Affinity uses HOCON configuration which is layered on top of set of reference.conf files
that includes Akka default and Affinity defaults.

Applications typically have their own default configuration layered on top of the reference
files and a set of thin, deployment specific conf files.

Internally Affinity has it's own type-safe configuration abstraction that is initialized
from the HOCON conf files. These type-safe configuration descriptors handles validation,
enforcement and messaging around invalid configuration.

# Development 

The codebase is split into several modules:

 - `api` is the internal programming api and utiliities for writing memstore and storage plugins (Java)
 - `avro` scala case class <--> avro automatic conversion with schema registry wrappers (conf,zk,local,embedded) (Scala)
 - `core` is the main scala library with js-avro extension (Scala)
 - `examples/..` contain example applications (Scala)
 - `kafka/kafka_X.X/avro-formatter-kafka` kafka formatter for console consumer for the `avro` module (Scala)
 - `kafka/kafka_X.X/avro-serde-kafka` kafka produer serializer and consumer deserializer for the `avro` module (Scala)
 - `kafka/kafka_X.X/storage-kafka` module with kafka storage and confluent schema provider (Scala)
 - `kafka/kafka_X.X/test-util-kafka` provides EmbeddedZooKeeper, EmbeddedKafka and EmbeddedCfRegistry for testing
 - `mapdb` module with MapDb implementation of the MemStore (Java)
 - `rocksdb` module with RocksDb implementation of the MemStore (Java)
 - `spark/spark_2.0` uses the underlying stream storage as CompactRDD with all the serde magic
 - `ws-client` custom web socket with avro support (Java)


## Testing the code

    ./gradlew test    
    
## Building the project
        
    ./gradlew build

## JavaScript (affinity.js)

There is affinity_node.js file which contains the source for avro
web socket implementation. It is based on a node avsc.js library:

    npm install avsc


To generate final affinity.js for borwsers:

    npm install -g browserify

When working on this script the browser script affinity.js can
be generated then by:

    browserify core/src/main/resources/affinity_node.js -o core/src/main/resources/affinity.js
    
When doing a lot of work on the javascript watchify can be used
  to automatically generate the new affinity.js when the affinity_node.js
  is modified:

    npm install -g watchify
    watchify core/src/main/resources/affinity_node.js -v -o core/src/main/resources/affinity.js -d

## Logging

Affinity uses SLF4j and it also redirects alla Akka loggin to SLF4J.
It doesn't provide any binding for compile configuration, that is left to applications - in one of the example,
logback is used, in another log4j.

For all testing slf4j-jdk14 (Java Logging) binding is used and is configured for all modules in:
api/src/test/resources/logging.properties

## Avro Schemas

..

### Avro Kafka Serializer and Deserializer

...