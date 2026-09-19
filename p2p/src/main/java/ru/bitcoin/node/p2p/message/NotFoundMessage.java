package ru.bitcoin.node.p2p.message;

import java.util.List;

public final class NotFoundMessage {

    private final List<InventoryVector> inventory;

    public NotFoundMessage(
            List<InventoryVector> inventory
    ) {
        if (inventory == null) {
            throw new IllegalArgumentException(
                    "inventory must not be null"
            );
        }

        if (inventory.size()
                > GetDataMessage.MAX_INVENTORY_SIZE) {
            throw new IllegalArgumentException(
                    "Too many inventory entries"
            );
        }

        if (inventory.stream().anyMatch(
                item -> item == null
        )) {
            throw new IllegalArgumentException(
                    "inventory must not contain null"
            );
        }

        this.inventory =
                List.copyOf(
                        inventory
                );
    }

    public List<InventoryVector> inventory() {
        return inventory;
    }

    public int size() {
        return inventory.size();
    }
}