package com.aws.iot.ipc.handler;

import com.aws.iot.ipc.exceptions.IPCGenericException;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static com.aws.iot.evergreen.ipc.common.FrameReader.Message;


/***
 * MessageHandler enables a service to write and receive messages from an external process
 *
 * 1. Service can opt in to receive messages of a particular op code by registering a handler
 *    The handler will receive messages of the registered op code and respond with another message
 *    A service can register handlers for one or more opcodes, but there can only be one handler for one opcode
 *
 * 2. Service can write message to external process using the process's token aka client id
 */

public interface MessageHandler {

    void registerOpCodeCallBack(Integer opCode, Function<Message, Message> handler) throws IPCGenericException;

    CompletableFuture<Message> send(Message msg, String clientId) throws IPCGenericException;
}
