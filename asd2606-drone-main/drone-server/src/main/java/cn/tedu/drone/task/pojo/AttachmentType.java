package cn.tedu.drone.task.pojo;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 附件的类型词汇表 —— 对应 {@code task_attachment.file_type} 列。
 *
 * <h2>它管三件事</h2>
 * <ol>
 *   <li><b>白名单</b>：只允许登记过的扩展名。<b>这是安全边界，不是体验问题</b> ——
 *       不设白名单的话，传一个 {@code .jsp} / {@code .sh} / {@code .html}
 *       上去就是一个隐患（将来某天目录被当成静态资源服务、或者有人从浏览器直接打开）</li>
 *   <li><b>分类</b>：扩展名 → {@code image} / {@code video} / {@code log}，
 *       界面按类别决定怎么展示（照片给缩略图、日志给下载）</li>
 *   <li><b>分类型的大小上限</b>：手机原图 20MB、视频 500MB、飞行日志 200MB。
 *       为什么不统一设一个上限：给视频的那个上限放到照片上等于没有限制，
 *       而给照片的上限放到视频上就没法用了</li>
 * </ol>
 *
 * <h2>为什么上限不在 {@code application.yml} 的 multipart 里配</h2>
 * 那个配置是<b>全局</b>的（Spring 只认一个 {@code max-file-size}），
 * 分不了类型。所以全局按最大的那类（视频）设，细分的在业务代码里判 ——
 * 判据就是本类。
 *
 * <h2>这个类不做的事</h2>
 * <b>不校验文件的真实内容</b>。扩展名是可以随便改的，把 {@code .exe} 改成 {@code .jpg}
 * 一样能传上来。要真验就得读文件头（magic number），那是另一个层次的防护，
 * 而且对「飞手传现场照片」这个场景收益很低 —— 拿到附件的人都是内部已登录用户。
 * 这条边界要清楚：<b>白名单挡的是「误传可执行文件」，不是「恶意构造的文件」</b>。
 */
public final class AttachmentType {

    private AttachmentType() {
    }

    public static final String IMAGE = "image";
    public static final String VIDEO = "video";
    public static final String LOG = "log";

    /** 扩展名（全小写，不含点） → 类别 */
    private static final Map<String, String> EXTENSION_TYPES = Map.ofEntries(
            // 照片：heic 是 iPhone 的默认格式，不收的话飞手得先去改设置，很容易劝退
            Map.entry("jpg", IMAGE),
            Map.entry("jpeg", IMAGE),
            Map.entry("png", IMAGE),
            Map.entry("webp", IMAGE),
            Map.entry("heic", IMAGE),
            // 视频：现场拍一段「飞机是怎么掉下来的」比十张照片都说明问题
            Map.entry("mp4", VIDEO),
            Map.entry("mov", VIDEO),
            // 飞行日志：bin 和 ulg 是大疆的两种格式，txt/log 是通用的文本导出
            Map.entry("bin", LOG),
            Map.entry("ulg", LOG),
            Map.entry("txt", LOG),
            Map.entry("log", LOG));

    /** 分类型大小上限（字节）。数值取自设计文档 7.3 节 */
    private static final Map<String, Long> MAX_SIZES = Map.of(
            IMAGE, 20L * 1024 * 1024,
            VIDEO, 500L * 1024 * 1024,
            LOG, 200L * 1024 * 1024);

    /**
     * 扩展名 → MIME 类型。
     *
     * <p><b>为什么不直接信 {@code MultipartFile.getContentType()}</b>：
     * 那是客户端自己填的，可以随便写。这里按扩展名给出确定的答案，
     * 让下载时的 {@code Content-Type} 来自我们自己的表，而不是来自上传者的输入。
     *
     * <p>找不到时返回 null，由下载接口退回 {@code application/octet-stream}。
     * 配合 {@code Content-Disposition: attachment}，就算有人把网页文件改名成
     * {@code .jpg} 传上来，浏览器也只会下载它、不会当场渲染。
     */
    private static final Map<String, String> EXTENSION_MIMES = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("webp", "image/webp"),
            Map.entry("heic", "image/heic"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("bin", "application/octet-stream"),
            Map.entry("ulg", "application/octet-stream"),
            Map.entry("txt", "text/plain"),
            Map.entry("log", "text/plain"));

    /** 全部类别，顺序固定（界面与文档里的顺序都以它为准） */
    public static final List<String> ALL = List.of(IMAGE, VIDEO, LOG);

    /**
     * 取扩展名对应的类别；<b>不在白名单里返回 null</b>。
     *
     * <p>调用方拿到 null 就应当拒收。刻意不给一个「other」兜底：
     * 一旦有兜底，白名单就退化成「什么都能传」，那这道防线就白设了。
     */
    public static String ofFileName(String fileName) {
        String extension = extensionOf(fileName);
        return extension == null ? null : EXTENSION_TYPES.get(extension);
    }

    /**
     * 从文件名里取扩展名（小写、不含点）；取不到返回 null。
     *
     * <p>{@code lastIndexOf('.')} 之前要判一下这个点不是第一个字符：
     * {@code .gitignore} 这种「点开头的隐藏文件」会被误当成扩展名 {@code gitignore}。
     * 这类文件传不上来（不在白名单），但错误信息会变得莫名其妙。
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 该类别的大小上限（字节）；类别未知时返回 0（调用方应当先判类别合法） */
    public static long maxSizeOf(String type) {
        return MAX_SIZES.getOrDefault(type, 0L);
    }

    /** 按扩展名给出确定的 MIME 类型；认不出来返回 null */
    public static String mimeTypeOf(String fileName) {
        String extension = extensionOf(fileName);
        return extension == null ? null : EXTENSION_MIMES.get(extension);
    }

    /** 白名单里的全部扩展名，供错误提示与文档使用 */
    public static Set<String> allowedExtensions() {
        return EXTENSION_TYPES.keySet();
    }

    /** 人类可读的类型名，用于错误提示 */
    public static String label(String type) {
        if (IMAGE.equals(type)) {
            return "照片";
        }
        if (VIDEO.equals(type)) {
            return "视频";
        }
        if (LOG.equals(type)) {
            return "飞行日志";
        }
        return type;
    }
}
