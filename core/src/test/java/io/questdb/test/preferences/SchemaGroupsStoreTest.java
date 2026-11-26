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

package io.questdb.test.preferences;

import io.questdb.PropertyKey;
import io.questdb.cairo.CairoConfigurationWrapper;
import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.CairoException;
import io.questdb.cairo.SecurityContext;
import io.questdb.cairo.TableToken;
import io.questdb.cairo.TableUtils;
import io.questdb.cairo.security.AllowAllSecurityContext;
import io.questdb.preferences.SchemaGroupsStore;
import io.questdb.std.Files;
import io.questdb.std.FilesFacade;
import io.questdb.std.FilesFacadeImpl;
import io.questdb.std.str.LPSZ;
import io.questdb.std.str.Path;
import io.questdb.std.str.Utf8StringSink;
import io.questdb.std.str.Utf8s;
import io.questdb.test.AbstractCairoTest;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SchemaGroupsStoreTest extends AbstractCairoTest {
    private static final SecurityContext EDITOR = AllowAllSecurityContext.INSTANCE;

    @Test
    public void testNamesIdsAndIndependentKinds() throws Exception {
        assertMemoryLeak(() -> {
            SchemaGroupsStore store = freshStore();
            String snapshot = update(store, "table", "\"type\":\"create\",\"name\":\"\\u00a0 Orders \\ufeff\"");
            String firstId = groupId(snapshot);
            Assert.assertTrue(snapshot.contains("\"name\":\"Orders\""));
            assertInvalid(store, "table", "\"type\":\"create\",\"name\":\"orders\"");
            assertInvalid(store, "table", "\"type\":\"create\",\"name\":\"\\u2000\\t\"");
            assertInvalid(store, "table", "\"type\":\"create\",\"name\":\"a\\nb\"");
            assertInvalid(store, "table", "\"type\":\"create\",\"name\":\"" + "x".repeat(129) + "\"");
            update(store, "view", "\"type\":\"create\",\"name\":\"ORDERS\"");
            update(store, "table", "\"type\":\"delete\",\"groupId\":\"" + firstId + "\"");
            snapshot = update(store, "table", "\"type\":\"create\",\"name\":\"Orders\"");
            Assert.assertFalse(snapshot.contains(firstId));
            Assert.assertNotEquals(firstId, groupId(snapshot));
            update(store, "table", "\"type\":\"create\",\"name\":\"ÉVÉNEMENTS\"");
            assertInvalid(store, "table", "\"type\":\"create\",\"name\":\"événements\"");
            String before = snapshot(store, EDITOR);
            SchemaGroupsStore restarted = new SchemaGroupsStore(engine);
            restarted.init(false);
            restarted.reconcile();
            Assert.assertEquals(before, snapshot(restarted, EDITOR));
        });
    }

    @Test
    public void testMoveVisibilityAndAtomicValidation() throws Exception {
        assertMemoryLeak(() -> {
            SchemaGroupsStore store = freshStore();
            execute("create table public_table (x int)");
            execute("create table hidden_table (x int)");
            int publicId = engine.verifyTableName("public_table").getTableId();
            int hiddenId = engine.verifyTableName("hidden_table").getTableId();
            String groupId = groupId(update(store, "table", "\"type\":\"create\",\"name\":\"Shared\""));
            update(store, "table", "\"type\":\"move\",\"objectIds\":[" + publicId + "," + hiddenId + "],\"groupId\":\"" + groupId + "\"");
            SecurityContext restricted = new AllowAllSecurityContext() {
                @Override
                public boolean isTableVisible(TableToken token) {
                    return token.getTableId() != hiddenId;
                }
            };
            String filtered = snapshot(store, restricted);
            Assert.assertTrue(filtered.contains("\"" + publicId + "\":\"" + groupId + "\""));
            Assert.assertFalse(filtered.contains("\"" + hiddenId + "\":"));
            Assert.assertFalse(filtered.contains("hidden_table"));
            Assert.assertTrue(filtered.contains("\"name\":\"Shared\""));
            String before = snapshot(store, EDITOR);
            assertInvalid(store, restricted, "table", "\"type\":\"move\",\"objectIds\":[" + publicId + "," + hiddenId + "],\"groupId\":null");
            Assert.assertEquals(before, snapshot(store, EDITOR));
            assertInvalid(store, "table", "\"type\":\"move\",\"objectIds\":[" + publicId + ",2147483647],\"groupId\":null");
            Assert.assertEquals(before, snapshot(store, EDITOR));
            assertInvalid(store, "view", "\"type\":\"move\",\"objectIds\":[" + publicId + "],\"groupId\":null");
            Assert.assertEquals(before, snapshot(store, EDITOR));
            String response = update(store, restricted, "table", "\"type\":\"delete\",\"groupId\":\"" + groupId + "\"");
            Assert.assertFalse(response.contains(groupId));
            Assert.assertFalse(response.contains("\"" + hiddenId + "\":"));
            Assert.assertFalse(snapshot(store, EDITOR).contains(groupId));
        });
    }

    @Test
    public void testSettingsPolicyAndMalformedCommandsDoNotMutate() throws Exception {
        assertMemoryLeak(() -> {
            SchemaGroupsStore store = freshStore();
            String before = snapshot(store, EDITOR);
            Assert.assertTrue(snapshot(store, AllowAllSecurityContext.SETTINGS_READ_ONLY).contains("\"editable\":false"));
            try {
                update(store, AllowAllSecurityContext.SETTINGS_READ_ONLY, "table", "\"type\":\"create\",\"name\":\"No\"");
                Assert.fail();
            } catch (CairoException e) {
                Assert.assertTrue(e.isAuthorizationError());
            }
            String[] invalid = {
                    "\"type\":\"move\",\"objectIds\":[],\"groupId\":null",
                    "\"type\":\"move\",\"objectIds\":[1.0],\"groupId\":null",
                    "\"type\":\"move\",\"objectIds\":[\"1\"],\"groupId\":null",
                    "\"type\":\"move\",\"objectIds\":[2147483648],\"groupId\":null",
                    "\"type\":\"move\",\"objectIds\":[18446744073709551617],\"groupId\":null",
                    "\"type\":\"move\",\"objectIds\":[" + "9".repeat(65000) + "],\"groupId\":null",
                    "\"type\":\"move\",\"objectIds\":[1,],\"groupId\":null",
                    "\"type\":\"create\",\"name\":null",
                    "\"type\":\"create\",\"name\":\"a\",\"name\":\"b\"",
                    "\"type\":\"create\",\"name\":\"a\",\"unknown\":\"b\"",
                    "\"type\":\"create\",\"name\":\"\\ud800\"",
                    "\"type\":\"create\",\"name\":\"\\uZZZZ\"",
                    "\"type\":\"create\",\"name\":\"a\",",
                    "\"type\":\"create\",\"name\":\"" + "x".repeat(65536) + "\"",
                    "\"type\":\"move\",\"objectIds\":[" + "1,".repeat(1024) + "1],\"groupId\":null"
            };
            for (String command : invalid) {
                assertInvalid(store, "table", command);
                Assert.assertEquals(command, before, snapshot(store, EDITOR));
            }
        });
    }

    @Test
    public void testConcurrentUpdatesHaveOneWinner() throws Exception {
        assertMemoryLeak(() -> {
            SchemaGroupsStore store = freshStore();
            String version = version(snapshot(store, EDITOR));
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread[] threads = new Thread[8];
            for (int i = 0; i < threads.length; i++) {
                final int index = i;
                threads[i] = new Thread(() -> {
                    try {
                        start.await();
                        store.update(EDITOR, command(version, "table", "\"type\":\"create\",\"name\":\"Group" + index + "\""), new Utf8StringSink());
                        successes.incrementAndGet();
                    } catch (CairoException e) {
                        if (e.isPreferencesOutOfDateError()) {
                            conflicts.incrementAndGet();
                        } else {
                            failure.set(e);
                        }
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                });
                threads[i].start();
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join();
            }
            Assert.assertNull(failure.get());
            Assert.assertEquals(1, successes.get());
            Assert.assertEquals(7, conflicts.get());
        });
    }

    @Test
    public void testRenameDropRecreateAndStartupReconciliation() throws Exception {
        assertMemoryLeak(() -> {
            SchemaGroupsStore store = freshStore();
            execute("create table original (x int)");
            TableToken original = engine.verifyTableName("original");
            String groupId = groupId(update(store, "table", "\"type\":\"create\",\"name\":\"G\""));
            update(store, "table", "\"type\":\"move\",\"objectIds\":[" + original.getTableId() + "],\"groupId\":\"" + groupId + "\"");
            execute("rename table original to renamed");
            Assert.assertEquals(original.getTableId(), engine.verifyTableName("renamed").getTableId());
            Assert.assertTrue(snapshot(store, EDITOR).contains("\"" + original.getTableId() + "\":\"" + groupId + "\""));
            String priorDropSnapshot = snapshot(store, EDITOR);
            try (Path layoutPath = new Path().of(configuration.getDbRoot()).concat(SchemaGroupsStore.FILE_NAME);
                 Path stalePath = new Path().of(configuration.getDbRoot()).concat("stale-groups")) {
                Assert.assertTrue(configuration.getFilesFacade().copy(layoutPath.$(), stalePath.$()) >= 0);
                execute("drop table renamed");
                Assert.assertFalse(snapshot(store, EDITOR).contains("\"" + original.getTableId() + "\":"));
                execute("create table original (x int)");
                int recreatedId = engine.verifyTableName("original").getTableId();
                Assert.assertNotEquals(original.getTableId(), recreatedId);
                Assert.assertFalse(snapshot(store, EDITOR).contains("\"" + recreatedId + "\":"));
                // Simulate a cleanup write lost at crash, without losing the DROP itself.
                Assert.assertTrue(configuration.getFilesFacade().copy(stalePath.$(), layoutPath.$()) >= 0);
                SchemaGroupsStore restarted = new SchemaGroupsStore(engine);
                restarted.init(false);
                Assert.assertFalse(snapshot(restarted, EDITOR).contains("\"" + original.getTableId() + "\":"));
                restarted.reconcile();
                Assert.assertNotEquals(version(priorDropSnapshot), version(snapshot(restarted, EDITOR)));
                configuration.getFilesFacade().remove(stalePath.$());
            }
            Assert.assertTrue(priorDropSnapshot.contains(groupId));
        });
    }

    @Test
    public void testAllObjectKindsKeepMembershipAcrossRestartAndRename() throws Exception {
        assertMemoryLeak(() -> {
            setProperty(PropertyKey.CAIRO_MAT_VIEW_ENABLED, "true");
            SchemaGroupsStore store = freshStore();
            execute("create table base (val int, ts timestamp) timestamp(ts) partition by day wal");
            execute("create view v as select * from base");
            execute("create materialized view mv as (select ts, sum(val) val from base sample by 1h)");
            execute("create live view lv flush every 1s start from now as select val, ts, count(*) over (partition by 0 order by ts rows between 1000000 preceding and current row) rn from base");
            String[] names = {"base", "v", "mv", "lv"};
            String[] kinds = {"table", "view", "matview", "liveview"};
            TableToken.Type[] types = {TableToken.Type.TABLE, TableToken.Type.VIEW, TableToken.Type.MAT_VIEW, TableToken.Type.LIVE_VIEW};
            int[] ids = new int[names.length];
            for (int i = 0; i < names.length; i++) {
                TableToken token = engine.verifyTableName(names[i]);
                ids[i] = token.getTableId();
                Assert.assertEquals(types[i], token.getType());
                String groupId = groupIdInSection(update(store, kinds[i], "\"type\":\"create\",\"name\":\"G\""), kinds[i]);
                update(store, kinds[i], "\"type\":\"move\",\"objectIds\":[" + token.getTableId() + "],\"groupId\":\"" + groupId + "\"");
            }
            String before = snapshot(store, EDITOR);
            engine.closeNameRegistry();
            try (CairoEngine reopened = new CairoEngine(configuration)) {
                for (int i = 0; i < names.length; i++) {
                    TableToken restored = reopened.verifyTableName(names[i]);
                    Assert.assertEquals(types[i], restored.getType());
                    Assert.assertEquals(ids[i], restored.getTableId());
                }
                Assert.assertEquals(before, snapshot(reopened.getSchemaGroupsStore(), EDITOR));
            } finally {
                engine.reloadTableNames();
            }
            // Exercise storage-level renames, including view kinds that reject SQL RENAME.
            for (int i = names.length - 1; i >= 0; i--) {
                TableToken token = engine.verifyTableName(names[i]);
                engine.applyTableRename(token, token.renamed(names[i] + "_renamed"));
                Assert.assertEquals(ids[i], engine.verifyTableName(names[i] + "_renamed").getTableId());
                Assert.assertEquals(before, snapshot(store, EDITOR));
            }
            execute("drop live view lv_renamed");
            execute("drop materialized view mv_renamed");
            execute("drop view v_renamed");
            execute("drop table base_renamed");
            String after = snapshot(store, EDITOR);
            for (int id : ids) {
                Assert.assertFalse(after.contains("\"" + id + "\":"));
            }
            Assert.assertEquals(4, count(after, "\"assignments\":{}"));
            execute("create table base (val int, ts timestamp) timestamp(ts) partition by day wal");
            execute("create view v as select * from base");
            execute("create materialized view mv as (select ts, sum(val) val from base sample by 1h)");
            execute("create live view lv flush every 1s start from now as select val, ts, count(*) over (partition by 0 order by ts rows between 1000000 preceding and current row) rn from base");
            Assert.assertEquals(4, count(snapshot(store, EDITOR), "\"assignments\":{}"));
        });
    }

    @Test
    public void testRebaseWalKeepsMembershipUnderNewId() throws Exception {
        setProperty(PropertyKey.CAIRO_WAL_APPLY_SUSPENDED_WRITE_DENIED, "true");
        assertMemoryLeak(() -> {
            SchemaGroupsStore store = freshStore();
            execute("create table rebased (x int, ts timestamp) timestamp(ts) partition by day wal");
            int oldId = engine.verifyTableName("rebased").getTableId();
            String groupId = groupId(update(store, "table", "\"type\":\"create\",\"name\":\"G\""));
            update(store, "table", "\"type\":\"move\",\"objectIds\":[" + oldId + "],\"groupId\":\"" + groupId + "\"");
            String before = snapshot(store, EDITOR);

            execute("alter table rebased suspend wal");
            execute("alter table rebased rebase wal");
            drainWalQueue();
            drainPurgeJob();

            int newId = engine.verifyTableName("rebased").getTableId();
            String after = snapshot(store, EDITOR);
            Assert.assertNotEquals(oldId, newId);
            Assert.assertFalse(after.contains("\"" + oldId + "\":"));
            Assert.assertTrue(after.contains("\"" + newId + "\":\"" + groupId + "\""));
            Assert.assertNotEquals(version(before), version(after));
            store.init(false);
            Assert.assertEquals(after, snapshot(store, EDITOR));
        });
    }

    @Test
    public void testWriteFailureLeavesMemoryAndDiskUnchanged() throws Exception {
        AtomicInteger fail = new AtomicInteger();
        FilesFacadeImpl facade = new FilesFacadeImpl() {
            @Override
            public int rename(LPSZ from, LPSZ to) {
                if (fail.get() == 1 && Utf8s.endsWithAscii(to, SchemaGroupsStore.FILE_NAME)) {
                    return -1;
                }
                return super.rename(from, to);
            }

            @Override
            public void msync(long addr, long len, boolean async) {
                if (fail.get() == 2) {
                    throw CairoException.critical(5).put("Injected schema groups sync failure");
                }
                super.msync(addr, len, async);
            }
        };
        assertMemoryLeak(() -> {
            CairoConfigurationWrapper testConfiguration = new CairoConfigurationWrapper(configuration) {
                @Override
                public FilesFacade getFilesFacade() {
                    return facade;
                }
            };
            engine.closeNameRegistry();
            try (CairoEngine testEngine = new CairoEngine(testConfiguration)) {
                SchemaGroupsStore store = testEngine.getSchemaGroupsStore();
                String before = snapshot(store, EDITOR);
                for (int failureMode = 1; failureMode <= 2; failureMode++) {
                    fail.set(failureMode);
                    try {
                        update(store, "table", "\"type\":\"create\",\"name\":\"Failure\"");
                        Assert.fail();
                    } catch (CairoException expected) {
                        Assert.assertTrue(expected.isCritical());
                    } finally {
                        fail.set(0);
                    }
                    Assert.assertEquals(before, snapshot(store, EDITOR));
                    SchemaGroupsStore restarted = new SchemaGroupsStore(testEngine);
                    restarted.init(false);
                    Assert.assertEquals(before, snapshot(restarted, EDITOR));
                }
                Assert.assertTrue(update(store, "table", "\"type\":\"create\",\"name\":\"Retry\"").contains("\"name\":\"Retry\""));
            } finally {
                engine.reloadTableNames();
            }
        });
    }

    @Test
    public void testCheckpointRestoreRotatesVersionEveryTime() throws Exception {
        assertMemoryLeak(() -> {
            SchemaGroupsStore store = freshStore();
            update(store, "table", "\"type\":\"create\",\"name\":\"Checkpoint\"");
            String before = snapshot(store, EDITOR);
            try (Path checkpoint = new Path().of(configuration.getDbRoot()).concat("groups-checkpoint");
                 Path source = new Path();
                 Path destination = new Path().of(configuration.getDbRoot()).concat(SchemaGroupsStore.FILE_NAME)) {
                Assert.assertEquals(0, Files.mkdirs(checkpoint.slash(), configuration.getMkDirMode()));
                store.persistCheckpoint(checkpoint.toString());
                update(store, "table", "\"type\":\"create\",\"name\":\"Later\"");
                source.of(checkpoint).concat(SchemaGroupsStore.FILE_NAME);
                Assert.assertTrue(configuration.getFilesFacade().copy(source.$(), destination.$()) >= 0);
                store.init(true);
                String restored = snapshot(store, EDITOR);
                Assert.assertFalse(restored.contains("Later"));
                Assert.assertTrue(restored.contains("Checkpoint"));
                Assert.assertNotEquals(version(before), version(restored));
                Assert.assertTrue(configuration.getFilesFacade().copy(source.$(), destination.$()) >= 0);
                store.init(true);
                Assert.assertNotEquals(version(restored), version(snapshot(store, EDITOR)));
                String after = snapshot(store, EDITOR);
                store.init(false);
                Assert.assertEquals(after, snapshot(store, EDITOR));
            }
        });
    }

    @Test
    public void testPreFeatureCheckpointClearsLaterGroups() throws Exception {
        assertMemoryLeak(() -> {
            setProperty(PropertyKey.CAIRO_LEGACY_SNAPSHOT_INSTANCE_ID, "schema-groups");
            SchemaGroupsStore store = freshStore();
            engine.checkpointCreate(sqlExecutionContext.getCircuitBreaker(), true);
            try {
                try (Path path = new Path().of(configuration.getCheckpointRoot()).concat(configuration.getDbDirectory()).concat(SchemaGroupsStore.FILE_NAME)) {
                    Assert.assertTrue(configuration.getFilesFacade().removeQuiet(path.$()));
                }
                String before = update(store, "table", "\"type\":\"create\",\"name\":\"Later\"");

                engine.clear();
                engine.closeNameRegistry();
                try (Path path = new Path().of(configuration.getDbRoot()).parent().concat(TableUtils.RESTORE_FROM_CHECKPOINT_TRIGGER_FILE_NAME)) {
                    Assert.assertTrue(Files.touch(path.$()));
                }
                engine.checkpointRecover();

                String restored = snapshot(store, EDITOR);
                Assert.assertEquals(4, count(restored, "\"groups\":[]"));
                Assert.assertNotEquals(version(before), version(restored));
            } finally {
                engine.checkpointRelease();
            }
        });
    }

    @Test
    public void testEngineCheckpointRestoresMembership() throws Exception {
        assertMemoryLeak(() -> {
            setProperty(PropertyKey.CAIRO_LEGACY_SNAPSHOT_INSTANCE_ID, "schema-groups");
            SchemaGroupsStore store = freshStore();
            execute("create table cp (x int, ts timestamp) timestamp(ts) partition by day wal");
            int id = engine.verifyTableName("cp").getTableId();
            String groupId = groupId(update(store, "table", "\"type\":\"create\",\"name\":\"Captured\""));
            update(store, "table", "\"type\":\"move\",\"objectIds\":[" + id + "],\"groupId\":\"" + groupId + "\"");
            String before = snapshot(store, EDITOR);
            engine.checkpointCreate(sqlExecutionContext.getCircuitBreaker(), true);
            try {
                update(store, "table", "\"type\":\"delete\",\"groupId\":\"" + groupId + "\"");
                engine.clear();
                engine.closeNameRegistry();
                try (Path path = new Path().of(configuration.getDbRoot()).parent().concat(TableUtils.RESTORE_FROM_CHECKPOINT_TRIGGER_FILE_NAME)) {
                    Assert.assertTrue(Files.touch(path.$()));
                }
                engine.checkpointRecover();
                Assert.assertEquals(id, engine.verifyTableName("cp").getTableId());
                String restored = snapshot(store, EDITOR);
                Assert.assertTrue(restored.contains("\"" + id + "\":\"" + groupId + "\""));
                Assert.assertNotEquals(version(before), version(restored));
            } finally {
                engine.checkpointRelease();
            }
        });
    }

    private static void assertInvalid(SchemaGroupsStore store, String kind, String operation) {
        assertInvalid(store, EDITOR, kind, operation);
    }

    private static void assertInvalid(SchemaGroupsStore store, SecurityContext context, String kind, String operation) {
        try {
            update(store, context, kind, operation);
            Assert.fail("Expected invalid request");
        } catch (CairoException e) {
            Assert.assertFalse(e.isAuthorizationError());
            Assert.assertFalse(e.isPreferencesOutOfDateError());
            Assert.assertFalse(e.isCritical());
        }
    }

    private static String command(String version, String kind, String operation) {
        return "{\"version\":\"" + version + "\",\"kind\":\"" + kind + "\"," + operation + "}";
    }

    private static int count(String text, String term) {
        return (text.length() - text.replace(term, "").length()) / term.length();
    }

    private static SchemaGroupsStore freshStore() {
        SchemaGroupsStore store = engine.getSchemaGroupsStore();
        store.init(false);
        return store;
    }

    private static String groupId(String snapshot) {
        Matcher matcher = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(snapshot);
        Assert.assertTrue(snapshot, matcher.find());
        return matcher.group(1);
    }

    private static String groupIdInSection(String snapshot, String kind) {
        return groupId(snapshot.substring(snapshot.indexOf("\"" + kind + "\":{")));
    }

    private static String snapshot(SchemaGroupsStore store, SecurityContext context) {
        Utf8StringSink sink = new Utf8StringSink();
        store.snapshot(context, false, sink);
        return sink.toString();
    }

    private static String update(SchemaGroupsStore store, String kind, String operation) {
        return update(store, EDITOR, kind, operation);
    }

    private static String update(SchemaGroupsStore store, SecurityContext context, String kind, String operation) {
        Utf8StringSink sink = new Utf8StringSink();
        store.update(context, command(version(snapshot(store, context)), kind, operation), sink);
        return sink.toString();
    }

    private static String version(String snapshot) {
        return snapshot.substring(snapshot.indexOf(":\"") + 2, snapshot.indexOf("\","));
    }
}
