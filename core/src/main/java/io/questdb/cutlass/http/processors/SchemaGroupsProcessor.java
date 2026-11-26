/*+*****************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2026 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cutlass.http.processors;

import io.questdb.ServerConfiguration;
import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.CairoException;
import io.questdb.cutlass.http.HttpChunkedResponse;
import io.questdb.cutlass.http.HttpConnectionContext;
import io.questdb.cutlass.http.HttpKeywords;
import io.questdb.cutlass.http.HttpPostPutProcessor;
import io.questdb.cutlass.http.HttpRequestHandler;
import io.questdb.cutlass.http.HttpRequestHeader;
import io.questdb.cutlass.http.HttpRequestProcessor;
import io.questdb.cutlass.http.LocalValue;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.network.PeerDisconnectedException;
import io.questdb.network.PeerIsSlowToReadException;
import io.questdb.preferences.SchemaGroupsStore;
import io.questdb.std.str.DirectUtf8Sink;
import io.questdb.std.str.StringSink;
import io.questdb.std.str.Utf8s;

import java.io.Closeable;

import static io.questdb.cutlass.http.HttpConstants.CONTENT_TYPE_JSON;
import static java.net.HttpURLConnection.*;

public final class SchemaGroupsProcessor implements HttpRequestHandler {
    private static final Log LOG = LogFactory.getLog(SchemaGroupsProcessor.class);
    private static final LocalValue<State> STATE = new LocalValue<>();
    private final GetProcessor getProcessor = new GetProcessor();
    private final PostProcessor postProcessor = new PostProcessor();
    private final byte requiredAuthType;
    private final boolean settingsReadOnly;
    private final SchemaGroupsStore store;

    public SchemaGroupsProcessor(CairoEngine engine, ServerConfiguration configuration) {
        store = engine.getSchemaGroupsStore();
        requiredAuthType = configuration.getHttpServerConfiguration().getJsonQueryProcessorConfiguration().getRequiredAuthType();
        settingsReadOnly = configuration.getHttpServerConfiguration().isSettingsReadOnly();
    }

    @Override
    public HttpRequestProcessor getProcessor(HttpRequestHeader requestHeader) {
        return requestHeader.isGetRequest() ? getProcessor : postProcessor;
    }

    private abstract class Processor implements HttpRequestProcessor {
        @Override
        public byte getRequiredAuthType() {
            return requiredAuthType;
        }

        @Override
        public void onHeadersReady(HttpConnectionContext context) {
            State state = STATE.get(context);
            if (state == null) {
                STATE.set(context, state = new State());
            }
            state.body.clear();
            state.response.clear();
            state.tooLarge = false;
            state.headersStarted = false;
            state.responseOffset = 0;
            state.status = HTTP_OK;
        }

        @Override
        public void resumeSend(HttpConnectionContext context) throws PeerDisconnectedException, PeerIsSlowToReadException {
            context.resumeResponseSend();
            STATE.get(context).send(context);
        }
    }

    private final class GetProcessor extends Processor {
        @Override
        public void onRequestComplete(HttpConnectionContext context) throws PeerDisconnectedException, PeerIsSlowToReadException {
            State state = STATE.get(context);
            try {
                store.snapshot(context.getSecurityContext(), settingsReadOnly, state.response);
            } catch (CairoException e) {
                state.error(e);
            }
            state.send(context);
        }
    }

    private final class PostProcessor extends Processor implements HttpPostPutProcessor {
        private State transientState;

        @Override
        public void onChunk(long lo, long hi) {
            if (hi - lo > SchemaGroupsStore.MAX_REQUEST_BYTES - transientState.body.size()) {
                transientState.tooLarge = true;
            } else if (!transientState.tooLarge) {
                transientState.body.putNonAscii(lo, hi);
            }
        }

        @Override
        public void onHeadersReady(HttpConnectionContext context) {
            super.onHeadersReady(context);
            transientState = STATE.get(context);
        }

        @Override
        public void onRequestComplete(HttpConnectionContext context) throws PeerDisconnectedException, PeerIsSlowToReadException {
            State state = STATE.get(context);
            try {
                // Authorize before parsing or resolving any object identifiers.
                context.getSecurityContext().authorizeSettings();
                if (settingsReadOnly) {
                    throw CairoException.authorization().put("Schema groups are read-only");
                }
                if (!HttpKeywords.isPOST(context.getRequestHeader().getMethod())) {
                    state.status = HTTP_BAD_METHOD;
                    state.response.putAscii("{\"error\":\"Method not allowed\"}");
                } else if (state.tooLarge) {
                    state.status = HTTP_ENTITY_TOO_LARGE;
                    state.response.putAscii("{\"error\":\"Request too large\"}");
                } else {
                    StringSink json = new StringSink();
                    if (!Utf8s.utf8ToUtf16(state.body.lo(), state.body.hi(), json)) {
                        throw CairoException.nonCritical().put("Invalid schema groups request");
                    }
                    store.update(context.getSecurityContext(), json.toString(), state.response);
                }
            } catch (CairoException e) {
                state.error(e);
            }
            state.send(context);
        }

        @Override
        public void resumeRecv(HttpConnectionContext context) {
            transientState = STATE.get(context);
        }
    }

    private static final class State implements Closeable {
        private final DirectUtf8Sink body = new DirectUtf8Sink(1024);
        private final DirectUtf8Sink response = new DirectUtf8Sink(1024);
        private boolean headersStarted;
        private int responseOffset;
        private int status;
        private boolean tooLarge;

        @Override
        public void close() {
            body.close();
            response.close();
        }

        private void error(CairoException e) {
            response.clear();
            if (e.isAuthorizationError()) {
                status = HTTP_UNAUTHORIZED;
                response.putAscii("{\"error\":\"Not authorized\"}");
            } else if (e.isPreferencesOutOfDateError()) {
                status = HTTP_CONFLICT;
                response.putAscii("{\"error\":\"Schema groups changed\"}");
            } else if (e.isCritical()) {
                status = HTTP_INTERNAL_ERROR;
                LOG.error().$("Could not access schema groups: ").$safe(e.getFlyweightMessage()).$();
                response.putAscii("{\"error\":\"Could not access schema groups\"}");
            } else {
                status = HTTP_BAD_REQUEST;
                response.putAscii("{\"error\":\"Invalid schema groups request\"}");
            }
        }

        private void send(HttpConnectionContext context) throws PeerDisconnectedException, PeerIsSlowToReadException {
            HttpChunkedResponse channel = context.getChunkedResponse();
            if (!headersStarted) {
                headersStarted = true;
                channel.status(status, CONTENT_TYPE_JSON);
                channel.sendHeader();
            }
            while (responseOffset < response.size()) {
                responseOffset += channel.writeBytes(response.ptr() + responseOffset, response.size() - responseOffset);
                channel.sendChunk(false);
            }
            channel.done();
        }
    }
}
