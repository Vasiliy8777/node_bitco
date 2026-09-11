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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof Witness witness)) {
            return false;
        }

        if (items.size()
                != witness.items.size()) {
            return false;
        }

        for (int i = 0;
             i < items.size();
             i++) {

            if (!java.util.Arrays.equals(
                    items.get(i),
                    witness.items.get(i)
            )) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;

        for (byte[] item : items) {
            result =
                    31 * result
                            + java.util.Arrays.hashCode(
                            item
                    );
        }

        return result;
    }
}
