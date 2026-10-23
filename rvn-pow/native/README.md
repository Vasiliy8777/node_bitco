# Native consensus implementation boundary (NOT bundled)
The Java `RvnPowNativeBackend` expects a JNI library exporting:
`Java_ru_bitcoin_node_rvn_pow_RvnPowNativeBackend_calculateNative(JNIEnv*,jclass,jint,jbyteArray)`.
Inputs: algorithm ordinal 0=X16R, 1=X16RV2, 2=KawPoW; header 80 or 120 bytes.
Return exactly 64 bytes: first 32 = block ID **wire order**, next 32 =
PoW digest **wire order**. Throw on invalid inputs or calculation failure.
Do not substitute SHA256d of the 80-byte header for KawPoW final digest.
A real binding must call the audited Ravencoin Core implementations for
X16R, X16RV2, and KawPoW, preserving historical activation and target rules.
This repository patch does NOT provide those algorithms or native binary.
A JNI library must be independently built, vetted, and validated with
authoritative Ravencoin Core vectors before enabling production use.
