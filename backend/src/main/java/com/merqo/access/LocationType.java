package com.merqo.access;

/**
 * The kind of a location.
 *
 * <p>Responsibility: name the two kinds of place Merqo stocks and sells from.
 *
 * <p>OOP concept: a closed enum means the database check constraint and the Java enum can be kept
 * in step, and a switch over the type is always exhaustive.
 */
public enum LocationType {

    /** A retail branch with its own till and its own stock. */
    BRANCH,

    /** The warehouse that holds the shared stock and fulfils online orders. */
    WAREHOUSE
}
