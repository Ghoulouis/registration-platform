package com.registration.common.protocol;

/** The message kinds exchanged between Client and Server (ADR-0003, ADR-0004). */
public sealed interface ProtocolMessage
        permits RegisterRequest, RegisterResponse, RenewRequest, RenewResponse, CancelRequest, CancelResponse {

    MessageType type();
}
