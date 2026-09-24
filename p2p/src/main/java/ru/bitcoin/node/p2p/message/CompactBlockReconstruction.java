package ru.bitcoin.node.p2p.message;

import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.util.*;

/**
 * Stateless BIP152 reconstruction helper. Ambiguous short IDs are deliberately left missing.
 */
public final class CompactBlockReconstruction {
    private CompactBlockReconstruction() {
    }

    public record Partial(
            CompactBlockMessage compactBlock,
            List<Transaction> transactions,
            List<Integer> missingIndexes
    ) {
        public Partial {
            Objects.requireNonNull(compactBlock, "compactBlock");
            Objects.requireNonNull(transactions, "transactions");
            Objects.requireNonNull(missingIndexes, "missingIndexes");

            // A partial compact-block reconstruction intentionally contains
            // null slots for transactions that still have to be requested
            // through GETBLOCKTXN. List.copyOf() cannot be used because it
            // rejects null elements.
            transactions = Collections.unmodifiableList(
                    new ArrayList<>(transactions)
            );

            missingIndexes = List.copyOf(missingIndexes);
        }

        public boolean complete() {
            return missingIndexes.isEmpty();
        }

        public Block toBlock() {
            if (!complete()
                    || transactions.stream().anyMatch(Objects::isNull)) {
                throw new IllegalStateException(
                        "compact block reconstruction is incomplete"
                );
            }

            return new Block(
                    compactBlock.header(),
                    transactions
            );
        }

        public Block fill(List<Transaction> missingTransactions) {
            Objects.requireNonNull(
                    missingTransactions,
                    "missingTransactions"
            );

            if (missingTransactions.size() != missingIndexes.size()) {
                throw new IllegalArgumentException(
                        "blocktxn transaction count does not match request"
                );
            }

            List<Transaction> filled =
                    new ArrayList<>(transactions);

            for (int i = 0; i < missingIndexes.size(); i++) {
                filled.set(
                        missingIndexes.get(i),
                        Objects.requireNonNull(
                                missingTransactions.get(i),
                                "missing transaction"
                        )
                );
            }

            if (filled.stream().anyMatch(Objects::isNull)) {
                throw new IllegalStateException(
                        "compact block still has missing transactions"
                );
            }

            return new Block(
                    compactBlock.header(),
                    filled
            );
        }
    }

    public static Partial initialize(CompactBlockMessage compact, Collection<Transaction> candidates, long version) {
        Objects.requireNonNull(compact, "compact");
        Objects.requireNonNull(candidates, "candidates");
        if (version != 1 && version != 2) throw new IllegalArgumentException("version");

        int count = compact.transactionCount();
        if (count <= 0) throw new IllegalArgumentException("compact block has no transactions");
        List<Transaction> slots = new ArrayList<>(Collections.nCopies(count, null));
        for (PrefilledTransaction prefilled : compact.prefilledTransactions()) {
            if (prefilled.index() >= count)
                throw new IllegalArgumentException("prefilled transaction index out of bounds");
            if (slots.set(prefilled.index(), prefilled.transaction()) != null)
                throw new IllegalArgumentException("duplicate prefilled transaction index");
        }

        Map<Long, Transaction> unique = new HashMap<>();
        Set<Long> collisions = new HashSet<>();
        Set<Long> announcedIds = new HashSet<>();
        for (long id : compact.shortIds()) {
            if (!announcedIds.add(id)) collisions.add(id);
        }
        for (Transaction tx : candidates) {
            long id = CompactBlockFactory.shortId(compact.header(), compact.nonce(), tx, version);
            if (unique.putIfAbsent(id, tx) != null) collisions.add(id);
        }
        collisions.forEach(unique::remove);

        int shortIndex = 0;
        List<Integer> missing = new ArrayList<>();
        for (int position = 0; position < count; position++) {
            if (slots.get(position) != null) continue;
            if (shortIndex >= compact.shortIds().size())
                throw new IllegalArgumentException("compact block short-id count does not match transaction layout");
            Transaction candidate = unique.get(compact.shortIds().get(shortIndex++));
            if (candidate == null) missing.add(position);
            else slots.set(position, candidate);
        }
        if (shortIndex != compact.shortIds().size())
            throw new IllegalArgumentException("compact block has unused short IDs");
        return new Partial(compact, slots, missing);
    }
}
