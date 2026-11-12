package com.itzplana.sudoersemc.scanner;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Bootstraps the vanilla registries once for scanner tests. */
final class MinecraftTestBootstrap {

    private static boolean bootstrapped;

    private MinecraftTestBootstrap() {
    }

    static synchronized void ensure() {
        if (bootstrapped) {
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bootstrapped = true;
    }
}
