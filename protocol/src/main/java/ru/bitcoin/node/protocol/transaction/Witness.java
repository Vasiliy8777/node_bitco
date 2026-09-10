package ru.bitcoin.node.protocol.transaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Witness {

    public static final Witness EMPTY =
            new Witness(List.of());

    private final List<byte[]> items;

    public Witness(List<byte[]> items) {
        if (items == null) {
            throw new IllegalArgumentException(
                    "items must not be null"
            );
        }

        List<byte[]> copy =
                new ArrayList<>(items.size());

        for (byte[] item : items) {
            if (item == null) {
                throw new IllegalArgumentException(
                        "Witness item must not be null"
                );
            }

            copy.add(item.clone());
        }

        this.items =
                Collections.unmodifiableList(copy);
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public byte[] item(int index) {
        return items.get(index).clone();
    }

    public List<byte[]> items() {
        List<byte[]> copy =
                new ArrayList<>(items.size());

        for (byte[] item : items) {
            copy.add(item.clone());
        }

        return Collections.unmodifiableList(copy);
    }
}
