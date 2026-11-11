package com.example.sudoersemc.scanner;

/**
 * Recipe families SudoersEMC knows how to evaluate.
 *
 * <p>Anything else is reported as {@link #UNSUPPORTED} so the scanner can count it instead of
 * silently dropping it.</p>
 */
public enum RecipeKind {
    SHAPED,
    SHAPELESS,
    FURNACE,
    BLAST_FURNACE,
    SMOKER,
    STONECUTTER,
    /** Ritual recipes contributed by Goety ({@code goety:ritual}). */
    GOETY_RITUAL,
    /** A recipe type this build cannot evaluate yet. */
    UNSUPPORTED
}
