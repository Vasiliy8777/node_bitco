package ru.bitcoin.node.app.rpc;

import com.sun.net.httpserver.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.group.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded HTTP transport that preserves the raw request target for Core routing. */
final class CoreHttpServer {
    private final EventLoopGroup boss = new NioEventLoopGroup(1, Thread.ofPlatform().daemon().name("rpc-accept-", 0).factory());
    private final EventLoopGroup io = new NioEventLoopGroup(2, Thread.ofPlatform().daemon().name("rpc-http-", 0).factory());
    private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final Channel listener;
    private final AtomicInteger connections = new AtomicInteger();

    CoreHttpServer(InetSocketAddress address, int maximum, Executor executor, HttpHandler handler) throws IOException {
        if (maximum < 1) throw new IllegalArgumentException("rpcmaxconnections must be positive");
        try {
            listener = new ServerBootstrap().group(boss, io).channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInboundHandlerAdapter() {
                        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                            if (message instanceof Channel channel) {
                                if (connections.get() >= maximum) { channel.unsafe().closeForcibly(); return; }
                                connections.incrementAndGet();
                                channels.add(channel);
                                channel.closeFuture().addListener(f -> connections.decrementAndGet());
                            }
                            ctx.fireChannelRead(message);
                        }
                    })
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast("readDeadline", new ReadTimeoutHandler(30));
                            channel.pipeline().addLast(new HttpServerCodec());
                            channel.pipeline().addLast(new HttpObjectAggregator(32 * 1024 * 1024));
                            channel.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                                private final ArrayDeque<Exchange> pending = new ArrayDeque<>();
                                private boolean busy;
                                private long queuedBytes;
                                @Override protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                                    if (!request.decoderResult().isSuccess()) { ctx.close(); return; }
                                    if (pending.size() >= 16 || queuedBytes + request.content().readableBytes() > 32L * 1024 * 1024) { ctx.close(); return; }
                                    if (ctx.pipeline().get("readDeadline") != null) ctx.pipeline().remove("readDeadline");
                                    queuedBytes += request.content().readableBytes();
                                    pending.add(new Exchange(ctx, request, () -> {
                                        busy = false;
                                        runNext(ctx);
                                    }));
                                    runNext(ctx);
                                }
                                private void runNext(ChannelHandlerContext ctx) {
                                    if (busy) return;
                                    if (pending.isEmpty()) {
                                        if (ctx.channel().isActive() && ctx.pipeline().get("readDeadline") == null)
                                            ctx.pipeline().addFirst("readDeadline", new ReadTimeoutHandler(30));
                                        return;
                                    }
                                    busy = true;
                                    Exchange exchange = pending.remove();
                                    queuedBytes -= exchange.requestSize;
                                    try {
                                        executor.execute(() -> {
                                            try (exchange) { handler.handle(exchange); }
                                            catch (IOException | RuntimeException error) { ctx.close(); }
                                        });
                                    } catch (RejectedExecutionException error) { ctx.close(); }
                                }
                                @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
                            });
                        }
                    }).bind(address).sync().channel();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            boss.shutdownGracefully(); io.shutdownGracefully();
            throw new IOException("Interrupted binding RPC", error);
        } catch (RuntimeException error) {
            boss.shutdownGracefully(); io.shutdownGracefully();
            throw new IOException("Cannot bind RPC", error);
        }
    }
    InetSocketAddress getAddress() { return (InetSocketAddress) listener.localAddress(); }
    void stop(int delay) {
        listener.close().awaitUninterruptibly();
        if (delay > 0) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(delay);
            while (!channels.isEmpty() && System.nanoTime() < deadline) {
                try { Thread.sleep(10); } catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
            }
        }
        channels.close().awaitUninterruptibly();
        boss.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
        io.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
    }

    private static final class Exchange extends HttpExchange {
        private final ChannelHandlerContext context;
        private final Headers requestHeaders = new Headers(), responseHeaders = new Headers();
        private final Map<String, Object> attributes = new HashMap<>();
        private final String method, target, protocol;
        private final boolean keepAlive;
        private final int requestSize;
        private final Runnable completed;
        private InputStream input;
        private OutputStream output;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int status = -1;
        private long length = -1;
        private boolean closed;
        Exchange(ChannelHandlerContext context, FullHttpRequest request, Runnable completed) {
            this.context = context; this.completed = completed;
            method = request.method().name(); target = request.uri(); protocol = request.protocolVersion().text();
            keepAlive = HttpUtil.isKeepAlive(request);
            requestSize = request.content().readableBytes();
            request.headers().forEach(entry -> requestHeaders.add(entry.getKey(), entry.getValue()));
            byte[] bytes = new byte[request.content().readableBytes()]; request.content().getBytes(request.content().readerIndex(), bytes);
            input = new ByteArrayInputStream(bytes); output = body;
        }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() {
            try { return URI.create(target); }
            catch (IllegalArgumentException error) { return URI.create("http://invalid" + target); }
        }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public InputStream getRequestBody() { return input; }
        @Override public OutputStream getResponseBody() { return output; }
        @Override public void sendResponseHeaders(int status, long length) { this.status = status; this.length = length; }
        @Override public InetSocketAddress getRemoteAddress() { return (InetSocketAddress) context.channel().remoteAddress(); }
        @Override public InetSocketAddress getLocalAddress() { return (InetSocketAddress) context.channel().localAddress(); }
        @Override public int getResponseCode() { return status; }
        @Override public String getProtocol() { return protocol; }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
        @Override public void setStreams(InputStream input, OutputStream output) { if (input != null) this.input = input; if (output != null) this.output = output; }
        @Override public HttpPrincipal getPrincipal() { return null; }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            if (status < 0) { context.close(); return; }
            byte[] bytes = method.equals("HEAD") || length < 0 || status == 204 ? new byte[0] : body.toByteArray();
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status), Unpooled.wrappedBuffer(bytes));
            responseHeaders.forEach((key, values) -> response.headers().set(key, values));
            if (status != 204) HttpUtil.setContentLength(response, bytes.length);
            HttpUtil.setKeepAlive(response, keepAlive);
            context.executor().execute(() -> {
                var future = context.writeAndFlush(response);
                if (!keepAlive) future.addListener(ChannelFutureListener.CLOSE);
                future.addListener(f -> completed.run());
            });
        }
    }
}
