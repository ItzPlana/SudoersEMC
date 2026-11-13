package com.itzplana.sudoersemc.integration;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.List;
import java.util.OptionalLong;
import org.jetbrains.annotations.Nullable;

/**
 * 永远不可用的外部基线来源。
 *
 * <p>它是 Phase 24 行为的精确等价物：不读取任何资源、不产生候选、不改变指纹中的固定标记。
 * 单例避免每次重建扫描管线都分配新对象。</p>
 */
final class UnavailableExternalBaselineSource implements ExternalBaselineSource {

    static final UnavailableExternalBaselineSource INSTANCE = new UnavailableExternalBaselineSource();

    private static final List<String> FINGERPRINT =
            List.of("external-baseline=unavailable");

    private UnavailableExternalBaselineSource() {
    }

    @Override
    public String id() {
        return "unavailable";
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public OptionalLong lookup(@Nullable ItemKey item) {
        return OptionalLong.empty();
    }

    @Override
    public List<String> fingerprintEntries() {
        return FINGERPRINT;
    }
}
