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

import io.questdb.cairo.CairoException;
import io.questdb.cairo.TableToken;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Strict, bounded parser for the flat schema-groups command (not a general JSON DOM).
 */
final class SchemaGroupsRequest {
    static final int MAX_MOVE_SIZE = 1024;
    final String groupId;
    final TableToken.Type kind;
    final String name;
    final int[] objectIds;
    final String type;
    final String version;
    private final String json;
    private int pos;

    private SchemaGroupsRequest(String json) {
        this.json = json;
        Map<String, Object> fields = new HashMap<>();
        expect('{');
        if (!take('}')) {
            do {
                String key = string();
                if (fields.containsKey(key)) {
                    throw invalid();
                }
                expect(':');
                Object value;
                if ("objectIds".equals(key)) {
                    value = ids();
                } else if ("groupId".equals(key) && takeNull()) {
                    value = null;
                } else {
                    value = string();
                }
                fields.put(key, value);
            } while (take(','));
            expect('}');
        }
        whitespace();
        if (pos != json.length()) {
            throw invalid();
        }
        version = required(fields, "version");
        kind = switch (required(fields, "kind")) {
            case "table" -> TableToken.Type.TABLE;
            case "matview" -> TableToken.Type.MAT_VIEW;
            case "view" -> TableToken.Type.VIEW;
            case "liveview" -> TableToken.Type.LIVE_VIEW;
            default -> throw invalid();
        };
        type = required(fields, "type");
        name = (String) fields.get("name");
        groupId = (String) fields.get("groupId");
        objectIds = (int[]) fields.get("objectIds");
        boolean valid = switch (type) {
            case "create" -> fields.size() == 4 && name != null;
            case "delete" -> fields.size() == 4 && groupId != null;
            case "move" -> fields.size() == 5 && fields.containsKey("groupId") && objectIds != null;
            default -> false;
        };
        if (!valid) {
            throw invalid();
        }
    }

    static CairoException invalid() {
        return CairoException.nonCritical().put("Invalid schema groups request");
    }

    static SchemaGroupsRequest parse(String json) {
        if (json.length() > SchemaGroupsStore.MAX_REQUEST_BYTES) {
            throw invalid();
        }
        return new SchemaGroupsRequest(json);
    }

    private static String required(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof String)) {
            throw invalid();
        }
        return (String) value;
    }

    private void expect(char c) {
        if (!take(c)) {
            throw invalid();
        }
    }

    private int[] ids() {
        expect('[');
        int[] values = new int[MAX_MOVE_SIZE];
        int size = 0;
        do {
            whitespace();
            if (size == MAX_MOVE_SIZE || pos == json.length() || json.charAt(pos) < '1' || json.charAt(pos) > '9') {
                throw invalid();
            }
            int value = 0;
            while (pos < json.length() && json.charAt(pos) >= '0' && json.charAt(pos) <= '9') {
                int digit = json.charAt(pos++) - '0';
                if (value > (Integer.MAX_VALUE - digit) / 10) {
                    throw invalid();
                }
                value = value * 10 + digit;
            }
            values[size++] = value;
        } while (take(','));
        expect(']');
        return Arrays.copyOf(values, size);
    }

    private String string() {
        expect('"');
        StringBuilder value = new StringBuilder();
        boolean ended = false;
        while (pos < json.length()) {
            char c = json.charAt(pos++);
            if (c == '"') {
                ended = true;
                break;
            }
            if (c < 0x20) {
                throw invalid();
            }
            if (c == '\\') {
                if (pos == json.length()) {
                    throw invalid();
                }
                c = json.charAt(pos++);
                c = switch (c) {
                    case '"', '\\', '/' -> c;
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> unicode();
                    default -> throw invalid();
                };
            }
            value.append(c);
        }
        if (!ended) {
            throw invalid();
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw invalid();
                }
            } else if (Character.isLowSurrogate(c)) {
                throw invalid();
            }
        }
        return value.toString();
    }

    private boolean take(char c) {
        whitespace();
        if (pos < json.length() && json.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private boolean takeNull() {
        whitespace();
        if (json.startsWith("null", pos)) {
            pos += 4;
            return true;
        }
        return false;
    }

    private char unicode() {
        if (json.length() - pos < 4) {
            throw invalid();
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            char c = json.charAt(pos++);
            int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
            if (digit < 0) {
                throw invalid();
            }
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private void whitespace() {
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                break;
            }
            pos++;
        }
    }
}
