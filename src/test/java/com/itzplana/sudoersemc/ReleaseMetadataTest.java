package com.itzplana.sudoersemc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * 发布元数据一致性测试。
 *
 * <p>防止 {@code gradle.properties}、{@code mods.toml}、{@code LICENSE} 与 README
 * 在后续修改中再次出现许可或版本声明冲突；同时锁定本机 JDK 路径不得提交。</p>
 */
class ReleaseMetadataTest {

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("gradle.properties"))
                    && Files.isRegularFile(candidate.resolve("LICENSE"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("无法从 " + current + " 定位项目根目录");
    }

    private static Properties gradleProperties() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(
                projectRoot().resolve("gradle.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    @Test
    void licenseMetadataMatchesLicenseAndReadme() throws IOException {
        Properties properties = gradleProperties();

        assertEquals("CC BY-NC-SA 4.0", properties.getProperty("mod_license"),
                "mods.toml 的 license 占位符来自该属性");
        String license = Files.readString(projectRoot().resolve("LICENSE"), StandardCharsets.UTF_8);
        assertTrue(license.contains("Attribution-NonCommercial-ShareAlike 4.0 International"),
                "LICENSE 必须是 CC BY-NC-SA 4.0 全文");
        String readme = Files.readString(projectRoot().resolve("README.md"), StandardCharsets.UTF_8);
        assertTrue(readme.contains("CC BY-NC-SA 4.0"), "README 必须声明与 LICENSE 一致的许可");
        assertFalse(readme.contains("MIT License"), "README 不得残留 MIT 许可声明");
    }

    @Test
    void versionAndResourcePlaceholdersAreConsistent() throws IOException {
        Properties properties = gradleProperties();
        String version = properties.getProperty("mod_version");

        assertNotNull(version, "gradle.properties 必须定义 mod_version");
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+([-.+].+)?"),
                "mod_version 应为语义化版本，实际为 " + version);
        assertEquals("sudoersemc", properties.getProperty("mod_id"));

        String modsToml = Files.readString(
                projectRoot().resolve("src/main/resources/META-INF/mods.toml"), StandardCharsets.UTF_8);
        assertTrue(modsToml.contains("version=\"${mod_version}\""),
                "mods.toml 的版本必须由 Gradle 展开");
        assertTrue(modsToml.contains("license=\"${mod_license}\""),
                "mods.toml 的许可必须由 Gradle 展开");
    }

    @Test
    void localJdkPathIsNotCommitted() throws IOException {
        String text = Files.readString(projectRoot().resolve("gradle.properties"), StandardCharsets.UTF_8);

        assertNull(gradleProperties().getProperty("org.gradle.java.installations.paths"),
                "不得提交本机 JDK 安装路径");
        assertFalse(text.contains("/home/"), "gradle.properties 不应包含本机绝对路径");
    }
}
