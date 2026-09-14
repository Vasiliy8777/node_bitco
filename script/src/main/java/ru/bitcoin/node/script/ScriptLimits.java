package ru.bitcoin.node.script;

/**
 * Consensus limits Bitcoin Script.
 *
 * Это consensus-константы, а не policy limits.
 */
public final class ScriptLimits {

    /*
     * Максимальный размер выполняемого script.
     */
    public static final int MAX_SCRIPT_SIZE =
            10_000;

    /*
     * Максимальный размер элемента,
     * помещаемого на stack push-операцией.
     */
    public static final int MAX_SCRIPT_ELEMENT_SIZE =
            520;

    /*
     * Максимальное суммарное количество элементов
     * stack + altstack.
     *
     * Altstack пока не реализован, поэтому сейчас
     * фактически проверяется основной stack.
     */
    public static final int MAX_STACK_SIZE =
            1_000;

    /*
     * Максимальное количество non-push opcodes
     * на один script.
     */
    public static final int MAX_OPS_PER_SCRIPT =
            201;

    /*
     * Consensus maximum public keys
     * для OP_CHECKMULTISIG.
     */
    public static final int MAX_PUBKEYS_PER_MULTISIG =
            20;

    private ScriptLimits() {
    }
}