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

package io.questdb.preferences;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.CairoException;
import io.questdb.cairo.CommitMode;
import io.questdb.cairo.SecurityContext;
import io.questdb.cairo.TableToken;
import io.questdb.cairo.TableUtils;
import io.questdb.cairo.file.AppendableBlock;
import io.questdb.cairo.file.BlockFileReader;
import io.questdb.cairo.file.BlockFileUtils;
import io.questdb.cairo.file.BlockFileWriter;
import io.questdb.cairo.file.ReadableBlock;
import io.questdb.cairo.vm.Vm;
import io.questdb.std.Files;
import io.questdb.std.FilesFacade;
import io.questdb.std.ObjHashSet;
import io.questdb.std.str.LPSZ;
import io.questdb.std.str.Path;
import io.questdb.std.str.Utf8Sink;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Instance-wide presentation metadata, deliberately separate from exported preferences.
 * The monitor serializes revisions, snapshots, reconciliation and checkpoint copies.
 */
public final class SchemaGroupsStore {
    public static final String FILE_NAME = "_schema_groups~store";
    public static final int MAX_REQUEST_BYTES = 65536;
    private static final int FORMAT = 1;
    private static final int MAX_GROUPS = 256;
    private static final int MAX_NAME_LENGTH = 128;
    private final CairoConfiguration configuration;
    private final CairoEngine engine;
    private Layout layout;

    public SchemaGroupsStore(CairoEngine engine) {
        this.engine = engine;
        configuration = engine.getConfiguration();
    }

    private static boolean isEditable(SecurityContext securityContext) {
        try {
            securityContext.authorizeSettings();
            return true;
        } catch (CairoException e) {
            if (!e.isAuthorizationError()) {
                throw e;
            }
            return false;
        }
    }

    private static String kindName(TableToken.Type kind) {
        return switch (kind) {
            case TABLE -> "table";
            case MAT_VIEW -> "matview";
            case VIEW -> "view";
            case LIVE_VIEW -> "liveview";
        };
    }

    /**
     * Loads before name-registry loading. Only an actual restore invalidates the revision.
     */
    public synchronized void init(boolean restored) {
        try (Path path = new Path().of(configuration.getDbRoot()).concat(FILE_NAME)) {
            boolean exists = configuration.getFilesFacade().exists(path.$());
            Layout loaded = exists ? readLayout(configuration, path.$()) : new Layout();
            if (restored || !exists) {
                loaded.version = UUID.randomUUID().toString();
                persist(loaded);
            }
            layout = loaded;
        }
    }

    /**
     * Removes orphans after registry loading and before serving requests.
     */
    public synchronized void reconcile() {
        // The persisted monotonic table-ID generator prevents reuse within an instance.
        Layout candidate = new Layout(layout);
        Map<Integer, TableToken> tokens = tokens();
        boolean changed = false;
        for (Section section : candidate.sections.values()) {
            var iterator = section.assignments.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                TableToken token = tokens.get(entry.getKey());
                if (token == null || candidate.sections.get(token.getType()) != section) {
                    iterator.remove();
                    changed = true;
                }
            }
        }
        if (changed) {
            commit(candidate);
        }
    }

    /**
     * A drop is already committed to the registry when this runs. If persistence fails,
     * the assignment remains invisible and startup reconciliation will retry cleanup.
     */
    public synchronized void onDrop(TableToken token) {
        if (layout == null || !layout.sections.get(token.getType()).assignments.containsKey(token.getTableId())) {
            return;
        }
        Layout candidate = new Layout(layout);
        candidate.sections.get(token.getType()).assignments.remove(token.getTableId());
        commit(candidate);
    }

    public synchronized void onIdChanged(TableToken oldToken, TableToken newToken) {
        String groupId = layout.sections.get(oldToken.getType()).assignments.get(oldToken.getTableId());
        if (groupId != null) {
            Layout candidate = new Layout(layout);
            candidate.sections.get(oldToken.getType()).assignments.remove(oldToken.getTableId());
            candidate.sections.get(newToken.getType()).assignments.put(newToken.getTableId(), groupId);
            commit(candidate);
        }
    }

    public synchronized void persistCheckpoint(CharSequence checkpointDbRoot) {
        try (Path destination = new Path().of(checkpointDbRoot).concat(FILE_NAME)) {
            writeLayout(configuration, destination.$(), layout);
        }
    }

    public synchronized void snapshot(SecurityContext securityContext, boolean settingsReadOnly, Utf8Sink sink) {
        snapshot0(securityContext, !settingsReadOnly && isEditable(securityContext), sink);
    }

    public synchronized void update(SecurityContext securityContext, String json, Utf8Sink sink) {
        securityContext.authorizeSettings();
        SchemaGroupsRequest request = SchemaGroupsRequest.parse(json);
        if (!layout.version.equals(request.version)) {
            throw CairoException.schemaGroupsOutOfDate();
        }
        Section current = layout.sections.get(request.kind);
        String name = request.name;
        switch (request.type) {
            case "create" -> {
                name = normalize(name);
                if (current.groups.size() >= MAX_GROUPS) {
                    throw SchemaGroupsRequest.invalid();
                }
                String folded = name.toLowerCase(Locale.ROOT);
                for (String existing : current.groups.values()) {
                    if (existing.toLowerCase(Locale.ROOT).equals(folded)) {
                        throw SchemaGroupsRequest.invalid();
                    }
                }
            }
            case "delete" -> {
                if (!current.groups.containsKey(request.groupId)) {
                    throw SchemaGroupsRequest.invalid();
                }
            }
            case "move" -> {
                if (request.groupId != null && !current.groups.containsKey(request.groupId)) {
                    throw SchemaGroupsRequest.invalid();
                }
                Map<Integer, TableToken> tokens = tokens();
                for (int id : request.objectIds) {
                    TableToken token = tokens.get(id);
                    if (token == null || token.getType() != request.kind || !visible(securityContext, token)) {
                        throw SchemaGroupsRequest.invalid();
                    }
                }
            }
            default -> throw SchemaGroupsRequest.invalid();
        }

        Layout candidate = new Layout(layout);
        Section section = candidate.sections.get(request.kind);
        switch (request.type) {
            case "create" -> section.groups.put(UUID.randomUUID().toString(), name);
            case "delete" -> {
                section.groups.remove(request.groupId);
                section.assignments.values().removeIf(request.groupId::equals);
            }
            case "move" -> {
                for (int id : request.objectIds) {
                    if (request.groupId == null) {
                        section.assignments.remove(id);
                    } else {
                        section.assignments.put(id, request.groupId);
                    }
                }
            }
            default -> throw SchemaGroupsRequest.invalid();
        }
        commit(candidate);
        snapshot0(securityContext, true, sink);
    }

    private static String normalize(String value) {
        int lo = 0;
        int hi = value.length();
        while (lo < hi && trimCharacter(value.charAt(lo))) {
            lo++;
        }
        while (hi > lo && trimCharacter(value.charAt(hi - 1))) {
            hi--;
        }
        if (hi == lo || hi - lo > MAX_NAME_LENGTH) {
            throw SchemaGroupsRequest.invalid();
        }
        String name = value.substring(lo, hi);
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw SchemaGroupsRequest.invalid();
            }
        }
        return name;
    }

    private static Layout readLayout(CairoConfiguration configuration, LPSZ path) {
        try (BlockFileReader reader = new BlockFileReader(configuration)) {
            reader.of(path);
            var cursor = reader.getCursor();
            if (!cursor.hasNext()) {
                throw CairoException.critical(0).put("Invalid schema groups store");
            }
            ReadableBlock block = cursor.next();
            if (block.type() != FORMAT) {
                throw CairoException.critical(0).put("Unsupported schema groups store format");
            }
            Layout result = new Layout();
            result.version = block.getStr(0).toString();
            long offset = Vm.getStorageLength(result.version);
            for (TableToken.Type kind : TableToken.Type.values()) {
                Section section = result.sections.get(kind);
                int count = block.getInt(offset);
                offset += Integer.BYTES;
                if (count < 0 || count > MAX_GROUPS) {
                    throw CairoException.critical(0).put("Invalid schema groups store");
                }
                for (int i = 0; i < count; i++) {
                    String id = block.getStr(offset).toString();
                    offset += Vm.getStorageLength(id);
                    String name = block.getStr(offset).toString();
                    offset += Vm.getStorageLength(name);
                    section.groups.put(id, name);
                }
                count = block.getInt(offset);
                offset += Integer.BYTES;
                if (count < 0 || count > block.length() / (Integer.BYTES * 3)) {
                    throw CairoException.critical(0).put("Invalid schema groups store");
                }
                for (int i = 0; i < count; i++) {
                    int id = block.getInt(offset);
                    offset += Integer.BYTES;
                    String groupId = block.getStr(offset).toString();
                    offset += Vm.getStorageLength(groupId);
                    section.assignments.put(id, groupId);
                }
            }
            if (offset != block.length() - BlockFileUtils.BLOCK_HEADER_SIZE || cursor.hasNext()) {
                throw CairoException.critical(0).put("Invalid schema groups store");
            }
            return result;
        }
    }

    private static boolean trimCharacter(char c) {
        // ECMAScript WhiteSpace + LineTerminator, including NBSP and BOM (not U+0085).
        return c >= '\t' && c <= '\r' || c == ' ' || c == '\u00a0' || c == '\u1680'
                || c >= '\u2000' && c <= '\u200a' || c == '\u2028' || c == '\u2029'
                || c == '\u202f' || c == '\u205f' || c == '\u3000' || c == '\ufeff';
    }

    private boolean visible(SecurityContext securityContext, TableToken token) {
        if (!securityContext.isTableVisible(token)
                || !TableUtils.isFinalTableName(token.getTableName(), configuration.getTempRenamePendingTablePrefix())) {
            return false;
        }
        try (var metadata = engine.getMetadataCache().readLock()) {
            return metadata.isVisibleTable(token.getTableName());
        }
    }

    private static void writeLayout(CairoConfiguration configuration, LPSZ destination, Layout value) {
        FilesFacade ff = configuration.getFilesFacade();
        try (Path target = new Path().of(destination);
             Path pending = new Path().of(destination).put(".pending")) {
            // Write a fresh block file, never mutate the published file before success.
            if (ff.exists(pending.$())) {
                ff.remove(pending.$());
            }
            try {
                try (BlockFileWriter writer = new BlockFileWriter(ff, CommitMode.SYNC)) {
                    writer.of(pending.$());
                    AppendableBlock block = writer.append();
                    block.putStr(value.version);
                    for (TableToken.Type kind : TableToken.Type.values()) {
                        Section section = value.sections.get(kind);
                        block.putInt(section.groups.size());
                        for (var entry : section.groups.entrySet()) {
                            block.putStr(entry.getKey());
                            block.putStr(entry.getValue());
                        }
                        block.putInt(section.assignments.size());
                        for (var entry : section.assignments.entrySet()) {
                            block.putInt(entry.getKey());
                            block.putStr(entry.getValue());
                        }
                    }
                    block.commit(FORMAT);
                    writer.commit();
                }
                if (ff.renameDurable(pending.$(), target.$()) != Files.FILES_RENAME_OK) {
                    throw CairoException.critical(ff.errno()).put("Could not save schema groups");
                }
                TableUtils.fsyncDirDurable(ff, target.parent().$());
            } finally {
                ff.removeQuiet(pending.$());
            }
        }
    }

    private void commit(Layout candidate) {
        candidate.version = UUID.randomUUID().toString();
        persist(candidate);
        layout = candidate;
    }

    private void persist(Layout candidate) {
        try (Path path = new Path().of(configuration.getDbRoot()).concat(FILE_NAME)) {
            writeLayout(configuration, path.$(), candidate);
        }
    }

    private void snapshot0(SecurityContext securityContext, boolean editable, Utf8Sink sink) {
        Map<Integer, TableToken> tokens = tokens();
        sink.putAscii("{\"version\":").putQuoted(layout.version).putAscii(",\"editable\":").put(editable).putAscii(",\"sections\":{");
        boolean comma = false;
        for (TableToken.Type kind : TableToken.Type.values()) {
            if (comma) {
                sink.putAscii(',');
            }
            comma = true;
            Section section = layout.sections.get(kind);
            sink.putQuoted(kindName(kind)).putAscii(":{\"groups\":[");
            ArrayList<Map.Entry<String, String>> groups = new ArrayList<>(section.groups.entrySet());
            groups.sort(Comparator.comparing(Map.Entry<String, String>::getValue).thenComparing(Map.Entry::getKey));
            boolean groupComma = false;
            for (var group : groups) {
                if (groupComma) {
                    sink.putAscii(',');
                }
                groupComma = true;
                sink.putAscii("{\"id\":").putQuoted(group.getKey()).putAscii(",\"name\":").putQuotedEscapedStr(group.getValue()).putAscii('}');
            }
            sink.putAscii("],\"assignments\":{");
            boolean assignmentComma = false;
            for (var entry : section.assignments.entrySet()) {
                TableToken token = tokens.get(entry.getKey());
                if (token != null && token.getType() == kind && visible(securityContext, token)) {
                    if (assignmentComma) {
                        sink.putAscii(',');
                    }
                    assignmentComma = true;
                    sink.putAscii('"').put(entry.getKey()).putAscii("\":").putQuoted(entry.getValue());
                }
            }
            sink.putAscii("}}");
        }
        sink.putAscii("}}");
    }

    private Map<Integer, TableToken> tokens() {
        ObjHashSet<TableToken> bucket = new ObjHashSet<>();
        engine.getTableTokens(bucket, false);
        Map<Integer, TableToken> tokens = new HashMap<>();
        for (int i = 0; i < bucket.size(); i++) {
            TableToken token = bucket.get(i);
            tokens.put(token.getTableId(), token);
        }
        return tokens;
    }

    private static final class Layout {
        private final EnumMap<TableToken.Type, Section> sections = new EnumMap<>(TableToken.Type.class);
        private String version = UUID.randomUUID().toString();

        private Layout() {
            for (TableToken.Type kind : TableToken.Type.values()) {
                sections.put(kind, new Section());
            }
        }

        private Layout(Layout source) {
            version = source.version;
            for (TableToken.Type kind : TableToken.Type.values()) {
                Section section = new Section();
                section.groups.putAll(source.sections.get(kind).groups);
                section.assignments.putAll(source.sections.get(kind).assignments);
                sections.put(kind, section);
            }
        }
    }

    private static final class Section {
        private final Map<Integer, String> assignments = new HashMap<>();
        private final Map<String, String> groups = new HashMap<>();
    }
}
