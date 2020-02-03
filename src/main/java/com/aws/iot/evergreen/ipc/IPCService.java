package com.aws.iot.evergreen.ipc;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.ipc.codec.MessageFrameDecoder;
import com.aws.iot.evergreen.ipc.codec.MessageFrameEncoder;
import com.aws.iot.evergreen.ipc.handler.MessageRouter;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Log;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import javax.inject.Inject;

import static com.aws.iot.evergreen.util.Log.Level;


/**
 * Entry point to the kernel service IPC mechanism. IPC service manages the lifecycle of all IPC components
 * <p>
 * IPCService relies on the kernel to synchronize between startup() and run() calls.
 * <p>
 * How messages flow:
 * <p>
 * New connection:
 * Server listens for new connections, new connections are forwarded to MessageRouter using the Netty pipeline.
 * MessageRouter authorizes connection and will then allow further queries to be routed.
 * <p>
 * Outgoing messages:
 * The client must first send a request to setup a "listener" on the server. As part of handling that request,
 * the service will receive a pointer to the channel that they will then be able to use to push messages
 * to the client at any time in the future.
 */

@ImplementsService(name = "IPCService", autostart = true)
public class IPCService extends EvergreenService {
    private static final int MAX_SO_BACKLOG = 128;

    public static final String KERNEL_URI_ENV_VARIABLE_NAME = "AWS_GG_KERNEL_URI";
    private static final String LOCAL_IP = "127.0.0.1";

    @Inject
    Log log;

    private final EventLoopGroup bossGroup = new NioEventLoopGroup();
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private int port;

    @Inject
    private MessageRouter messageHandler;

    public IPCService(Topics c) {
        super(c);
    }

    /**
     * server.startup() binds the server socket to localhost:port. That information is
     * pushed to the IPCService config store
     */
    @Override
    public void startup() {
        log.log(Level.Note, "Startup called for IPC service");
        try {
            port = listen();

            String serverUri = "tcp://" + LOCAL_IP + ":" + port;
            log.log(Log.Level.Note, "IPC service URI: ", serverUri);
            // adding KERNEL_URI under setenv of the root topic. All subsequent processes will have KERNEL_URI
            // set via environment variables
            config.parent.lookup("setenv", KERNEL_URI_ENV_VARIABLE_NAME).setValue(serverUri);

            super.startup();
        } catch (InterruptedException e) {
            log.warn("Failed IPC server startup");
        }
    }

    private int listen() throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();

        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();

                        p.addLast(new MessageFrameDecoder());
                        p.addLast(new MessageFrameEncoder());
                        p.addLast(messageHandler);
                    }
                })
                .option(ChannelOption.SO_BACKLOG, MAX_SO_BACKLOG)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        // Bind and start to accept incoming connections.
        ChannelFuture f = b.bind(InetAddress.getLoopbackAddress(), 0).sync();
        int port = ((InetSocketAddress) f.channel().localAddress()).getPort();

        log.note("IPC ready to accept connections on port", port);
        return port;
    }

    /**
     * Blocks indefinitely listening for new connection. If the server socket errors while listening, the exception
     * is bubbled up and IPCService will transition to Errored state.
     */
    @Override
    public void run() {
        log.log(Level.Note, "Run called for IPC service");
    }

    /**
     *
     */
    @Override
    public void shutdown() {
        log.log(Level.Note, "Shutdown called for IPC service");
        //TODO: transition to errored state if shutdown failed ?
        workerGroup.shutdownGracefully();
        bossGroup.shutdownGracefully();
    }

    /**
     * IPCService will only transition to errored state if the server socket is not able to bind or accept connections
     */
    @Override
    public void handleError() {

    }
}
