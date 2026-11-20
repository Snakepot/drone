package cn.tedu.droneagent.skill;

import cn.tedu.droneagent.prompt.Frontmatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Skill 目录：启动时扫描 `classpath*:skills/*` 下的 {@code SKILL.md}。
 *
 * <h2>启动时只解析 frontmatter，不把正文塞进上下文</h2>
 * 目录里保留每条 skill 的完整定义（正文 + 参考资料），但**只有 L1 会被渲染进系统提示词**；
 * L2/L3 由工具在需要时取。这样常驻成本与 skill 数量是「一行一个」的线性关系，
 * 而不是把每份 SOP 都塞进每次请求。
 *
 * <h2>为什么坏文件要让启动失败</h2>
 * Skill 是「业务流程资产」：缺权限声明、正文为空、目录名与 name 不符，都会让流程在执行时行为不可预期。
 * 与其上线后才发现，不如启动即失败——与 prompts 的处理一致。
 */
@Slf4j
public final class SkillCatalog {

    private static final String SKILL_FILE = "SKILL.md";

    /** 下划线开头的目录视为模板/草稿，不参与加载 */
    private static final String SKIP_PREFIX = "_";

    /** L3 允许读取的扩展名白名单（只给文本类资料；html/css 用于报表模板） */
    private static final Set<String> RESOURCE_EXTENSIONS =
            Set.of(".md", ".txt", ".json", ".csv", ".html", ".css");

    /** L3 只允许从这两个目录取：references/ 放口径资料，assets/ 放模板与素材 */
    private static final List<String> RESOURCE_DIRS = List.of("references/", "assets/");

    private final List<SkillDefinition> skills;
    private final Map<String, SkillDefinition> byName;

    private SkillCatalog(List<SkillDefinition> skills) {
        this.skills = List.copyOf(skills);
        Map<String, SkillDefinition> map = new LinkedHashMap<>();
        for (SkillDefinition skill : skills) {
            map.put(skill.name(), skill);
        }
        this.byName = Map.copyOf(map);
    }

    public static SkillCatalog empty() {
        return new SkillCatalog(List.of());
    }

    /**
     * 从 classpath 加载。
     *
     * @param locationPattern 例如 {@code classpath*:skills/**}{@code /*}
     * @param maxBodyChars    单个正文上限；超出即启动失败（细节应放进 references/）
     */
    public static SkillCatalog load(String locationPattern, int maxBodyChars) {
        Map<String, String> files = new LinkedHashMap<>();
        Map<String, String> resources = new LinkedHashMap<>();
        try {
            Resource[] found = new PathMatchingResourcePatternResolver().getResources(locationPattern);
            for (Resource resource : found) {
                if (!resource.isReadable() || resource.getFilename() == null) {
                    continue;
                }
                String relative = relativePath(resource);
                if (relative == null || relative.isBlank()) {
                    continue;
                }
                String text = resource.getContentAsString(StandardCharsets.UTF_8);
                if (relative.endsWith(SKILL_FILE)) {
                    files.put(relative, text);
                } else if (isResource(relative)) {
                    resources.put(relative, text);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("扫描 Skill 目录失败：" + e.getMessage(), e);
        }

        SkillCatalog catalog = fromContent(files, resources, maxBodyChars);
        log.info("【Skill】加载完成：{} 个（{}）", catalog.skills.size(),
                catalog.skills.stream().map(SkillDefinition::name).toList());
        return catalog;
    }

    /**
     * 从内存内容构建（供测试与加载器复用）。
     *
     * @param files     相对路径 → 文本，键形如 {@code daily-operations-report/SKILL.md}
     * @param resources 相对路径 → 文本，键形如 {@code daily-operations-report/references/x.md}
     */
    public static SkillCatalog fromContent(Map<String, String> files, Map<String, String> resources,
                                           int maxBodyChars) {
        List<SkillDefinition> built = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();

        for (Map.Entry<String, String> file : files.entrySet()) {
            String path = file.getKey().replace('\\', '/');
            if (!path.endsWith("/" + SKILL_FILE)) {
                throw new IllegalStateException("Skill 文件必须叫 " + SKILL_FILE + "：" + path);
            }
            String dir = path.substring(0, path.length() - ("/" + SKILL_FILE).length());
            if (dir.startsWith(SKIP_PREFIX)) {
                continue;
            }

            Frontmatter.Parsed parsed = Frontmatter.parse(path, file.getValue());
            Map<String, Object> meta = parsed.fields();

            String name = Frontmatter.string(meta, "name");
            if (!dir.equals(name)) {
                throw new IllegalStateException("Skill「" + path + "」的 name 必须等于目录名「"
                        + dir + "」，实际是「" + name + "」");
            }
            if (!names.add(name)) {
                throw new IllegalStateException("Skill 名重复：" + name);
            }

            String description = Frontmatter.string(meta, "description");
            if (description == null || description.isBlank()) {
                throw new IllegalStateException("Skill「" + name + "」缺少 description（L1 必须有）");
            }
            List<String> permissions = Frontmatter.list(meta, "permissions");
            if (permissions.isEmpty()) {
                throw new IllegalStateException("Skill「" + name + "」必须声明 permissions"
                        + "（否则谁可见无法判定）");
            }
            int version = Frontmatter.positiveInt(meta, "version");
            if (version <= 0) {
                throw new IllegalStateException("Skill「" + name + "」必须有正整数 version");
            }
            String body = parsed.body();
            if (body.isEmpty()) {
                throw new IllegalStateException("Skill「" + name + "」正文不能为空");
            }
            if (body.length() > maxBodyChars) {
                throw new IllegalStateException("Skill「" + name + "」正文 " + body.length()
                        + " 字符，超过上限 " + maxBodyChars + "；请把细节放到 references/");
            }

            Map<String, String> own = new TreeMap<>();
            String resourcePrefix = dir + "/";
            for (Map.Entry<String, String> resource : resources.entrySet()) {
                String key = resource.getKey().replace('\\', '/');
                for (String dirName : RESOURCE_DIRS) {
                    if (key.startsWith(resourcePrefix + dirName)) {
                        own.put(key.substring(resourcePrefix.length()), resource.getValue());
                        break;
                    }
                }
            }

            built.add(new SkillDefinition(name, description,
                    Frontmatter.string(meta, "when_to_use"),
                    version, Frontmatter.string(meta, "owner"),
                    permissions, Frontmatter.list(meta, "tools"),
                    body, own, path));
        }

        built.sort((a, b) -> a.name().compareTo(b.name()));
        return new SkillCatalog(built);
    }

    /** 全部 skill（含当前用户无权使用的），供排障与校验 */
    public List<SkillDefinition> all() {
        return skills;
    }

    /** 当前用户可见的 skill */
    public List<SkillDefinition> visibleTo(Set<String> permissions) {
        return skills.stream().filter(skill -> skill.visibleTo(permissions)).toList();
    }

    /**
     * 按名取**可见**的 skill；不存在与无权返回同一个 empty，调用方对外应给同一句文案。
     */
    public Optional<SkillDefinition> findVisible(String name, Set<String> permissions) {
        SkillDefinition skill = byName.get(name);
        return skill != null && skill.visibleTo(permissions) ? Optional.of(skill) : Optional.empty();
    }

    /** 按名取（不校验权限），仅用于日志与校验，**不要用它给模型返回内容** */
    public Optional<SkillDefinition> findAny(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * L1 文本：每个 skill 一行。没有可见项时给一句明确的「暂无」，
     * 避免模型以为「系统没接上」。
     */
    public String l1Text(Set<String> permissions) {
        List<SkillDefinition> visible = visibleTo(permissions);
        if (visible.isEmpty()) {
            return "（当前账号没有可用的标准化流程）";
        }
        StringBuilder out = new StringBuilder();
        for (SkillDefinition skill : visible) {
            out.append("- `").append(skill.name()).append("`：").append(skill.description());
            if (skill.whenToUse() != null && !skill.whenToUse().isBlank()) {
                out.append("（适用：").append(skill.whenToUse()).append("）");
            }
            out.append('\n');
        }
        return out.toString().stripTrailing();
    }

    /** 路径是否落在允许的 L3 目录（references/ 或 assets/）下 */
    public static boolean isResource(String path) {
        String normalized = path.replace('\\', '/');
        return RESOURCE_DIRS.stream().anyMatch(normalized::contains);
    }

    /** 资源扩展名是否在白名单内（L3 只给文本类资料） */
    public static boolean isAllowedResource(String path) {
        String lower = path.toLowerCase();
        return RESOURCE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static String relativePath(Resource resource) {
        try {
            String url = resource.getURL().toString();
            int at = url.lastIndexOf("skills/");
            if (at < 0) {
                return null;
            }
            return url.substring(at + "skills/".length());
        } catch (Exception e) {
            return null;
        }
    }
}
