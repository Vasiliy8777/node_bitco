package ru.bitcoin.node.consensus.deployment;

public record Deployment(
        String name,
        int bit,
        long startTime,
        long timeout,
        int threshold,
        int window
) {}
