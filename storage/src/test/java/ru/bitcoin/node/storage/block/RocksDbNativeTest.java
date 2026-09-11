package ru.bitcoin.node.storage.block;

import org.junit.jupiter.api.Test;
import org.rocksdb.RocksDB;

class RocksDbNativeTest {

    @Test
    void shouldLoadRocksDbNativeLibrary() {

        System.out.println(
                "Java version: "
                        + System.getProperty("java.version")
        );

        System.out.println(
                "Java home: "
                        + System.getProperty("java.home")
        );

        System.out.println(
                "OS: "
                        + System.getProperty("os.name")
        );

        System.out.println(
                "Architecture: "
                        + System.getProperty("os.arch")
        );

        System.out.println(
                "Loading RocksDB..."
        );

        RocksDB.loadLibrary();

        System.out.println(
                "RocksDB loaded successfully"
        );
    }
}