package com.itzplana.sudoersemc.task;

/**
 * 测试用 {@link ScanManager.SnapshotCollector} 工厂。
 *
 * <p>Phase 7 之后 {@link ScanManager.SnapshotCollector} 返回分片会话而不是直接返回快照；
 * 仍在只关心调度器生命周期的测试里使用这个立即完成的会话，避免为每个测试重复实现
 * {@link ScanManager.CollectSession} 的样板方法。</p>
 */
public final class TestCollectSessions {

    private TestCollectSessions() {
    }

    /**
     * 创建一个立即报告完成的分片采集会话。
     *
     * @param label   快照标签，不能为 {@code null}
     * @param items   计划物品数量，不得为负
     * @param recipes 计划配方数量，不得为负
     * @return 每次 {@code begin()} 都返回已完成会话的采集器
     */
    public static ScanManager.SnapshotCollector completed(String label, int items, int recipes) {
        ScanManager.SnapshotData snapshot = new ScanManager.SnapshotData(label, items, recipes);
        return () -> new ScanManager.CollectSession() {

            @Override
            public int itemCount() {
                return items;
            }

            @Override
            public int recipeCount() {
                return recipes;
            }

            @Override
            public int processedItems() {
                return items;
            }

            @Override
            public int processedRecipes() {
                return recipes;
            }

            @Override
            public boolean isDone() {
                return true;
            }

            @Override
            public ScanManager.StepResult step(int maxItems) {
                return new ScanManager.StepResult(0, true);
            }

            @Override
            public ScanManager.SnapshotData finish() {
                return snapshot;
            }
        };
    }
}
