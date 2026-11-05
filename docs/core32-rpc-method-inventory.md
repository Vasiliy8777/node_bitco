# RPC method-name inventory — Bitcoin Core 32.1

Generated from the native Core getopenrpcinfo catalog with show_hidden=true and the Java node catalog.

Reference: 176 methods; local: 52; missing names: 124; local names absent from Core: 0.

This compares names only. Matching names do not prove parameter, result-schema or behavioral equivalence. Wallet and hidden/regtest commands are included and categorized separately.

| Core category | Missing count | Missing method names |
| --- | ---: | --- |
| blockchain | 15 | getblockfrompeer, getblockstats, getchaintxstats, getdeploymentinfo, getdescriptoractivity, gettxoutproof, importmempool, preciousblock, scanblocks, scantxoutset, verifychain, verifytxoutproof, waitforblock, waitforblockheight, waitfornewblock |
| control | 3 | getmemoryinfo, logging, stop |
| hidden | 16 | addconnection, addpeeraddress, echo, echoipc, echojson, estimaterawfee, generate, generateblock, generatetoaddress, generatetodescriptor, getorphantxs, getrawaddrman, mockscheduler, sendmsgtopeer, setmocktime, syncwithvalidationinterfacequeue |
| mining | 1 | submitheader |
| network | 7 | addnode, exportasmap, getaddednodeinfo, getaddrmaninfo, getnettotals, getnodeaddresses, setnetworkactive |
| rawtransactions | 16 | abortprivatebroadcast, analyzepsbt, combinepsbt, combinerawtransaction, converttopsbt, createpsbt, createrawtransaction, decodepsbt, decodescript, descriptorprocesspsbt, finalizepsbt, fundrawtransaction, getprivatebroadcastinfo, joinpsbts, signrawtransactionwithkey, utxoupdatepsbt |
| signer | 1 | enumeratesigners |
| util | 6 | createmultisig, deriveaddresses, getdescriptorinfo, signmessagewithprivkey, validateaddress, verifymessage |
| wallet | 58 | abandontransaction, abortrescan, addhdkey, backupwallet, bumpfee, createwallet, createwalletdescriptor, derivehdkey, encryptwallet, exportwatchonlywallet, getaddressesbylabel, getaddressinfo, getbalance, getbalances, gethdkeys, getnewaddress, getrawchangeaddress, getreceivedbyaddress, getreceivedbylabel, gettransaction, getwalletinfo, importdescriptors, importprunedfunds, keypoolrefill, listaddressgroupings, listdescriptors, listlabels, listlockunspent, listreceivedbyaddress, listreceivedbylabel, listsinceblock, listtransactions, listunspent, listwalletdir, listwallets, loadwallet, lockunspent, migratewallet, psbtbumpfee, removeprunedfunds, rescanblockchain, restorewallet, send, sendall, sendmany, sendtoaddress, setlabel, setwalletflag, signmessage, signrawtransactionwithwallet, simulaterawtransaction, unloadwallet, walletcreatefundedpsbt, walletdisplayaddress, walletlock, walletpassphrase, walletpassphrasechange, walletprocesspsbt |
| zmq | 1 | getzmqnotifications |

Evidence: target/core-update-audit/core32-rpc-method-inventory.json.

Acceptance for all eight directions: [core32-eight-acceptance.md](core32-eight-acceptance.md).
