
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

import static com.huskar_t.Util.getStringFromByteBuffer;

/**
 * {@link PublishInboundInterceptor},
 * it save the payload of every incoming PUBLISH with any topic to tdengine'.
 *
 * @author huskar-t
 * @since 0.0.1
 */
public class TDengineInterceptor implements PublishInboundInterceptor {
    private final TDengine tdengine;
    private static final @NotNull Logger log = LoggerFactory.getLogger(TDengine.class);

    public TDengineInterceptor(TDengine tdengine) {
        this.tdengine = tdengine;
    }

    @Override
    public void onInboundPublish(final @NotNull PublishInboundInput publishInboundInput, final @NotNull PublishInboundOutput publishInboundOutput) {
        final Async<PublishInboundOutput> asyncOutput = publishInboundOutput.async(Duration.ofSeconds(10), TimeoutFallback.FAILURE);
        final CompletableFuture<?> taskFuture = Services.extensionExecutorService().submit(() -> {
            final ModifiablePublishPacket publishPacket = publishInboundOutput.getPublishPacket();
            try {
                @NotNull Optional<ByteBuffer> payload = publishPacket.getPayload();
                if (payload.isPresent()) {
                    final String payloadStr = getStringFromByteBuffer(payload.orElse(null));
                    if (payloadStr.equals("")) {
                        return;
                    }
                    tdengine.saveData(publishPacket.getTopic(), payloadStr.replace("'", "\\'"));
                }
            } catch (Exception e) {
                log.error("save data to tdengine error", e);
            }
        });
        // add a callback for completion of the task
        taskFuture.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                log.error("tdengine save data error", throwable);
            }
            // resume output to tell HiveMQ that asynchronous precessing is done
            asyncOutput.resume();
        });
    }

}