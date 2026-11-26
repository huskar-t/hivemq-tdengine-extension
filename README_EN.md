# HiveMQ TDengine extension

## Goal
Write the data from publish to hivemq into tdengine database in real time according to TS (timestamp) topic (message topic) payload(message payload)
Supports HTTP and SDK

## Execution process
> + Read configuration file
> + Create tdengine connection
> + Create DB (the DB name is in the configuration file 'db' configuration item)
> + Create a table (table name in configuration file 'table' configuration item)
> + intercept  all publish messages
> + Write topic and payload asynchronously to the table
>   - The topic column name is in the configuration file topicColumn configuration item
>   - The payload column name is in the configuration file payloadColumn configuration item
>   - If the payload contains special characters (GBK cannot encode), try encoding the payload with Base64
>   - If an exception occurs, the message will be discarded and the exception will be printed
> + Call to close the database connection when the extension is uninstalled

## Architecture
> + 'TDengine.java' Responsible for handling database connections, creating database tables, writing data
> + 'TDengineExtension.java' Main entrance for extension 
>  - extensionStart Method is called when the extension starts
>    * Pass in the location of the tdengine.xml (tdengine configuration file) file to TDengine to create a database connection and initialize the database and table
>    * Register TDengineInterceptor
>  - 'extensionStop' Method is called when the extension ends its life cycle
>    * Call the 'tdengine.close' method to disconnect the SDK
> + 'TDengineInterceptor.java' Interceptor implementation
>  - onInboundPublish Method is triggered when the message is published
>    * Asynchronously call the 'tdengine.saveData' method to write to the TDengine database

## IoTDB backend

The extension also supports the IoTDB Tree model. The existing storage backend remains selected unless `iotdbEnabled` in `storage.xml` is explicitly set to `true`:

```xml
<storage>
    <iotdbEnabled>true</iotdbEnabled>
</storage>
```

Configure the IoTDB REST API URL, your authentication credentials, database and device paths, measurements, batch size, bounded queue, and HTTP timeout in `iotdb.xml`. The IoTDB REST service must be enabled. On startup, the extension creates the database and two TEXT measurements through REST v2; failed initialization is logged and the sink is closed.

Each MQTT PUBLISH is stored as one record on a single device: `mqtt_topic` contains the topic and `payload_base64` contains the original payload bytes encoded as Base64 text. Decode that value with Base64 to recover the bytes; empty payloads are retained. Writes use the REST v2 `insertRecords` batch API. A process-local monotonic timestamp prevents records in the same precision unit from overwriting one another. `timestampPrecision` must match the IoTDB server's `timestamp_precision` and accepts `ms`, `us`, or `ns`. The default is `ms`; for more than 1,000 records per device per second, configure both sides to `us` or `ns`.

The queue is bounded. When full, enqueueing waits up to `queueOfferTimeoutMillis`, then drops the publish and logs a warning. Failed batches are retried twice. The extension drains the queue on shutdown; records still pending beyond the HTTP timeout budget are logged as dropped. The IoTDB REST service must use valid credentials and grant write permission to the configured Tree paths.

## Compilation steps
```shell script
mvn clean
mvn package
```

## Deployment
### TDengine 
See official documentation: [https://www.taosdata.com/cn/getting-started/](https://www.taosdata.com/cn/getting-started/)

### HiveMQ
See official documentation: [https://www.hivemq.com/docs/hivemq/4.4/user-guide/install-hivemq.html](https://www.hivemq.com/docs/hivemq/4.4/user-guide/install-hivemq.html)

### Extension deployment
> 1. Unzip the packaged compressed package such as: hivemq-tdengine-extension-{version}-distribution.zip to the extensions folder of the HiveMQ directory
> 2. The existing storage backend remains the default. Enable `iotdbEnabled` in `storage.xml` to choose IoTDB, then edit its XML configuration file.
> 3. The selected backend's database and table/measurements are initialized automatically at extension startup

### Precautions
> 1. When using sdk to save payload, if the number of concurrency is too high, it will cause "Invalid result set pointer",According to issue #1477 [https://github.com/taosdata/TDengine/issues/1477](https://github.com/taosdata/TDengine/issues/1477) The error remains after modification, so use ReentrantLock to lock to avoid competition
> 2. If the ip attribute of the dengine.xml file specifies the type as sdk, the domain name should be used or the host should be modified. The ip address cannot be used directly. See details [https://www.taosdata.com/blog/2020/09/11/1824.html](https://www.taosdata.com/blog/2020/09/11/1824.html)

TDengine FAQ: [https://www.taosdata.com/cn/documentation/faq/](https://www.taosdata.com/cn/documentation/faq/)

### Performance test results
#### Environment
> * TDengine : docker Single node  V2.0.3.1
> * HiveMQ : Single node V4.4.1
> * CPU : Intel 9600k @ 3.70GHz
> * Memory : 16,224 MB
> * System : Microsoft Windows 10 Professional 2004
#### Test Results
Because of the mqttloader payload encoding problem, TDengine cannot recognize it, so the payload will be base64-encoded and then attempted to write (the result is not very meaningful, because the payload can generally be directly parsed by GBK encoding during normal writing, thereby avoiding encoding Base64)
##### http

```shell script
.\mqttloader -b tcp://127.0.0.1:1883 -p 1 -s 1 -m 1000

-----Publisher-----
Maximum throughput[msg/s]: 1000
Average throughput[msg/s]: 1000.00
Number of published messages: 1000
Per second throughput[msg/s]: 1000

-----Subscriber-----
Maximum throughput[msg/s]: 326
Average throughput[msg/s]: 166.67
Number of received messages: 1000
Per second throughput[msg/s]: 326, 109, 220, 109, 74, 162
Maximum latency[ms]: 5709
Average latency[ms]: 2326.10
```

```shell script
.\mqttloader -b tcp://127.0.0.1:1883 -p 10 -s 10 -m 100

-----Publisher-----
Maximum throughput[msg/s]: 1000
Average throughput[msg/s]: 1000.00
Number of published messages: 1000
Per second throughput[msg/s]: 1000

-----Subscriber-----
Maximum throughput[msg/s]: 3830
Average throughput[msg/s]: 3333.33
Number of received messages: 10000
Per second throughput[msg/s]: 3410, 3830, 2760
Maximum latency[ms]: 2506
Average latency[ms]: 1349.07
```

```shell script
.\mqttloader -b tcp://127.0.0.1:1883 -p 10 -s 10 -m 1000

-----Publisher-----
Maximum throughput[msg/s]: 10000
Average throughput[msg/s]: 10000.00
Number of published messages: 10000
Per second throughput[msg/s]: 10000

-----Subscriber-----
Maximum throughput[msg/s]: 8329
Average throughput[msg/s]: 6666.67
Number of received messages: 100000
Per second throughput[msg/s]: 4070, 7590, 8329, 8191, 6410, 8142, 7188, 8190, 8174, 7736, 7000, 7561, 6059, 3870, 1490
Maximum latency[ms]: 14631
Average latency[ms]: 6826.93
```

##### sdk

```shell script
.\mqttloader -b tcp://127.0.0.1:1883 -p 1 -s 1 -m 1000

-----Publisher-----
Maximum throughput[msg/s]: 1000
Average throughput[msg/s]: 1000.00
Number of published messages: 1000
Per second throughput[msg/s]: 1000

-----Subscriber-----
Maximum throughput[msg/s]: 676
Average throughput[msg/s]: 333.33
Number of received messages: 1000
Per second throughput[msg/s]: 676, 109, 215
Maximum latency[ms]: 2295
Average latency[ms]: 821.72
```

```shell script
.\mqttloader -b tcp://127.0.0.1:1883 -p 10 -s 10 -m 100

-----Publisher-----
Maximum throughput[msg/s]: 1000
Average throughput[msg/s]: 1000.00
Number of published messages: 1000
Per second throughput[msg/s]: 1000

-----Subscriber-----
Maximum throughput[msg/s]: 5357
Average throughput[msg/s]: 5000.00
Number of received messages: 10000
Per second throughput[msg/s]: 4643, 5357
Maximum latency[ms]: 1841
Average latency[ms]: 1008.62
```

```shell script
.\mqttloader -b tcp://127.0.0.1:1883 -p 10 -s 10 -m 1000
-----Publisher-----
Maximum throughput[msg/s]: 10000
Average throughput[msg/s]: 10000.00
Number of published messages: 10000
Per second throughput[msg/s]: 10000

-----Subscriber-----
Maximum throughput[msg/s]: 7600
Average throughput[msg/s]: 6250.00
Number of received messages: 100000
Per second throughput[msg/s]: 2310, 6640, 5850, 7220, 6070, 6990, 7410, 7320, 7470, 7600, 7420, 7290, 7470, 6990, 4940, 1010
Maximum latency[ms]: 14980
Average latency[ms]: 7762.69
```
