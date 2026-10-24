// Test-only adapters for executing unchanged Ravencoin Core v4.8.0 qualifier
// methods. No node, PoW or RPC implementation is linked into the Java runtime.
#include <map>
#include <set>
#include <string>
#include <iostream>
#include <utility>
using std::string;
enum class QualifierType { REMOVE_QUALIFIER, ADD_QUALIFIER };
struct Identity {
    string value;
    string GetHex() const { return value; }
};
struct CAssetCacheQualifierAddress {
    string assetName, address; QualifierType type;
    CAssetCacheQualifierAddress(string n, string a, QualifierType t):assetName(n),address(a),type(t){}
    bool operator<(const CAssetCacheQualifierAddress& b) const { return std::tie(assetName,address)<std::tie(b.assetName,b.address); }
    Identity GetHash() const { return {assetName+":"+address}; }
};
struct CAssetCacheRootQualifierChecker {
    string name,address;
    CAssetCacheRootQualifierChecker(string n,string a):name(n),address(a){}
    bool operator<(const CAssetCacheRootQualifierChecker& b) const { return std::tie(name,address)<std::tie(b.name,b.address); }
};
bool IsAssetNameASubQualifier(const string& n) { return n.find('/')!=string::npos; }
string GetParentName(const string& n) { return n.substr(0,n.find('/')); }
struct RestrictedDB {
    std::set<std::pair<string,string>> entries;
    bool ReadAddressQualifier(const string& a,const string& n) { return entries.count({a,n}); }
    bool WriteAddressQualifier(const string& a,const string& n) { entries.insert({a,n}); return true; }
    bool EraseAddressQualifier(const string& a,const string& n) { entries.erase({a,n}); return true; }
    bool WriteQualifierAddress(const string&,const string&) { return true; }
    bool EraseQualifierAddress(const string&,const string&) { return true; }
    bool CheckForAddressRootQualifier(const string& a,const string& n) {
        for (auto& p:entries) if (p.first==a && (p.second==n || p.second.rfind(n+"/",0)==0)) return true;
        return false;
    }
};
struct QualifierCache {
    std::set<string> entries;
    bool Exists(const string& k) { return entries.count(k); }
    void Put(const string& k,int) { entries.insert(k); }
    void Erase(const string& k) { entries.erase(k); }
};
struct CAssetsCache {
    std::set<CAssetCacheQualifierAddress> setNewQualifierAddressToAdd,setNewQualifierAddressToRemove;
    std::map<CAssetCacheRootQualifierChecker,std::set<string>> mapRootQualifierAddressesAdd,mapRootQualifierAddressesRemove;
    bool AddQualifierAddress(const string&,const string&,QualifierType);
    bool RemoveQualifierAddress(const string&,const string&,QualifierType);
    bool CheckForAddressQualifier(const string&,const string&,bool);
    bool DumpQualifiers();
    void ClearDirtyCache() {
        setNewQualifierAddressToAdd.clear(); setNewQualifierAddressToRemove.clear();
        mapRootQualifierAddressesAdd.clear(); mapRootQualifierAddressesRemove.clear();
    }
};
CAssetsCache global;
CAssetsCache* passets=&global;
RestrictedDB disk;
RestrictedDB* prestricteddb=&disk;
QualifierCache lru;
QualifierCache* passetsQualifierCache=&lru;
bool fAssetIndex=false;
bool error(const char*,const char*,const string&) { return false; }
#include "qualifier-methods.inc"

int main() {
    // The Java policy makes every parent snapshot durable, clearing dirty
    // command caches at every block boundary. Probe both modes to make that
    // choice visible rather than hiding Core's cache-sensitive behavior.
    const string a="ab";
    auto query=[&](const string& label) {
        std::cout<<label<<'\t'<<global.CheckForAddressQualifier("#KYC",a,true)<<'\t'
            <<global.CheckForAddressQualifier("#KYC/#ID",a,true)<<'\n';
    };
    query("empty");
    global.AddQualifierAddress("#KYC/#ID",a,QualifierType::ADD_QUALIFIER);
    if (!global.DumpQualifiers()) return 2;
    query("child-flushed");
    global.AddQualifierAddress("#KYC",a,QualifierType::REMOVE_QUALIFIER);
    query("root-remove-dirty");
    if (!global.DumpQualifiers()) return 2;
    query("root-remove-flushed");
    global.RemoveQualifierAddress("#KYC",a,QualifierType::REMOVE_QUALIFIER);
    if (!global.DumpQualifiers()) return 2;
    query("root-remove-core-inverse-undo");
    global.AddQualifierAddress("#KYC/#ID",a,QualifierType::REMOVE_QUALIFIER);
    if (!global.DumpQualifiers()) return 2;
    query("child-remove-after-core-inverse");
    return 0;
}
