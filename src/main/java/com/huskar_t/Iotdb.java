/*
 * Copyright 2018-present HiveMQ GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huskar_t;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

/** IoTDB Tree-model writer using the REST API v2. */
public class Iotdb implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(Iotdb.class);
    private static final Pattern PATH_PATTERN = Pattern.compile("root(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");
    private static final Pattern MEASUREMENT_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final String TEXT_TYPE = "TEXT";

    private final String baseUrl;
    private final String username;
    private final String password;
    private final String databasePath;
    private final String devicePath;
    private final String topicMeasurement;
    private final String payloadMeasurement;
    private final long timestampUnitMultiplier;
    private final int batchSize;
    private final long batchFlushIntervalMillis;
    private final long queueOfferTimeoutMillis;
    private final int requestTimeoutMillis;
    private final ArrayBlockingQueue<Record> queue;
    private final CloseableHttpClient client;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean forceStop = new AtomicBoolean(false);
    private final AtomicLong lastTimestamp;
    private final ReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private volatile Thread worker;

    public Iotdb(String configPath) throws Exception {
        this(new File(configPath));
    }

    public Iotdb(File configFile) throws Exception {
        SAXReader reader = new SAXReader();
        reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Element root = reader.read(configFile).getRootElement();

        this.baseUrl = trimTrailingSlash(requiredText(root, "url"));
        this.username = requiredText(root, "username");
        this.password = requiredText(root, "password");
        this.databasePath = valueOrDefault(root.elementTextTrim("database"), "root.hivemq");
        this.devicePath = valueOrDefault(root.elementTextTrim("device"), this.databasePath + ".mqtt_client");
        this.topicMeasurement = valueOrDefault(root.elementTextTrim("topicMeasurement"), "mqtt_topic");
        this.payloadMeasurement = valueOrDefault(root.elementTextTrim("payloadMeasurement"), "payload_base64");
        this.timestampUnitMultiplier = timestampUnitMultiplier(root.elementTextTrim("timestampPrecision"));
        this.batchSize = positiveInt(root, "batchSize", 100);
        this.batchFlushIntervalMillis = positiveInt(root, "batchFlushIntervalMillis", 10);
        int queueCapacity = positiveInt(root, "queueCapacity", 10000);
        this.queueOfferTimeoutMillis = positiveInt(root, "queueOfferTimeoutMillis", 100);
        this.requestTimeoutMillis = positiveInt(root, "requestTimeoutMillis", 5000);

        validatePath("database", databasePath);
        validatePath("device", devicePath);
        if (!devicePath.startsWith(databasePath + ".")) {
            throw new IllegalArgumentException("device must be below the configured database path");
        }
        validateMeasurement("topicMeasurement", topicMeasurement);
        validateMeasurement("payloadMeasurement", payloadMeasurement);
        if (topicMeasurement.equals(payloadMeasurement)) {
            throw new IllegalArgumentException("topicMeasurement and payloadMeasurement must be different");
        }
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.lastTimestamp = new AtomicLong(System.currentTimeMillis() * timestampUnitMultiplier - 1L);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(requestTimeoutMillis)
                .setConnectionRequestTimeout(requestTimeoutMillis)
                .setSocketTimeout(requestTimeoutMillis)
                .build();
        this.client = HttpClients.custom().setDefaultRequestConfig(requestConfig).build();
    }

    public boolean connect() {
        lifecycleLock.writeLock().lock();
        try {
            if (closed.get()) {
                return false;
            }
            if (worker != null) {
                return true;
            }
            if (!ensureSchema("CREATE DATABASE " + databasePath, 501,
                    "SHOW DATABASES " + databasePath, "Database", databasePath)
                    || !ensureTimeseries(topicMeasurement) || !ensureTimeseries(payloadMeasurement)) {
                return false;
            }
            worker = new Thread(this::writeLoop, "hivemq-iotdb-writer");
            worker.setDaemon(true);
            worker.start();
            return true;
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /** Enqueues one MQTT publish. Payload bytes are retained as Base64 in IoTDB TEXT. */
    public boolean saveData(String topic, byte[] payload) {
        lifecycleLock.readLock().lock();
        try {
            if (closed.get() || worker == null) {
                return false;
            }
            long wallClockTimestamp = System.currentTimeMillis() * timestampUnitMultiplier;
            long timestamp = lastTimestamp.updateAndGet(previous -> Math.max(previous + 1L, wallClockTimestamp));
            Record record = new Record(timestamp, topic, Base64.getEncoder().encodeToString(payload));
            if (queue.offer(record, queueOfferTimeoutMillis, TimeUnit.MILLISECONDS)) {
                return true;
            }
            log.warn("IoTDB write queue is full; dropping MQTT publish for topic {}", topic);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while enqueueing MQTT publish for IoTDB");
            return false;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private boolean ensureTimeseries(String measurement) {
        String path = devicePath + "." + measurement;
        return ensureSchema("CREATE TIMESERIES " + path + " WITH DATATYPE=" + TEXT_TYPE + ", ENCODING=PLAIN",
                503, "SHOW TIMESERIES " + path, "DataType", TEXT_TYPE);
    }

    private boolean ensureSchema(String create, int duplicateCode, String show, String column, String expected) {
        try {
            JSONObject result = request("nonQuery", Map.of("sql", create));
            int code = result.getIntValue("code");
            if (code == 200) return true;
            if (code == duplicateCode) {
                JSONObject metadata = request("query", Map.of("sql", show));
                JSONArray names = metadata.getJSONArray("column_names");
                JSONArray values = metadata.getJSONArray("values");
                if (!metadata.containsKey("code") && names != null && values != null && names.size() == values.size()) {
                    int index = names.indexOf(column);
                    if (index >= 0 && List.of(expected).equals(values.get(index))) return true;
                }
            }
            log.warn("Unable to initialize IoTDB schema for {}: {}", create, result);
        } catch (Exception e) {
            log.warn("IoTDB schema initialization failed for " + create, e);
        }
        return false;
    }

    private void writeLoop() {
        while (!forceStop.get() && (!closed.get() || !queue.isEmpty())) {
            List<Record> batch = new ArrayList<>(batchSize);
            try {
                Record first = queue.poll(250, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                long flushDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(batchFlushIntervalMillis);
                while (batch.size() < batchSize) {
                    long remainingNanos = flushDeadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        break;
                    }
                    Record next;
                    try {
                        next = queue.poll(remainingNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                }
                queue.drainTo(batch, batchSize - batch.size());
            } catch (InterruptedException e) {
                if (closed.get() && queue.isEmpty()) {
                    break;
                }
                continue;
            }
            writeBatchWithRetry(batch);
        }
    }

    private void writeBatchWithRetry(List<Record> batch) {
        for (int attempt = 1; attempt <= 3 && !forceStop.get(); attempt++) {
            if (post("insertRecords", makeBatchBody(batch))) {
                return;
            }
            if (attempt < 3) {
                try {
                    Thread.sleep(100L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.error("Failed to write {} MQTT publish record(s) to IoTDB after retries", batch.size());
    }

    private Map<String, Object> makeBatchBody(List<Record> batch) {
        List<Long> timestamps = new ArrayList<>(batch.size());
        List<List<String>> measurements = new ArrayList<>(batch.size());
        List<List<String>> dataTypes = new ArrayList<>(batch.size());
        List<List<String>> values = new ArrayList<>(batch.size());
        List<String> devices = new ArrayList<>(batch.size());
        for (Record record : batch) {
            timestamps.add(record.timestamp);
            measurements.add(List.of(topicMeasurement, payloadMeasurement));
            dataTypes.add(List.of(TEXT_TYPE, TEXT_TYPE));
            values.add(List.of(record.topic, record.payloadBase64));
            devices.add(devicePath);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamps", timestamps);
        body.put("measurements_list", measurements);
        body.put("data_types_list", dataTypes);
        body.put("values_list", values);
        body.put("devices", devices);
        body.put("is_aligned", false);
        return body;
    }

    private boolean post(String endpoint, Map<String, Object> body) {
        try {
            JSONObject result = request(endpoint, body);
            if (result.getIntValue("code") == 200) return true;
            log.warn("IoTDB REST {} returned an error: {}", endpoint, result);
        } catch (Exception e) {
            log.warn("IoTDB REST {} request failed", endpoint, e);
        }
        return false;
    }

    private JSONObject request(String endpoint, Map<String, Object> body) throws IOException {
        HttpPost request = new HttpPost(baseUrl + "/rest/v2/" + endpoint);
        request.setHeader("Content-Type", "application/json; charset=UTF-8");
        request.setHeader("Accept", "application/json");
        String credentials = username + ":" + password;
        request.setHeader("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        request.setEntity(new StringEntity(JSON.toJSONString(body), StandardCharsets.UTF_8));
        try (CloseableHttpResponse response = client.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300 || response.getEntity() == null) {
                throw new IOException("IoTDB REST " + endpoint + " returned HTTP " + status);
            }
            JSONObject result = JSONObject.parseObject(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
            if (result == null) throw new IOException("IoTDB REST " + endpoint + " returned an empty response");
            return result;
        }
    }

    @Override
    public void close() throws IOException {
        lifecycleLock.writeLock().lock();
        try {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
        Thread currentWorker = worker;
        if (currentWorker != null) {
            try {
                currentWorker.join(requestTimeoutMillis * 2L + 2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (currentWorker.isAlive()) {
                log.warn("IoTDB writer did not finish draining before shutdown timeout");
                forceStop.set(true);
                int discarded = queue.size();
                queue.clear();
                if (discarded > 0) {
                    log.error("Discarding {} queued IoTDB record(s) after shutdown timeout", discarded);
                }
                client.close();
                currentWorker.interrupt();
                try {
                    currentWorker.join(requestTimeoutMillis + 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
        }
        client.close();
    }

    private static String requiredText(Element root, String name) {
        String value = root.elementTextTrim(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required in iotdb.xml");
        }
        return value;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static int positiveInt(Element root, String name, int defaultValue) {
        String value = root.elementTextTrim(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return parsed;
    }

    private static long timestampUnitMultiplier(String precision) {
        String value = valueOrDefault(precision, "ms");
        switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "ms":
                return 1L;
            case "us":
                return 1000L;
            case "ns":
                return 1000000L;
            default:
                throw new IllegalArgumentException("timestampPrecision must be ms, us, or ns");
        }
    }

    private static String trimTrailingSlash(String url) {
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static void validatePath(String name, String path) {
        if (!PATH_PATTERN.matcher(path).matches()) {
            throw new IllegalArgumentException(name + " must be a plain IoTDB root path (for example root.hivemq)");
        }
    }

    private static void validateMeasurement(String name, String measurement) {
        if (!MEASUREMENT_PATTERN.matcher(measurement).matches()) {
            throw new IllegalArgumentException(name + " must be a simple IoTDB measurement identifier");
        }
    }

    private static final class Record {
        private final long timestamp;
        private final String topic;
        private final String payloadBase64;

        private Record(long timestamp, String topic, String payloadBase64) {
            this.timestamp = timestamp;
            this.topic = topic;
            this.payloadBase64 = payloadBase64;
        }
    }
}
