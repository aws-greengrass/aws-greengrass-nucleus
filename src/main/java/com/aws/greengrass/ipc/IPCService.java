/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.ipc.codec.MessageFrameDecoder;
import com.aws.greengrass.ipc.codec.MessageFrameEncoder;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import java.io.Closeable;
import java.net.InetSocketAddress;
import javax.inject.Inject;

import static com.aws.greengrass.ipc.codec.MessageFrameEncoder.LENGTH_FIELD_LENGTH;
import static com.aws.greengrass.ipc.codec.MessageFrameEncoder.LENGTH_FIELD_OFFSET;
import static com.aws.greengrass.ipc.codec.MessageFrameEncoder.MAX_PAYLOAD_SIZE;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;


/**
 * Entry point to the kernel service IPC mechanism. IPC service manages the lifecycle of all IPC components
 *
 * <p>IPCService relies on the kernel to synchronize between startup() and run() calls.
 *
 * <p>How messages flow:
 *
 * <p>New connection:
 * Server listens for new connections, new connections are forwarded to MessageRouter using the Netty pipeline.
 * MessageRouter authorizes connection and will then allow further queries to be routed.
 *
 * <p>Outgoing messages:
 * The client must first send a request to setup a "listener" on the server. As part of handling that request,
 * the service will receive a pointer to the channel that they will then be able to use to push messages
 * to the client at any time in the future.
 */
public class IPCService implements Closeable, Startable {
    private static final Logger logger = LogManager.getLogger(IPCService.class);
    public static final String KERNEL_URI_ENV_VARIABLE_NAME = "AWS_GG_KERNEL_URI";
    private static final int MAX_SO_BACKLOG = 128;
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    private static final String LOCAL_IP = "127.0.0.1";
    private final EventLoopGroup bossGroup = new NioEventLoopGroup();
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();

    private int port;

    @Inject
    private IPCChannelHandler ipcChannelHandler;
    @Inject
    private Configuration config;

    /**
     * server.startup() binds the server socket to localhost:port. That information is
     * pushed to the IPCService config store
     */
    @Override
    public void startup() {
        logger.atInfo().setEventType("ipc-starting").log();
        try {
            port = listen();

            String serverUri = "tcp://" + LOCAL_IP + ":" + port;
            // adding KERNEL_URI under setenv of the root topic. All subsequent processes will have KERNEL_URI
            // set via environment variables
            Topic kernelUri = config.getRoot().lookup(SETENV_CONFIG_NAMESPACE, KERNEL_URI_ENV_VARIABLE_NAME);
            kernelUri.withValue(serverUri);

            logger.atInfo().setEventType("ipc-started").addKeyValue("uri", serverUri).log();
        } catch (InterruptedException e) {
            logger.atError().setCause(e).setEventType("ipc-start-error").log();
            Thread.currentThread().interrupt();
        }
    }

    private int listen() throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();

        b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();

                        p.addLast(new LengthFieldBasedFrameDecoder(MAX_PAYLOAD_SIZE, LENGTH_FIELD_OFFSET,
                                LENGTH_FIELD_LENGTH));
                        p.addLast(new MessageFrameDecoder());
                        p.addLast(new MessageFrameEncoder());
                        p.addLast(ipcChannelHandler);
                    }
                }).option(ChannelOption.SO_BACKLOG, MAX_SO_BACKLOG).childOption(ChannelOption.SO_KEEPALIVE, true);

        // Bind and start to accept incoming connections.
        ChannelFuture f = b.bind(new InetSocketAddress("localhost", 0)).sync();
        int port = ((InetSocketAddress) f.channel().localAddress()).getPort();

        logger.atDebug().addKeyValue("port", port).log("IPC ready to accept connections");
        return port;
    }

    /**
     * Shutdown the IPC server.
     */
    @Override
    public void close() {
        logger.atInfo().setEventType("ipc-shutdown").log();
        workerGroup.shutdownGracefully();
        bossGroup.shutdownGracefully();
        try {
            workerGroup.terminationFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                bossGroup.terminationFuture().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
