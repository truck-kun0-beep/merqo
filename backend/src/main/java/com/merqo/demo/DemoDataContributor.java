package com.merqo.demo;

/**
 * One part of the demo data.
 *
 * <p>Responsibility: seed one kind of demo data. Every later prompt adds its own contributor, so no
 * existing contributor is ever changed.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface</b> - the seeder only knows this idea, so a new contributor needs no change
 *       anywhere else.
 *   <li><b>Open/closed principle</b> - new demo data is a new class.
 *   <li><b>Strategy</b> - each contributor decides what it writes.
 * </ul>
 */
public interface DemoDataContributor {

    /**
     * The order this contributor runs in. Lower numbers run first.
     *
     * @return the order
     */
    int order();

    /**
     * Writes the demo data of this contributor.
     *
     * <p>Every contributor must be idempotent: running it twice gives the same result as running it
     * once. The stable demo keys are what makes that possible.
     */
    void seed();
}
