# HiveMQ TDengine 插件

## 目标
将 publish 到 HiveMQ 的数据按照 ts(时间戳) topic(消息主题) payload(消息内容) 实时写入 TDengine 数据库  
写入支持 HTTP 和 SDK 两种方式

## 执行过程
> + 读取配置文件
> + 创建 TDengine 连接
> + 创建库 (库名在配置文件 db 配置项)
> + 创建表 (表名在配置文件 table 配置项)
> + 拦截所有 publish 消息
> + 将 topic 和 payload 异步写入表中 
>   - topic 列名在配置文件 topicColumn 配置项
>   - payload 列名在配置文件 PayloadColumn 配置项
>   - 如果 payload 包含特殊字符(GBK 无法编码)尝试使用 base64 编码 payload
>   - 出现异常将抛弃该消息并打印异常
> + 插件卸载时调用关闭数据库连接

## 架构
> + TDengine.java 负责处理数据库连接 创建库表 写入数据
> + TDengineExtension.java 为插件主入口 
>  - extensionStart 方法在插件启动时调用
>    * 传入 tdengine.xml (tdengine配置文件)文件位置给 TDengine 进行创建数据库连接和初始化库表
>    * 注册 TDengineInterceptor
>  - extensionStop 方法在插件结束生命周期时调用
>    * 调用 tdengine.close 方法断开 SDK 连接
> + TDengineInterceptor.java 拦截器实现
>  - onInboundPublish 方法在消息 publish 时触发
>    * 异步调用 tdengine.saveData 方法写入 TDengine 数据库

## IoTDB 后端

扩展同时支持 IoTDB Tree 模型。未改动分发包中的 `storage.xml` 配置时，会继续使用现有存储后端。选择 IoTDB 时，将其中的 `iotdbEnabled` 改为 `true`：

```xml
<storage>
    <iotdbEnabled>true</iotdbEnabled>
</storage>
```

`iotdb.xml` 配置 IoTDB REST API 地址、认证信息、数据库路径、设备路径、测点名、批次大小、有界队列和 HTTP 超时。需将自己的认证信息填入配置文件，并启用 IoTDB REST 服务。扩展启动时通过 REST v2 创建数据库及两个 TEXT 测点；初始化失败会记录错误并关闭 sink。

每条 MQTT PUBLISH 写入同一个设备的一条记录：`mqtt_topic` 保存 topic，`payload_base64` 保存原始 payload 字节的 Base64 文本。payload 可通过 Base64 解码还原，空 payload 也会保留。写入使用 REST v2 `insertRecords` 批量接口；相同精度的时间戳通过进程内单调递增避免覆盖。`timestampPrecision` 必须与 IoTDB 服务的 `timestamp_precision` 一致，支持 `ms`、`us`、`ns`；默认 `ms`，每设备速率超过每秒 1000 条时应同时将两端调整为 `us` 或 `ns`。

队列有容量上限，满载时最多等待 `queueOfferTimeoutMillis`，超时后该消息会被丢弃并记录警告。批次写入失败会重试两次；扩展停止时会排空队列，超过 HTTP 超时预算仍未完成的记录会记录为丢弃。IoTDB 服务端需使用有效认证信息，并授予配置 Tree 路径的写入权限。

## 编译步骤
```shell script
mvn clean
mvn package
```

## 部署方法
### TDengine 
见官方文档: [https://www.taosdata.com/cn/getting-started/](https://www.taosdata.com/cn/getting-started/)

### HiveMQ
见官方文档: [https://www.hivemq.com/docs/hivemq/4.4/user-guide/install-hivemq.html](https://www.hivemq.com/docs/hivemq/4.4/user-guide/install-hivemq.html)

### 插件部署
> 1. 将打包好的压缩包如: hivemq-tdengine-extension-{version}-distribution.zip 解压到 HiveMQ 目录的 extensions 文件夹下  
> 2. 默认使用现有存储后端；修改 storage.xml 的 iotdbEnabled 后，可编辑对应的 XML 配置文件
> 3. 插件启动时会自动初始化所选后端的数据库和表/测点

### 注意事项
> 1. 在使用 sdk 进行保存 payload 时如果并发数过会引发 “Invalid result set pointer”,根据issue #1477 [https://github.com/taosdata/TDengine/issues/1477](https://github.com/taosdata/TDengine/issues/1477) 修改后错误依旧，所以使用 ReentrantLock 加锁避免竞争。
> 2. tdengine.xml 文件的 ip 属性如果指定 type 为 sdk 则应使用域名或修改 host 不可直接使用 ip 地址 详情见 [https://www.taosdata.com/blog/2020/09/11/1824.html](https://www.taosdata.com/blog/2020/09/11/1824.html)

TDengine 的 FAQ: [https://www.taosdata.com/cn/documentation/faq/](https://www.taosdata.com/cn/documentation/faq/)

### 性能测试结果
#### 环境
> * TDengine : docker 单节点  V2.0.3.1
> * HiveMQ : 裸机 单节点 V4.4.1
> * CPU : Intel 9600k @ 3.70GHz
> * Memory : 16,224 MB
> * System : Microsoft Windows 10 专业版 2004
#### 测试结果
因为 mqttloader 测试 payload 编码问题 TDengine 无法识别 所以会将 payload 进行 base64 编码后尝试写入(结果参考意义不是很大,因为正常写入时 payload 一般可以直接 GBK 编码解析,从而避免编码 Base64 )

##### http
1 发布者 1订阅者 每个发布者发布 1000 条
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

10 发布者 10订阅者 每个发布者发布 100 条
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

10 发布者 10订阅者 每个发布者发布 1000 条
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
1 发布者 1订阅者 每个发布者发布 1000 条
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

10 发布者 10订阅者 每个发布者发布 100 条
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

10 发布者 10订阅者 每个发布者发布 1000 条
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
