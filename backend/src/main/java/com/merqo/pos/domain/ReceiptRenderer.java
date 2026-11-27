package com.merqo.pos.domain;

/**
 * Strategy and Adapter contract for rendering a {@link ReceiptData} snapshot into a target output
 * format.
 *
 * <p>Responsibility: decouple receipt data assembly from output representation (API JSON map vs.
 * fixed-width thermal printer text, and future hardware drivers).
 *
 * <p>OOP concept: <b>Strategy &amp; Adapter Pattern (Interface)</b> — callers program against this
 * abstraction and swap renderers without changing sale or reprint logic.
 *
 * @param <T> output representation produced by the renderer
 */
public interface ReceiptRenderer<T> {

    /**
     * Renders the receipt payload into the target representation.
     *
     * @param receipt immutable receipt data
     * @return rendered output
     */
    T render(ReceiptData receipt);
}
