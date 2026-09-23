package ru.bitcoin.node.p2p.address;

import java.time.Instant;
import java.util.Objects;

public record TriedCollision(
        PeerAddress candidate,
        PeerAddress incumbent,
        Instant candidateLastSuccess,
        Instant incumbentLastAttempt
) {

    public TriedCollision {

        Objects.requireNonNull(
                candidate,
                "candidate"
        );

        Objects.requireNonNull(
                incumbent,
                "incumbent"
        );

        Objects.requireNonNull(
                candidateLastSuccess,
                "candidateLastSuccess"
        );
    }
}