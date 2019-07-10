/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

 /* Started life from the blog http://www.seepingmatter.com/2016/03/30/a-simple-standalone-http-server-with-netty.html */
package com.aws.iot.httpservice;

import com.aws.iot.config.*;
import com.aws.iot.gg2k.*;
import com.aws.iot.util.*;
import static com.aws.iot.util.Utils.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import static io.netty.buffer.Unpooled.copiedBuffer;
import java.nio.charset.*;
import java.util.*;

public class SimpleHttpServer extends GGService {
    private ChannelFuture channel;
    private final EventLoopGroup masterGroup = new NioEventLoopGroup();
    private final EventLoopGroup slaveGroup = new NioEventLoopGroup();
    private final Deque<Log.Entry> v = new ArrayDeque<>();

    public SimpleHttpServer(Topics t) {
        super(t);
    }

    @Override
    public void postInject() {
        log().addWatcher(e -> {
            if (v.size() >= 50)
                v.removeFirst();
            v.addLast(e);
        });
    }

    @Override
    public void startup() {
        log().significant("Starting httpd");
        try {
            final ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(masterGroup, slaveGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(final SocketChannel ch) throws Exception {
                            ch.pipeline().addLast("codec", new HttpServerCodec());
                            ch.pipeline().addLast("aggregator", new HttpObjectAggregator(512 * 1024));
                            ch.pipeline().addLast("request", new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg)
                                        throws Exception {
                                    System.out.println("****** channelRead " + msg);
                                    if (msg instanceof FullHttpRequest) {
                                        final FullHttpRequest request = (FullHttpRequest) msg;
                                        final StringBuilder sb = new StringBuilder();

                                        sb.append("<htm><header><title>Yahoo!</title></header>\n<body>\n<table>");
                                        context.get(GG2K.class).orderedDependencies().forEach(
                                                t -> {
                                                    sb.append("\n<tr><td>")
                                                            .append(t.config.getFullName())
                                                            .append("<td>")
                                                            .append(t.getState());
                                                    t.config.forEach(v -> {
                                                        sb.append("\n<tr><td><td>")
                                                                .append(v.getFullName())
                                                                .append("<td>")
                                                                .append(v instanceof Topic
                                                                        ? String.valueOf(((Topic) v).getOnce())
                                                                        : "{...}");
                                                    });
                                                });
                                        sb.append("\n</table><p><table>");
                                        v.descendingIterator().forEachRemaining(e -> {
                                            sb.append("\n<tr><td>").append(e.time)
                                                    .append("<td>").append(e.level);
                                            if (e.args != null)
                                                for (Object o : e.args) {
                                                    sb.append("<td>");
                                                    sb.append(deepToString(o));
                                                }
                                        });
                                        sb.append("\n</table>\n</html>\n");

                                        ByteBuf bb = copiedBuffer(sb, StandardCharsets.UTF_8);
                                        FullHttpResponse response = new DefaultFullHttpResponse(
                                                HttpVersion.HTTP_1_1,
                                                HttpResponseStatus.OK,
                                                bb);

                                        if (HttpUtil.isKeepAlive(request))
                                            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html");
                                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bb.writerIndex());

                                        ctx.writeAndFlush(response);
                                    } else
                                        super.channelRead(ctx, msg);
                                }

                                @Override
                                public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
                                    ctx.flush();
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
                                        throws Exception {
                                    ctx.writeAndFlush(new DefaultFullHttpResponse(
                                            HttpVersion.HTTP_1_1,
                                            HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                            copiedBuffer(cause.getMessage().getBytes())
                                    ));
                                }
                            });
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            channel = bootstrap.bind(8080).sync();
            //channels.add(bootstrap.bind(8080).sync());
        } catch (final InterruptedException e) {
        }
        log().significant("Finished starting httpd");
    }

    @Override
    public void shutdown() {
        log().significant("Shutting down httpd");
        slaveGroup.shutdownGracefully();
        masterGroup.shutdownGracefully();

        try {
            channel.channel().closeFuture().sync();
        } catch (InterruptedException e) {
        }
    }

}
