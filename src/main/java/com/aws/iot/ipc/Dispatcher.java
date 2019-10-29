package com.aws.iot.ipc;

import com.aws.iot.gg2k.client.common.Contants;
import com.aws.iot.ipc.IPCServer.ClientConnection;

import javax.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Function;

import static com.aws.iot.gg2k.client.common.FrameReader.*;
import static com.aws.iot.gg2k.client.common.FrameReader.RequestType.*;


public class Dispatcher {

    public static final int DEFAULT_EXECUTOR_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2;
    public static final String THREAD_POOL_NAME = "Dispatcher";
    private static final int DEFAULT_QUEUE_SIZE = 1;
    private final ThreadPoolExecutor threadPoolExecutor;

    private final ConcurrentHashMap<Integer, Function<Message, Message>> ops = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BlockingQueue<Message>> responseMap = new ConcurrentHashMap<>();

    @Inject
    private ConnectionsManager connManager;

    public Dispatcher() {
        this.threadPoolExecutor = new ThreadPoolExecutor(DEFAULT_EXECUTOR_POOL_SIZE, DEFAULT_EXECUTOR_POOL_SIZE, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), new ThreadFactory() {
            ThreadFactory f = Executors.defaultThreadFactory();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread t = f.newThread(runnable);
                t.setName(THREAD_POOL_NAME + "-" + t.getName());
                return t;
            }
        });
       // addHandler(Contants.errOpCode,null);
    }

    public boolean addHandler(Integer opCode, Function<Message, Message> handler) {
        Function<Message, Message> existingFuntion = ops.putIfAbsent(opCode, handler);
        return existingFuntion == null;
    }

    public void dispatch(MessageFrame req, String clientId) {

        Message m = req.message;
        BlockingQueue<Message> queue;
        if ((queue = responseMap.get(req.uuid.toString())) != null) {
            queue.offer(m);
        } else {
            try {
                threadPoolExecutor.submit(() -> {
                    try {
                        Function<Message, Message> handler = ops.getOrDefault(m.getOpCode(), (msg) -> {
                            throw new UnsupportedOperationException("Invalid opcode " + m.getOpCode());
                        });
                        Message response = handler.apply(m);
                        if (REQUEST_RESPONSE.equals(m.getType())) {
                            connManager.getConnection(clientId).send(new MessageFrame(req.uuid, response));
                        }
                    } catch (Exception e) {
                        handleDispatchError(clientId, req, e.getMessage());
                    }
                });
            } catch (Exception e) {
              e.printStackTrace();
            }
        }
    }

    protected void handleDispatchError(String clientId, MessageFrame req, String errorMsg) {
        // log error
        try {
            Message m = req.message;
            ClientConnection conn = connManager.getConnection(clientId);
            if (conn != null && req.message.getType().equals(REQUEST_RESPONSE)) {
                conn.send(new MessageFrame(req.uuid, new Message(Contants.errOpCode, m.getType(), errorMsg.getBytes(StandardCharsets.UTF_8))));
            }
        } catch (Exception e) {
            //log
        }
    }

    protected void shutdown() {
        threadPoolExecutor.shutdown();
        try {
            if (threadPoolExecutor.awaitTermination(1, TimeUnit.MINUTES)) {
                threadPoolExecutor.shutdownNow();
            }
            connManager.shutdown();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public String send(Message msg, String clientId) {
        MessageFrame f = new MessageFrame(msg);
        connManager.getConnection(clientId).send(f);
        responseMap.put(f.uuid.toString(), new ArrayBlockingQueue<>(DEFAULT_QUEUE_SIZE));
        return f.uuid.toString();
    }

    public Message getResponse(String messageId, long timeout, TimeUnit timeUnit) {
        BlockingQueue<Message> queue = responseMap.get(messageId);
        if (queue == null) {
            // no message id registered throw exception
        }
        Message response = null;
        try {
            response = queue.poll(timeout, timeUnit);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        responseMap.remove(messageId);
        return response;
    }
}
