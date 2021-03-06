/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.qpid.jms.transports.netty;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.apache.qpid.jms.transports.TransportOptions;
import org.apache.qpid.jms.transports.TransportSslOptions;
import org.apache.qpid.jms.transports.TransportSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

/**
 * Base Server implementation used to create Netty based server implementations for
 * unit testing aspects of the client code.
 */
public abstract class NettyServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(NettyServer.class);

    static final int PORT = Integer.parseInt(System.getProperty("port", "8007"));
    static final String WEBSOCKET_PATH = "/";

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private final TransportOptions options;
    private int serverPort;
    private final boolean needClientAuth;
    private final boolean webSocketServer;
    private String webSocketPath = WEBSOCKET_PATH;
    private volatile SslHandler sslHandler;

    private final AtomicBoolean started = new AtomicBoolean();

    public NettyServer(TransportOptions options) {
        this(options, false);
    }

    public NettyServer(TransportOptions options, boolean needClientAuth) {
        this(options, needClientAuth, false);
    }

    public NettyServer(TransportOptions options, boolean needClientAuth, boolean webSocketServer) {
        this.options = options;
        this.needClientAuth = needClientAuth;
        this.webSocketServer = webSocketServer;
    }

    public boolean isSecureServer() {
        return options instanceof TransportSslOptions;
    }

    public boolean isWebSocketServer() {
        return webSocketServer;
    }

    public String getWebSocketPath() {
        return webSocketPath;
    }

    public void setWebSocketPath(String webSocketPath) {
        this.webSocketPath = webSocketPath;
    }

    protected URI getConnectionURI() throws Exception {
        if (!started.get()) {
            throw new IllegalStateException("Cannot get URI of non-started server");
        }

        int port = getServerPort();

        String scheme;
        String path;

        if (isWebSocketServer()) {
            if (isSecureServer()) {
                scheme = "amqpwss";
            } else {
                scheme = "amqpws";
            }
        } else {
            if (isSecureServer()) {
                scheme = "amqps";
            } else {
                scheme = "amqp";
            }
        }

        if (isWebSocketServer()) {
            path = getWebSocketPath();
        } else {
            path = null;
        }

        return new URI(scheme, null, "localhost", port, path, null, null);
    }

    public void start() throws Exception {

        if (started.compareAndSet(false, true)) {

            // Configure the server.
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();

            ServerBootstrap server = new ServerBootstrap();
            server.group(bossGroup, workerGroup);
            server.channel(NioServerSocketChannel.class);
            server.option(ChannelOption.SO_BACKLOG, 100);
            server.handler(new LoggingHandler(LogLevel.INFO));
            server.childHandler(new ChannelInitializer<Channel>() {

                @Override
                public void initChannel(Channel ch) throws Exception {
                    if (options instanceof TransportSslOptions) {
                        TransportSslOptions sslOptions = (TransportSslOptions) options;
                        SSLContext context = TransportSupport.createSslContext(sslOptions);
                        SSLEngine engine = TransportSupport.createSslEngine(context, sslOptions);
                        engine.setUseClientMode(false);
                        engine.setNeedClientAuth(needClientAuth);
                        sslHandler = new SslHandler(engine);
                        ch.pipeline().addLast(sslHandler);
                    }

                    if (webSocketServer) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(65536));
                        ch.pipeline().addLast(new WebSocketServerProtocolHandler(getWebSocketPath(), "amqp", true));
                    }

                    ch.pipeline().addLast(new NettyServerOutboundHandler());
                    ch.pipeline().addLast(new NettyServerInboundHandler());
                    ch.pipeline().addLast(getServerHandler());
                }
            });

            // Start the server.
            serverChannel = server.bind(getServerPort()).sync().channel();
        }
    }

    protected abstract ChannelHandler getServerHandler();

    public void stop() throws InterruptedException {
        if (started.compareAndSet(true, false)) {
            try {
                LOG.info("Syncing channel close");
                serverChannel.close().sync();
            } catch (InterruptedException e) {
            }

            // Shut down all event loops to terminate all threads.
            LOG.info("Shutting down boss group");
            bossGroup.shutdownGracefully(10, 100, TimeUnit.MILLISECONDS);
            LOG.info("Shutting down worker group");
            workerGroup.shutdownGracefully(10, 100, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void close() throws InterruptedException {
        stop();
    }

    public int getServerPort() {
        if (serverPort == 0) {
            ServerSocket ss = null;
            try {
                ss = ServerSocketFactory.getDefault().createServerSocket(0);
                serverPort = ss.getLocalPort();
            } catch (IOException e) { // revert back to default
                serverPort = PORT;
            } finally {
                try {
                    if (ss != null ) {
                        ss.close();
                    }
                } catch (IOException e) { // ignore
                }
            }
        }
        return serverPort;
    }

    private class NettyServerOutboundHandler extends ChannelOutboundHandlerAdapter  {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            LOG.trace("NettyServerHandler: Channel write: {}", msg);
            if (isWebSocketServer() && msg instanceof ByteBuf) {
                BinaryWebSocketFrame frame = new BinaryWebSocketFrame((ByteBuf) msg);
                ctx.write(frame, promise);
            } else {
                ctx.write(msg, promise);
            }
        }
    }

    private class NettyServerInboundHandler extends ChannelInboundHandlerAdapter  {

        @Override
        public void channelActive(final ChannelHandlerContext ctx) {
            LOG.info("NettyServerHandler -> New active channel: {}", ctx.channel());
            SslHandler handler = ctx.pipeline().get(SslHandler.class);
            if (handler != null) {
                handler.handshakeFuture().addListener(new GenericFutureListener<Future<Channel>>() {
                    @Override
                    public void operationComplete(Future<Channel> future) throws Exception {
                        LOG.info("Server -> SSL handshake completed. Succeeded: {}", future.isSuccess());
                        if (!future.isSuccess()) {
                            sslHandler.close();
                            ctx.close();
                        }
                    }
                });
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            LOG.info("NettyServerHandler: channel has gone inactive: {}", ctx.channel());
            ctx.close();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            LOG.trace("NettyServerHandler: Channel read: {}", msg);
            if (msg instanceof WebSocketFrame) {
                WebSocketFrame frame = (WebSocketFrame) msg;
                ctx.fireChannelRead(frame.content());
            } else if (msg instanceof FullHttpRequest) {
                // Reject anything not on the WebSocket path
                FullHttpRequest request = (FullHttpRequest) msg;
                sendHttpResponse(ctx, request, new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST));
            } else {
                // Forward anything else along to the next handler.
                ctx.fireChannelRead(msg);
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.info("NettyServerHandler: NettyServerHandlerException caught on channel: {}", ctx.channel());
            // Close the connection when an exception is raised.
            cause.printStackTrace();
            ctx.close();
        }
    }

    private static void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest request, FullHttpResponse response) {
        // Generate an error page if response getStatus code is not OK (200).
        if (response.getStatus().code() != 200) {
            ByteBuf buf = Unpooled.copiedBuffer(response.getStatus().toString(), StandardCharsets.UTF_8);
            response.content().writeBytes(buf);
            buf.release();
            HttpHeaders.setContentLength(response, response.content().readableBytes());
        }

        // Send the response and close the connection if necessary.
        ChannelFuture f = ctx.channel().writeAndFlush(response);
        if (!HttpHeaders.isKeepAlive(request) || response.getStatus().code() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    protected SslHandler getSslHandler() {
        return sslHandler;
    }
}
