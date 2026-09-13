package ru.bitcoin.node.script;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

public final class ScriptMachine {

    private final Deque<byte[]> stack =
            new ArrayDeque<>();

    public void push(
            byte[] value
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "value must not be null"
            );
        }

        stack.addLast(
                Arrays.copyOf(
                        value,
                        value.length
                )
        );
    }

    public byte[] pop() {

        requireStackSize(1);

        byte[] value =
                stack.removeLast();

        return Arrays.copyOf(
                value,
                value.length
        );
    }

    public byte[] peek() {

        requireStackSize(1);

        byte[] value =
                stack.peekLast();

        return Arrays.copyOf(
                value,
                value.length
        );
    }

    public void duplicateTop() {

        push(
                peek()
        );
    }

    public int size() {
        return stack.size();
    }

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public void requireStackSize(
            int required
    ) {
        if (required < 0) {
            throw new IllegalArgumentException(
                    "required must not be negative"
            );
        }

        if (stack.size() < required) {
            throw new ScriptExecutionException(
                    "Stack underflow: required "
                            + required
                            + " element(s), but found "
                            + stack.size()
            );
        }
    }
    public ScriptMachine copy() {

        ScriptMachine copy =
                new ScriptMachine();

        /*
         * ArrayDeque iteration идёт от первого
         * элемента к последнему.
         *
         * Мы используем addLast/push через addLast,
         * поэтому порядок stack сохраняется.
         */
        for (byte[] value : stack) {

            copy.push(
                    value
            );
        }

        return copy;
    }
}