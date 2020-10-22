
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

import com.alibaba.fastjson.JSONObject;
import com.hivemq.extension.sdk.api.annotations.NotNull;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link TDengine}
 * Processing tdengine related operations, including initializing connections and writing data
 * the ip must be the username when connecting to tdengine with sdk
 * FAQ ( https://github.com/taosdata/TDengine/blob/develop/documentation20/webdocs/markdowndocs/faq-ch.md )
 */
public class TDengine {
    private static final @NotNull Logger log = LoggerFactory.getLogger(TDengine.class);
    private static final int SUCCESS_CODE = 200;
    private String ip;
    private String port;
    private String username;
    private String password;
    private String type;
    private Integer maxlength;
    private Connection conn;
    private String token;
    private String url;
    private Statement stmt = null;
    private String db;
    private String table;
    private String topicColumn;
    private String PayloadColumn;
    private String insertTemplate;
    private final CloseableHttpClient client;
    private boolean httpLock;
    private final Lock lock = new ReentrantLock();

    /**
     * @param configPath 配置文件路径
     * @throws Exception 解析配置文件异常或连接数据库创建库和表异常
     */
    public TDengine(String configPath) throws Exception {
        File file = new File(configPath);
        SAXReader read = new SAXReader();
        org.dom4j.Document doc = read.read(file);
        Element root = doc.getRootElement();
        this.setType(root.elementTextTrim("type"));
        this.setIp(root.elementTextTrim("ip"));
        this.setPort(root.elementTextTrim("port"));
        this.setUsername(root.elementTextTrim("username"));
        this.setPassword(root.elementTextTrim("password"));
        this.setDb(root.elementTextTrim("db"));
        this.setTable(root.elementTextTrim("table"));
        this.setTopicColumn(root.elementTextTrim("topicColumn"));
        this.setPayloadColumn(root.elementTextTrim("PayloadColumn"));
        this.setMaxlength(Integer.valueOf(root.elementTextTrim("maxlength")));
        this.setInsertTemplate(String.format("import into %s.%s values (now,'%%s','%%s')", this.getDb(), this.getTable()));
        this.setHttpLock(Boolean.parseBoolean(root.elementTextTrim("httpLock")));
        this.client = HttpClients.createDefault();
    }

    public boolean connect() {
        switch (getType()) {
            case "http":
                this.url = String.format("http://%s:%s/rest/sql", this.getIp(), this.getPort());
                final Base64.Encoder encoder = Base64.getEncoder();
                final byte[] textByte = String.format("%s:%s", this.getUsername(), this.getPassword()).getBytes(StandardCharsets.UTF_8);
                this.token = "Basic " + encoder.encodeToString(textByte);
                return this.doConnectHttp();
            case "sdk":
                return this.doConnectSDK();
            default:
                this.setType("http");
                log.error("tdengine connect type unsupported using http");
                return this.doConnectHttp();
        }
    }

    private boolean doConnectHttp() {
        return httpCreateDBAndTable();
    }

    private boolean doConnectSDK() {
        try {
            Class.forName("com.taosdata.jdbc.TSDBDriver");
        } catch (ClassNotFoundException e) {
            log.error("get tdengine class error", e);
            return false;
        }
        String connectStr = String.format("jdbc:TAOS://%s:%s/?user=%s&password=%s", this.getIp(), this.getPort(), this.getUsername(), this.getPassword());
        try {
            this.conn = DriverManager.getConnection(connectStr);
        } catch (SQLException e) {
            log.error("connect to tdengine false", e);
        }
        return sdkCreateDBAndTable();
    }

    private boolean sdkCreateDBAndTable() {
        if (this.conn != null) {
            try {
                this.stmt = this.conn.createStatement();
            } catch (SQLException e) {
                log.error("tdengine create statement error", e);
                return false;
            }
            if (this.stmt == null) {
                log.error("tdengine statement is null");
                return false;
            }
            // create database
            try {
                this.stmt.executeUpdate(String.format("create database if not exists %s", this.getDb()));
            } catch (SQLException e) {
                log.error("tdengine create database error", e);
                return false;
            }
            // create table
            try {
                this.stmt.executeUpdate(
                        String.format(
                                "create table if not exists %s.%s (ts timestamp, %s NCHAR(%d), %s NCHAR(%d))",
                                this.getDb(),
                                this.getTable(),
                                this.getTopicColumn(),
                                this.getMaxlength(),
                                this.getPayloadColumn(),
                                this.getMaxlength())
                );
            } catch (SQLException e) {
                log.error("tdengine create table error", e);
                return false;
            }
        }
        return true;
    }

    private boolean httpCreateDBAndTable() {
        JSONObject createDBResult = doPost(String.format("create database if not exists %s", this.getDb()));
        if (createDBResult == null) {
            log.error("http create db error");
            return false;
        }
        JSONObject createTableResult = doPost(
                String.format(
                        "create table if not exists %s.%s (ts timestamp, %s NCHAR(%d), %s NCHAR(%d))",
                        this.getDb(),
                        this.getTable(),
                        this.getTopicColumn(),
                        this.getMaxlength(),
                        this.getPayloadColumn(),
                        this.getMaxlength())
        );
        if (createTableResult == null) {
            log.error("http create table error");
            return false;
        }
        return true;
    }

    public void close() {
        if (this.stmt != null) {
            try {
                this.stmt.close();
            } catch (SQLException e) {
                log.warn("close tdengine statement error", e);
            }
        }
        if (this.conn != null) {
            try {
                this.conn.close();
            } catch (SQLException e) {
                log.warn("close tdengine connect error", e);
            }
        }
        if (this.client != null) {
            try {
                this.client.close();
            } catch (IOException e) {
                log.error("close client error", e);
            }
        }
    }

    private JSONObject doPost(String sql) {
        JSONObject jsonObject;
        CloseableHttpResponse response = null;
        try {
            HttpPost post = new HttpPost(this.url);
            StringEntity entity = new StringEntity(sql, "UTF-8");
            post.setEntity(entity);
            post.setHeader(new BasicHeader("Content-Type", "application/json"));
            post.setHeader(new BasicHeader("Authorization", this.token));
            post.setHeader(new BasicHeader("Accept", "text/plain;charset=utf-8"));
            response = this.client.execute(post);
            int statusCode = response.getStatusLine().getStatusCode();
            String result = EntityUtils.toString(response.getEntity(), "UTF-8");
            if (SUCCESS_CODE == statusCode) {
                try {
                    jsonObject = JSONObject.parseObject(result);
                    return jsonObject;
                } catch (Exception e) {
                    log.error("parse json error", e);
                    return null;
                }
            } else {
                log.error("HttpClientService errorMsg：{}", result);
                return null;
            }
        } catch (Exception e) {
            log.error("Http Exception：", e);
            return null;
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    log.error("close response error", e);
                }
            }

        }
    }

    /**
     * save topic and payload to table "hivemq.mqtt_payload"
     *
     * @param topic   主题
     * @param payload 消息内容
     * @return boolen
     */
    public boolean saveData(String topic, String payload) {
        final String sql = String.format(
                this.getInsertTemplate(),
                topic,
                payload
        );

        switch (this.getType()) {
            case "http":
                if (this.isHttpLock()) {
                    lock.lock();
                }
                try {
                    JSONObject result = this.doPost(sql);
                    if (result == null) {
                        log.info("saveData to TDengine error, try to use base64");
                        final String retrySql = String.format(
                                this.getInsertTemplate(),
                                topic,
                                Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                        );
                        JSONObject retryResult = this.doPost(retrySql);
                        if (retryResult == null) {
                            return false;
                        }
                        String status = retryResult.getString("status");
                        return status.equals("succ");
                    }
                    String status = result.getString("status");
                    return status.equals("succ");
                } finally {
                    if (this.isHttpLock()) {
                        lock.lock();
                    }
                }
            case "sdk":
                lock.lock();
                try {
                    if (this.stmt != null) {

                        this.stmt.executeUpdate(sql);
                    } else {
                        try {
                            this.stmt = this.conn.createStatement();
                        } catch (SQLException e) {
                            log.error("recreate statement error", e);
                        }
                    }
                } catch (SQLException e) {
                    if (e.getMessage().startsWith("TDengine Error: syntax error")) {
//                        maybe the codec error,try to use base64
                        final String retrySql = String.format(
                                this.getInsertTemplate(),
                                topic,
                                Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                        );
                        try {
                            this.stmt.executeUpdate(retrySql);
                        } catch (SQLException e2) {
                            log.error("save data error", e2);
                            return false;
                        }
                    } else {
                        log.error("retry save data error", e);
                        return false;
                    }
                } finally {
                    lock.unlock();
                }
                return true;
            default:
                return false;
        }
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) throws Exception {
        if (ip.equals("")) {
            throw new Exception("ip is required");
        }
        if (ip.startsWith("http://")) {
            ip = ip.substring(7);
        }
        this.ip = ip;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        if (username.equals("")) {
            username = "root";
        }
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        if (password.equals("")) {
            password = "taosdata";
        }
        this.password = password;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        if (port.equals("")) {
            if ("sdk".equals(this.type)) {
                port = "6030";
            } else {
                port = "6041";
            }
        }
        this.port = port;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        if (type.equals("")) {
            type = "http";
        }
        this.type = type;
    }


    public Integer getMaxlength() {
        return maxlength;
    }

    public void setMaxlength(Integer maxlength) {
        if (maxlength < 64) {
            maxlength = 64;
        }
        this.maxlength = maxlength;
    }

    public String getDb() {
        return db;
    }

    public void setDb(String db) {
        if (db.equals("")) {
            db = "hivemq";
        }
        this.db = db;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        if (table.equals("")) {
            table = "mqtt_payload";
        }
        this.table = table;
    }

    public String getTopicColumn() {
        return topicColumn;
    }

    public void setTopicColumn(String topicColumn) {
        if (topicColumn.equals("")) {
            this.topicColumn = "topic";
        }
        this.topicColumn = topicColumn;
    }

    public String getPayloadColumn() {
        return PayloadColumn;
    }

    public void setPayloadColumn(String payloadColumn) {
        if (payloadColumn.equals("")) {
            payloadColumn = "payload";
        }
        PayloadColumn = payloadColumn;
    }

    public String getInsertTemplate() {
        return insertTemplate;
    }

    public void setInsertTemplate(String insertTemplate) {
        this.insertTemplate = insertTemplate;
    }

    public boolean isHttpLock() {
        return httpLock;
    }

    public void setHttpLock(boolean httpLock) {
        this.httpLock = httpLock;
    }
}
