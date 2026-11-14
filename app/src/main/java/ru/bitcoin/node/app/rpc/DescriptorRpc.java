package ru.bitcoin.node.app.rpc;

import ru.bitcoin.node.protocol.network.NetworkParameters;
import java.util.*;

/** Descriptor RPC roles share the same parser and expansion used by PSBT updates. */
final class DescriptorRpc {
    private DescriptorRpc() { }
    static Map<String,Object> info(String encoded,NetworkParameters network) {
        var parsed=OutputDescriptor.parse(encoded,false,network);
        var descriptors=parsed.descriptors();var result=new LinkedHashMap<String,Object>();
        var texts=descriptors.stream().map(descriptor->DescriptorChecksum.add(descriptor.text())).toList();
        result.put("descriptor",texts.getFirst());if(texts.size()>1)result.put("multipath_expansion",texts);
        result.put("checksum",parsed.checksum());result.put("isrange",descriptors.getFirst().ranged());
        result.put("issolvable",descriptors.getFirst().solvable());result.put("hasprivatekeys",!parsed.privateKeys().isEmpty());return result;
    }
    static Object derive(List<?> params,NetworkParameters network) {
        Object range=TransactionCreation.optional(params,1);long low=0,high=0;
        if(range!=null){long[] limits=range(range);low=limits[0];high=limits[1];}
        var parsed=OutputDescriptor.parse((String)params.getFirst(),true,network);var first=parsed.descriptors().getFirst();
        if(!first.ranged()&&range!=null)throw new RpcException(-8,"Range should not be specified for an un-ranged descriptor");
        if(first.ranged()&&range==null)throw new RpcException(-8,"Range must be specified for a ranged descriptor");
        var branches=new ArrayList<List<String>>();
        for(var descriptor:parsed.descriptors()) {
            var addresses=new ArrayList<String>();
            for(long position=low;position<=high;position++) {
                var expanded=descriptor.expand(position,parsed.privateKeys());
                if(expanded.isEmpty())throw new RpcException(-5,"Cannot derive script without private keys");
                for(byte[] script:expanded.get()) {
                    var json=ScriptJson.output(script,network);String address=(String)json.get("address");
                    if(address==null){if(expanded.get().size()>1&&"pubkey".equals(json.get("type")))continue;throw new RpcException(-5,"Descriptor does not have a corresponding address");}
                    addresses.add(address);
                }
            }
            if(addresses.isEmpty())throw new RpcException(-1,"Unexpected empty result");branches.add(addresses);
        }
        return branches.size()==1?branches.getFirst():branches;
    }
    static long[] range(Object value) {
        long low,high;
        if(value instanceof Number){low=0;high=TransactionCreation.integer(value,Long.MIN_VALUE,Long.MAX_VALUE);}
        else if(value instanceof List<?> list&&list.size()==2&&list.get(0) instanceof Number&&list.get(1) instanceof Number) {
            low=TransactionCreation.integer(list.get(0),Long.MIN_VALUE,Long.MAX_VALUE);high=TransactionCreation.integer(list.get(1),Long.MIN_VALUE,Long.MAX_VALUE);
            if(low>high)throw new RpcException(-8,"Range specified as [begin,end] must not have begin after end");
        } else throw new RpcException(-8,"Range must be specified as end or as [begin,end]");
        if(low<0)throw new RpcException(-8,"Range should be greater or equal than 0");
        if((high>>31)!=0)throw new RpcException(-8,"End of range is too high");
        if(high>=low+1_000_000)throw new RpcException(-8,"Range is too large");return new long[]{low,high};
    }
}
