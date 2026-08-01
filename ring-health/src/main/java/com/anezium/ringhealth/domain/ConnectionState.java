package com.anezium.ringhealth.domain;

public enum ConnectionState {
    UNPAIRED,
    SCANNING,
    BONDING,
    CONNECTING_GATT,
    DISCOVERING_SERVICES,
    ENABLING_NOTIFICATIONS,
    INITIALIZING,
    READY,
    WAITING_FOR_WAKE,
    DISCONNECTED_RETRYING,
    FATAL_ERROR
}
