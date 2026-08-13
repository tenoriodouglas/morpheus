package com.morpheus.family.data

/**
 * Which role this installation runs as. Chosen once, at first launch, and then
 * persisted. The parent device controls; the child device is enforced.
 */
enum class AppMode {
    UNSET,
    PARENT,
    CHILD,
}
