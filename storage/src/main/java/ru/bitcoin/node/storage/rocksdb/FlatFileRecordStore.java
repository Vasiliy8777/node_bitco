package ru.bitcoin.node.storage.rocksdb;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;

/** Core-style append-only flat-file payload storage (blkNNNNN.dat / revNNNNN.dat). */
public final class FlatFileRecordStore implements AutoCloseable {
    public static final long DEFAULT_MAX_FILE_SIZE = 128L * 1024L * 1024L;
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();

    public record Position(int fileNumber, long payloadOffset, int payloadLength) {
        public byte[] serialize() {
            return ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                    .putInt(fileNumber).putLong(payloadOffset).putInt(payloadLength).array();
        }
        public static Position deserialize(byte[] value) {
            if (value == null || value.length != 16) throw new IllegalStateException("Invalid flat-file position metadata");
            var b = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN);
            int n=b.getInt(); long o=b.getLong(); int l=b.getInt();
            if (n < 0 || o < 0 || l < 0) throw new IllegalStateException("Invalid flat-file position values");
            return new Position(n,o,l);
        }
    }

    private final Path directory;
    private final String prefix;
    private final int magic;
    private final long maxFileSize;
    private final Object lock;
    private final boolean keepOpen;
    private java.nio.channels.FileChannel writer;
    private int writerFile = -1;
    private boolean dirty;
    private boolean closed;

    public FlatFileRecordStore(Path directory, String prefix, long magic) {
        this(directory, prefix, magic, DEFAULT_MAX_FILE_SIZE);
    }

    FlatFileRecordStore(Path directory, String prefix, long magic, long maxFileSize) {
        this(directory, prefix, magic, maxFileSize, false);
    }

    FlatFileRecordStore(Path directory, String prefix, long magic, long maxFileSize, boolean keepOpen) {
        if (directory == null || prefix == null || prefix.length() != 3) throw new IllegalArgumentException("Invalid flat-file configuration");
        if (maxFileSize <= 8) throw new IllegalArgumentException("maxFileSize too small");
        this.directory=directory.toAbsolutePath().normalize(); this.prefix=prefix; this.magic=(int)magic; this.maxFileSize=maxFileSize;
        this.lock=LOCKS.computeIfAbsent(this.directory.resolve(prefix), ignored -> new Object());
        this.keepOpen = keepOpen;
        try { Files.createDirectories(this.directory); } catch(IOException e) { throw new IllegalStateException("Cannot create flat-file directory: "+directory,e); }
    }

    public Position append(byte[] payload) {
        if (payload == null) throw new IllegalArgumentException("payload must not be null");
        synchronized(lock) {
            if (closed) throw new IllegalStateException("Flat-file store is closed");
            try {
                if (keepOpen) return appendOpen(payload);
                int file=lastFileNumber(); Path path=file(file); long size=Files.exists(path)?Files.size(path):0L;
                long recordSize=8L+payload.length;
                if (size > 0 && size + recordSize > maxFileSize) { file++; path=file(file); size=0L; }
                byte[] header=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(magic).putInt(payload.length).array();
                try (var out=Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    out.write(header); out.write(payload);
                }
                return new Position(file,size+8L,payload.length);
            } catch(IOException e) { throw new IllegalStateException("Failed to append "+prefix+" flat-file record",e); }
        }
    }

    /** Database-owned stores reuse the sequential append handle between checkpoints. */
    private Position appendOpen(byte[] payload) throws IOException {
        if (writer == null) openWriter(lastFileNumber());
        long size = writer.size();
        if (size > 0 && size + 8L + payload.length > maxFileSize) {
            flush();
            writer.close();
            writer = null;
            openWriter(writerFile + 1);
            size = 0;
        }
        writer.position(size);
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(magic).putInt(payload.length).flip();
        // A partial write must also participate in the next durability barrier.
        dirty = true;
        while (header.hasRemaining()) writer.write(header);
        ByteBuffer body = ByteBuffer.wrap(payload);
        while (body.hasRemaining()) writer.write(body);
        return new Position(writerFile, size + 8L, payload.length);
    }

    private void openWriter(int fileNumber) throws IOException {
        writer = java.nio.channels.FileChannel.open(file(fileNumber),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        writerFile = fileNumber;
    }

    /** Flush payloads before publishing durable database pointers to them. */
    public void flush() {
        synchronized (lock) {
            if (writer == null || !dirty) return;
            try {
                writer.force(true);
                dirty = false;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to flush " + prefix + " flat file", e);
            }
        }
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            flush();
            if (writer != null) {
                try { writer.close(); }
                catch (IOException e) { throw new IllegalStateException("Failed to close flat file", e); }
                writer = null;
            }
            closed = true;
        }
    }

    public byte[] read(Position p) {
        synchronized(lock) {
            Path path=file(p.fileNumber());
            if (!Files.isRegularFile(path)) throw new IllegalStateException("Missing flat file: "+path);
            try (var raf=new RandomAccessFile(path.toFile(),"r")) {
                long headerOffset=p.payloadOffset()-8L;
                if (headerOffset < 0 || p.payloadOffset()+p.payloadLength() > raf.length()) throw new IllegalStateException("Flat-file position outside file: "+path);
                raf.seek(headerOffset);
                byte[] header=new byte[8]; raf.readFully(header);
                var b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                int storedMagic=b.getInt(), length=b.getInt();
                if (storedMagic != magic || length != p.payloadLength()) throw new IllegalStateException("Flat-file framing mismatch: "+path);
                byte[] payload=new byte[length]; raf.readFully(payload); return payload;
            } catch(IOException e) { throw new IllegalStateException("Failed to read flat-file record: "+path,e); }
        }
    }

    private Path file(int number) { return directory.resolve(String.format(java.util.Locale.ROOT, "%s%05d.dat",prefix,number)); }
    private int lastFileNumber() throws IOException {
        int max=0;
        try(var stream=Files.list(directory)) {
            for(Path p:(Iterable<Path>)stream::iterator) {
                String n=p.getFileName().toString();
                if(n.length()==12 && n.startsWith(prefix) && n.endsWith(".dat")) {
                    try { max=Math.max(max,Integer.parseInt(n.substring(3,8))); } catch(NumberFormatException ignored) {}
                }
            }
        }
        return max;
    }
}
