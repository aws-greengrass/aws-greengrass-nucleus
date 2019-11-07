package com.aws.iot.ipc.handler;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.aws.iot.evergreen.ipc.common.FrameReader.Message;


/***
 * MessageHandler enables a service to send and receive messages from an external process
 *
 * 1. Service can opt in to receive messages of a particular op code by registering a handler
 *    The handler will receive messages of the registered op code and respond with another message
 *    A service can register handlers for one or more opcodes, but there can only be one handler for one opcode
 *
 * 2. Service can send message to external process using the process's token aka client id
 */

public interface MessageHandler {

    boolean registerOpCodeHandler(Integer opCode, Function<Message, Message> handler);

    String send(Message msg, String clientId);

    Message getResponse(String messageId, long timeout, TimeUnit timeUnit) throws InterruptedException;

}
