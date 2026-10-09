import java.nio.file.*;
import ru.bitcoin.node.p2p.codec.AddrV2MessageCodec;

class AddrV2Replay {
    public static void main(String[] args) throws Exception {
        try (var files = Files.list(Path.of(args[0]))) {
            for (var file : files.filter(path -> path.toString().endsWith(".bin")).sorted().toList()) {
                try {
                    var message = AddrV2MessageCodec.decode(Files.readAllBytes(file));
                    System.out.println(file.getFileName() + " OK entries=" + message.size());
                } catch (Exception error) {
                    System.out.println(file.getFileName() + " FAILED " + error);
                    error.printStackTrace(System.out);
                }
            }
        }
    }
}
