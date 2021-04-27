/*
 * Copyright © 2021 ProStore
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.arenadata.dtm.query.execution.core.edml.mppw.factory.impl;

import io.arenadata.dtm.query.execution.core.edml.mppw.factory.MppwErrorMessageFactory;
import io.arenadata.dtm.query.execution.core.edml.mppw.dto.MppwStopFuture;
import io.arenadata.dtm.query.execution.core.edml.mppw.dto.MppwStopReason;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

@Component
public class MppwErrorMessageFactoryImpl implements MppwErrorMessageFactory {
    private static final String OFFSET_RECEIVED_TEMPLATE = "Plugin: %s, status: %s, offset: %d";
    private static final String WITH_ERROR_REASON_TEMPLATE = "Plugin: %s, status: %s, offset: %d, reason: %s";

    @Override
    public String create(MppwStopFuture stopFuture) {
        if (MppwStopReason.OFFSET_RECEIVED != stopFuture.getStopReason() || stopFuture.getFuture().failed()) {
            Throwable error = stopFuture.getFuture().failed() ? stopFuture.getFuture().cause() : stopFuture.getCause();
            return String.format(WITH_ERROR_REASON_TEMPLATE,
                    stopFuture.getSourceType().name(), stopFuture.getStopReason().name(),
                    stopFuture.getOffset() == null ? -1L : stopFuture.getOffset(),
                    error == null ? "" : NestedExceptionUtils.getMostSpecificCause(error).getMessage());
        } else {
            return String.format(OFFSET_RECEIVED_TEMPLATE,
                    stopFuture.getSourceType().name(), stopFuture.getStopReason().name(),
                    stopFuture.getOffset() == null ? -1L : stopFuture.getOffset());
        }
    }
}
