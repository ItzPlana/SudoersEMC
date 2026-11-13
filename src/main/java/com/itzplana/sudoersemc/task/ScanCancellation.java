package com.itzplana.sudoersemc.task;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation token for one scan.
 *
 * <p>{@link ScanManager} sets the token on {@link ScanManager#cancel()}; the real pipeline actively
 * checks {@link #isRequested()} inside long loops. Cancellation therefore no longer takes effect only at
 * tick boundaries — item collection, recipe scanning, NBT variant generation and the solve loop can all
 * exit early at the next checkpoint.</p>
 *
 * <p>The token can be read from any thread; {@link #clear()} should only be called by the scheduler in
 * the scan-termination phase, to avoid clearing a cancellation signal the pipeline has already observed
 * too early.</p>
 */
public final class ScanCancellation {

    private final AtomicBoolean requested = new AtomicBoolean(false);

    /**
     * Requests cancellation. Callable from any thread; repeated calls have no side effect.
     */
    public void request() {
        requested.set(true);
    }

    /**
     * Clears the cancellation signal so the next scan can reuse the same token.
     */
    public void clear() {
        requested.set(false);
    }

    /**
     * @return whether cancellation has been requested
     */
    public boolean isRequested() {
        return requested.get();
    }
}
