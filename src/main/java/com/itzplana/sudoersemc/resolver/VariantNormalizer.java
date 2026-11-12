package com.itzplana.sudoersemc.resolver;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/**
 * 把物品堆叠的 NBT 规范化为确定性的 SNBT 字符串，供 {@link ItemKey#variantHash()} 使用。
 *
 * <p>规范化会递归删除所有层级中的纯展示字段（{@code display}、{@code RepairCost}、
 * {@code HideFlags}、{@code CustomModelData}、{@code EnchantmentGlintOverride}），并对
 * {@code AttributeModifiers} 的每个条目移除 {@code UUID}。删除这些字段后，仅仅因为随机
 * UUID、自定义名称或提示文本不同而变化的物品会被视为同一个经济变体。</p>
 *
 * <p>经济相关数据必须保留，包括 {@code Damage}、{@code Enchantments}、
 * {@code StoredEnchantments}、{@code Potion}/{@code CustomPotionEffects}、
 * {@code BlockEntityTag}（容器内容）、{@code Items}、{@code stored_emc} 与
 * {@code SkullOwner}。所有复合标签的键都会按字典序排序，因此相同的标签无论插入顺序如何
 * 都会得到完全相同的字符串。返回值是合法的 SNBT，可直接交给
 * {@code TagParser.parseTag}。清洗后没有任何剩余字段时返回 {@code null}，表示普通物品。</p>
 */
public final class VariantNormalizer {

    private static final Set<String> STRIPPED_KEYS = Set.of(
            "display",
            "RepairCost",
            "HideFlags",
            "CustomModelData",
            "EnchantmentGlintOverride"
    );

    private static final String ATTRIBUTE_MODIFIERS = "AttributeModifiers";
    private static final String UUID = "UUID";
    private static final String DAMAGE = "Damage";

    private VariantNormalizer() {
    }

    /**
     * 规范化一个物品 NBT 标签。
     *
     * <p>该方法不会修改传入的标签；返回的字符串可安全地长期保存。嵌套的复合标签与列表
     * 也会递归清洗，因此容器内容中物品的自定义名称不会影响容器变体的身份。</p>
     *
     * <p>可损坏物品默认携带的 {@code {Damage:0}} 只表示「未受损」，不构成经济差异；
     * 因此当清洗后的标签只剩 {@code Damage} 且值为 0 时同样返回 {@code null}。若还包含
     * 其它经济字段，或伤害值非 0，则保留 {@code Damage}。</p>
     *
     * @param tag 原始物品标签，允许为 {@code null}
     * @return 规范化的 SNBT；清洗后没有经济相关数据时返回 {@code null}
     */
    public static @Nullable String normalize(@Nullable CompoundTag tag) {
        return normalize(tag, false);
    }

    private static @Nullable String normalize(@Nullable CompoundTag tag, boolean stripTopLevelDamage) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        CompoundTag sanitized = sanitizeCompound(tag, stripTopLevelDamage);
        if (sanitized.isEmpty() || isDefaultDamageOnly(sanitized)) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        writeCompound(sanitized, out);
        return out.toString();
    }

    /**
     * 为指定物品构建带有规范化变体哈希的物品键。
     *
     * @param itemId 物品注册 id
     * @param tag    原始物品标签，允许为 {@code null}
     * @return 带有变体哈希的物品键；没有可保留数据时返回普通物品键
     */
    public static ItemKey keyFor(ResourceLocation itemId, @Nullable CompoundTag tag) {
        Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        boolean stripTopLevelDamage = item != null && item.canBeDepleted();
        return ItemKey.of(itemId, normalize(tag, stripTopLevelDamage));
    }

    /**
     * 判断清洗后的标签是否只包含「未受损」这一默认伤害标记。
     *
     * <p>只有唯一键 {@code Damage} 且数值为 0 时才算默认值。若还有其它键，说明
     * 该标签携带额外经济数据，{@code Damage} 必须作为组合变体的一部分保留。</p>
     *
     * @param tag 已清洗的标签，不能为空
     * @return 仅包含默认 {@code Damage:0} 时返回 {@code true}
     */
    private static boolean isDefaultDamageOnly(CompoundTag tag) {
        if (tag.size() != 1) {
            return false;
        }
        Tag damage = tag.get(DAMAGE);
        return damage instanceof NumericTag numeric && numeric.getAsInt() == 0;
    }

    private static CompoundTag sanitizeCompound(CompoundTag source, boolean stripTopLevelDamage) {
        CompoundTag result = new CompoundTag();
        for (String key : source.getAllKeys()) {
            if (STRIPPED_KEYS.contains(key) || (stripTopLevelDamage && DAMAGE.equals(key))) {
                continue;
            }
            if (STRIPPED_KEYS.contains(key)) {
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
                    CompoundTag sanitized = sanitizeCompound(entry, false);
                    sanitized.remove(UUID);
                    result.add(sanitized);
                } else {
                    result.add(sanitizeTag(element));
                }
            }
            return result;
        }
        if (tag instanceof CompoundTag compound) {
            CompoundTag sanitized = sanitizeCompound(compound, false);
            sanitized.remove(UUID);
            return sanitized;
        }
        return sanitizeTag(tag);
    }

    private static Tag sanitizeTag(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            return sanitizeCompound(compound, false);
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
