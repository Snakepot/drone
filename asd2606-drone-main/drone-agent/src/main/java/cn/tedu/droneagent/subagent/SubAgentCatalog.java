package cn.tedu.droneagent.subagent;

import cn.tedu.droneagent.prompt.Frontmatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 子 Agent 目录：启动时加载 `classpath*:subagents/*.md`（`REQ-AGENT-0032`）。
 *
 * <p>格式与 Skill 一致：YAML frontmatter 描述元数据，**正文就是子 Agent 的系统提示词**。
 * 不再单独维护 prompt 文件，避免“定义”和“提示词”两处漂移。
 */
@Slf4j
public final class SubAgentCatalog {

    private final List<SubAgentDefinition> definitions;

    private SubAgentCatalog(List<SubAgentDefinition> definitions) {
        this.definitions = List.copyOf(definitions);
    }

    public static SubAgentCatalog empty() {
        return new SubAgentCatalog(List.of());
    }

    public static SubAgentCatalog load(String locationPattern) {
        List<SubAgentDefinition> loaded = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(locationPattern);
            for (Resource resource : resources) {
                if (!resource.isReadable() || resource.getFilename() == null
                        || !resource.getFilename().endsWith(".md")) {
                    continue;
                }
                String fileName = resource.getFilename();
                String text = resource.getContentAsString(StandardCharsets.UTF_8);
                loaded.add(parse(fileName, text));
            }
        } catch (Exception e) {
            throw new IllegalStateException("加载子 Agent 定义失败：" + e.getMessage(), e);
        }

        Set<String> names = new HashSet<>();
        for (SubAgentDefinition definition : loaded) {
            if (!names.add(definition.name())) {
                throw new IllegalArgumentException("子 Agent 名重复：" + definition.name());
            }
        }
        loaded.sort(Comparator.comparing(SubAgentDefinition::name));
        log.info("【子代理】加载完成：{} 个（{}）", loaded.size(),
                loaded.stream().map(SubAgentDefinition::name).toList());
        return new SubAgentCatalog(loaded);
    }

    /** 程序化构建（测试与动态定义）；同样做校验。 */
    public static SubAgentCatalog fromDefinitions(List<SubAgentDefinition> definitions) {
        Set<String> names = new HashSet<>();
        for (SubAgentDefinition definition : definitions) {
            validate(definition);
            if (!names.add(definition.name())) {
                throw new IllegalArgumentException("子 Agent 名重复：" + definition.name());
            }
        }
        return new SubAgentCatalog(definitions);
    }

    public List<SubAgentDefinition> all() {
        return definitions;
    }

    public Optional<SubAgentDefinition> find(String name) {
        return definitions.stream().filter(d -> d.name().equals(name)).findFirst();
    }

    private static SubAgentDefinition parse(String fileName, String content) {
        Frontmatter.Parsed parsed = Frontmatter.parse(fileName, content);
        Map<String, Object> meta = parsed.fields();
        String name = Frontmatter.string(meta, "name");
        String expectedName = fileName.endsWith(".md")
                ? fileName.substring(0, fileName.length() - 3) : fileName;
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("子 Agent「" + fileName + "」缺少 name");
        }
        if (!expectedName.equals(name)) {
            throw new IllegalArgumentException("子 Agent「" + fileName + "」的 name 必须等于文件名「"
                    + expectedName + "」，实际是「" + name + "」");
        }
        SubAgentDefinition definition = new SubAgentDefinition(
                name,
                Frontmatter.string(meta, "description"),
                Frontmatter.list(meta, "tools"),
                Frontmatter.positiveInt(meta, "max_rounds"),
                Frontmatter.positiveInt(meta, "timeout_seconds"),
                Boolean.parseBoolean(Frontmatter.string(meta, "read_only")),
                parsed.body());
        validate(definition);
        return definition;
    }

    private static void validate(SubAgentDefinition definition) {
        if (definition.name() == null || definition.name().isBlank()) {
            throw new IllegalArgumentException("子 Agent 缺少 name");
        }
        if (!definition.readOnly()) {
            throw new IllegalArgumentException("子 Agent「" + definition.name()
                    + "」在 P0 必须是只读（read_only: true）");
        }
        if (definition.tools().isEmpty()) {
            throw new IllegalArgumentException("子 Agent「" + definition.name() + "」没有工具白名单");
        }
        if (definition.maxRounds() < 1 || definition.maxRounds() > 8) {
            throw new IllegalArgumentException("子 Agent「" + definition.name()
                    + "」max_rounds 必须在 1~8");
        }
        if (definition.timeoutSeconds() < 5 || definition.timeoutSeconds() > 300) {
            throw new IllegalArgumentException("子 Agent「" + definition.name()
                    + "」timeout_seconds 必须在 5~300");
        }
        if (definition.prompt() == null || definition.prompt().isBlank()) {
            throw new IllegalArgumentException("子 Agent「" + definition.name() + "」正文不能为空");
        }
        if (definition.prompt().length() > 20000) {
            throw new IllegalArgumentException("子 Agent「" + definition.name()
                    + "」正文过长（" + definition.prompt().length() + "），上限 20000");
        }
    }
}
