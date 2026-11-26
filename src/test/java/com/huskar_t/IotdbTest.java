package com.huskar_t;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IotdbTest {
    @TempDir
    Path temporaryDirectory;

    private HttpServer server;
    private ExecutorService httpExecutor;
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger responseCode = new AtomicInteger(200);
    private final String testPassword = UUID.randomUUID().toString();
    private CountDownLatch insertRecordsLatch;

    @Test
    void keepsExistingWriterWhenStorageSelectionIsAbsentOrEmpty() throws Exception {
        Path missing = temporaryDirectory.resolve("missing-storage.xml");
        assertFalse(StorageBackendSelector.readIotdbEnabled(missing.toFile()));
        Path empty = temporaryDirectory.resolve("storage.xml");
        Files.writeString(empty, "<storage/>", StandardCharsets.UTF_8);
        assertFalse(StorageBackendSelector.readIotdbEnabled(empty.toFile()));
        Files.writeString(empty, "<storage><iotdbEnabled>true</iotdbEnabled></storage>", StandardCharsets.UTF_8);
        assertTrue(StorageBackendSelector.readIotdbEnabled(empty.toFile()));
        Files.writeString(empty, "<storage><iotdbEnabled>unexpected</iotdbEnabled></storage>", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> StorageBackendSelector.readIotdbEnabled(empty.toFile()));
    }

    @Test
    void copiesPayloadWithoutConsumingSourceAndPreservesEmptyPublishes() {
        assertArrayEquals(new byte[0], IotdbInterceptor.copyPayload(Optional.empty()));
        ByteBuffer payload = ByteBuffer.wrap(new byte[]{1, 2, 3});
        payload.position(1);
        assertArrayEquals(new byte[]{2, 3}, IotdbInterceptor.copyPayload(Optional.of(payload)));
        assertEquals(1, payload.position());
    }

    @BeforeEach
    void startHttpServer() throws IOException {
        insertRecordsLatch = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpExecutor = Executors.newCachedThreadPool();
        server.setExecutor(httpExecutor);
        server.createContext("/rest/v2/", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            requests.add(new CapturedRequest(path,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(requestBody, StandardCharsets.UTF_8)));
            if (path.endsWith("/insertRecords")) {
                insertRecordsLatch.countDown();
            }
            byte[] responseBody = ("{\"code\":" + responseCode.get() + ",\"message\":\"test\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopHttpServer() {
        if (server != null) {
            server.stop(0);
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
        }
    }

    @Test
    void createsTreeSchemaAndWritesOriginalPayloadAsBase64Batch() throws Exception {
        Iotdb iotdb = new Iotdb(writeConfig("root.hivemq", 2, "ms").toFile());
        assertTrue(iotdb.connect());
        byte[] firstPayload = new byte[]{0, '\'', (byte) 0xff};
        byte[] secondPayload = "温度".getBytes(StandardCharsets.UTF_8);

        assertTrue(iotdb.saveData("factory/line-1", firstPayload));
        assertTrue(iotdb.saveData("factory/line-2", secondPayload));
        assertTrue(insertRecordsLatch.await(3, TimeUnit.SECONDS));
        iotdb.close();

        assertEquals(4, requests.size());
        assertEquals("/rest/v2/nonQuery", requests.get(0).path);
        assertTrue(JSONObject.parseObject(requests.get(0).body).getString("sql")
                .equals("CREATE DATABASE root.hivemq"));
        assertTrue(JSONObject.parseObject(requests.get(1).body).getString("sql")
                .contains("root.hivemq.mqtt_client.mqtt_topic"));
        assertTrue(JSONObject.parseObject(requests.get(2).body).getString("sql")
                .contains("root.hivemq.mqtt_client.payload_base64"));

        CapturedRequest insert = requests.get(3);
        assertEquals("/rest/v2/insertRecords", insert.path);
        assertEquals("Basic " + Base64.getEncoder().encodeToString(("fixture:" + testPassword).getBytes(StandardCharsets.UTF_8)),
                insert.authorization);
        JSONObject body = JSONObject.parseObject(insert.body);
        JSONArray timestamps = body.getJSONArray("timestamps");
        assertEquals(2, timestamps.size());
        assertNotEquals(timestamps.getLongValue(0), timestamps.getLongValue(1));
        JSONArray measurements = body.getJSONArray("measurements_list").getJSONArray(0);
        assertEquals("mqtt_topic", measurements.getString(0));
        assertEquals("payload_base64", measurements.getString(1));
        JSONArray dataTypes = body.getJSONArray("data_types_list").getJSONArray(1);
        assertEquals("TEXT", dataTypes.getString(0));
        assertEquals("TEXT", dataTypes.getString(1));
        assertEquals("factory/line-1", body.getJSONArray("values_list").getJSONArray(0).getString(0));
        assertArrayEquals(firstPayload, Base64.getDecoder().decode(
                body.getJSONArray("values_list").getJSONArray(0).getString(1)));
        assertArrayEquals(secondPayload, Base64.getDecoder().decode(
                body.getJSONArray("values_list").getJSONArray(1).getString(1)));
        assertEquals("root.hivemq.mqtt_client", body.getJSONArray("devices").getString(0));
        assertEquals("root.hivemq.mqtt_client", body.getJSONArray("devices").getString(1));
        assertTrue(!body.getBooleanValue("is_aligned"));
    }

    @Test
    void reportsSchemaCreationFailure() throws Exception {
        responseCode.set(500);
        Iotdb iotdb = new Iotdb(writeConfig("root.hivemq", 1, "ms").toFile());
        try {
            assertTrue(!iotdb.connect());
        } finally {
            iotdb.close();
        }
        assertEquals(1, requests.size());
    }

    @Test
    void rejectsInvalidTreePathsBeforeMakingRequests() throws Exception {
        Path config = writeConfig("root.hivemq; DROP DATABASE root.x", 1, "ms");
        assertThrows(IllegalArgumentException.class, () -> new Iotdb(config.toFile()));
        assertTrue(requests.isEmpty());
    }

    private Path writeConfig(String database, int batchSize, String precision) throws IOException {
        String xml = "<iotdb>"
                + "<url>http://127.0.0.1:" + server.getAddress().getPort() + "/</url>"
                + "<username>fixture</username><password>" + testPassword + "</password>"
                + "<database>" + database + "</database>"
                + "<device>root.hivemq.mqtt_client</device>"
                + "<batchSize>" + batchSize + "</batchSize>"
                + "<batchFlushIntervalMillis>50</batchFlushIntervalMillis>"
                + "<queueCapacity>20</queueCapacity>"
                + "<queueOfferTimeoutMillis>100</queueOfferTimeoutMillis>"
                + "<requestTimeoutMillis>500</requestTimeoutMillis>"
                + "<timestampPrecision>" + precision + "</timestampPrecision>"
                + "</iotdb>";
        Path config = temporaryDirectory.resolve("iotdb.xml");
        Files.writeString(config, xml, StandardCharsets.UTF_8);
        return config;
    }

    private static final class CapturedRequest {
        private final String path;
        private final String authorization;
        private final String body;

        private CapturedRequest(String path, String authorization, String body) {
            this.path = path;
            this.authorization = authorization;
            this.body = body;
        }
    }
}
