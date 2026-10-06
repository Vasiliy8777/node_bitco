package ru.bitcoin.node.storage.rocksdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class FlatFileRecordStoreTest {
    @TempDir Path directory;

    @Test void sequentialWriterRotatesAndRecordsAreReadableBeforeAndAfterClose() {
        var positions = new ArrayList<FlatFileRecordStore.Position>();
        try (var files = new FlatFileRecordStore(directory, "blk", 42, 48, true)) {
            for (int i = 0; i < 20; i++) {
                byte[] payload = new byte[]{(byte) i, 2, 3, 4, 5, 6, 7, 8};
                var position = files.append(payload);
                positions.add(position);
                assertArrayEquals(payload, files.read(position));
                assertEquals(i / 3, position.fileNumber());
                assertEquals((i % 3) * 16L + 8, position.payloadOffset());
            }
        }
        try (var reopened = new FlatFileRecordStore(directory, "blk", 42, 48, true)) {
            for (int i = 0; i < positions.size(); i++) {
                assertEquals((byte) i, reopened.read(positions.get(i))[0]);
            }
            var next = reopened.append(new byte[8]);
            assertEquals(6, next.fileNumber());
            assertEquals(40, next.payloadOffset());
        }
    }

    @Test void databaseOwnsSharedWritersAndCheckpointPublishesReadablePayload() {
        Path path = directory.resolve("db");
        byte[] key = new byte[]{100};
        byte[] payload = new byte[]{1, 2, 3};
        FlatFileRecordStore owned;
        try (var db = new RocksDbDatabase(path)) {
            owned = db.payloadFiles("blk");
            assertSame(owned, db.payloadFiles("blk"));
            db.enableChainstateWriteBack();
            var position = owned.append(payload);
            try (var batch = new RocksDbWriteBatch()) {
                batch.put(key, position.serialize());
                db.write(batch);
            }
            db.forceFlushChainstate();
            assertArrayEquals(payload, owned.read(position));
        }
        assertThrows(IllegalStateException.class, () -> owned.append(payload));
        try (var reopened = new RocksDbDatabase(path)) {
            var position = FlatFileRecordStore.Position.deserialize(reopened.get(key));
            assertArrayEquals(payload, reopened.payloadFiles("blk").read(position));
            var appended = reopened.payloadFiles("blk").append(new byte[]{4});
            assertTrue(appended.payloadOffset() > position.payloadOffset());
        }
    }
}
