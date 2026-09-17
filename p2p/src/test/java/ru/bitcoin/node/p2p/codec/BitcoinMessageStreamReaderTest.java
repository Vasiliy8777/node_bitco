package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitcoinMessageStreamReaderTest {

    @Test
    void shouldReadSingleMessage()
            throws Exception {

        BitcoinMessageEncoder encoder =
                encoder();

        BitcoinMessageStreamReader reader =
                reader();

        byte[] packet =
                encoder.encode(
                        new BitcoinMessage(
                                "ping",
                                new byte[]{
                                        1, 2, 3, 4
                                }
                        )
                );

        Optional<BitcoinMessage> result =
                reader.read(
                        new ByteArrayInputStream(packet)
                );

        assertTrue(result.isPresent());

        assertEquals(
                "ping",
                result.orElseThrow().command()
        );

        assertArrayEquals(
                new byte[]{
                        1, 2, 3, 4
                },
                result.orElseThrow().payload()
        );
    }

    @Test
    void shouldReturnEmptyOnCleanEof()
            throws Exception {

        BitcoinMessageStreamReader reader =
                reader();

        Optional<BitcoinMessage> result =
                reader.read(
                        new ByteArrayInputStream(
                                new byte[0]
                        )
                );

        assertFalse(
                result.isPresent()
        );
    }

    @Test
    void shouldRejectTruncatedHeader() {

        BitcoinMessageStreamReader reader =
                reader();

        byte[] partialHeader =
                new byte[]{
                        (byte) 0xf9,
                        (byte) 0xbe,
                        (byte) 0xb4
                };

        assertThrows(
                EOFException.class,
                () -> reader.read(
                        new ByteArrayInputStream(
                                partialHeader
                        )
                )
        );
    }

    @Test
    void shouldRejectTruncatedPayload() {

        BitcoinMessageEncoder encoder =
                encoder();

        BitcoinMessageStreamReader reader =
                reader();

        byte[] packet =
                encoder.encode(
                        new BitcoinMessage(
                                "ping",
                                new byte[]{
                                        1, 2, 3, 4,
                                        5, 6, 7, 8
                                }
                        )
                );

        byte[] truncated =
                java.util.Arrays.copyOf(
                        packet,
                        packet.length - 3
                );

        assertThrows(
                EOFException.class,
                () -> reader.read(
                        new ByteArrayInputStream(
                                truncated
                        )
                )
        );
    }

    @Test
    void shouldReadMultipleMessagesFromSameStream()
            throws Exception {

        BitcoinMessageEncoder encoder =
                encoder();

        byte[] first =
                encoder.encode(
                        new BitcoinMessage(
                                "verack",
                                new byte[0]
                        )
                );

        byte[] second =
                encoder.encode(
                        new BitcoinMessage(
                                "ping",
                                new byte[]{
                                        10, 20, 30
                                }
                        )
                );

        byte[] stream =
                new byte[
                        first.length
                                + second.length
                        ];

        System.arraycopy(
                first,
                0,
                stream,
                0,
                first.length
        );

        System.arraycopy(
                second,
                0,
                stream,
                first.length,
                second.length
        );

        ByteArrayInputStream input =
                new ByteArrayInputStream(stream);

        BitcoinMessageStreamReader reader =
                reader();

        BitcoinMessage firstResult =
                reader.read(input)
                        .orElseThrow();

        BitcoinMessage secondResult =
                reader.read(input)
                        .orElseThrow();

        Optional<BitcoinMessage> end =
                reader.read(input);

        assertEquals(
                "verack",
                firstResult.command()
        );

        assertEquals(
                "ping",
                secondResult.command()
        );

        assertArrayEquals(
                new byte[]{
                        10, 20, 30
                },
                secondResult.payload()
        );

        assertTrue(
                end.isEmpty()
        );
    }

    @Test
    void shouldHandleOneByteReads()
            throws Exception {

        BitcoinMessageEncoder encoder =
                encoder();

        byte[] packet =
                encoder.encode(
                        new BitcoinMessage(
                                "ping",
                                new byte[]{
                                        1, 2, 3, 4,
                                        5, 6, 7, 8
                                }
                        )
                );

        InputStream input =
                new OneByteInputStream(packet);

        BitcoinMessageStreamReader reader =
                reader();

        BitcoinMessage result =
                reader.read(input)
                        .orElseThrow();

        assertEquals(
                "ping",
                result.command()
        );

        assertArrayEquals(
                new byte[]{
                        1, 2, 3, 4,
                        5, 6, 7, 8
                },
                result.payload()
        );
    }

    @Test
    void shouldRejectOversizedPayloadBeforeAllocation() {

        BitcoinMessageStreamReader reader =
                reader();

        byte[] header =
                validHeaderWithPayloadLength(
                        (long)
                                BitcoinMessageDecoder
                                        .MAX_PAYLOAD_LENGTH
                                + 1
                );

        assertThrows(
                IOException.class,
                () -> reader.read(
                        new ByteArrayInputStream(
                                header
                        )
                )
        );
    }

    @Test
    void shouldRejectInvalidChecksum()
            throws Exception {

        BitcoinMessageEncoder encoder =
                encoder();

        byte[] packet =
                encoder.encode(
                        new BitcoinMessage(
                                "ping",
                                new byte[]{
                                        1, 2, 3
                                }
                        )
                );

        packet[packet.length - 1] ^= 1;

        BitcoinMessageStreamReader reader =
                reader();

        IOException exception =
                assertThrows(
                        IOException.class,
                        () -> reader.read(
                                new ByteArrayInputStream(
                                        packet
                                )
                        )
                );

        assertTrue(
                exception.getCause()
                        instanceof IllegalArgumentException
        );
    }

    private static BitcoinMessageEncoder encoder() {
        return new BitcoinMessageEncoder(
                NetworkParametersRegistry.mainnet()
        );
    }

    private static BitcoinMessageStreamReader reader() {
        return new BitcoinMessageStreamReader(
                new BitcoinMessageDecoder(
                        NetworkParametersRegistry.mainnet()
                )
        );
    }

    private static byte[] validHeaderWithPayloadLength(
            long payloadLength
    ) {
        byte[] header =
                new byte[
                        BitcoinMessageDecoder.HEADER_LENGTH
                        ];

        header[0] = (byte) 0xf9;
        header[1] = (byte) 0xbe;
        header[2] = (byte) 0xb4;
        header[3] = (byte) 0xd9;

        header[4] = 't';
        header[5] = 'e';
        header[6] = 's';
        header[7] = 't';

        header[16] =
                (byte) payloadLength;

        header[17] =
                (byte) (payloadLength >>> 8);

        header[18] =
                (byte) (payloadLength >>> 16);

        header[19] =
                (byte) (payloadLength >>> 24);

        return header;
    }

    private static final class OneByteInputStream
            extends InputStream {

        private final byte[] data;
        private int position;

        private OneByteInputStream(
                byte[] data
        ) {
            this.data = data.clone();
        }

        @Override
        public int read() {
            if (position >= data.length) {
                return -1;
            }

            return data[position++] & 0xff;
        }

        @Override
        public int read(
                byte[] buffer,
                int offset,
                int length
        ) {
            if (position >= data.length) {
                return -1;
            }

            if (length == 0) {
                return 0;
            }

            buffer[offset] =
                    data[position++];

            return 1;
        }
    }
}