package com.itzplana.sudoersemc.pricing;

import java.util.Locale;

/**
 * 物品的材料类别。
 *
 * <p>标签只表示“材料关系”，并不表示两种物品的价值相同。例如 {@code forge:ingots/iron}
 * 与 {@code forge:ingots/gold} 都属于 {@link #INGOT}，但它们的实际价值差异很大。
 * 因此类别只是相对价格的锚点，具体数值由 {@link TagPricing} 的配置决定。</p>
 *
 * <p>本枚举是纯计算类型，不读取 Minecraft 的标签注册表；标签路径由调用方解析后传入。</p>
 */
public enum MaterialCategory {

    /** 锭类材料，例如 {@code forge:ingots/iron}。 */
    INGOT,

    /** 粒类材料，例如 {@code forge:nuggets/iron}。 */
    NUGGET,

    /** 宝石类材料，例如 {@code forge:gems/diamond}。 */
    GEM,

    /** 粉类材料，例如 {@code forge:dusts/redstone}。 */
    DUST,

    /** 粗矿类材料，例如 {@code forge:raw_materials/iron}。 */
    RAW_MATERIAL,

    /** 储存块类材料，例如 {@code forge:storage_blocks/iron}。 */
    STORAGE_BLOCK,

    /** 板材类材料，例如 {@code forge:plates/iron}。 */
    PLATE,

    /** 棒材类材料，例如 {@code forge:rods/iron}。 */
    ROD,

    /** 矿石类材料，例如 {@code forge:ores/iron}。 */
    ORE,

    /** 无法识别的材料类别。 */
    UNKNOWN;

    /**
     * 从标签路径推断材料类别。
     *
     * <p>支持带命名空间（{@code forge:ingots/iron}、{@code create:plates/iron}）和不带命名空间
     * （{@code ingots/iron}）两种写法，并同时接受单数与复数形式（例如 {@code ingot} 与
     * {@code ingots}）。无法识别、空白或 {@code null} 的路径统一返回 {@link #UNKNOWN}，
     * 本方法不会抛出异常。</p>
     *
     * <p>只读取命名空间之后的第一段路径作为类别名，因此 {@code forge:ores/iron/deepslate}
     * 这类更深的标签同样会被识别为 {@link #ORE}。</p>
     *
     * @param tagPath 标签路径，例如 {@code forge:storage_blocks/iron}
     * @return 对应的材料类别，无法识别时返回 {@link #UNKNOWN}
     */
    public static MaterialCategory fromTagPath(String tagPath) {
        if (tagPath == null) {
            return UNKNOWN;
        }
        String normalized = tagPath.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (normalized.isEmpty()) {
            return UNKNOWN;
        }
        int namespaceSeparator = normalized.lastIndexOf(':');
        if (namespaceSeparator >= 0) {
            normalized = normalized.substring(namespaceSeparator + 1);
        }
        int pathSeparator = normalized.indexOf('/');
        String head = pathSeparator >= 0 ? normalized.substring(0, pathSeparator) : normalized;
        if (head.isEmpty()) {
            return UNKNOWN;
        }
        return switch (head) {
            case "ingot", "ingots" -> INGOT;
            case "nugget", "nuggets" -> NUGGET;
            case "gem", "gems" -> GEM;
            case "dust", "dusts" -> DUST;
            case "raw_material", "raw_materials" -> RAW_MATERIAL;
            case "storage_block", "storage_blocks" -> STORAGE_BLOCK;
            case "plate", "plates" -> PLATE;
            case "rod", "rods" -> ROD;
            case "ore", "ores" -> ORE;
            default -> UNKNOWN;
        };
    }
}
