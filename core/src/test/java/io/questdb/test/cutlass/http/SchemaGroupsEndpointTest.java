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

package io.questdb.test.cutlass.http;

import io.questdb.PropertyKey;
import io.questdb.ServerMain;
import io.questdb.client.DefaultHttpClientConfiguration;
import io.questdb.client.cutlass.http.client.Fragment;
import io.questdb.client.cutlass.http.client.HttpClient;
import io.questdb.client.cutlass.http.client.HttpClientFactory;
import io.questdb.std.str.StringSink;
import io.questdb.std.str.Utf8s;
import io.questdb.test.AbstractBootstrapTest;
import io.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class SchemaGroupsEndpointTest extends AbstractBootstrapTest {
    @Before
    public void setUp() {
        super.setUp();
        TestUtils.unchecked(() -> createDummyConfiguration());
        dbPath.parent().$();
    }

    @Test
    public void testAuthenticatedGetMutationsConflictsAndPrivateSettings() throws Exception {
        assertMemoryLeak(() -> {
            Map<String, String> env = new HashMap<>();
            env.put(PropertyKey.HTTP_USER.getEnvVarName(), "editor");
            env.put(PropertyKey.HTTP_PASSWORD.getEnvVarName(), "password");
            env.put(PropertyKey.DEBUG_FORCE_RECV_FRAGMENTATION_CHUNK_SIZE.getEnvVarName(), "10");
            env.put(PropertyKey.DEBUG_FORCE_SEND_FRAGMENTATION_CHUNK_SIZE.getEnvVarName(), "10");
            try (ServerMain server = ServerMain.create(root, env);
                 HttpClient client = HttpClientFactory.newPlainTextInstance(new DefaultHttpClientConfiguration())) {
                server.start();
                request(client, "/schema-groups", null, false, 401);
                String initial = request(client, "/schema-groups", null, true, 200);
                Assert.assertTrue(initial.contains("\"editable\":true"));
                request(client, "/settings?version=0", "{\"instance_name\":\"Unrelated\"}", true, 200);
                Assert.assertEquals(initial, request(client, "/schema-groups", null, true, 200));
                String version = version(initial);
                String create = "{\"version\":\"" + version + "\",\"kind\":\"table\",\"type\":\"create\",\"name\":\"Private layout\"}";
                String created = request(client, "/schema-groups", create, true, 200);
                Assert.assertTrue(created.contains("\"name\":\"Private layout\""));
                request(client, "/schema-groups", create, true, 409);
                request(client, "/schema-groups", "{\"version\":123}", true, 400);
                Assert.assertEquals(created, request(client, "/schema-groups", null, true, 200));
                server.getEngine().execute("create table grouped (x int)");
                int id = server.getEngine().verifyTableName("grouped").getTableId();
                int groupStart = created.indexOf("\"id\":\"") + 6;
                String groupId = created.substring(groupStart, created.indexOf('"', groupStart));
                String moved = request(client, "/schema-groups", "{\"version\":\"" + version(created) + "\",\"kind\":\"table\",\"type\":\"move\",\"objectIds\":[" + id + "],\"groupId\":\"" + groupId + "\"}", true, 200);
                Assert.assertTrue(moved.contains("\"" + id + "\":\"" + groupId + "\""));
                String deleted = request(client, "/schema-groups", "{\"version\":\"" + version(moved) + "\",\"kind\":\"table\",\"type\":\"delete\",\"groupId\":\"" + groupId + "\"}", true, 200);
                Assert.assertFalse(deleted.contains(groupId));
                String settings = request(client, "/settings", null, false, 200);
                Assert.assertFalse(settings.contains("Private layout"));
                Assert.assertFalse(settings.contains("assignments"));
            }
        });
    }

    @Test
    public void testReadOnlyPolicyForAuthenticatedUser() throws Exception {
        assertMemoryLeak(() -> {
            Map<String, String> env = new HashMap<>();
            env.put(PropertyKey.HTTP_USER.getEnvVarName(), "editor");
            env.put(PropertyKey.HTTP_PASSWORD.getEnvVarName(), "password");
            env.put(PropertyKey.HTTP_SETTINGS_READONLY.getEnvVarName(), "true");
            try (ServerMain server = ServerMain.create(root, env);
                 HttpClient client = HttpClientFactory.newPlainTextInstance(new DefaultHttpClientConfiguration())) {
                server.start();
                String before = request(client, "/schema-groups", null, true, 200);
                Assert.assertTrue(before.contains("\"editable\":false"));
                request(client, "/schema-groups", "{\"version\":\"" + version(before) + "\",\"kind\":\"table\",\"type\":\"create\",\"name\":\"No\"}", true, 401);
                Assert.assertEquals(before, request(client, "/schema-groups", null, true, 200));
            }
        });
    }

    @Test
    public void testWebConsoleContextPath() throws Exception {
        assertMemoryLeak(() -> {
            try (ServerMain server = ServerMain.create(root, Map.of(PropertyKey.HTTP_CONTEXT_WEB_CONSOLE.getEnvVarName(), "/console"));
                 HttpClient client = HttpClientFactory.newPlainTextInstance(new DefaultHttpClientConfiguration())) {
                server.start();
                String response = request(client, "/console/schema-groups", null, false, 200);
                Assert.assertTrue(response.contains("\"sections\":{"));
            }
        });
    }

    @Test
    public void testLargeSnapshotsAndOversizedRequests() throws Exception {
        assertMemoryLeak(() -> {
            try (ServerMain server = ServerMain.create(root, Map.of(
                    PropertyKey.HTTP_SEND_BUFFER_SIZE.getEnvVarName(), "1024",
                    PropertyKey.DEBUG_FORCE_SEND_FRAGMENTATION_CHUNK_SIZE.getEnvVarName(), "17"
            ));
                 HttpClient client = HttpClientFactory.newPlainTextInstance(new DefaultHttpClientConfiguration() {
                     @Override
                     public int getMaximumRequestBufferSize() {
                         return 131072;
                     }
                 })) {
                server.start();
                String snapshot = request(client, "/schema-groups", null, false, 200);
                for (int i = 0; i < 10; i++) {
                    snapshot = request(client, "/schema-groups", "{\"version\":\"" + version(snapshot) + "\",\"kind\":\"table\",\"type\":\"create\",\"name\":\"" + "A".repeat(120) + i + "\"}", false, 200);
                }
                Assert.assertTrue(snapshot.length() > 1024);
                Assert.assertEquals(snapshot, request(client, "/schema-groups", null, false, 200));
                request(client, "/schema-groups", " ".repeat(65537), false, 413);
                Assert.assertEquals(snapshot, request(client, "/schema-groups", null, false, 200));
            }
        });
    }

    private static String request(HttpClient client, String url, String body, boolean authenticated, int status) {
        HttpClient.Request request = client.newRequest("localhost", HTTP_PORT);
        if (body == null) {
            request.GET();
        } else {
            request.POST();
        }
        request.url(url);
        if (authenticated) {
            request.authBasic("editor", "password");
        }
        if (body != null) {
            request.withContent().put(body);
        }
        try (HttpClient.ResponseHeaders headers = request.send()) {
            headers.await();
            TestUtils.assertEquals(Integer.toString(status), headers.getStatusCode().asAsciiCharSequence());
            StringSink response = new StringSink();
            Fragment fragment;
            while ((fragment = headers.getResponse().recv()) != null) {
                Utf8s.utf8ToUtf16(fragment.lo(), fragment.hi(), response);
            }
            return response.toString().trim();
        }
    }

    private static String version(String snapshot) {
        return snapshot.substring(snapshot.indexOf(":\"") + 2, snapshot.indexOf("\","));
    }
}
