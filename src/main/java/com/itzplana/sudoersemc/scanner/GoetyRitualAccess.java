package com.itzplana.sudoersemc.scanner;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

/**
 * Goety 仪式配方的字段访问接缝。
 *
 * <p>生产实现 {@link ReflectiveGoetyRitualAccess} 通过反射读取可选依赖 Goety 的配方对象；
 * 单元测试可以注入假实现，从而在完全没有 Goety 的 JVM 中验证必需字段的失败语义。</p>
 *
 * <p>每个访问器返回 {@link FieldRead} 三态结果，用于区分「访问器不存在」与「调用失败」。
 * 两者在经济上都按 fail-closed 处理，但日志与排查方式不同。</p>
 */
interface GoetyRitualAccess {

    /**
     * @return 四个访问器是否全部解析成功
     */
    boolean available();

    /**
     * 读取仪式材料列表。
     *
     * @param recipe Goety 仪式配方对象
     * @return 三态读取结果；值允许为 {@code null}，由调用方判定是否为失败
     */
    FieldRead<List<Ingredient>> ingredients(Object recipe);

    /**
     * 读取激活物品。
     *
     * @param recipe Goety 仪式配方对象
     * @return 三态读取结果；合法零成本时返回 {@link Ingredient#EMPTY}
     */
    FieldRead<Ingredient> activationItem(Object recipe);

    /**
     * 读取仪式类型标识，例如 {@code goety:craft}。
     *
     * @param recipe Goety 仪式配方对象
     * @return 三态读取结果；缺失或无法转换时由调用方跳过整条配方
     */
    FieldRead<String> ritualType(Object recipe);

    /**
     * 读取仪式产物。
     *
     * @param recipe Goety 仪式配方对象
     * @return 三态读取结果；空栈由调用方判定为数据异常
     */
    FieldRead<ItemStack> result(Object recipe);

    /**
     * 单次字段读取结果。
     *
     * @param status 读取状态
     * @param value  读取到的值，仅 {@link Status#OK} 时有意义
     * @param failure 调用失败的原因，仅 {@link Status#FAILED} 时非空
     * @param <T>    字段类型
     */
    record FieldRead<T>(Status status, @Nullable T value, @Nullable Throwable failure) {

        /**
         * 读取状态。
         */
        enum Status {
            /** 访问器存在且调用成功；值本身允许为 {@code null}。 */
            OK,
            /** 访问器在解析期就不存在，通常意味着版本或签名漂移。 */
            ABSENT,
            /** 访问器存在，但调用、类型转换或访问权限检查失败。 */
            FAILED
        }

        /**
         * @param value 读取到的值，允许为 {@code null}
         * @return 成功结果
         */
        static <T> FieldRead<T> ok(@Nullable T value) {
            return new FieldRead<>(Status.OK, value, null);
        }

        /**
         * @return 访问器缺失结果
         */
        static <T> FieldRead<T> absent() {
            return new FieldRead<>(Status.ABSENT, null, null);
        }

        /**
         * @param cause 失败原因
         * @return 调用失败结果
         */
        static <T> FieldRead<T> failed(Throwable cause) {
            return new FieldRead<>(Status.FAILED, null, cause);
        }

        /**
         * @return 是否读取成功
         */
        boolean ok() {
            return status == Status.OK;
        }
    }

    /**
     * @return 不读取任何字段的禁用实现
     */
    static GoetyRitualAccess unavailable() {
        return new GoetyRitualAccess() {

            @Override
            public boolean available() {
                return false;
            }

            @Override
            public FieldRead<List<Ingredient>> ingredients(Object recipe) {
                return FieldRead.absent();
            }

            @Override
            public FieldRead<Ingredient> activationItem(Object recipe) {
                return FieldRead.absent();
            }

            @Override
            public FieldRead<String> ritualType(Object recipe) {
                return FieldRead.absent();
            }

            @Override
            public FieldRead<ItemStack> result(Object recipe) {
                return FieldRead.absent();
            }
        };
    }
}
