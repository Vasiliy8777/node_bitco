package ru.bitcoin.node.p2p.message;

import java.util.List;

public final class GetDataMessage {

    public static final int MAX_INVENTORY_SIZE = 50_000;

    private final List<InventoryVector> inventory;

    public GetDataMessage(
            List<InventoryVector> inventory
    ) {
        if (inventory == null) {
            throw new IllegalArgumentException(
                    "inventory must not be null"
            );
        }

        if (inventory.size() > MAX_INVENTORY_SIZE) {
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
                List.copyOf(inventory);
    }

    public List<InventoryVector> inventory() {
        return inventory;
    }

    public int size() {
        return inventory.size();
    }
}