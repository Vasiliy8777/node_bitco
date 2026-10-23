# Vendored sphlib Java sources

Upstream: https://github.com/pornin/sphlib

Pinned commit: `15b6b8d8f3e4a43c58ba102d712fa6b8a3317035`

The 32 files under `src/main/java/fr/cryptohash` are unchanged upstream Java
sources selected for the fourteen missing X16R primitives and Tiger192 plus
their support classes. Existing RVN Keccak512 and JDK SHA512 remain in use.
These files have no external runtime dependencies.

Each file retains its upstream copyright and permissive license. The upstream
LICENSE is also packaged at `META-INF/licenses/sphlib-LICENSE.txt`.

The Java implementation is isolated to rvn-pow; Bitcoin's modules and dependencies
do not acquire sphlib. Mutable digest objects are created per operation.
