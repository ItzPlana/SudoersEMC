package com.itzplana.sudoersemc.scanner;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Helpers for reading {@link net.minecraft.world.item.crafting.Ingredient} candidate stacks. */
final class IngredientStacks {

    private IngredientStacks() {
    }

    /**
     * Forge replaces the item list of an unbound tag with a named barrier stack. Such a slot has no
     * real candidates, so treating the placeholder as an item would silently understate its cost.
     */
    static boolean isEmptyTagPlaceholder(ItemStack stack) {
        return stack.is(Items.BARRIER) && stack.hasCustomHoverName();
    }
}
