package cn.tedu.droneagent.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 提示词的**注册处**：启动时把 {@code prompts/} 下的 md 全部读进来、解析、校验，按名字供上层取用。
 *
 * <h2>为什么要它</h2>
 * 以前提示词一半在 yml、一半硬编码在 Java 里：改措辞要么改配置、要么改代码，新增提示词没有统一去处，
 * 写坏了要到运行时才暴露。收口到这里以后，**加一条提示词 = 加一个 md 文件**，代码不用动。
 *
 * <h2>启动期就把问题拦下（fail fast）</h2>
 * 下列任一情况都**直接让服务启动失败**，并指明是哪条、缺什么：目录为空、正文为空、
 * frontmatter 缺失或没结束、`name` 与文件路径不符、缺 `version`、`vars` 与正文占位符对不上、名字重复。
 * 参照 `ToolRegistry` 的做法：配置类的东西写错了，就不该让它跑起来。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不决定用哪条提示词</b>：取用是上层的事（如 {@code ChatService} 取 {@code assistant.system}）。</li>
 *   <li><b>不做版本回滚 / A/B</b>：版本只用来「知道现在用的是哪版」。</li>
 *   <li><b>不打正文进日志</b>：提示词含业务口径，日志只留「名字@版本」。</li>
 * </ul>
 */
@Slf4j
public class PromptCatalog {

    /** 相对路径的前缀：`prompts/a/b.md` → 去掉它 → `a/b.md` */
    private static final String BASE = "prompts/";

    private static final String SUFFIX = ".md";

    /** frontmatter 的分隔行 */
    private static final String DELIMITER = "---";

    private final Map<String, PromptDefinition> prompts;

    /**
     * 生产用：扫 classpath 上的提示词文件并加载。
     *
     * @param locationPattern 形如 {@code classpath*:prompts/**\/*.md}
     */
    public static PromptCatalog load(String locationPattern) {
        log.info("【提示词】开始加载：位置={}", locationPattern);

        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(locationPattern);
        } catch (IOException e) {
            throw new UncheckedIOException("读取提示词目录失败：" + locationPattern, e);
        }
        Map<String, String> files = new LinkedHashMap<>();
        for (Resource resource : resources) {
            try {
                files.put(relativePath(resource), resource.getContentAsString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("读取提示词文件失败：" + resource, e);
            }
        }
        log.info("【提示词】扫到 {} 个文件：{}", files.size(), files.keySet());
        return new PromptCatalog(files);
    }

    /**
     * 用「相对路径 → 文件正文」构造。**校验不通过直接抛异常**（调用发生在启动期，即 fail fast）。
     *
     * <p>包可见之外也公开，是为了让测试能直接喂内容，不必真造文件。
     *
     * @param files key 是相对 {@code prompts/} 的路径（如 {@code assistant/system.md}）
     */
    public PromptCatalog(Map<String, String> files) {
        Map<String, PromptDefinition> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            PromptDefinition definition = parse(file.getKey(), file.getValue());
            if (parsed.putIfAbsent(definition.name(), definition) != null) {
                throw new IllegalStateException("提示词名重复：「" + definition.name() + "」（来自 " + file.getKey() + "）");
            }
            // 逐条记「哪个文件 → 哪条提示词、什么版本、要哪些变量」——**正文绝不打**（含业务口径，不该外泄）
            log.info("【提示词】已加载 {} → {}@{}（vars={}）",
                    file.getKey(), definition.name(), definition.version(), definition.vars());
        }
        if (parsed.isEmpty()) {
            throw new IllegalStateException("没有加载到任何提示词：prompts/ 目录为空或路径不对");
        }
        this.prompts = parsed;
        log.info("【提示词】加载完成：共 {} 条 {}", parsed.size(), parsed.values().stream()
                .map(prompt -> prompt.name() + "@" + prompt.version())
                .collect(Collectors.joining("、")));
    }

    /** 取一条提示词的正文 */
    public String text(String name) {
        return get(name).body();
    }

    /** 取一条提示词并填好占位符；缺变量抛错 */
    public String render(String name, Map<String, Object> values) {
        return get(name).render(values);
    }

    /** 已加载的提示词名（供测试与观测） */
    public Set<String> names() {
        return Collections.unmodifiableSet(prompts.keySet());
    }

    /** 名字 → 版本（供测试与观测；启动日志里的就是它） */
    public Map<String, Integer> versions() {
        Map<String, Integer> versions = new LinkedHashMap<>();
        prompts.forEach((name, prompt) -> versions.put(name, prompt.version()));
        return Collections.unmodifiableMap(versions);
    }

    private PromptDefinition get(String name) {
        PromptDefinition definition = prompts.get(name);
        if (definition == null) {
            throw new IllegalStateException("没有名为「" + name + "」的提示词");
        }
        return definition;
    }

    // ---------------- 解析与校验 ----------------

    /**
     * 解析一个提示词文件。**任何不合格都抛异常**。
     *
     * <p>文件形态：首行 {@code ---}、frontmatter、再一行 {@code ---}，之后到文件末尾就是**正文**。
     * frontmatter 只支持三种写法（够我们用了，所以不引 YAML 库）：
     * {@code key: 值}、{@code key:} 后跟若干 {@code - 列表项}、{@code key: [a, b]}。
     */
    static PromptDefinition parse(String relativePath, String content) {
        String name = nameOf(relativePath);
        Frontmatter.Parsed parsed = Frontmatter.parse(relativePath, content);
        Map<String, Object> meta = parsed.fields();
        String body = parsed.body();

        String declaredName = Frontmatter.string(meta, "name");
        if (!name.equals(declaredName)) {
            throw invalid(relativePath, "frontmatter 的 name 必须与文件位置一致（应为「" + name
                    + "」），实际是「" + declaredName + "」");
        }
        if (body.isEmpty()) {
            throw invalid(relativePath, "正文不能为空");
        }
        int version = Frontmatter.positiveInt(meta, "version");
        if (version <= 0) {
            throw invalid(relativePath, "frontmatter 必须有正整数 version");
        }
        String purpose = Frontmatter.string(meta, "purpose");
        if (purpose == null || purpose.isBlank()) {
            throw invalid(relativePath, "frontmatter 必须有 purpose");
        }

        List<String> vars = Frontmatter.list(meta, "vars");
        List<String> changelog = Frontmatter.list(meta, "changelog");

        // vars 必须与正文里出现的占位符**完全一致**（多了、少了、拼错都算错）
        Set<String> declared = new LinkedHashSet<>(vars);
        Set<String> used = new LinkedHashSet<>(PromptDefinition.placeholdersOf(body));
        if (!declared.equals(used)) {
            throw invalid(relativePath, "vars " + declared + " 与正文占位符 " + used + " 不一致");
        }

        return new PromptDefinition(name, version, purpose, vars, changelog, body);
    }

    private static String nameOf(String relativePath) {
        String path = relativePath.replace('\\', '/');
        if (!path.endsWith(SUFFIX)) {
            throw new IllegalStateException("提示词文件必须以 " + SUFFIX + " 结尾：" + relativePath);
        }
        return path.substring(0, path.length() - SUFFIX.length()).replace('/', '.');
    }

    private static String relativePath(Resource resource) {
        try {
            String path = resource.getURL().toString().replace('\\', '/');
            int index = path.lastIndexOf(BASE);
            if (index < 0) {
                throw new IllegalStateException("提示词文件不在 " + BASE + " 下：" + resource);
            }
            return path.substring(index + BASE.length());
        } catch (IOException e) {
            throw new UncheckedIOException("读取提示词路径失败：" + resource, e);
        }
    }





    private static IllegalStateException invalid(String relativePath, String why) {
        return new IllegalStateException("提示词「" + relativePath + "」不合格：" + why);
    }
}
