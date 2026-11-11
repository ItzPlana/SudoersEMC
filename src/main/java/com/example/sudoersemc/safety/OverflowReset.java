package com.example.sudoersemc.safety;

import com.example.sudoersemc.core.ItemKey;
import com.example.sudoersemc.core.OverflowScope;

/**
 * Audit record for a single overflow reset.
 *
 * @param item          the item whose value was reset
 * @param originalValue the value that exceeded the threshold
 * @param resetValue    the value written back, always inside the configured reset range
 * @param threshold     the threshold that was exceeded
 * @param scope         which entries were eligible when the reset happened
 * @param timestamp     epoch milliseconds of the reset
 */
public record OverflowReset(
        ItemKey item,
        long originalValue,
        long resetValue,
        long threshold,
        OverflowScope scope,
        long timestamp
) {

    public OverflowReset {
        if (originalValue <= threshold) {
            throw new IllegalArgumentException("originalValue must exceed threshold");
        }
        if (resetValue > originalValue) {
            throw new IllegalArgumentException("resetValue must not increase the value");
        }
    }
}
