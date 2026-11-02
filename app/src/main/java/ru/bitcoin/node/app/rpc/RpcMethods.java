package ru.bitcoin.node.app.rpc;

import java.util.*;

/** Metadata for the implemented RPC surface; shared by discovery, help and named arguments. */
final class RpcMethods {
    private RpcMethods() { }
    private record Parameter(String name, String type, boolean required, Object defaultValue) { }
    private record Method(String name, String resultType, List<Parameter> parameters) { }
    private static final Map<String, Method> METHODS = create();

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
                prioritisetransaction boolean txid:string dummy:any fee_delta:number
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

    static List<?> positional(String methodName, Map<?, ?> named) {
        var method = METHODS.get(methodName);
        if (method == null) throw new RpcException(-32601, "Method not found");
        var known = new HashSet<String>();
        for (var parameter : method.parameters()) known.add(parameter.name());
        for (var key : named.keySet())
            if (!known.contains(key)) throw new RpcException(-8, "Unknown named parameter " + key);
        int last = -1;
        for (int i = 0; i < method.parameters().size(); i++)
            if (named.containsKey(method.parameters().get(i).name())) last = i;
        var result = new ArrayList<Object>();
        for (int i = 0; i <= last; i++) {
            var parameter = method.parameters().get(i);
            Object value = named.get(parameter.name());
            if (value == null) value = parameter.defaultValue();
            if (value == null && parameter.required() && !named.containsKey(parameter.name()))
                throw new RpcException(-8, "Missing required parameter " + parameter.name());
            result.add(value);
        }
        return result;
    }

    static Map<String, Object> discovery() {
        var methods = new ArrayList<Map<String, Object>>();
        for (var method : METHODS.values()) {
            var params = new ArrayList<Map<String, Object>>();
            for (var parameter : method.parameters()) {
                var descriptor = new LinkedHashMap<String, Object>();
                descriptor.put("name", parameter.name());
                descriptor.put("required", parameter.required());
                var schema = schema(parameter.type());
                if (parameter.defaultValue() != null) schema.put("default", parameter.defaultValue());
                descriptor.put("schema", schema);
                params.add(descriptor);
            }
            methods.add(Map.of("name", method.name(), "paramStructure", "either", "params", params,
                    "result", Map.of("name", "result", "schema", schema(method.resultType()))));
        }
        return Map.of("openrpc", "1.4.1", "info", Map.of("title", "java-bitcoin-node RPC", "version", "0.0.1"),
                "methods", methods);
    }

    private static Map<String, Object> schema(String type) {
        var schema = new LinkedHashMap<String, Object>();
        if (!type.equals("any")) schema.put("type", type);
        if (type.equals("array")) schema.put("items", Map.of());
        return schema;
    }
}
