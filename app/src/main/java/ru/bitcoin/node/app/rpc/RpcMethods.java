package ru.bitcoin.node.app.rpc;

import java.util.*;

/** Metadata for the implemented RPC surface; shared by discovery, help and named arguments. */
final class RpcMethods {
    private RpcMethods() { }
    private record Parameter(String name, String type, boolean required, Object defaultValue) { }
    private record Method(String name, String resultType, List<Parameter> parameters) { }
    private static final Map<String, Method> METHODS = create();
    private record Canonical(Map<String, Map<String, Object>> methods, Map<String, String> help) {}
    private static final Canonical CHAIN_WAIT = loadChainWait();
    private static final Map<String, Object> CORE = loadCore();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadCore() {
        try (var input = RpcMethods.class.getResourceAsStream("/rpc/core32-methods.json")) {
            if (input == null) throw new IllegalStateException("Missing Core RPC metadata");
            return tools.jackson.databind.json.JsonMapper.builder().build().readValue(input, Map.class);
        } catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }

    @SuppressWarnings("unchecked")
    private static Canonical loadChainWait() {
        try (var input = RpcMethods.class.getResourceAsStream("/rpc/core32-wait-methods.json")) {
            if (input == null) throw new IllegalStateException("Missing Core RPC chain-wait metadata");
            var root = tools.jackson.databind.json.JsonMapper.builder().build().readValue(input, Map.class);
            var methods = new LinkedHashMap<String, Map<String, Object>>();
            for (var entry : (List<Map<String, Object>>) root.get("methods")) methods.put((String) entry.get("name"), entry);
            return new Canonical(Map.copyOf(methods), Map.copyOf((Map<String, String>) root.get("help")));
        } catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }

    static String canonicalHelp(String method) { return (String) ((Map<?, ?>) CORE.get("help")).get(method); }
    static String helpList() { return (String) CORE.get("help_list"); }

    @SuppressWarnings("unchecked")
    static void validateRequest(String name, List<?> values) {
        if (!METHODS.containsKey(name)) throw new RpcException(-32601, "Method not found");
        Map<?, ?> method = ((List<Map<String, Object>>) CORE.get("methods")).stream()
                .filter(entry -> name.equals(entry.get("name"))).findFirst().orElseThrow();
        var parameters = (List<Map<String, Object>>) method.get("params");
        int required = 0;
        for (int i = 0; i < parameters.size(); i++) if (Boolean.TRUE.equals(parameters.get(i).get("required"))) required = i + 1;
        if (values.size() < required || values.size() > parameters.size()) throw new RpcException(-1, canonicalHelp(name));
        var errors = new ArrayList<String>();
        for (int i = 0; i < parameters.size(); i++) {
            var parameter = parameters.get(i);
            Object value = i < values.size() ? values.get(i) : null;
            if (value == null && !Boolean.TRUE.equals(parameter.get("required"))) continue;
            Object type = ((Map<?, ?>) parameter.get("schema")).get("type");
            if (!(type instanceof String expected)) continue;
            if (expected.equals("integer")) expected = "number";
            if (expected.equals("boolean")) expected = "bool";
            String actual = value == null ? "null" : value instanceof Boolean ? "bool" : value instanceof Number ? "number"
                    : value instanceof String ? "string" : value instanceof List<?> ? "array" : "object";
            if (actual.equals(expected)) continue;
            String label = parameter.get("name").toString();
            if (parameter.get("x-bitcoin-aliases") instanceof List<?> aliases)
                for (Object alias : aliases) label += "|" + alias;
            errors.add("    \"Position " + (i + 1) + " (" + label + ")\": \"JSON value of type " + actual
                    + " is not of expected type " + expected + "\"");
        }
        if (!errors.isEmpty()) throw new RpcException(-3, "Wrong type passed:\n{\n" + String.join(",\n", errors) + "\n}");
    }

    static void validateChainWaitParameters(String method, List<?> params) {
        var definition = METHODS.get(method);
        long required = definition.parameters().stream().filter(Parameter::required).count();
        if (params.size() < required || params.size() > definition.parameters().size())
            throw new RpcException(-1, canonicalHelp(method));
        validateParameterTypes(method, params);
    }

    static void validateParameterTypes(String method, List<?> params) {
        var definition = METHODS.get(method);
        var errors = new ArrayList<String>();
        for (int i = 0; i < params.size(); i++) {
            var parameter = definition.parameters().get(i); var value = params.get(i);
            if (value == null && !parameter.required()) continue;
            boolean valid = parameter.type().equals("number") ? value instanceof Number : value instanceof String;
            if (valid) continue;
            String actual = value == null ? "null" : value instanceof Boolean ? "bool" : value instanceof Number ? "number"
                    : value instanceof String ? "string" : value instanceof List<?> ? "array" : "object";
            errors.add("    \"Position " + (i + 1) + " (" + parameter.name() + ")\": \"JSON value of type " + actual
                    + " is not of expected type " + parameter.type() + "\"");
        }
        if (!errors.isEmpty()) throw new RpcException(-3, "Wrong type passed:\n{\n" + String.join(",\n", errors) + "\n}");
    }

    private static Map<String, Method> create() {
        // name, result type, then positional parameter descriptors. '?' denotes optional.
        String definitions = """
                help string command:string?
                getrpcinfo object
                estimatesmartfee object conf_target:number estimate_mode:string?=economical options:object?
                getopenrpcinfo object show_hidden:boolean?=false
                rpc.discover object
                ping null
                savemempool object
                getblocktemplate object template_request:object
                submitblock any hexdata:string
                sendrawtransaction string hexstring:string maxfeerate:any? maxburnamount:any?
                testmempoolaccept array rawtxs:array maxfeerate:any?
                submitpackage object package:array maxfeerate:any? maxburnamount:any?
                decoderawtransaction object hexstring:string
                getrawtransaction any txid:string verbosity:any?=0 blockhash:string?
                prioritisetransaction boolean txid:string dummy:number? fee_delta:number
                getprioritisedtransactions object
                getmempoolentry object txid:string
                getmempoolancestors any txid:string verbose:boolean?=false
                getmempooldescendants any txid:string verbose:boolean?=false
                getmempoolcluster object txid:string
                getmempoolfeeratediagram array
                gettxspendingprevout array outputs:array options:object?
                gettxout any txid:string n:number include_mempool:boolean?=true
                gettxoutsetinfo object hash_type:string?=hash_serialized_3 hash_or_height:any? use_index:boolean?=true
                getblockhash string height:number
                waitfornewblock object timeout:number?=0 current_tip:string?
                waitforblock object blockhash:string timeout:number?=0
                waitforblockheight object height:number timeout:number?=0
                getblockheader any blockhash:string verbose:boolean?=true
                getblock any blockhash:string verbosity:number?=1
                getconnectioncount number
                getpeerinfo array
                disconnectnode null address:string
                setban null subnet:string command:string bantime:number?=86400 absolute:boolean?=false
                listbanned array
                clearbanned null
                pruneblockchain number height:number
                invalidateblock null blockhash:string
                reconsiderblock null blockhash:string
                getchaintips array
                getblockfilter object blockhash:string filtertype:string?=basic
                dumptxoutset object path:string type:string?=latest
                loadtxoutset object path:string
                getchainstates object
                getblockchaininfo object
                getblockcount number
                getbestblockhash string
                getdifficulty number
                getnetworkhashps number nblocks:number?=120 height:number?=-1
                uptime number
                getmempoolinfo object
                getnetworkinfo object
                getindexinfo object index_name:string?
                getmininginfo object
                getrawmempool any verbose:boolean?=false mempool_sequence:boolean?=false
                """;
        var methods = new TreeMap<String, Method>();
        for (String line : definitions.strip().split("\\R")) {
            String[] fields = line.strip().split("\\s+");
            var params = new ArrayList<Parameter>();
            for (int i = 2; i < fields.length; i++) {
                String[] spec = fields[i].split(":", 2);
                String[] typeAndDefault = spec[1].split("=", 2);
                boolean optional = typeAndDefault[0].endsWith("?");
                String type = typeAndDefault[0].replace("?", "");
                Object value = null;
                if (typeAndDefault.length == 2) {
                    String raw = typeAndDefault[1];
                    value = switch (type) {
                        case "boolean" -> Boolean.valueOf(raw);
                        case "number", "any" -> Long.valueOf(raw);
                        default -> raw;
                    };
                }
                params.add(new Parameter(spec[0], type, !optional, value));
            }
            methods.put(fields[0], new Method(fields[0], fields[1], List.copyOf(params)));
        }
        return Collections.unmodifiableMap(methods);
    }

    static Set<String> names() { return METHODS.keySet(); }

    @SuppressWarnings("unchecked")
    static List<?> positional(String methodName, Map<?, ?> named) {
        if (!METHODS.containsKey(methodName)) throw new RpcException(-32601, "Method not found");
        if (named instanceof RpcProtocol.JsonObject object && !object.duplicates.isEmpty())
            throw new RpcException(-8, "Parameter " + object.duplicates.getFirst() + " specified multiple times");
        var definition = ((List<Map<String, Object>>) CORE.get("methods")).stream()
                .filter(entry -> methodName.equals(entry.get("name"))).findFirst().orElseThrow();
        var parameters = (List<Map<String, Object>>) definition.get("params");
        var remaining = new LinkedHashMap<String, Object>();
        named.forEach((key, value) -> remaining.put(key.toString(), value));
        var result = new ArrayList<Object>();
        int hole = 0, initialHole = 0;
        String initialParameter = null;
        for (var parameter : parameters) {
            String name = parameter.get("name").toString();
            var aliases = new ArrayList<String>();
            aliases.add(name);
            if (parameter.get("x-bitcoin-aliases") instanceof List<?> alternatives)
                for (Object alias : alternatives) aliases.add(alias.toString());
            String selected = aliases.stream().filter(remaining::containsKey).findFirst().orElse(null);
            var options = new LinkedHashMap<String, Object>();
            if (name.equals("options") && Set.of("gettxspendingprevout", "dumptxoutset").contains(methodName)) {
                var schema = (Map<?, ?>) parameter.get("schema");
                if (schema.get("properties") instanceof Map<?, ?> properties) {
                    for (Object key : properties.keySet()) {
                        if (remaining.containsKey(key.toString())) options.put(key.toString(), remaining.remove(key.toString()));
                    }
                }
            }
            if (selected != null || !options.isEmpty()) {
                for (int i = 0; i < hole; i++) result.add(null);
                hole = 0;
                if (initialParameter == null) initialParameter = String.join("|", aliases);
            } else {
                hole++;
                if (result.isEmpty()) initialHole = hole;
            }
            if (selected != null) {
                if (!options.isEmpty()) throw new RpcException(-8, "Parameter " + selected + " conflicts with parameter " + options.keySet().iterator().next());
                result.add(remaining.remove(selected));
            }
            if (!options.isEmpty()) result.add(options);
        }
        Object positional = remaining.remove("args");
        if (positional instanceof List<?> args) {
            if (initialHole < args.size() && initialParameter != null)
                throw new RpcException(-8, "Parameter " + initialParameter + " specified twice both as positional and named argument");
            var merged = new ArrayList<Object>(args);
            for (int i = merged.size(); i < result.size(); i++) merged.add(result.get(i));
            result = merged;
        }
        if (!remaining.isEmpty()) throw new RpcException(-8, "Unknown named parameter " + new TreeSet<>(remaining.keySet()).first());
        return result;
    }

    static Map<String, Object> discovery() {
        return discovery(false);
    }
    static Map<String, Object> discovery(boolean showHidden) {
        return Map.of("openrpc", "1.4.1", "info", Map.of("title", "java-bitcoin-node RPC", "version", "0.0.1",
                "description", "Bitcoin Core 32.1 compatible RPC methods implemented by java-bitcoin-node."),
                "methods", CORE.get(showHidden ? "methods" : "public_methods"));
    }
}
