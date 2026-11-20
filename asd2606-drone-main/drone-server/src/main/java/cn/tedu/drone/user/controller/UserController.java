package cn.tedu.drone.user.controller;

import cn.tedu.drone.common.BusinessException;
import cn.tedu.drone.common.ImportResultVO;
import cn.tedu.drone.common.JsonResult;
import cn.tedu.drone.common.LoginUser;
import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.common.RequirePermission;
import cn.tedu.drone.common.UserContext;
import cn.tedu.drone.user.pojo.RoleVO;
import cn.tedu.drone.user.pojo.UserLoginParam;
import cn.tedu.drone.user.pojo.UserLoginVO;
import cn.tedu.drone.user.pojo.UserQueryParam;
import cn.tedu.drone.user.pojo.UserSaveParam;
import cn.tedu.drone.user.pojo.UserStatusParam;
import cn.tedu.drone.user.pojo.UserVO;
import cn.tedu.drone.user.service.UserExcelService;
import cn.tedu.drone.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 用户模块接口。
 *
 * <h2>这几个接口在「要不要登录」上的区别（重要）</h2>
 * <ul>
 *   <li>{@code POST /user/login}：<b>不需要登录</b>。用户还没登录，当然带不了 Token，
 *       所以它在 {@link cn.tedu.drone.config.WebMvcConfig} 的白名单里</li>
 *   <li>{@code GET /user/health}、{@code GET /user/me}：<b>需要登录</b>。
 *       请求头必须带 {@code Authorization: Bearer xxx.yyy.zzz}，否则被拦截器返回 401</li>
 * </ul>
 *
 * @see cn.tedu.drone.interceptor.AuthInterceptor 负责校验 Token 的拦截器
 */
@Slf4j
@Tag(name = "用户模块")
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private UserExcelService userExcelService;

    /**
     * 模块健康检查。
     *
     * <p>注意这个接口也需要登录（不在白名单里）。
     * 如果以后要接监控探针，记得把它加进 {@code WebMvcConfig} 的白名单。
     */
    @Operation(summary = "模块健康检查")
    @GetMapping("/health")
    public JsonResult<String> health() {
        return JsonResult.ok("user 模块就绪");
    }

    /**
     * 用户登录。
     *
     * <p>调用链：本方法 → {@link UserService#login} → 查库比对密码 → 签发 Token → 返回给前端。
     * 前端拿到 Token 后会存进 localStorage，之后每个请求都带上它。
     *
     * <p>登录失败时返回的 HTTP 状态码是 200，但响应体里的 {@code state} 是 500，
     * 并带有具体的错误提示 —— 这是本项目统一的返回风格。
     */
    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public JsonResult<UserLoginVO> login(@RequestBody UserLoginParam param) {
        return JsonResult.ok("登录成功", userService.login(param));
    }

    /**
     * 员工列表（支持检索与分页）。
     *
     * <p>查询条件：关键字（模糊匹配 姓名/账号/手机号）、角色编码、账号状态；
     * 都不传就是查全部。导出接口复用同一套条件，保证「先筛选、再导出」结果一致。
     *
     * <p>注意方法上多了 {@link RequirePermission}：只有拥有 {@code staff:list} 权限的账号才能调。
     * 权限不足时返回的是 <b>403</b> 而不是 401 ——「你登录了，但你的角色没有这个权限」。
     * 前端员工菜单的显隐也依赖同一个权限码。
     */
    @Operation(summary = "员工列表")
    @RequirePermission("staff:list")
    @GetMapping("/list")
    public JsonResult<PageResult<UserVO>> list(UserQueryParam param) {
        log.info("Controller [{}] 查询员工列表，条件={}", UserContext.threadLabel(), param);
        return JsonResult.ok(userService.pageUsers(param));
    }

    /**
     * 角色选项，供员工管理页的「角色」筛选下拉使用。
     *
     * <p>只要求 {@code staff:list}，不复用权限管理的角色接口 ——
     * 「能看员工列表」不该被要求先获得权限管理的配置权限。
     */
    @Operation(summary = "角色选项")
    @RequirePermission("staff:list")
    @GetMapping("/role-options")
    public JsonResult<List<RoleVO>> roleOptions() {
        return JsonResult.ok(userService.listRoleOptions());
    }

    /**
     * 下载员工导入模版。
     *
     * <p>返回的是文件流而不是 JSON —— 这类接口不能用 {@link JsonResult} 包装，
     * 前端拿到的是 blob，需要按文件下载处理。
     */
    @Operation(summary = "下载员工导入模版")
    @RequirePermission("staff:import")
    @GetMapping("/import/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        prepareExcelResponse(response, "员工导入模版.xlsx");
        userExcelService.writeImportTemplate(response.getOutputStream());
    }

    /**
     * 导入员工。
     *
     * <p>不会因为某几行有问题就整体失败：成功的照常入库，
     * 失败的在返回结果里逐条列出（行号 + 原因），用户改完重新导那几行即可。
     *
     * <p>当前是同步实现（上传后等处理完再返回）。数据量到百万级、
     * 或耗时超过网关超时时，应改为「上传即返回任务 ID + 前端轮询进度」，
     * 方案见 {@code docs/Excel导入导出.md}。
     */
    @Operation(summary = "导入员工")
    @RequirePermission("staff:import")
    @PostMapping("/import")
    public JsonResult<ImportResultVO> importUsers(@RequestParam("file") MultipartFile file) {
        log.info("Controller [{}] 导入员工，文件名={}", UserContext.threadLabel(), file.getOriginalFilename());
        return JsonResult.ok("导入完成", userExcelService.importUsers(file));
    }

    /**
     * 按条件导出员工。
     *
     * <p>查询条件与列表接口完全一致（但忽略分页参数），
     * 所以「先筛选、再导出」拿到的就是页面上那批数据。
     */
    @Operation(summary = "按条件导出员工")
    @RequirePermission("staff:export")
    @GetMapping("/export")
    public void exportUsers(UserQueryParam param, HttpServletResponse response) throws IOException {
        log.info("Controller [{}] 导出员工，条件={}", UserContext.threadLabel(), param);
        prepareExcelResponse(response, "员工列表.xlsx");
        userExcelService.exportUsers(param, response.getOutputStream());
    }

    /**
     * 新增员工。
     */
    @Operation(summary = "新增员工")
    @RequirePermission("staff:add")
    @PostMapping("/add")
    public JsonResult<Void> add(@RequestBody UserSaveParam param) {
        log.info("Controller [{}] 新增员工，账号={}", UserContext.threadLabel(), param.getUsername());
        userService.addUser(param);
        return JsonResult.ok("新增成功", null);
    }

    /**
     * 编辑员工。
     *
     * <p>登录账号不可修改（它是登录凭证，改了用户自己都不知道该怎么登）；
     * 密码与角色留空表示保持原样。
     */
    @Operation(summary = "编辑员工")
    @RequirePermission("staff:edit")
    @PostMapping("/update")
    public JsonResult<Void> update(@RequestBody UserSaveParam param) {
        log.info("Controller [{}] 编辑员工，id={}", UserContext.threadLabel(), param.getId());
        userService.updateUser(param);
        return JsonResult.ok("保存成功", null);
    }

    /**
     * 启用或禁用员工。
     *
     * <p>传的是「目标状态」而不是「翻转」—— 即使用户连点两次，结果也是确定的。
     */
    @Operation(summary = "启用或禁用员工")
    @RequirePermission("staff:toggle")
    @PostMapping("/toggle")
    public JsonResult<Void> toggle(@RequestBody UserStatusParam param) {
        log.info("Controller [{}] 变更员工状态，id={}，目标状态={}",
                UserContext.threadLabel(), param.getId(), param.getStatus());
        userService.changeStatus(param.getId(), param.getStatus());
        return JsonResult.ok("操作成功", null);
    }

    /**
     * 员工详情（含角色），供编辑弹窗回显。
     *
     * <p>列表接口刻意不带角色（避免 N+1），打开编辑弹窗时再按需查这一个用户。
     */
    @Operation(summary = "员工详情")
    @RequirePermission("staff:list")
    @GetMapping("/{id}")
    public JsonResult<UserVO> detail(@PathVariable Long id) {
        return JsonResult.ok(userService.getUserDetail(id));
    }

    /**
     * 统一设置 Excel 下载的响应头。
     *
     * <p>文件名用 RFC 5987 的 {@code filename*=UTF-8''} 写法 ——
     * 直接写中文文件名在部分浏览器会乱码，这种写法各浏览器都认。
     */
    private void prepareExcelResponse(HttpServletResponse response, String filename) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8));
    }

    /**
     * 获取当前登录用户。
     *
     * <p>这里分两步拿到「我是谁」：
     * <ol>
     *   <li>拦截器校验 Token 后已经把用户 ID 放进了 {@link UserContext}，所以本方法不需要任何入参</li>
     *   <li>再按这个 ID 去数据库查一次，返回完整资料（含角色与权限）</li>
     * </ol>
     *
     * <p>为什么不直接返回 Token 里的信息？因为 Token 是登录那一刻的快照，
     * 用户后来改了姓名、头像，Token 里还是旧的。前端每次进页面调一次本接口就能拿到最新的，
     * 顺带还能验证本地 Token 是否仍然有效。
     */
    @Operation(summary = "获取当前登录用户")
    @GetMapping("/me")
    public JsonResult<UserVO> me() {
        log.info("Controller [{}] 处理「获取当前登录用户」，从 ThreadLocal 里取登录人",
                UserContext.threadLabel());

        LoginUser loginUser = UserContext.get();
        if (loginUser == null) {
            // 正常情况下拦截器已保证这里不为空，这里做兜底防御
            throw new BusinessException(401, "未登录，请先登录");
        }

        log.info("Controller [{}] 取到 userId={}，转交 Service 查库",
                UserContext.threadLabel(), loginUser.getId());
        return JsonResult.ok(userService.getCurrentUser(loginUser.getId()));
    }

    /**
     * 退出登录。
     *
     * <p><b>这个接口目前只做两件事：记录一条审计日志、返回成功。</b>
     * 真正让本地登录状态消失的，是前端在收到成功响应后清掉自己存的 token。
     *
     * <p>为什么服务端不能顺手让 Token 失效？因为 JWT 是<b>无状态</b>的 ——
     * 服务端压根没保存过它，也就没有「删掉」的动作可做。Token 在过期之前依然合法，
     * 只是前端不再带着它了。
     *
     * <p>如果要做到「退出后这个 Token 立刻不能再访问任何接口」，需要引入外部状态，
     * 常见做法是黑名单（把失效的 Token 存 Redis）或版本号（见
     * {@code docs/登录与Token.md} 里「第四步：主动失效」）。
     *
     * <p>本接口需要登录才能调用（不在白名单里），这样才能在日志里记下「是谁退出的」。
     */
    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public JsonResult<Void> logout() {
        LoginUser loginUser = UserContext.get();
        log.info("用户退出登录，userId={}，用户名={}",
                loginUser == null ? null : loginUser.getId(),
                loginUser == null ? null : loginUser.getUsername());
        return JsonResult.<Void>ok("已退出登录", null);
    }
}
