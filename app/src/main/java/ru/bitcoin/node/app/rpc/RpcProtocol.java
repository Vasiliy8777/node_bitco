package ru.bitcoin.node.app.rpc;

import java.util.*;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/** Core's request envelope. Batch entries deliberately share request parsing state. */
final class RpcProtocol {
    interface Command { Object execute(String method, Object params); }
    record Reply(int status, Object body) { }
    private static final class Request {
        Object id;
        boolean hasId = true;
        boolean v2;
        boolean notification() { return v2 && !hasId; }
        Object response(Object result, Object error) {
            var reply = new LinkedHashMap<String, Object>();
            if (v2) {
                reply.put("jsonrpc", "2.0");
                reply.put(error == null ? "result" : "error", error == null ? result : error);
            } else {
                reply.put("result", result);
                reply.put("error", error);
            }
            if (hasId) reply.put("id", id);
            return reply;
        }
    }
    static Reply execute(byte[] bytes, JsonMapper mapper, Command command) {
        var request = new Request();
        final Object root;
        try (var parser = mapper.createParser(bytes)) {
            root = read(parser, parser.nextToken());
            if (parser.nextToken() != null) throw new IllegalArgumentException("Trailing JSON");
        } catch (RuntimeException error) {
            return failure(request, -32700, "Parse error", false);
        }
        if (root instanceof Map<?, ?>) return entry(root, request, command, false);
        if (root instanceof List<?> batch) {
            var replies = new ArrayList<>();
            for (Object item : batch) {
                Reply reply = entry(item, request, command, true);
                if (reply.status != 204) replies.add(reply.body);
            }
            return new Reply(!batch.isEmpty() && replies.isEmpty() ? 204 : 200, replies);
        }
        return failure(request, -32700, "Top-level object parse error", false);
    }
    private static Reply entry(Object item, Request state, Command command, boolean batch) {
        boolean parsed = false;
        try {
            if (!(item instanceof Map<?, ?> map)) throw new RpcException(-32600, "Invalid Request object");
            state.hasId = map.containsKey("id");
            state.id = map.get("id");
            state.v2 = false;
            Object version = map.get("jsonrpc");
            if (version != null) {
                if (!(version instanceof String)) throw new RpcException(-32600, "jsonrpc field must be a string");
                if (!version.equals("1.0") && !version.equals("2.0"))
                    throw new RpcException(-32600, "JSON-RPC version not supported");
                state.v2 = version.equals("2.0");
            }
            Object method = map.get("method");
            if (method == null) throw new RpcException(-32600, "Missing method");
            if (!(method instanceof String name)) throw new RpcException(-32600, "Method must be a string");
            Object params = map.get("params");
            if (params != null && !(params instanceof List<?>) && !(params instanceof Map<?, ?>))
                throw new RpcException(-32600, "Params must be an array or object");
            parsed = true;
            Object result = command.execute(name, params);
            return state.notification() ? new Reply(204, null) : new Reply(200, state.response(result, null));
        } catch (RpcException error) {
            if ((batch || parsed) && state.notification()) return new Reply(204, null);
            return failure(state, error.code(), error.getMessage(), batch || parsed && state.v2);
        }
    }
    private static Reply failure(Request state, int code, String message, boolean successStatus) {
        int status = successStatus ? 200 : code == -32600 ? 400 : code == -32601 ? 404 : 500;
        return new Reply(status, state.response(null, Map.of("code", code, "message", message)));
    }
    private static Object read(JsonParser parser, JsonToken token) {
        if (token == null) throw new IllegalArgumentException("Missing JSON");
        return switch (token) {
            case START_OBJECT -> {
                var object = new JsonObject();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw new IllegalArgumentException("Invalid object");
                    String name = parser.getString();
                    Object value = read(parser, parser.nextToken());
                    if (!object.containsKey(name)) object.put(name, value);
                    else object.duplicates.add(name);
                }
                yield object;
            }
            case START_ARRAY -> {
                var array = new ArrayList<>();
                JsonToken next;
                while ((next = parser.nextToken()) != JsonToken.END_ARRAY) array.add(read(parser, next));
                yield array;
            }
            case VALUE_STRING -> parser.getString();
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> new JsonNumber(parser.getText());
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_NULL -> null;
            default -> throw new IllegalArgumentException("Invalid JSON value");
        };
    }
    static final class JsonObject extends LinkedHashMap<String, Object> {
        final List<String> duplicates = new ArrayList<>();
    }
    /** Keep the numeric token intact, including large IDs and exponent notation. */
    private static final class JsonNumber extends Number {
        private final String token;
        JsonNumber(String token) { this.token = token; }
        @Override public int intValue() { return new java.math.BigDecimal(token).intValue(); }
        @Override public long longValue() { return new java.math.BigDecimal(token).longValue(); }
        @Override public float floatValue() { return Float.parseFloat(token); }
        @Override public double doubleValue() { return Double.parseDouble(token); }
        @Override public String toString() { return token; }
    }
}
