package com.itzplana.sudoersemc.resolver;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/**
 * 把物品堆叠的 NBT 规范化为确定性的 SNBT 字符串，供 {@link ItemKey#variantHash()} 使用。
 *
 * <p>规范化会递归删除所有层级中的纯展示字段（{@code display}、{@code RepairCost}、
 * {@code HideFlags}、{@code CustomModelData}、{@code EnchantmentGlintOverride}），并删除
 * 根级（物品自身）的 ProjectE {@code StoredEMC} 以及所有层级的非标准 {@code stored_emc}。
 * 根级 {@code StoredEMC} 由 ProjectE 的 {@code StoredEMCProcessor} 在查询时按
 * {@code IItemEmcHolder} 动态叠加，不能进入变体身份，否则每个存储值都会产生一个新变体。</p>
 *
 * <p>容器内容物（{@code BlockEntityTag.Items[].tag}）中的 ProjectE {@code StoredEMC}
 * 是例外：ProjectE 的 processor 只处理被查询的物品本身，不会读取嵌套内容物；而
 * {@code DefaultMaterialResolver} 会把该值计入容器价格。ProjectE 的
 * {@code EMCMappingHandler.getStoredEmcValue} 对完整 {@code ItemInfo} 做精确查找，
 * 因此容器变体键必须保留这个嵌套字段，否则生成映射永远命中不到真实容器堆叠，修复不会
 * 生效。该值在扫描阶段是经济上不同的变体（不同存储量对应不同价值），由
 * {@code VariantScanner} 的 {@code maxVariantsPerItem}/{@code HARD_VARIANT_CEILING}
 * 做有界、确定性的截断。</p>
 *
 * <p>经济相关数据必须保留，包括 {@code Enchantments}、{@code StoredEnchantments}、
 * {@code Potion}/{@code CustomPotionEffects}、{@code BlockEntityTag}（容器内容）、
 * {@code Items} 与 {@code SkullOwner}。{@link #keyFor(ResourceLocation, CompoundTag)}
 * 会把可损坏物品顶层的 {@code Damage} 规范化为 {@code 0}：ProjectE 的
 * {@code DamageProcessor} 在查询时按耐久动态折价，把每个耐久值都枚举成独立变体会造成
 * 变体爆炸与重复折价；若清洗后仍有附魔等经济字段，则保留 {@code {Damage:0,...}}。
 * <strong>已知限制：</strong>真实堆叠的 {@code Damage>0} 且带其它 NBT 时，其精确
 * {@code ItemInfo} 是 {@code {Damage:d,...}}，与这里生成的 {@code {Damage:0,...}}
 * 并不相等；ProjectE 会安全地回退到无 NBT 基础价并执行 {@code DamageProcessor}，
 * 不会误用其它变体的价格。这是为避免每耐久一个变体而有意接受的限制，不是遗漏。
 * 原始标签不含 {@code Damage} 时不凭空添加，以匹配 ProjectE 的无伤害形状。</p>
 *
 * <p>{@link #normalize(CompoundTag)} 不知道物品类型，因此保留 {@code Damage}，仅用于
 * 无法确定物品身份的嵌套数据。所有复合标签的键都会按字典序排序，因此相同的标签无论插入
 * 顺序如何都会得到完全相同的字符串。返回值是合法的 SNBT，可直接交给
 * {@code TagParser.parseTag}。清洗后没有任何剩余字段时返回 {@code null}，表示普通物品。</p>
 *
 * <p>已知限制：{@code Enchantments}/{@code StoredEnchantments}/{@code AttributeModifiers}
 * 等语义无序列表仍按原有顺序参与哈希；列表顺序不同的等价物品会得到不同变体。</p>
 */
public final class VariantNormalizer {

    private static final Set<String> STRIPPED_KEYS = Set.of(
            "display",
            "RepairCost",
            "HideFlags",
            "CustomModelData",
            "EnchantmentGlintOverride",
            // 非 ProjectE 的小写约定；ProjectE 只认根级 "StoredEMC"，
            // 且根级 StoredEMC 由 StoredEMCProcessor 在查询时处理。
            "stored_emc"
    );

    private static final String ATTRIBUTE_MODIFIERS = "AttributeModifiers";
    private static final String DAMAGE = "Damage";
    private static final String STORED_EMC = "StoredEMC";
    private static final String UUID = "UUID";

    private VariantNormalizer() {
    }

    /**
     * 规范化一个物品 NBT 标签。
     *
     * <p>该方法不会修改传入的标签；返回的字符串可安全地长期保存。嵌套的复合标签与列表
     * 也会递归清洗，因此容器内容中物品的自定义名称不会影响容器变体的身份。</p>
     *
     * <p>该方法不知道物品类型，因此不会剥离 {@code Damage}；构建可损坏物品的变体身份
     * 请使用 {@link #keyFor(ResourceLocation, CompoundTag)}。</p>
     *
     * @param tag 原始物品标签，允许为 {@code null}
     * @return 规范化的 SNBT；清洗后没有经济相关数据时返回 {@code null}
     */
    public static @Nullable String normalize(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        CompoundTag sanitized = sanitizeCompound(tag, false, true);
        if (sanitized.isEmpty() || isOnlyDefaultDamage(sanitized)) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        writeCompound(sanitized, out);
        return out.toString();
    }

    /**
     * 判断清洗后的标签是否只剩默认的 {@code Damage:0}。
     *
     * <p>配方输出常把 {@code {Damage:0}} 当作“无 NBT”写回；默认伤害值不携带任何经济信息，
     * 若保留会为普通物品生成伪变体 {@code variantHash={Damage:0}}。只有标签中仅有该字段且
     * 数值为 0 时才视为空；若还存在附魔、容器内容等经济字段，仍保留 {@code Damage}。</p>
     */
    private static boolean isOnlyDefaultDamage(CompoundTag sanitized) {
        if (sanitized.size() != 1) {
            return false;
        }
        Tag damage = sanitized.get(DAMAGE);
        return damage instanceof NumericTag numeric && numeric.getAsDouble() == 0.0D;
    }

    /**
     * 为指定物品构建带有规范化变体哈希的物品键。
     *
     * <p>可损坏物品（例如剑、工具）的顶层 {@code Damage} 会被规范化为 {@code 0}：ProjectE 的
     * {@code DamageProcessor} 在 EMC 查询时按剩余耐久折价。若把每个耐久值都作为独立变体写入
     * 映射，既会占满变体上限，又会在查询时被再次折价。清洗后若还有附魔等其它经济字段，则保留
     * {@code {Damage:0,...}}。原始标签不含 {@code Damage} 时保持 ProjectE 的无伤害形状。</p>
     *
     * <p><strong>有意限制：</strong>实际 {@code Damage>0} 且带其它 NBT 的堆叠，其精确
     * {@code ItemInfo} 与 {@code {Damage:0,...}} 不相等，生成映射不会命中；ProjectE 会回退到
     * 基础价并执行 {@code DamageProcessor}。这避免了每耐久一个变体，也不会把不同损坏程度
     * 错误合并到同一变体价格；代价是这类堆叠无法使用变体溢价。</p>
     *
     * <p>容器内容物中的嵌套 {@code StoredEMC} 会被保留（根级 {@code StoredEMC} 仍委托给
     * ProjectE），以保证容器映射键与真实堆叠的完整 {@code ItemInfo} 精确相等。</p>
     *
     * @param itemId 物品注册 id
     * @param tag    原始物品标签，允许为 {@code null}
     * @return 带有变体哈希的物品键；没有可保留数据时返回普通物品键
     */
    public static ItemKey keyFor(ResourceLocation itemId, @Nullable CompoundTag tag) {
        Objects.requireNonNull(itemId, "itemId");
        return keyFor(itemId, tag, BuiltInRegistries.ITEM.getOptional(itemId).orElse(null));
    }

    /**
     * 已解析 {@link Item} 的重载，供同一调用链复用注册表查询结果。
     */
    static ItemKey keyFor(ResourceLocation itemId, @Nullable CompoundTag tag, @Nullable Item item) {
        Objects.requireNonNull(itemId, "itemId");
        if (item == null || !item.canBeDepleted()) {
            return ItemKey.of(itemId, normalize(tag));
        }
        boolean hadDamage = tag != null && tag.contains(DAMAGE);
        CompoundTag sanitized = tag == null || tag.isEmpty()
                ? new CompoundTag()
                : sanitizeCompound(tag, true, true);
        if (sanitized.isEmpty()) {
            return ItemKey.of(itemId);
        }
        // ProjectE 的 ItemInfo 按完整 NBT 精确匹配：带其它 NBT 的可损坏物品若原本带有
        // Damage，其默认形状是 {Damage:0,...}；若原本没有 Damage，则必须保持无伤害形状。
        // 把 Damage 统一归一到 0 只消除“每耐久一个变体”，不会凭空改变 ItemInfo 的形状。
        if (hadDamage) {
            sanitized.putInt(DAMAGE, 0);
        }
        if (isOnlyDefaultDamage(sanitized)) {
            return ItemKey.of(itemId);
        }
        StringBuilder out = new StringBuilder();
        writeCompound(sanitized, out);
        return ItemKey.of(itemId, out.toString());
    }

    private static CompoundTag sanitizeCompound(CompoundTag source, boolean stripTopLevelDamage,
                                                boolean stripTopLevelStoredEmc) {
        CompoundTag result = new CompoundTag();
        for (String key : source.getAllKeys()) {
            if (STRIPPED_KEYS.contains(key)
                    || (stripTopLevelStoredEmc && STORED_EMC.equals(key))
                    || (stripTopLevelDamage && DAMAGE.equals(key))) {
                continue;
            }
            Tag value = source.get(key);
            if (value == null) {
                continue;
            }
            if (ATTRIBUTE_MODIFIERS.equals(key)) {
                result.put(key, sanitizeAttributeModifiers(value));
            } else {
                result.put(key, sanitizeTag(value));
            }
        }
        return result;
    }

    /**
     * 属性修饰符的 UUID 只是随机身份，不参与经济价值比较。
     *
     * <p>原版与多数模组使用列表保存修饰符，因此对列表中的每个复合标签移除 {@code UUID}；
     * 少数模组直接使用单个复合标签，这里也一并处理。其余字段原样保留。</p>
     */
    private static Tag sanitizeAttributeModifiers(Tag tag) {
        if (tag instanceof ListTag list) {
            ListTag result = new ListTag();
            for (int i = 0; i < list.size(); i++) {
                Tag element = list.get(i);
                if (element instanceof CompoundTag entry) {
                    CompoundTag sanitized = sanitizeCompound(entry, false, false);
                    sanitized.remove(UUID);
                    result.add(sanitized);
                } else {
                    result.add(sanitizeTag(element));
                }
            }
            return result;
        }
        if (tag instanceof CompoundTag compound) {
            CompoundTag sanitized = sanitizeCompound(compound, false, false);
            sanitized.remove(UUID);
            return sanitized;
        }
        return sanitizeTag(tag);
    }

    private static Tag sanitizeTag(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            return sanitizeCompound(compound, false, false);
        }
        if (tag instanceof ListTag list) {
            ListTag result = new ListTag();
            for (int i = 0; i < list.size(); i++) {
                result.add(sanitizeTag(list.get(i)));
            }
            return result;
        }
        // 基本类型与数组不会被修改，可以直接复用。
        return tag;
    }

    private static void writeCompound(CompoundTag compound, StringBuilder out) {
        Map<String, Tag> sorted = new TreeMap<>();
        for (String key : compound.getAllKeys()) {
            Tag value = compound.get(key);
            if (value != null) {
                sorted.put(key, value);
            }
        }

        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Tag> entry : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            appendKey(entry.getKey(), out);
            out.append(':');
            writeTag(entry.getValue(), out);
        }
        out.append('}');
    }

    private static void writeList(ListTag list, StringBuilder out) {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            writeTag(list.get(i), out);
        }
        out.append(']');
    }

    private static void writeTag(Tag tag, StringBuilder out) {
        if (tag instanceof CompoundTag compound) {
            writeCompound(compound, out);
        } else if (tag instanceof ListTag list) {
            writeList(list, out);
        } else if (tag instanceof StringTag stringTag) {
            // StringTag#getAsString 返回未经引号包裹的原始值，必须显式转义。
            out.append(StringTag.quoteAndEscape(stringTag.getAsString()));
        } else {
            // 基本类型与数组使用原版访问器输出合法 SNBT。
            out.append(tag.getAsString());
        }
    }

    private static void appendKey(String key, StringBuilder out) {
        if (isSimpleKey(key)) {
            out.append(key);
        } else {
            out.append(StringTag.quoteAndEscape(key));
        }
    }

    /**
     * 判断键是否可以不加引号地写入 SNBT。允许的字符集与 {@code TagParser} 的非引号键规则一致。
     */
    private static boolean isSimpleKey(String key) {
        if (key.isEmpty()) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean allowed = (c >= '0' && c <= '9')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || c == '_' || c == '-' || c == '+' || c == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
