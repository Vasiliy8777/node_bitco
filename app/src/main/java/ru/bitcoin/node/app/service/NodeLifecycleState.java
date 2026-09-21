package ru.bitcoin.node.app.service;

public enum NodeLifecycleState {

    NEW,
    STARTING,
    SYNCHRONIZING_HEADERS,
    SYNCHRONIZING_BLOCKS,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}