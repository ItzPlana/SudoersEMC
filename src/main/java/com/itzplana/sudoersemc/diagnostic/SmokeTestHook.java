package com.itzplana.sudoersemc.diagnostic;

import com.itzplana.sudoersemc.Sudoersemc;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.integration.EmcProvider;
import com.itzplana.sudoersemc.integration.OptionalIntegrationManager;
import com.mojang.logging.LogUtils;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Temporary Phase 1 end-to-end probe.
 *
 * <p>Disabled by default. Run the dedicated server with {@code -Dsudoersemc.smokeTest=true} to
 * stage one generated value, force a ProjectE remap, and log whether the value became queryable
 * through {@code IEMCProxy}. This exists so the ProjectE write path can be verified without
 * shipping test-only commands in production code.</p>
 */
public final class SmokeTestHook {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String MARKER = "sudoersemc.smokeTest";
    private static final String QUERY_MARKER = "sudoersemc.smokeQuery";
    private static final String PIPELINE_MARKER = "sudoersemc.pipelineScan";
    private static final ResourceLocation TEST_ITEM = new ResourceLocation("minecraft", "bedrock");
    private static final long TEST_VALUE = 12_345L;
    private static final int CHECK_DELAY_TICKS = 60;

    private static boolean enabled;
    private static boolean staged;
    private static int ticks;
    private static boolean logged;

    /** 管线扫描探针状态。 */
    private static boolean pipelineScanActive;
    private static int pipelineTicks;
    /** 管线探针最长等待 tick 数（约 60 秒）。 */
    private static final int PIPELINE_TIMEOUT_TICKS = 1200;

    public static void onServerStarted(ServerStartedEvent event) {
        boolean pipelineMode = System.getProperty(PIPELINE_MARKER, "false").equalsIgnoreCase("true");
        if (pipelineMode) {
            onPipelineScanStarted();
            return;
        }
        boolean writeMode = System.getProperty(MARKER, "false").equalsIgnoreCase("true");
        boolean queryMode = System.getProperty(QUERY_MARKER, "false").equalsIgnoreCase("true");
        if (!writeMode && !queryMode) {
            return;
        }
        enabled = true;
        Optional<EmcProvider> provider = OptionalIntegrationManager.provider();
        if (provider.isEmpty()) {
            LOGGER.warn("[smoke] ProjectE provider is unavailable; skipping the EMC probe");
            return;
        }
        EmcProvider emc = provider.get();
        ItemStack probeStack = new ItemStack(Items.BEDROCK);
        long before = emc.getValue(probeStack);
        LOGGER.info("[smoke] before: minecraft:bedrock hasValue={} emc={}",
                emc.hasValue(probeStack), before);
        if (queryMode) {
            LOGGER.info("[smoke] query-only mode: expected {} => {}",
                    TEST_VALUE,
                    before == TEST_VALUE ? "PERSISTED VALUE APPLIED" : "PERSISTED VALUE MISSING");
            logged = true;
            return;
        }

        boolean accepted = emc.stageGeneratedValue(
                ItemKey.of(TEST_ITEM), TEST_VALUE, PriceSource.FALLBACK, null);
        LOGGER.info("[smoke] stageGeneratedValue accepted={}", accepted);
        staged = accepted;
    }

    private static void runRemap(MinecraftServer server) {
        Optional<EmcProvider> provider = OptionalIntegrationManager.provider();
        provider.ifPresent(emc -> {
            LOGGER.info("[smoke] requesting ProjectE remap");
            emc.requestRemap(server);
        });
    }
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (pipelineScanActive) {
            tickPipelineScan();
            return;
        }
        if (!enabled || !staged || logged) {
            return;
        }
        ticks++;
        if (ticks == 20) {
            runRemap(event.getServer());
        }
        if (ticks >= CHECK_DELAY_TICKS) {
            logged = true;
            Optional<EmcProvider> provider = OptionalIntegrationManager.provider();
            if (provider.isEmpty()) {
                LOGGER.warn("[smoke] provider disappeared before the probe could be checked");
                return;
            }
            ItemStack probeStack = new ItemStack(Items.BEDROCK);
            EmcProvider emc = provider.get();
            long after = emc.getValue(probeStack);
            boolean has = emc.hasValue(probeStack);
            LOGGER.info("[smoke] after: minecraft:bedrock hasValue={} emc={} (expected {}) => {}",
                    has, after, TEST_VALUE,
                    after == TEST_VALUE ? "WRITE VERIFIED" : "WRITE FAILED");
            GeneratedEntryStoreAccess.save();
            LOGGER.info("[smoke] probe finished");
        }
    }

    /**
     * 全量扫描探针：请求一次真实的管线扫描，并在完成或超时后记录结果。
     *
     * <p>由 {@code -Dsudoersemc.pipelineScan=true} 启用。这是唯一能验证「扫描 → 求解 → 定价 →
     * 写入 ProjectE」端到端链路的开发手段，默认关闭。</p>
     */
    private static void onPipelineScanStarted() {
        var manager = Sudoersemc.scanManager();
        if (manager == null) {
            LOGGER.warn("[pipeline] 扫描管理器不可用，跳过探针");
            return;
        }
        boolean accepted = manager.requestScan();
        LOGGER.info("[pipeline] requestScan accepted={}", accepted);
        pipelineScanActive = accepted;
        pipelineTicks = 0;
    }

    /**
     * 推进扫描探针。每 40 tick 记录一次进度，完成后打印结果摘要。
     */
    private static void tickPipelineScan() {
        var manager = Sudoersemc.scanManager();
        if (manager == null) {
            pipelineScanActive = false;
            return;
        }
        pipelineTicks++;
        if (pipelineTicks % 40 == 0) {
            var progress = manager.progress();
            LOGGER.info("[pipeline] 阶段={} 进度={}/{}",
                    progress.phase().label(), progress.processed(), progress.total());
        }
        if (!manager.isRunning()) {
            pipelineScanActive = false;
            var pipeline = Sudoersemc.scanPipeline();
            if (pipeline != null) {
                LOGGER.info("[pipeline] 完成：已定价 {} 项，未解析 {} 项，耗时 {} ms",
                        pipeline.lastSolvedCount(), pipeline.lastUnresolvedCount(),
                        pipeline.lastDurationMillis());
            }
            var store = Sudoersemc.entryStore();
            LOGGER.info("[pipeline] 存储条目总数={}", store == null ? -1 : store.size());
        }
        if (pipelineTicks > PIPELINE_TIMEOUT_TICKS) {
            pipelineScanActive = false;
            LOGGER.warn("[pipeline] 探针超时，扫描可能仍未结束");
        }
    }

    /** Small indirection so the probe can persist its staged entry. */
    private static final class GeneratedEntryStoreAccess {

        private GeneratedEntryStoreAccess() {
        }

        static void save() {
            var store = Sudoersemc.entryStore();
            if (store != null) {
                store.saveIfDirty();
            }
        }
    }
}
