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

import com.hivemq.extension.sdk.api.annotations.NotNull;
import com.hivemq.extension.sdk.api.async.Async;
import com.hivemq.extension.sdk.api.async.TimeoutFallback;
import com.hivemq.extension.sdk.api.interceptor.publish.PublishInboundInterceptor;
import com.hivemq.extension.sdk.api.interceptor.publish.parameter.PublishInboundInput;
import com.hivemq.extension.sdk.api.interceptor.publish.parameter.PublishInboundOutput;
import com.hivemq.extension.sdk.api.packets.publish.ModifiablePublishPacket;
import com.hivemq.extension.sdk.api.services.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Copies inbound MQTT data into the bounded IoTDB writer queue asynchronously. */
public class IotdbInterceptor implements PublishInboundInterceptor {
    private static final Logger log = LoggerFactory.getLogger(IotdbInterceptor.class);
    private final Iotdb iotdb;

    public IotdbInterceptor(Iotdb iotdb) {
        this.iotdb = iotdb;
    }

    @Override
    public void onInboundPublish(
            final @NotNull PublishInboundInput publishInboundInput,
            final @NotNull PublishInboundOutput publishInboundOutput) {
        final Async<PublishInboundOutput> asyncOutput = publishInboundOutput.async(
                Duration.ofSeconds(10), TimeoutFallback.FAILURE);
        final CompletableFuture<?> taskFuture = Services.extensionExecutorService().submit(() -> {
            final ModifiablePublishPacket publishPacket = publishInboundOutput.getPublishPacket();
            try {
                iotdb.saveData(publishPacket.getTopic(), copyPayload(publishPacket.getPayload()));
            } catch (Exception e) {
                log.error("Failed to enqueue MQTT publish for IoTDB", e);
            }
        });
        taskFuture.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                log.error("IoTDB publish task failed", throwable);
            }
            asyncOutput.resume();
        });
    }

    static byte[] copyPayload(Optional<ByteBuffer> payload) {
        if (!payload.isPresent()) {
            return new byte[0];
        }
        ByteBuffer buffer = payload.get().duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
