# RPC method-name inventory — Bitcoin Core 32.1

Generated from the native Core getopenrpcinfo catalog with show_hidden=true and the Java node catalog.

Reference: 176 methods; local: 74; missing names: 102; local names absent from Core: 0.

This compares names only. Matching names do not prove parameter, result-schema or behavioral equivalence. Wallet and hidden/regtest commands are included and categorized separately.

| Core category | Missing count | Missing method names |
| --- | ---: | --- |
| blockchain | 9 | getblockfrompeer, getchaintxstats, getdeploymentinfo, getdescriptoractivity, importmempool, preciousblock, scanblocks, scantxoutset, verifychain |
| control | 3 | getmemoryinfo, logging, stop |
| hidden | 16 | addconnection, addpeeraddress, echo, echoipc, echojson, estimaterawfee, generate, generateblock, generatetoaddress, generatetodescriptor, getorphantxs, getrawaddrman, mockscheduler, sendmsgtopeer, setmocktime, syncwithvalidationinterfacequeue |
| mining | 0 | — |
| network | 7 | addnode, exportasmap, getaddednodeinfo, getaddrmaninfo, getnettotals, getnodeaddresses, setnetworkactive |
| rawtransactions | 7 | abortprivatebroadcast, combinerawtransaction, descriptorprocesspsbt, fundrawtransaction, getprivatebroadcastinfo, signrawtransactionwithkey, utxoupdatepsbt |
| signer | 1 | enumeratesigners |
| util | 0 | — |
| wallet | 58 | abandontransaction, abortrescan, addhdkey, backupwallet, bumpfee, createwallet, createwalletdescriptor, derivehdkey, encryptwallet, exportwatchonlywallet, getaddressesbylabel, getaddressinfo, getbalance, getbalances, gethdkeys, getnewaddress, getrawchangeaddress, getreceivedbyaddress, getreceivedbylabel, gettransaction, getwalletinfo, importdescriptors, importprunedfunds, keypoolrefill, listaddressgroupings, listdescriptors, listlabels, listlockunspent, listreceivedbyaddress, listreceivedbylabel, listsinceblock, listtransactions, listunspent, listwalletdir, listwallets, loadwallet, lockunspent, migratewallet, psbtbumpfee, removeprunedfunds, rescanblockchain, restorewallet, send, sendall, sendmany, sendtoaddress, setlabel, setwalletflag, signmessage, signrawtransactionwithwallet, simulaterawtransaction, unloadwallet, walletcreatefundedpsbt, walletdisplayaddress, walletlock, walletpassphrase, walletpassphrasechange, walletprocesspsbt |
| zmq | 1 | getzmqnotifications |

Evidence: target/core-update-audit/core32-rpc-method-inventory.json.

The three chain-wait methods additionally have full native OpenRPC/help and
behavior/error comparisons: [implementation and limits](rpc-chain-wait-core32.md).
The remaining RPC execution scope is tracked in [core32-rpc-execution.md](core32-rpc-execution.md).

Acceptance for all eight directions: [core32-eight-acceptance.md](core32-eight-acceptance.md).

The transaction-proof and submitheader methods now have native behavior checks: [implementation and remaining limits](rpc-proof-header-core32.md).

Transaction creation and PSBT Creator: [implementation and evidence](rpc-transaction-creators-core32.md).
Block and transaction reading: [implementation and limits](rpc-block-read-core32.md).

Multisig and signed messages: [implementation and evidence](rpc-multisig-message-core32.md).

PSBT document, Combiner and Joiner roles: [implementation and evidence](rpc-psbt-documents-core32.md).
