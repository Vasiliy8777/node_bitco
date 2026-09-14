package ru.bitcoin.node.script;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

public final class ScriptMachine {

    private final Deque<byte[]> stack =
            new ArrayDeque<>();

    private final Deque<byte[]> altStack =
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

    public byte[] peekFromTop(
            int depth
    ) {
        if (depth < 0) {
            throw new IllegalArgumentException(
                    "depth must not be negative"
            );
        }

        requireStackSize(
                depth + 1
        );

        int indexFromBottom =
                stack.size()
                        - 1
                        - depth;

        int index = 0;

        for (byte[] value : stack) {

            if (index
                    == indexFromBottom) {

                return Arrays.copyOf(
                        value,
                        value.length
                );
            }

            index++;
        }

        throw new IllegalStateException(
                "Stack index calculation failed"
        );
    }

    public byte[] removeFromTop(
            int depth
    ) {
        if (depth < 0) {
            throw new IllegalArgumentException(
                    "depth must not be negative"
            );
        }

        requireStackSize(
                depth + 1
        );

        /*
         * ArrayDeque не предоставляет indexed remove,
         * поэтому временно снимаем элементы над нужным.
         */
        Deque<byte[]> temporary =
                new ArrayDeque<>();

        for (int i = 0;
             i < depth;
             i++) {

            temporary.addLast(
                    stack.removeLast()
            );
        }

        byte[] value =
                stack.removeLast();

        while (!temporary.isEmpty()) {

            stack.addLast(
                    temporary.removeLast()
            );
        }

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

    public void pushAlt(
            byte[] value
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "value must not be null"
            );
        }

        altStack.addLast(
                Arrays.copyOf(
                        value,
                        value.length
                )
        );
    }

    public byte[] popAlt() {

        requireAltStackSize(1);

        byte[] value =
                altStack.removeLast();

        return Arrays.copyOf(
                value,
                value.length
        );
    }

    public byte[] peekAlt() {

        requireAltStackSize(1);

        byte[] value =
                altStack.peekLast();

        return Arrays.copyOf(
                value,
                value.length
        );
    }

    public int size() {
        return stack.size();
    }

    public int altSize() {
        return altStack.size();
    }

    public int totalStackSize() {
        return stack.size()
                + altStack.size();
    }

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public boolean isAltEmpty() {
        return altStack.isEmpty();
    }

    public void clearAltStack() {
        altStack.clear();
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

    public void requireAltStackSize(
            int required
    ) {
        if (required < 0) {
            throw new IllegalArgumentException(
                    "required must not be negative"
            );
        }

        if (altStack.size() < required) {
            throw new ScriptExecutionException(
                    "Altstack underflow: required "
                            + required
                            + " element(s), but found "
                            + altStack.size()
            );
        }
    }

    public void validateStackSize() {

        int total =
                totalStackSize();

        if (total
                > ScriptLimits.MAX_STACK_SIZE) {

            throw new ScriptExecutionException(
                    "Combined stack and altstack size exceeds consensus limit: "
                            + total
                            + " > "
                            + ScriptLimits.MAX_STACK_SIZE
            );
        }
    }

    public ScriptMachine copy() {

        ScriptMachine copy =
                new ScriptMachine();

        /*
         * ArrayDeque iteration идёт снизу вверх
         * в нашем представлении stack.
         */
        for (byte[] value : stack) {
            copy.push(
                    value
            );
        }

        for (byte[] value : altStack) {
            copy.pushAlt(
                    value
            );
        }

        return copy;
    }
}