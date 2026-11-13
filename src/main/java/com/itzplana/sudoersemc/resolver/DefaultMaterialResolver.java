package com.itzplana.sudoersemc.resolver;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 内置的 NBT 材料解析器，覆盖原版容器内容。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>{@code BlockEntityTag.Items} 中恰好只有一个非空堆叠时，容器价值等于该物品的
 *       单元 EMC 乘以 {@code Count}；容器里有多个非空堆叠时无法给出唯一价格，返回空。</li>
 *   <li>容器内可损坏物品的 {@code Damage} 会按与 ProjectE {@code DamageProcessor}
 *       相同的公式折价，避免把损坏物品按满耐久计价。</li>
 *   <li>容器内 EMC holder 物品自身的 {@code StoredEMC} 在折价后饱和叠加，顺序与
 *       ProjectE 的 {@code DamageProcessor} → {@code StoredEMCProcessor} 一致。ProjectE
 *       不会为容器内容物执行该 processor，因此必须在这里处理。默认只对 ProjectE 内置
 *       Klein Star 白名单生效，避免把非 holder 物品上的伪 {@code StoredEMC} 计入价值；
 *       其他模组的 EMC holder 需要接线方通过
 *       {@link #DefaultMaterialResolver(ToLongFunction, Predicate)} 传入基于
 *       {@code PECapabilities.EMC_HOLDER_ITEM_CAPABILITY} 的判断。容器根标签上的
 *       {@code StoredEMC} 仍由 ProjectE 处理，这里不重复叠加。</li>
 *   <li>容器变体键必须保留内容物的嵌套 {@code StoredEMC}，否则 ProjectE 对完整
 *       {@code ItemInfo} 的精确查找会 miss，本解析结果不会生效。</li>
 * </ul>
 *
 * <p>查询函数必须返回非负 EMC，0 表示未知。解析结果始终大于 0，避免把无法定价的物品
 * 当成免费物品。</p>
 */
public final class DefaultMaterialResolver implements NbtMaterialResolver {

    private static final String BLOCK_ENTITY_TAG = "BlockEntityTag";
    private static final String ITEMS = "Items";
    private static final String ID = "id";
    private static final String COUNT = "Count";
    private static final String ITEM_TAG = "tag";
    private static final String STORED_EMC = "StoredEMC";

    /**
     * ProjectE 1.0.1 中实现 {@code IItemEmcHolder} 的物品只有 Klein Star 系列。保留为
     * registry id 白名单而不是直接引用 ProjectE 类，确保 ProjectE 缺席时本类仍可加载。
     */
    private static final Set<ResourceLocation> PROJECTE_EMC_HOLDER_IDS = Set.of(
            new ResourceLocation("projecte", "klein_star_ein"),
            new ResourceLocation("projecte", "klein_star_zwei"),
            new ResourceLocation("projecte", "klein_star_drei"),
            new ResourceLocation("projecte", "klein_star_vier"),
            new ResourceLocation("projecte", "klein_star_sphere"),
            new ResourceLocation("projecte", "klein_star_omega")
    );

    private final ToLongFunction<ItemKey> emcLookup;
    private final Predicate<ItemStack> storedEmcHolder;

    /**
     * 使用内置的 ProjectE EMC holder 白名单构造解析器。
     *
     * <p>默认只把 ProjectE 内置 Klein Star 的 {@code StoredEMC} 计入容器价值；其他物品上的
     * 同名标签会被忽略，避免伪 {@code StoredEMC} 高估。Phase 7 接线其他模组的 EMC holder
     * 时，应使用 {@link #DefaultMaterialResolver(ToLongFunction, Predicate)} 传入基于
     * {@code PECapabilities.EMC_HOLDER_ITEM_CAPABILITY} 的精确判断。</p>
     *
     * @param emcLookup 从物品键查询基准变体 EMC 的函数，0 表示未知；不得为 {@code null}。
     *                  传入值必须尚未执行 ProjectE 的 {@code DamageProcessor}/
     *                  {@code StoredEMCProcessor}，本解析器会自行折价并叠加存储 EMC。
     */
    public DefaultMaterialResolver(ToLongFunction<ItemKey> emcLookup) {
        this(emcLookup, DefaultMaterialResolver::isKnownProjectEEmcHolder);
    }

    /**
     * 允许集成层注入 EMC holder 判断（例如 ProjectE item capability）。
     *
     * @param emcLookup       从物品键查询基准变体 EMC 的函数，0 表示未知；不得为 {@code null}
     * @param storedEmcHolder 判断内容物是否为 EMC holder 的谓词；不得为 {@code null}
     */
    public DefaultMaterialResolver(ToLongFunction<ItemKey> emcLookup, Predicate<ItemStack> storedEmcHolder) {
        this.emcLookup = Objects.requireNonNull(emcLookup, "emcLookup");
        this.storedEmcHolder = Objects.requireNonNull(storedEmcHolder, "storedEmcHolder");
    }

    @Override
    public boolean supports(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        return tag != null && containerItems(tag) != null;
    }

    @Override
    public OptionalLong resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return OptionalLong.empty();
        }
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return OptionalLong.empty();
        }
        return resolveSingleItemContainer(tag);
    }

    /**
     * 读取容器内容。只有列表中恰好一个 {@code Count > 0} 的条目时才能给出唯一价格。
     */
    private OptionalLong resolveSingleItemContainer(CompoundTag tag) {
        ListTag items = containerItems(tag);
        if (items == null) {
            return OptionalLong.empty();
        }

        CompoundTag single = null;
        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompound(i);
            if (entry.getByte(COUNT) <= 0) {
                continue;
            }
            if (single != null) {
                // 多个非空堆叠意味着容器价值不能由单个物品代表。
                return OptionalLong.empty();
            }
            single = entry;
        }

        if (single == null) {
            return OptionalLong.empty();
        }

        ResourceLocation itemId = ResourceLocation.tryParse(single.getString(ID));
        if (itemId == null) {
            return OptionalLong.empty();
        }
        CompoundTag itemTag = single.contains(ITEM_TAG, Tag.TAG_COMPOUND)
                ? single.getCompound(ITEM_TAG)
                : null;
        long unitValue = containedItemValue(itemId, itemTag);
        if (unitValue <= 0L) {
            return OptionalLong.empty();
        }
        long count = single.getByte(COUNT);
        return OptionalLong.of(saturatedMultiply(unitValue, count));
    }

    /**
     * 计算容器内单个物品的 EMC。可损坏物品按剩余耐久折价（公式与 ProjectE 的
     * {@code DamageProcessor} 一致），随后饱和叠加物品自身存储的 {@code StoredEMC}
     * （与 {@code StoredEMCProcessor} 的顺序一致）。只有 {@code storedEmcHolder} 判断为
     * EMC holder 的物品才叠加，避免伪 {@code StoredEMC} 高估。基础价值未知时返回
     * {@code 0}，让容器整体保持无价，避免把存储 EMC 误当成完整价值或给出低价。
     */
    private long containedItemValue(ResourceLocation itemId, @Nullable CompoundTag itemTag) {
        Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        long value = emcLookup.applyAsLong(VariantNormalizer.keyFor(itemId, itemTag, item));
        if (value <= 0L) {
            return value;
        }

        ItemStack contained = null;
        if (item != null) {
            contained = new ItemStack(item);
            if (itemTag != null) {
                contained.setTag(itemTag);
            }
        }

        if (item != null && item.canBeDepleted()) {
            int maxDamage = contained.getMaxDamage();
            int damage = contained.getDamageValue();
            // ProjectE 在 damage > maxDamage 时放弃折价，这里保持一致。
            if (maxDamage > 0 && damage > 0 && damage <= maxDamage) {
                long remaining = maxDamage - damage + 1L;
                value = value > Long.MAX_VALUE / remaining
                        ? Long.MAX_VALUE
                        : value * remaining / maxDamage;
            }
        }

        long storedEmc = 0L;
        if (contained != null && itemTag != null && storedEmcHolder.test(contained)) {
            storedEmc = Math.max(0L, itemTag.getLong(STORED_EMC));
        }
        if (storedEmc == 0L) {
            return value;
        }
        if (value > Long.MAX_VALUE - storedEmc) {
            return Long.MAX_VALUE;
        }
        return value + storedEmc;
    }

    /**
     * 默认 EMC holder 白名单：ProjectE 1.0.1 只有 Klein Star 系列实现
     * {@code IItemEmcHolder}。其他模组需要通过 predicate 重载接入 capability。
     */
    static boolean isKnownProjectEEmcHolder(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return isKnownProjectEEmcHolder(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    static boolean isKnownProjectEEmcHolder(@Nullable ResourceLocation itemId) {
        return itemId != null && PROJECTE_EMC_HOLDER_IDS.contains(itemId);
    }

    /**
     * 读取 {@code BlockEntityTag.Items}；不存在或类型不正确时返回 {@code null}。
     */
    private static @Nullable ListTag containerItems(CompoundTag tag) {
        if (!tag.contains(BLOCK_ENTITY_TAG, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag blockEntity = tag.getCompound(BLOCK_ENTITY_TAG);
        if (!blockEntity.contains(ITEMS, Tag.TAG_LIST)) {
            return null;
        }
        return blockEntity.getList(ITEMS, Tag.TAG_COMPOUND);
    }

    /**
     * 饱和乘法：避免容器内大量物品的 EMC 相乘时溢出成负数。
     */
    private static long saturatedMultiply(long value, long count) {
        if (value <= 0L || count <= 0L) {
            return 0L;
        }
        if (value > Long.MAX_VALUE / count) {
            return Long.MAX_VALUE;
        }
        return value * count;
    }
}
