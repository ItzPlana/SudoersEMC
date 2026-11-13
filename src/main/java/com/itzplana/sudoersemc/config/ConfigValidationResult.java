package com.itzplana.sudoersemc.config;

import java.util.List;

/**
 * 配置校验结果。
 *
 * <p>{@code errors} 非空即表示配置不可用；{@code warnings} 只提示风险，不影响可用性。
 * 两个列表在构造时都会被复制为不可变视图，调用方之后修改原列表不会影响本对象。</p>
 *
 * <p>为保证不变量，{@code valid = true} 且错误列表非空时会立即抛出
 * {@link IllegalArgumentException}；{@code valid = false} 且错误列表为空是允许的，
 * 用于表达校验器之外检测到的失败。</p>
 *
 * @param valid    是否通过校验
 * @param errors   阻止配置生效的错误
 * @param warnings 不阻止配置生效但需要提示的问题
 */
public record ConfigValidationResult(boolean valid, List<String> errors, List<String> warnings) {

    public ConfigValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        if (valid && !errors.isEmpty()) {
            throw new IllegalArgumentException("valid=true 时 errors 必须为空");
        }
    }

    /**
     * 构造一个通过校验、仅带警告的结果。
     *
     * @param warnings 警告列表，允许为 {@code null}（按空列表处理）
     * @return 通过校验的结果
     */
    public static ConfigValidationResult ok(List<String> warnings) {
        return new ConfigValidationResult(true, List.of(), warnings);
    }

    /**
     * 构造一个通过校验且没有任何警告的结果。
     *
     * @return 通过校验的结果
     */
    public static ConfigValidationResult ok() {
        return new ConfigValidationResult(true, List.of(), List.of());
    }

    /**
     * 构造一个未通过校验的结果。
     *
     * <p>{@code errors} 为空时结果仍标记为未通过，用于调用方明确拒绝配置但没有
     * 具体错误信息的场景。</p>
     *
     * @param errors   错误列表，允许为 {@code null}（按空列表处理）
     * @param warnings 警告列表，允许为 {@code null}（按空列表处理）
     * @return 未通过校验的结果
     */
    public static ConfigValidationResult failed(List<String> errors, List<String> warnings) {
        return new ConfigValidationResult(false, errors, warnings);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    /**
     * 生成面向日志或聊天界面的多行中文摘要。
     *
     * <p>通过校验且无警告时只有一行；有警告或错误时，每条形如
     * {@code "  [警告] ..."} 或 {@code "  [错误] ..."}，错误总是排在警告之前。</p>
     *
     * @return 多行中文摘要
     */
    public String format() {
        StringBuilder builder = new StringBuilder();
        if (valid) {
            if (warnings.isEmpty()) {
                return "配置校验通过";
            }
            builder.append("配置校验通过（").append(warnings.size()).append(" 条警告）");
            for (String warning : warnings) {
                builder.append('\n').append("  [警告] ").append(warning);
            }
            return builder.toString();
        }

        builder.append("配置校验失败（").append(errors.size()).append(" 个错误，")
                .append(warnings.size()).append(" 条警告）");
        for (String error : errors) {
            builder.append('\n').append("  [错误] ").append(error);
        }
        for (String warning : warnings) {
            builder.append('\n').append("  [警告] ").append(warning);
        }
        return builder.toString();
    }
}
