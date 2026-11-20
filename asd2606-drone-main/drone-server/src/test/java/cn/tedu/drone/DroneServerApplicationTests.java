package cn.tedu.drone;

import cn.tedu.drone.common.CoordinateUtil;
import cn.tedu.drone.common.JwtUtil;
import cn.tedu.drone.common.storage.LocalStorageService;
import cn.tedu.drone.analytics.dao.StatDailyDao;
import cn.tedu.drone.analytics.service.StatDailyService;
import cn.tedu.drone.task.dao.DispatchBlockDao;
import cn.tedu.drone.task.dao.TaskDao;
import cn.tedu.drone.task.dispatch.DispatchBlockPO;
import cn.tedu.drone.task.dispatch.DispatchBlockReason;
import cn.tedu.drone.task.mqtt.DeviceMessage;
import cn.tedu.drone.task.mqtt.TelemetryEnvelope;
import cn.tedu.drone.task.mqtt.TelemetryHandler;
import cn.tedu.drone.task.pojo.AlertCode;
import cn.tedu.drone.task.pojo.AlertSeverity;
import cn.tedu.drone.task.pojo.AttachmentType;
import cn.tedu.drone.task.pojo.TaskDispatchOptionVO;
import cn.tedu.drone.task.pojo.TaskPO;
import cn.tedu.drone.task.service.DispatchBlockService;
import cn.tedu.drone.task.service.TaskService;
import cn.tedu.drone.user.pojo.UserImportDTO;
import cn.tedu.drone.user.pojo.UserPO;
import com.alibaba.excel.EasyExcel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        // 关掉派发超时释放的定时任务。
        // 它跑在独立线程、独立事务里，不受本类 @Transactional 的约束，
        // 会在跑测试的过程中直接把改动提交到开发库。
        // 释放逻辑本身改为直接调用 releaseExpiredDispatched() 来验证。
        "drone.task.auto-release.enabled=false",
        // 关掉 MQTT 设备数据接入。
        // 本类测的是接口与业务规则，不需要真的去连本地 broker；
        // 更要紧的是「证据过期扫描」定时任务也归这个开关管 ——
        // 它和上面的释放任务一样跑在独立线程、独立事务里，
        // 不关掉就会在测试过程中偷偷改开发库的数据。
        // MQTT 与证据链路本身另有一组专门的用例（TaskEvidenceTests）。
        "drone.mqtt.enabled=false",
        // 附件写到 target 下的临时目录，别落在仓库根目录的 ./data/upload 里。
        // **必须显式指定**：@Transactional 只能回滚数据库，回滚不了磁盘 ——
        // 不换目录的话，每跑一次上传用例就会在项目里留下一个真文件。
        // 用例自己再用 LocalStorageService.delete 把文件删掉（见 uploadShouldPersistFileAndSha256）
        "drone.upload.root=./target/test-upload",
        // 大屏缓存：测试里压到 1 秒，好验证「同 TTL 内不重算、过了 TTL 会重算」
        "drone.screen.summary-cache-seconds=1",
        "drone.screen.recent-cache-seconds=1",
        // 关掉「待派发归因」的定时任务，理由与上面的超时释放完全一样：
        // 它跑在独立线程、独立事务里，会在用例跑的过程中往 task_dispatch_block 写数据。
        // 采样逻辑本身改为直接调用 DispatchBlockService.samplePendingTasks() 来验证。
        "drone.task.dispatch-block.enabled=false",
        // 关掉预聚合补算任务，理由同上：它会往 stat_daily 写数据、且不受本类事务约束。
        // 补算逻辑改为直接调用 StatDailyService.rebuildDay / ensureUpToDate 来验证。
        "drone.analytics.stat-daily.enabled=false"
})
@AutoConfigureMockMvc
@Transactional   // 本类里有会改库的用例（新增角色等），跑完自动回滚，不污染开发库
class DroneServerApplicationTests {

    /** 与 drone 库 user 表中的现有数据保持一致 */
    private static final String EXISTING_USERNAME = "13800001001";
    private static final String EXISTING_PASSWORD = "123456";
    /** 管理员账号（刘洋），用于验证 RBAC 放行 */
    private static final String ADMIN_USERNAME = "13800001005";
    private static final String AUTH_HEADER = "Authorization";

    /**
     * 账号 ID 与设备 ID，与 drone 库的种子数据对应（见 docs/sql/data.sql）。
     *
     * <p>只留下「用来验证某条校验规则」的那几个，且都断言的是<b>不会变的事实</b>：
     * 李明是飞手但账号禁用、赵强是机务不是飞手、UAV-001 存在。
     * 需要「能派成功」的用例一律现造人和设备 —— 见 {@link #dispatchShouldAssignPilotAndDroneAndWriteLog}
     * 上面的说明，原因是开发库会被人手工操作，依赖它的当前状态必然不稳。
     */
    private static final int PILOT_LIMING_DISABLED = 2;  // 李明，是飞手但账号已禁用
    private static final int MAINTAINER_ZHAOQIANG = 4;   // 赵强，机务：启用但不是飞手
    private static final int DRONE_UAV_001 = 1;          // 种子设备，仅用于「人和设备都会失败」的用例

    /** 接口收发时间用的格式，与 TaskCreateParam 上的 @JsonFormat 一致 */
    private static final DateTimeFormatter API_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    /** 撤回派发那类用例要直接查库验证「指派被清成了 NULL」，所以注入 DAO */
    @Autowired
    private TaskDao taskDao;

    /**
     * 超时释放逻辑直接调 Service 验证 —— 定时任务本身在测试里被关掉了，
     * 理由见类上的 @SpringBootTest 注释。
     */
    @Autowired
    private TaskService taskService;

    /**
     * 少数场景 API 造不出来（比如「执行中」的任务 —— 开始执行的接口属于 ③），
     * 用 JdbcTemplate 直接改库摆出前置状态，好让边界真的被验到。
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 设备消息的处理口。用例直接调它，跳过 MQTT 传输层 ——
     * 业务规则（校验、去重、归属、依据升级）与「消息是怎么来的」无关，
     * 直调既快又不依赖本地 broker。
     */
    @Autowired
    private TelemetryHandler telemetryHandler;

    /** 模拟订阅器解析报文用。真实链路上这一步由 Jackson 在订阅器里完成 */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 附件存储。注入的是<b>实现</b>而不是接口，因为用例要做两件接口上没有的事：
     * 拿到实际生效的根目录（断言文件真的落在配置的位置），
     * 以及用完把文件删掉 —— 磁盘不像数据库，@Transactional 回滚不了它。
     */
    @Autowired
    private LocalStorageService localStorage;

    /**
     * 待派发归因：定时任务在测试里被关掉了（见类上的 @SpringBootTest 注释），
     * 采样逻辑改为直接调它 —— 与超时释放同一个道理。
     */
    @Autowired
    private DispatchBlockService dispatchBlockService;

    /** 把采样结果读回来断言；生产代码里它只服务「同一原因不重复记」那一个判断 */
    @Autowired
    private DispatchBlockDao dispatchBlockDao;

    /**
     * 预聚合的写与补算：定时任务在测试里关掉了（见类上的 @SpringBootTest 注释），
     * 所以直接调它的 Service 来验。
     */
    @Autowired
    private StatDailyService statDailyService;

    /** 用例要直接删「派生行」摆前置状态；顺便避开 MyBatis 一级缓存（见下面补算用例的注释） */
    @Autowired
    private StatDailyDao statDailyDao;

    @Test
    void contextLoads() {
    }

    // ---------- Token 签发 ----------

    @Test
    void loginShouldReturnTokenAndUser() throws Exception {
        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EXISTING_USERNAME, EXISTING_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.user.username").value(EXISTING_USERNAME))
                .andExpect(jsonPath("$.data.user.password").doesNotExist());
    }

    @Test
    void loginTokenShouldBeParseableJwt() throws Exception {
        String token = loginAndGetToken();

        // JWT 由 header.payload.signature 三段组成
        assertThat(token.split("\\.")).hasSize(3);

        Jws<Claims> jws = jwtUtil.parseToken(token);
        Claims claims = jws.getPayload();

        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("username", String.class)).isEqualTo(EXISTING_USERNAME);
        assertThat(claims.getIssuer()).isEqualTo("drone-server");
        // 角色与权限刻意不放进 Token，改为每次请求查库（见 docs/RBAC权限设计.md）
        assertThat(claims.get("role")).isNull();
        assertThat(claims.getIssuedAt()).isNotNull();
        // 配置的有效期为 2 小时
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isEqualTo(2 * 3600_000L);
        assertThat(claims.getExpiration()).isAfter(new Date());
    }

    @Test
    void loginWithWrongPasswordShouldFail() throws Exception {
        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EXISTING_USERNAME, "wrong-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void loginWithUnknownUserShouldFail() throws Exception {
        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("not-exist-user", EXISTING_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void tamperedTokenShouldFailVerification() {
        UserPO user = new UserPO().setId(1L).setUsername(EXISTING_USERNAME);
        String token = jwtUtil.generateToken(user);

        String[] parts = token.split("\\.");
        // 换成「一定与原值不同」的字符：直接写死 'A' 有约 1/64 的概率
        // 恰好与签名首字符相同，篡改等于没篡改，测试就会偶发失败
        char head = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + head + parts[2].substring(1);

        assertThatThrownBy(() -> jwtUtil.parseToken(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedByOtherKeyShouldFailVerification() {
        String forged = Jwts.builder()
                .subject("1")
                .claim("role", "admin")
                .signWith(Keys.hmacShaKeyFor(
                        "another-secret-key-that-is-long-enough-000000".getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwtUtil.parseToken(forged)).isInstanceOf(JwtException.class);
    }

    // ---------- 拦截器校验 ----------

    @Test
    void loginIsAccessibleWithoutToken() throws Exception {
        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EXISTING_USERNAME, EXISTING_PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void apiDocsAreAccessibleWithoutToken() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpointWithoutTokenShouldReturn401() throws Exception {
        mockMvc.perform(get("/user/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.state").value(401))
                .andExpect(jsonPath("$.message").value("未登录，请先登录"));
    }

    @Test
    void protectedEndpointWithValidTokenShouldReturnCurrentUser() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(get("/user/me").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.username").value(EXISTING_USERNAME))
                .andExpect(jsonPath("$.data.name").value("张伟"))
                .andExpect(jsonPath("$.data.roles[0]").value("pilot"))
                // 用 ≥ ：以后给角色加权限不该让这个用例失败
                .andExpect(jsonPath("$.data.permissions.length()", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void protectedEndpointWithTamperedTokenShouldReturn401() throws Exception {
        String[] parts = loginAndGetToken().split("\\.");
        // 同样换成「一定不同」的字符，避免偶发通过
        char head = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + head + parts[2].substring(1);

        mockMvc.perform(get("/user/me").header(AUTH_HEADER, "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.state").value(401))
                .andExpect(jsonPath("$.message").value("登录状态已失效，请重新登录"));
    }

    @Test
    void protectedEndpointWithoutBearerPrefixShouldReturn401() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(get("/user/me").header(AUTH_HEADER, token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.state").value(401));
    }

    @Test
    void protectedEndpointWithGarbageTokenShouldReturn401() throws Exception {
        mockMvc.perform(get("/user/me").header(AUTH_HEADER, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.state").value(401));
    }

    @Test
    void healthEndpointIsAlsoProtected() throws Exception {
        mockMvc.perform(get("/user/health"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.state").value(401));

        mockMvc.perform(get("/user/health").header(AUTH_HEADER, "Bearer " + loginAndGetToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data").value("user 模块就绪"));
    }

    @Test
    void logoutShouldRequireLogin() throws Exception {
        mockMvc.perform(post("/user/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.state").value(401));
    }

    @Test
    void logoutWithValidTokenShouldSucceed() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(post("/user/logout").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.message").value("已退出登录"));
    }

    // ---------- RBAC 权限校验 ----------

    @Test
    void loginShouldReturnRolesAndPermissions() throws Exception {
        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EXISTING_USERNAME, EXISTING_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.roles[0]").value("pilot"))
                .andExpect(jsonPath("$.data.user.permissions.length()", greaterThanOrEqualTo(2)));
    }

    @Test
    void staffListShouldBeForbiddenForPilot() throws Exception {
        // 张伟是飞手，只有设备的查看权限，没有 staff:list
        String token = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/user/list").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value(403));
    }

    @Test
    void staffListShouldBeAccessibleForAdmin() throws Exception {
        // 刘洋是管理员，拥有全部权限
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/user/list").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(200))
                // 用 ≥ 而不是 = ：开发库上可能有人手工导入过数据，写死数量会误报失败
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(6)))
                .andExpect(jsonPath("$.data.list.length()", greaterThanOrEqualTo(6)));
    }

    @Test
    void staffListWithoutTokenShouldReturn401() throws Exception {
        mockMvc.perform(get("/user/list"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.state").value(401));
    }

    // ---------- 权限管理（配置端） ----------

    @Test
    void systemApiShouldBeForbiddenForPilot() throws Exception {
        // 飞手没有 system:rbac:list，连角色列表都看不了
        String token = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/system/role/list").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value(403));
    }

    @Test
    void systemRoleListShouldBeAccessibleForAdmin() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        String body = mockMvc.perform(get("/system/role/list").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 断言「种子里的三个角色都在」，而不是断言「一共 3 个」——
        // 后者每加一个角色（比如大屏只读的 screen）就得回来改一次，
        // 而它本来想验的是「管理员能看到角色列表」
        List<String> codes = JsonPath.read(body, "$.data[*].code");
        assertThat(codes).as("种子角色都在").contains("admin", "pilot", "maintainer");
    }

    @Test
    void saveRoleShouldCreateNewRole() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(post("/system/role/save")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"observer","name":"观察员","description":"只读账号"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(200));

        // 新增的 role 要**出现在列表里**；同样不断言总数（种子里有多少个角色不该由这条用例管）
        // 本用例跑完事务回滚，不会污染开发库
        String body = mockMvc.perform(get("/system/role/list").header(AUTH_HEADER, "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<String>>read(body, "$.data[*].code"))
                .as("新增的角色应当在列表里").contains("observer");
    }

    @Test
    void saveRoleShouldRejectDuplicateCode() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(post("/system/role/save")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"pilot","name":"重复的飞手"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("角色编码已存在：pilot"));
    }

    @Test
    void saveRoleShouldRejectDisablingAdminRole() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        // 管理员角色是 rbac_seed.sql 里第一个插入的，id 为 1
        mockMvc.perform(post("/system/role/save")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":1,"name":"管理员","status":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("管理员角色不允许禁用"));
    }

    @Test
    void grantUserRolesShouldRejectModifyingSelf() throws Exception {
        // 刘洋（id=5）是管理员，不能改自己的角色，否则可能把自己锁在门外
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(post("/system/user/grant")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":5,"roleIds":[2]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("不能修改自己的角色，请联系其他管理员"));
    }

    // ---------- 员工检索、导入导出 ----------

    @Test
    void staffListShouldSupportKeywordSearch() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/user/list").param("keyword", "张").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].name").value("张伟"));
    }

    @Test
    void staffListShouldSupportRoleFilter() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/user/list").param("roleCode", "pilot").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(4)))
                .andExpect(jsonPath("$.data.list[?(@.username == '13800001001')]").exists());
    }

    @Test
    void staffListShouldSupportStatusFilter() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/user/list").param("status", "0").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(3)));
    }

    @Test
    void staffListShouldSupportPaging() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/user/list").param("page", "1").param("pageSize", "2")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(6)))
                .andExpect(jsonPath("$.data.list.length()").value(2));
    }

    @Test
    void importTemplateShouldBeDownloadable() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/user/import/template").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("spreadsheetml.sheet")));
    }

    @Test
    void importShouldInsertValidRowsAndReportInvalidOnes() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(multipart("/user/import")
                        .file(new MockMultipartFile("file", "员工.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                buildImportExcel()))
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.success").value(1))
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.errors[0]").value("第 3 行：角色编码不存在：not-exist-role"));

        // 合法那一条应该真的入库了
        mockMvc.perform(get("/user/list").param("keyword", "13900000001").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void exportShouldReturnExcel() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/user/export").param("roleCode", "pilot").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("spreadsheetml.sheet")));
    }

    @Test
    void importAndExportShouldBeForbiddenForPilot() throws Exception {
        // 飞手只有设备权限，既不能导入也不能导出员工
        String token = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/user/import/template").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/user/export").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // ---------- 员工新增、编辑、启停用 ----------

    @Test
    void addUserShouldCreateUserWithRole() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(post("/user/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"13911112222","name":"新员工","password":"123456",
                                 "phone":"13911112222","age":30,"gender":"male","roleCode":"pilot"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(200));

        // 新员工能登录，说明密码和角色关联都写对了
        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("13911112222", "123456")))
                .andExpect(jsonPath("$.data.user.roles[0]").value("pilot"));
    }

    @Test
    void addUserShouldRejectDuplicateUsername() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(post("/user/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"13800001001","name":"重复账号","password":"123456","roleCode":"pilot"}
                                """))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("登录账号已存在：13800001001"));
    }

    @Test
    void addUserShouldBeForbiddenForPilot() throws Exception {
        String token = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(post("/user/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"13922223333","name":"不该成功","password":"123456","roleCode":"pilot"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateUserShouldModifyProfileButKeepUsername() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        // 张伟 id=1：改名 + 改手机号，同时故意传一个 username 试探是否会被改掉
        mockMvc.perform(post("/user/update")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":1,"username":"hacker","name":"张伟改名","phone":"13900001111"}
                                """))
                .andExpect(jsonPath("$.state").value(200));

        mockMvc.perform(get("/user/list").param("keyword", "张伟改名").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].username").value("13800001001"));
    }

    @Test
    void toggleShouldDisableUserAndBlockLogin() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        // 赵强（id=4）原本是启用的，禁用掉
        mockMvc.perform(post("/user/toggle")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":4,"status":"0"}
                                """))
                .andExpect(jsonPath("$.state").value(200));

        // 禁用后立刻登录不进去
        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("13800001004", "123456")))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("账号已被禁用，请联系管理员"));
    }

    @Test
    void toggleShouldRejectDisablingSelf() throws Exception {
        // 刘洋（id=5）是当前登录人，不能禁用自己
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(post("/user/toggle")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":5,"status":"0"}
                                """))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("不能禁用当前登录账号"));
    }

    // ---------- 设备模块 ----------

    @Test
    void deviceListShouldBeAccessibleForPilot() throws Exception {
        // 飞手有 device:list，能看设备
        String token = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/device/list").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(6)));
    }

    @Test
    void deviceListShouldSupportKeywordSearch() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        // 先建一台带独特关键字的
        mockMvc.perform(post("/device/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"DRN-9001","name":"检索测试机","model":"Mavic 3",
                                 "type":"multi-rotor","location":"测试机库"}
                                """))
                .andExpect(jsonPath("$.state").value(200));

        mockMvc.perform(get("/device/list").param("keyword", "检索测试").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(1))
                // 新增的设备默认状态是待命
                .andExpect(jsonPath("$.data.list[0].status").value("idle"));
    }

    @Test
    void deviceAddShouldBeForbiddenForPilot() throws Exception {
        // 飞手只有设备查看权限，不能新增
        String token = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(post("/device/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"DRN-9999","name":"不该成功"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void addDeviceShouldRejectDuplicateCode() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        String body = """
                {"code":"DRN-9002","name":"重复编号测试","type":"multi-rotor"}
                """;

        mockMvc.perform(post("/device/add").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.state").value(200));

        mockMvc.perform(post("/device/add").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("设备编号已存在：DRN-9002"));
    }

    @Test
    void updateDeviceShouldModifyNameButKeepCode() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(post("/device/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"DRN-9003","name":"原始名称","type":"multi-rotor"}
                                """))
                .andExpect(jsonPath("$.state").value(200));

        String body = mockMvc.perform(get("/device/list").param("keyword", "DRN-9003")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(body, "$.data.list[0].id");

        // 改名，同时故意传一个不同的 code 试探是否会被改掉
        mockMvc.perform(post("/device/update")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":%d,"code":"HACKED","name":"改名后"}
                                """.formatted(id)))
                .andExpect(jsonPath("$.state").value(200));

        mockMvc.perform(get("/device/list").param("keyword", "DRN-9003")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].name").value("改名后"));
    }

    @Test
    void deviceImportAndExportShouldBeForbiddenForPilot() throws Exception {
        String token = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/device/import/template").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/device/export").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void deviceExportShouldReturnExcel() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/device/export").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("spreadsheetml.sheet")));
    }

    // ---------- 任务模块 ----------

    @Test
    void createTaskShouldGenerateTaskNoAndPendingStatus() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskCreateBody("输电线路巡检")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.message").value("创建成功"))
                // 编号由服务端生成，格式 TK-yyyyMMdd-序号
                .andExpect(jsonPath("$.data.taskNo", matchesPattern("TK-\\d{8}-\\d{3}")))
                // 状态写死在服务端：参数里没有 status 这个字段，前端想指定也没有入口
                .andExpect(jsonPath("$.data.status").value("pending"))
                // 创建人来自登录态，不是前端传的
                .andExpect(jsonPath("$.data.createByName").value("刘洋"))
                // create_time 由数据库的 DEFAULT CURRENT_TIMESTAMP 生成，
                // Service 必须回查一次才拿得到；不回查这里就是 null
                .andExpect(jsonPath("$.data.createTime").isNotEmpty());
    }

    @Test
    void createTaskShouldWriteCreateLog() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        String body = mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskCreateBody("流水测试任务")))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(body, "$.data.id");

        // 任务和流水在同一个事务里写入，所以刚建完就能查到一条 create 流水
        mockMvc.perform(get("/task/" + id).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.taskName").value("流水测试任务"))
                .andExpect(jsonPath("$.data.logs.length()").value(1))
                .andExpect(jsonPath("$.data.logs[0].action").value("create"))
                .andExpect(jsonPath("$.data.logs[0].toStatus").value("pending"))
                .andExpect(jsonPath("$.data.logs[0].operatorName").value("刘洋"))
                .andExpect(jsonPath("$.data.logs[0].operatorRole").value("admin"));
    }

    @Test
    void taskDetailShouldRejectUnknownId() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/task/99999999").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("任务不存在"));
    }

    @Test
    void createTaskShouldRejectInvalidPriority() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        // 参数里塞个白名单外的 P9，必须被挡下来
        mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskName":"优先级非法","priority":"P9",
                                 "planStartTime":"2026-09-18 09:00:00","planEndTime":"2026-09-18 11:00:00"}
                                """))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("优先级只能是 P0~P3"));
    }

    @Test
    void createTaskShouldRejectReversedTime() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskName":"时间倒挂","priority":"P2",
                                 "planStartTime":"2026-09-18 11:00:00","planEndTime":"2026-09-18 09:00:00"}
                                """))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("计划结束时间不能早于开始时间"));
    }

    @Test
    void createTaskShouldRejectBlankName() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskName":"   ","priority":"P2",
                                 "planStartTime":"2026-09-18 09:00:00","planEndTime":"2026-09-18 11:00:00"}
                                """))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("任务名称不能为空"));
    }

    @Test
    void createTaskShouldBeForbiddenForPilot() throws Exception {
        // 张伟是飞手，只有 task:execute（干活），没有 task:add（派活）
        String token = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskCreateBody("飞手不该建得出来")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value(403));
    }

    @Test
    void taskListShouldBeAccessibleForAdminButNotForPilot() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/task/list").header(AUTH_HEADER, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(200))
                // 返回体和员工、设备列表一样是分页结构：list + total + page + pageSize
                .andExpect(jsonPath("$.data.total").exists())
                .andExpect(jsonPath("$.data.list").isArray());

        // 飞手看不到任务总表，需要的是「我的任务」（下一步实现）
        String pilotToken = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);
        mockMvc.perform(get("/task/list").header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value(403));
    }

    @Test
    void taskShouldBeSearchableByKeywordAndPriority() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskCreateBody("高压塔基排查")))
                .andExpect(jsonPath("$.state").value(200));

        mockMvc.perform(get("/task/list").param("keyword", "高压塔基")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].taskName").value("高压塔基排查"))
                .andExpect(jsonPath("$.data.list[0].priority").value("P1"))
                // 航线里的中文和箭头符号要能原样进出（呼应之前的乱码问题）
                .andExpect(jsonPath("$.data.list[0].routeInfo", containsString("B-02")));
    }

    // ---------- 任务派发 ----------

    /**
     * 派发的用例统一「现造」人和设备，不用库里已有的账号与设备。
     *
     * <p>原因很实际：开发库是会被人在页面上操作的真实库。一旦测试依赖
     * 「张伟当前空闲」「UAV-001 当前待命」，那么别人在界面上派一次活，
     * 测试就会莫名其妙地失败 —— 而失败原因跟被测代码毫无关系。
     * 自己造的数据在事务里跑完就回滚，与库里的现状彻底隔离。
     */
    @Test
    void dispatchShouldAssignPilotAndDroneAndWriteLog() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(token, "13900002010", "派发测试飞手");
        int droneId = addDeviceAndGetId(token, "DRN-9201", "派发测试机");
        int taskId = createTaskId(token, "派发测试任务", "2026-12-01 09:00:00", "2026-12-01 11:00:00");

        mockMvc.perform(post("/task/dispatch")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(taskId, pilotId, droneId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.message").value("派发成功"))
                // 状态由服务端按状态机推导，参数里没有 status 可传
                .andExpect(jsonPath("$.data.status").value("dispatched"))
                .andExpect(jsonPath("$.data.pilotName").value("派发测试飞手"))
                .andExpect(jsonPath("$.data.droneCode").value("DRN-9201"));

        // 时间轴应该有两条：create 和 dispatch
        mockMvc.perform(get("/task/" + taskId).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.logs.length()").value(2))
                .andExpect(jsonPath("$.data.logs[1].action").value("dispatch"))
                .andExpect(jsonPath("$.data.logs[1].fromStatus").value("pending"))
                .andExpect(jsonPath("$.data.logs[1].toStatus").value("dispatched"))
                // 没填附言时自动记一句，时间轴不会空一行
                .andExpect(jsonPath("$.data.logs[1].note", containsString("指派派发测试飞手驾驶DRN-9201")))
                .andExpect(jsonPath("$.data.logs[1].operatorName").value("刘洋"));
    }

    @Test
    void dispatchShouldMarkDroneAssignedAndPilotBusy() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(token, "13900002011", "状态联动飞手");
        int droneId = addDeviceAndGetId(token, "DRN-9202", "状态联动机");
        int taskId = createTaskId(token, "状态联动验证", "2026-12-11 09:00:00", "2026-12-11 11:00:00");

        // 派发前：设备待命、飞手空闲
        assertDroneStatus(token, droneId, "idle");
        assertUserState(token, pilotId, "idle");

        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(taskId, pilotId, droneId)))
                .andExpect(jsonPath("$.state").value(200));

        // 派发后：设备变「已派出」、飞手变「作业中」—— 这正是「派发要占用资源」的体现
        assertDroneStatus(token, droneId, "assigned");
        assertUserState(token, pilotId, "busy");

        // 已派出的设备不会再出现在派发下拉里，等于从源头挡住了重复占用
        String options = mockMvc.perform(get("/task/dispatch-options").header(AUTH_HEADER, "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((List<Integer>) JsonPath.read(options, "$.data.drones[*].id"))
                .doesNotContain(droneId);

        // 撤回后两边都要恢复
        mockMvc.perform(post("/task/withdraw").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d}
                                """.formatted(taskId)))
                .andExpect(jsonPath("$.state").value(200));

        assertDroneStatus(token, droneId, "idle");
        assertUserState(token, pilotId, "idle");
    }

    @Test
    void withdrawShouldKeepPilotBusyWhileHeStillHasOtherTasks() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(token, "13900002012", "连班飞手");
        int droneA = addDeviceAndGetId(token, "DRN-9203", "连班机A");
        int droneB = addDeviceAndGetId(token, "DRN-9204", "连班机B");

        // 同一个飞手、两个不重叠的时间段 —— 排班是允许的
        int morning = createTaskId(token, "上午巡检", "2026-12-12 09:00:00", "2026-12-12 11:00:00");
        int afternoon = createTaskId(token, "下午巡检", "2026-12-12 14:00:00", "2026-12-12 16:00:00");

        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(morning, pilotId, droneA)))
                .andExpect(jsonPath("$.state").value(200));
        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(afternoon, pilotId, droneB)))
                .andExpect(jsonPath("$.state").value(200));

        // 撤回上午那条：他下午还有活，不能改回「空闲」
        mockMvc.perform(post("/task/withdraw").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d}
                                """.formatted(morning)))
                .andExpect(jsonPath("$.state").value(200));
        assertUserState(token, pilotId, "busy");

        // 下午那条也撤掉，这回他才真的闲下来
        mockMvc.perform(post("/task/withdraw").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d}
                                """.formatted(afternoon)))
                .andExpect(jsonPath("$.state").value(200));
        assertUserState(token, pilotId, "idle");
    }

    @Test
    void dispatchShouldRejectTaskThatIsNotPending() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(token, "13900002013", "重复派发飞手");
        int droneId = addDeviceAndGetId(token, "DRN-9205", "重复派发机");
        int taskId = createTaskId(token, "重复派发测试", "2026-12-02 09:00:00", "2026-12-02 11:00:00");

        String body = dispatchBody(taskId, pilotId, droneId);
        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.state").value(200));

        // 再派一次：状态机不允许 dispatched → dispatched
        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("只有「待派发」的任务才能派发，当前状态：已派发"));
    }

    @Test
    void dispatchShouldRejectPilotWithoutPilotRole() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int taskId = createTaskId(token, "派给机务", "2026-12-03 09:00:00", "2026-12-03 11:00:00");

        // 赵强是机务，能登录、账号也是启用的，但他不是飞手
        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(taskId, MAINTAINER_ZHAOQIANG, DRONE_UAV_001)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("「赵强」不是飞手，不能派发任务"));
    }

    @Test
    void dispatchShouldRejectDisabledPilot() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int taskId = createTaskId(token, "派给禁用账号", "2026-12-04 09:00:00", "2026-12-04 11:00:00");

        // 李明是飞手，但账号已被禁用
        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(taskId, PILOT_LIMING_DISABLED, DRONE_UAV_001)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("「李明」的账号已被禁用，不能派发任务"));
    }

    @Test
    void dispatchShouldRejectDroneThatIsNotIdle() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(token, "13900002014", "非待命设备飞手");
        int droneId = addDeviceAndGetId(token, "DRN-9206", "送修机");
        // 现造一台再改成「维护中」：不去依赖种子数据里哪台设备恰好是维护中
        updateDeviceStatus(token, droneId, "maintenance");

        int taskId = createTaskId(token, "派维护中的设备", "2026-12-05 09:00:00", "2026-12-05 11:00:00");

        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(taskId, pilotId, droneId)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("DRN-9206")))
                .andExpect(jsonPath("$.message", containsString("不是待命状态")));
    }

    @Test
    void dispatchShouldRejectOverlappingScheduleForSamePilot() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(token, "13900002015", "排班冲突飞手");
        int droneA = addDeviceAndGetId(token, "DRN-9207", "排班机A");
        int droneB = addDeviceAndGetId(token, "DRN-9208", "排班机B");

        // 先占住这个飞手 12-06 的 09:00~11:00
        int busy = createTaskId(token, "占用时段的任务", "2026-12-06 09:00:00", "2026-12-06 11:00:00");
        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(busy, pilotId, droneA)))
                .andExpect(jsonPath("$.state").value(200));

        // 再来一个时间段重叠的：换一台设备，把冲突定位到「飞手」身上。
        // 报错里的时间窗与编号都来自「已存在的那条任务」，管理员能顺着编号去处理
        int overlapping = createTaskId(token, "时段重叠的任务", "2026-12-06 10:00:00", "2026-12-06 12:00:00");
        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(overlapping, pilotId, droneB)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message",
                        containsString("「排班冲突飞手」在 2026-12-06 09:00 ~ 11:00 已有任务 TK-")))
                .andExpect(jsonPath("$.message", containsString("同一时段不能重复派发")));

        // 首尾相接（11:00 开始）不算冲突 —— 区间重叠是开区间判定
        int adjacent = createTaskId(token, "首尾相接的任务", "2026-12-06 11:00:00", "2026-12-06 13:00:00");
        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(adjacent, pilotId, droneB)))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.status").value("dispatched"));
    }

    @Test
    void dispatchShouldRejectOverlappingScheduleForSameDrone() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        // 两个飞手 + 一台设备：这样时间窗冲突只可能怪设备，
        // 否则会被「飞手已有任务」先挡住，测不到设备那条分支
        int pilotA = addPilotAndGetId(token, "13900002016", "设备冲突飞手甲");
        int pilotB = addPilotAndGetId(token, "13900002017", "设备冲突飞手乙");
        int droneId = addDeviceAndGetId(token, "DRN-9209", "被抢的机");

        int busy = createTaskId(token, "占用设备", "2026-12-07 09:00:00", "2026-12-07 11:00:00");
        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(busy, pilotA, droneId)))
                .andExpect(jsonPath("$.state").value(200));

        // 派发已经把设备置成「已派出」，正常路径下它不会再出现在下拉里。
        // 这里故意把它手工改回「待命」，模拟有人在设备管理里改了状态 ——
        // 这种情况就该由时间窗检查兜住，而不是让一台设备同时扛两个重叠的任务
        updateDeviceStatus(token, droneId, "idle");

        int overlapping = createTaskId(token, "抢同一台设备", "2026-12-07 10:00:00", "2026-12-07 12:00:00");
        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(overlapping, pilotB, droneId)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("DRN-9209")))
                .andExpect(jsonPath("$.message", containsString("已被任务 TK-")))
                .andExpect(jsonPath("$.message", containsString("占用，请换一台设备")));
    }

    @Test
    void dispatchShouldBeForbiddenForPilot() throws Exception {
        // 飞手只有 task:execute，派活是管理员的事
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int taskId = createTaskId(adminToken, "飞手不该能派发", "2026-12-08 09:00:00", "2026-12-08 11:00:00");

        String pilotToken = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);
        // 请求在进入业务代码之前就被拦截器按权限码拦下了，
        // 所以这里的飞手、设备 id 只是占位，不会走到校验
        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(taskId, 1, DRONE_UAV_001)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value(403));
    }

    @Test
    void withdrawShouldClearAssignmentAndReturnToPending() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(token, "13900002018", "撤回测试飞手");
        int droneId = addDeviceAndGetId(token, "DRN-9210", "撤回测试机");
        int taskId = createTaskId(token, "撤回测试任务", "2026-12-09 09:00:00", "2026-12-09 11:00:00");

        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(taskId, pilotId, droneId)))
                .andExpect(jsonPath("$.state").value(200));

        mockMvc.perform(post("/task/withdraw").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"note":"飞手当天临时调班"}
                                """.formatted(taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("已撤回派发"))
                .andExpect(jsonPath("$.data.status").value("pending"));

        // 关键点：主表上的指派必须被真的清成 NULL。
        // 这里直接查库断言，而不是断言 JSON —— 因为「JSON 里字段是 null」有两种可能：
        // 真被清空了，或者只是查询没带上这个字段。查库才说明 clearDispatch 的 SQL 写对了。
        TaskPO after = taskDao.selectById((long) taskId);
        assertThat(after.getPilotId()).isNull();
        assertThat(after.getPilotName()).isNull();
        assertThat(after.getDroneId()).isNull();
        assertThat(after.getDroneCode()).isNull();
        assertThat(after.getStatus()).isEqualTo("pending");

        // 原指派记在流水里，事后还能查「这任务原本给谁的」
        mockMvc.perform(get("/task/" + taskId).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.logs.length()").value(3))
                .andExpect(jsonPath("$.data.logs[2].action").value("withdraw"))
                .andExpect(jsonPath("$.data.logs[2].fromStatus").value("dispatched"))
                .andExpect(jsonPath("$.data.logs[2].toStatus").value("pending"))
                .andExpect(jsonPath("$.data.logs[2].note", containsString("原指派 撤回测试飞手 / DRN-9210")))
                .andExpect(jsonPath("$.data.logs[2].note", containsString("飞手当天临时调班")));
    }

    @Test
    void withdrawShouldRejectTaskThatIsNotDispatched() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int taskId = createTaskId(token, "撤回未派发的任务", "2026-12-10 09:00:00", "2026-12-10 11:00:00");

        mockMvc.perform(post("/task/withdraw").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d}
                                """.formatted(taskId)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("只有「已派发」的任务才能撤回派发，当前状态：待派发"));
    }

    @Test
    void dispatchOptionsShouldListOnlyAvailablePilotsAndDrones() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        // 现造一组可控的候选，不去假设库里恰好有谁、设备恰好是什么状态
        int enabledPilot = addPilotAndGetId(token, "13900002019", "可用飞手甲");
        int disabledPilot = addPilotAndGetId(token, "13900002020", "禁用飞手乙");
        toggleUser(token, disabledPilot, "0");
        int idleDrone = addDeviceAndGetId(token, "DRN-9211", "候选机甲");
        int maintenanceDrone = addDeviceAndGetId(token, "DRN-9212", "候选机乙");
        updateDeviceStatus(token, maintenanceDrone, "maintenance");

        String body = mockMvc.perform(get("/task/dispatch-options").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                // 必须显式指定 UTF-8：MockMvc 的 getContentAsString() 在没有 charset 时
                // 按 ISO-8859-1 解码，中文 label 会变成「å¼ ä¼」这种乱码。
                // （jsonPath(...) 断言不受影响 —— Spring 在那条路径上自己按 UTF-8 读）
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<Integer> pilotIds = JsonPath.read(body, "$.data.pilots[*].id");
        List<Integer> droneIds = JsonPath.read(body, "$.data.drones[*].id");

        // 启用的飞手在候选里；被禁用的飞手、以及非飞手的赵强都不在
        assertThat(pilotIds).contains(enabledPilot);
        assertThat(pilotIds).doesNotContain(disabledPilot, MAINTAINER_ZHAOQIANG);

        // 待命的设备在；被改成维护中的那台不在
        assertThat(droneIds).contains(idleDrone);
        assertThat(droneIds).doesNotContain(maintenanceDrone);

        // label 由服务端拼好，前端直接渲染
        assertThat(JsonPath.read(body, "$.data.pilots[0].label").toString()).contains(" · ");
    }

    @Test
    void dispatchOptionsShouldBeForbiddenForPilot() throws Exception {
        String token = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/task/dispatch-options").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // ---------- 派发超时自动释放 ----------

    /**
     * 超时释放的核心用例：派发出去、计划时间早已过去、飞手一直没执行 ——
     * 资源和任务状态都要收回来，流水里要留下「系统干的」这条清楚记录。
     */
    @Test
    void autoReleaseShouldReleaseTaskPastWindow() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(token, "13900002021", "放鸽子飞手");
        int droneId = addDeviceAndGetId(token, "DRN-9301", "被占住的机");
        // 计划时间放在很久以前：创建接口不校验「必须是将来的时间」，
        // 正好方便构造「早就该干完、结果没人干」的场景
        int taskId = createTaskId(token, "没人执行的任务", "2020-01-01 09:00:00", "2020-01-01 11:00:00");

        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(taskId, pilotId, droneId)))
                .andExpect(jsonPath("$.state").value(200));
        assertDroneStatus(token, droneId, "assigned");
        assertUserState(token, pilotId, "busy");

        // 直接调释放逻辑（定时任务在测试里被关掉了，见类上的 @SpringBootTest）
        assertThat(taskService.releaseExpiredDispatched()).isEqualTo(1);

        assertDroneStatus(token, droneId, "idle");
        assertUserState(token, pilotId, "idle");

        // 任务回到待派发，指派被清空，资源可以重新派
        mockMvc.perform(get("/task/" + taskId).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.task.status").value("pending"))
                .andExpect(jsonPath("$.data.logs.length()").value(3))
                .andExpect(jsonPath("$.data.logs[2].action").value("expire"))
                .andExpect(jsonPath("$.data.logs[2].fromStatus").value("dispatched"))
                .andExpect(jsonPath("$.data.logs[2].toStatus").value("pending"))
                // 关键：定时任务没有登录用户，操作人必须记成「系统」，
                // 否则时间轴上分不清是人撤的还是系统释放的
                .andExpect(jsonPath("$.data.logs[2].operatorName").value("系统"))
                .andExpect(jsonPath("$.data.logs[2].operatorRole").value("system"))
                .andExpect(jsonPath("$.data.logs[2].note", containsString("系统自动释放")));
    }

    @Test
    void autoReleaseShouldSkipTaskStillWithinWindow() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(token, "13900002022", "正常排班飞手");
        int droneId = addDeviceAndGetId(token, "DRN-9302", "正常排班机");
        int taskId = createTaskId(token, "时间还没到的任务", "2026-12-20 09:00:00", "2026-12-20 11:00:00");

        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(taskId, pilotId, droneId)))
                .andExpect(jsonPath("$.state").value(200));

        assertThat(taskService.releaseExpiredDispatched()).isZero();

        // 一点都不能动：任务、设备、飞手都保持派发后的样子
        assertDroneStatus(token, droneId, "assigned");
        assertUserState(token, pilotId, "busy");
        mockMvc.perform(get("/task/" + taskId).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.task.status").value("dispatched"))
                .andExpect(jsonPath("$.data.logs.length()").value(2));
    }

    /**
     * 宽限期内的任务不释放：计划结束时间刚过 30 分钟，还在 2 小时的宽限期内。
     * 时间用「相对现在」算，不写死日期，否则这个用例过一阵子含义就变了。
     */
    @Test
    void autoReleaseShouldSkipTaskWithinGracePeriod() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(token, "13900002023", "迟到飞手");
        int droneId = addDeviceAndGetId(token, "DRN-9303", "迟到机");

        LocalDateTime end = LocalDateTime.now().minusMinutes(30);
        int taskId = createTaskId(token, "刚过点的任务",
                end.minusHours(2).format(API_TIME), end.format(API_TIME));

        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(taskId, pilotId, droneId)))
                .andExpect(jsonPath("$.state").value(200));

        assertThat(taskService.releaseExpiredDispatched()).isZero();
        assertDroneStatus(token, droneId, "assigned");
    }

    @Test
    void autoReleaseShouldBeIdempotent() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(token, "13900002024", "幂等飞手");
        int droneId = addDeviceAndGetId(token, "DRN-9304", "幂等机");
        int taskId = createTaskId(token, "幂等验证任务", "2020-02-02 09:00:00", "2020-02-02 11:00:00");

        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(taskId, pilotId, droneId)))
                .andExpect(jsonPath("$.state").value(200));

        assertThat(taskService.releaseExpiredDispatched()).isEqualTo(1);
        // 再跑一次：任务已经不是「已派发」，条件更新会拦住它，
        // 既不会重复释放，也不会多写一条流水
        assertThat(taskService.releaseExpiredDispatched()).isZero();

        mockMvc.perform(get("/task/" + taskId).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.logs.length()").value(3));
    }

    @Test
    void autoReleaseShouldKeepPilotBusyWhenHeHasOtherActiveTask() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(token, "13900002025", "连班超时飞手");
        int droneA = addDeviceAndGetId(token, "DRN-9305", "过期机");
        int droneB = addDeviceAndGetId(token, "DRN-9306", "在排班机");

        // 同一个飞手：一条早已过期的（该被释放），一条还在将来的（继续占着他）
        int expired = createTaskId(token, "过期的任务", "2020-03-03 09:00:00", "2020-03-03 11:00:00");
        int future = createTaskId(token, "未来的任务", "2026-12-25 09:00:00", "2026-12-25 11:00:00");

        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(expired, pilotId, droneA)))
                .andExpect(jsonPath("$.state").value(200));
        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(future, pilotId, droneB)))
                .andExpect(jsonPath("$.state").value(200));

        assertThat(taskService.releaseExpiredDispatched()).isEqualTo(1);

        // 设备释放了，但飞手手上还有将来那条任务，不能改回空闲
        assertDroneStatus(token, droneA, "idle");
        assertUserState(token, pilotId, "busy");
    }

    /**
     * 执行中的任务超时**不**释放：设备这时候是真的在飞，
     * 自动把状态改回「待命」等于谎报。这类「执行超时」归监控告警管。
     */
    @Test
    void autoReleaseShouldNotTouchExecutingTask() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(token, "13900002026", "执行中超时飞手");
        int droneId = addDeviceAndGetId(token, "DRN-9307", "还在飞的机");
        int taskId = createTaskId(token, "执行中超时", "2020-04-04 09:00:00", "2020-04-04 11:00:00");

        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(taskId, pilotId, droneId)))
                .andExpect(jsonPath("$.state").value(200));

        // 「开始执行」接口还没做（③），这里直接改库把任务置成执行中，
        // 好让「释放逻辑不碰 executing」这条边界能被真正验证到
        jdbcTemplate.update("UPDATE `task` SET status = 'executing' WHERE id = ?", taskId);

        assertThat(taskService.releaseExpiredDispatched()).isZero();

        // 这里用 JdbcTemplate 查状态、而不用 /task/{id} 接口：
        // JdbcTemplate 是绕开 MyBatis 直接改库的，MyBatis 不知道，它本地缓存里
        // 还存着这次事务中早先读过的那个 PO，走接口会读到「已派发」这个旧值。
        // （也顺带说明：MyBatis 的本地缓存是按 SqlSession 走的，同一事务内有效）
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM `task` WHERE id = ?", String.class, taskId);
        assertThat(status).isEqualTo("executing");

        // 流水是另一条语句，没被缓存过，走接口读没问题
        mockMvc.perform(get("/task/" + taskId).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.logs.length()").value(2));
    }

    /**
     * 释放时如果设备已被人工改成别的状态，不强改：
     * 任务与飞手照常释放，设备保持人工设定的值。
     *
     * <p>「作业中 → 空闲」这类状态是人工标记，业务流程不该把它冲掉。
     */
    @Test
    void autoReleaseShouldKeepManuallyChangedDroneStatus() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(token, "13900002027", "设备被改的飞手");
        int droneId = addDeviceAndGetId(token, "DRN-9308", "临时送修的机");
        int taskId = createTaskId(token, "设备被改的任务", "2020-05-05 09:00:00", "2020-05-05 11:00:00");

        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(taskId, pilotId, droneId)))
                .andExpect(jsonPath("$.state").value(200));

        // 派发之后有人在设备管理里把它送去维修
        updateDeviceStatus(token, droneId, "maintenance");

        assertThat(taskService.releaseExpiredDispatched()).isEqualTo(1);

        // 任务与飞手照常释放，设备保持「维护中」—— 人工设定优先
        mockMvc.perform(get("/task/" + taskId).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.task.status").value("pending"));
        assertUserState(token, pilotId, "idle");
        assertDroneStatus(token, droneId, "maintenance");
    }

    // ---------- 我的任务（飞手端） ----------

    /**
     * 飞手只能看到派给自己的任务 —— 这是这个接口存在的全部理由。
     *
     * <p>造两个飞手、各派一条任务，然后以 A 的身份查，B 的那条不能出现。
     */
    @Test
    void myTasksShouldOnlyReturnTasksAssignedToMe() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotA = addPilotAndGetId(adminToken, "13900002030", "飞手甲");
        int pilotB = addPilotAndGetId(adminToken, "13900002031", "飞手乙");
        int droneA = addDeviceAndGetId(adminToken, "DRN-9401", "甲机");
        int droneB = addDeviceAndGetId(adminToken, "DRN-9402", "乙机");

        int taskA = createTaskId(adminToken, "给甲的活", "2027-01-10 09:00:00", "2027-01-10 11:00:00");
        int taskB = createTaskId(adminToken, "给乙的活", "2027-01-10 09:00:00", "2027-01-10 11:00:00");
        dispatchAs(adminToken, taskA, pilotA, droneA);
        dispatchAs(adminToken, taskB, pilotB, droneB);

        // 甲用自己的账号登录，只看得到自己那条
        String tokenA = loginAndGetToken("13900002030", "123456");
        mockMvc.perform(get("/task/my").header(AUTH_HEADER, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].taskName").value("给甲的活"))
                .andExpect(jsonPath("$.data.list[0].pilotName").value("飞手甲"));
    }

    /**
     * 前端手动塞一个 pilotId 想查别人的任务 —— 服务端必须覆盖掉它。
     */
    @Test
    void myTasksShouldIgnorePilotIdPassedByClient() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotA = addPilotAndGetId(adminToken, "13900002032", "越权飞手甲");
        int pilotB = addPilotAndGetId(adminToken, "13900002033", "越权飞手乙");
        int droneId = addDeviceAndGetId(adminToken, "DRN-9403", "越权机");

        int taskB = createTaskId(adminToken, "乙的任务不该被看到", "2027-01-11 09:00:00", "2027-01-11 11:00:00");
        dispatchAs(adminToken, taskB, pilotB, droneId);

        // 甲登录后，故意把 pilotId 传成乙的
        String tokenA = loginAndGetToken("13900002032", "123456");
        mockMvc.perform(get("/task/my")
                        .param("pilotId", String.valueOf(pilotB))
                        .header(AUTH_HEADER, "Bearer " + tokenA))
                .andExpect(jsonPath("$.state").value(200))
                // 仍然只看到自己的（一条都没有），而不是乙的那条
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void myTasksShouldBeEmptyForPilotWithoutTasks() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        addPilotAndGetId(adminToken, "13900002034", "没活的飞手");

        String token = loginAndGetToken("13900002034", "123456");
        mockMvc.perform(get("/task/my").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.list").isArray());
    }

    @Test
    void myTasksShouldBeForbiddenForMaintainer() throws Exception {
        // 机务有 task:list（能看任务总表）但没有 task:execute，不该用「我的任务」
        String token = loginAndGetToken("13800001004", EXISTING_PASSWORD);

        mockMvc.perform(get("/task/my").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value(403));
    }

    @Test
    void myTasksWithoutTokenShouldReturn401() throws Exception {
        mockMvc.perform(get("/task/my"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.state").value(401));
    }

    // ---------- 开始执行 / 拒绝执行（飞手端） ----------

    /**
     * 开始执行：状态变「执行中」，落下实际开始时间，设备从「已派出」变「飞行中」。
     *
     * <p>这条用例同时验证了之前预留的 {@code flying} 状态确实用上了 ——
     * 「派发」只把设备置为「已派出」，「真的起飞」是开始执行这一刻。
     */
    @Test
    void startShouldMoveTaskToExecutingAndDroneToFlying() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(adminToken, "13900005001", "开始执行飞手");
        int droneId = addDeviceAndGetId(adminToken, "DRN-9701", "开始执行机");
        int taskId = createTaskId(adminToken, "开始执行验证", "2027-05-01 09:00:00", "2027-05-01 11:00:00");
        dispatchAs(adminToken, taskId, pilotId, droneId);

        // 派发之后是「已派出」，还不是飞行中
        assertDroneStatus(adminToken, droneId, "assigned");

        String pilotToken = loginAndGetToken("13900005001", "123456");
        mockMvc.perform(post("/task/start").header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d}
                                """.formatted(taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.status").value("executing"))
                // 实际开始时间这一刻必须写进去，否则「什么时候真开工的」就查无实据
                .andExpect(jsonPath("$.data.actualStartTime").isNotEmpty());

        assertDroneStatus(adminToken, droneId, "flying");

        mockMvc.perform(get("/task/" + taskId).header(AUTH_HEADER, "Bearer " + adminToken))
                .andExpect(jsonPath("$.data.logs.length()").value(3))
                .andExpect(jsonPath("$.data.logs[2].action").value("start"))
                .andExpect(jsonPath("$.data.logs[2].fromStatus").value("dispatched"))
                .andExpect(jsonPath("$.data.logs[2].toStatus").value("executing"));
    }

    @Test
    void startShouldRejectTaskOfAnotherPilot() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int owner = addPilotAndGetId(adminToken, "13900005002", "任务的飞手");
        addPilotAndGetId(adminToken, "13900005003", "路人飞手");
        int droneId = addDeviceAndGetId(adminToken, "DRN-9702", "越权开始机");
        int taskId = createTaskId(adminToken, "别人的任务", "2027-05-02 09:00:00", "2027-05-02 11:00:00");
        dispatchAs(adminToken, taskId, owner, droneId);

        // 另一个飞手拿到了任务 id，想直接开始 —— 必须被挡住，否则就是水平越权
        String otherToken = loginAndGetToken("13900005003", "123456");
        mockMvc.perform(post("/task/start").header(AUTH_HEADER, "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d}
                                """.formatted(taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(403))
                .andExpect(jsonPath("$.message").value("这条任务不是派给你的，无权操作"));

        // 任务状态没被动手
        mockMvc.perform(get("/task/" + taskId).header(AUTH_HEADER, "Bearer " + adminToken))
                .andExpect(jsonPath("$.data.task.status").value("dispatched"));
    }

    @Test
    void startShouldRejectTaskThatIsNotDispatched() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(adminToken, "13900005004", "重复开始飞手");
        int droneId = addDeviceAndGetId(adminToken, "DRN-9703", "重复开始机");
        int taskId = createTaskId(adminToken, "重复开始", "2027-05-03 09:00:00", "2027-05-03 11:00:00");
        dispatchAs(adminToken, taskId, pilotId, droneId);

        String pilotToken = loginAndGetToken("13900005004", "123456");
        String body = """
                {"taskId":%d}
                """.formatted(taskId);

        mockMvc.perform(post("/task/start").header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.state").value(200));

        // 第二次开始：状态机不允许 executing → executing
        mockMvc.perform(post("/task/start").header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("只有「已派发」的任务才能开始执行，当前状态：执行中"));
    }

    /**
     * 拒绝执行：任务退回「待派发」、设备与飞手都释放，
     * 而且流水里记的是 {@code refuse} 而不是 {@code withdraw} —— 这两件事必须分得清。
     */
    @Test
    void refuseShouldReleaseResourcesAndReturnTaskToPending() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(adminToken, "13900005005", "拒绝执行飞手");
        int droneId = addDeviceAndGetId(adminToken, "DRN-9704", "被拒的机");
        int taskId = createTaskId(adminToken, "被拒绝的任务", "2027-05-04 09:00:00", "2027-05-04 11:00:00");
        dispatchAs(adminToken, taskId, pilotId, droneId);

        assertDroneStatus(adminToken, droneId, "assigned");
        assertUserState(adminToken, pilotId, "busy");

        String pilotToken = loginAndGetToken("13900005005", "123456");
        mockMvc.perform(post("/task/refuse").header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"reason":"当天有别的安排，去不了"}
                                """.formatted(taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(200))
                // 任务回到待派发 —— 管理员直接就能重新指派
                .andExpect(jsonPath("$.data.status").value("pending"));

        // 资源都释放了：设备回待命、飞手回空闲
        assertDroneStatus(adminToken, droneId, "idle");
        assertUserState(adminToken, pilotId, "idle");

        // 指派关系清空，但「是谁拒的、为什么」留在流水里
        mockMvc.perform(get("/task/" + taskId).header(AUTH_HEADER, "Bearer " + adminToken))
                .andExpect(jsonPath("$.data.task.pilotName").doesNotExist())
                .andExpect(jsonPath("$.data.logs.length()").value(3))
                .andExpect(jsonPath("$.data.logs[2].action").value("refuse"))
                .andExpect(jsonPath("$.data.logs[2].fromStatus").value("dispatched"))
                .andExpect(jsonPath("$.data.logs[2].toStatus").value("pending"))
                .andExpect(jsonPath("$.data.logs[2].operatorName").value("拒绝执行飞手"))
                .andExpect(jsonPath("$.data.logs[2].operatorRole").value("pilot"))
                .andExpect(jsonPath("$.data.logs[2].note", containsString("当天有别的安排")));

        // 拒绝之后这条任务能被重新指派给别人 —— 这是「拒绝」而不是「终结」的关键
        int otherPilot = addPilotAndGetId(adminToken, "13900005006", "接手的飞手");
        dispatchAs(adminToken, taskId, otherPilot, droneId);
        mockMvc.perform(get("/task/" + taskId).header(AUTH_HEADER, "Bearer " + adminToken))
                .andExpect(jsonPath("$.data.task.status").value("dispatched"))
                .andExpect(jsonPath("$.data.task.pilotName").value("接手的飞手"));
    }

    @Test
    void refuseShouldRejectTaskOfAnotherPilot() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int owner = addPilotAndGetId(adminToken, "13900005007", "被抢任务的飞手");
        addPilotAndGetId(adminToken, "13900005008", "抢任务的飞手");
        int droneId = addDeviceAndGetId(adminToken, "DRN-9705", "越权拒绝机");
        int taskId = createTaskId(adminToken, "别人不能拒", "2027-05-05 09:00:00", "2027-05-05 11:00:00");
        dispatchAs(adminToken, taskId, owner, droneId);

        String otherToken = loginAndGetToken("13900005008", "123456");
        mockMvc.perform(post("/task/refuse").header(AUTH_HEADER, "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"reason":"不是我的活"}
                                """.formatted(taskId)))
                .andExpect(jsonPath("$.state").value(403))
                .andExpect(jsonPath("$.message").value("这条任务不是派给你的，无权操作"));

        // 别人的任务没被退掉
        mockMvc.perform(get("/task/" + taskId).header(AUTH_HEADER, "Bearer " + adminToken))
                .andExpect(jsonPath("$.data.task.status").value("dispatched"));
    }

    @Test
    void refuseShouldRejectTaskAlreadyExecuting() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pilotId = addPilotAndGetId(adminToken, "13900005009", "起飞后反悔的飞手");
        int droneId = addDeviceAndGetId(adminToken, "DRN-9706", "已经起飞的机");
        int taskId = createTaskId(adminToken, "起飞后不能拒", "2027-05-06 09:00:00", "2027-05-06 11:00:00");
        dispatchAs(adminToken, taskId, pilotId, droneId);

        String pilotToken = loginAndGetToken("13900005009", "123456");
        mockMvc.perform(post("/task/start").header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d}
                                """.formatted(taskId)))
                .andExpect(jsonPath("$.state").value(200));

        // 已经起飞了还想拒 —— 状态机不允许，这时候该走「执行失败」
        mockMvc.perform(post("/task/refuse").header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d}
                                """.formatted(taskId)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("只有「已派发」的任务才能拒绝执行，当前状态：执行中"));
    }

    @Test
    void startAndRefuseShouldBeForbiddenForMaintainer() throws Exception {
        // 机务有 task:list 但没有 task:execute
        String token = loginAndGetToken("13800001004", EXISTING_PASSWORD);

        mockMvc.perform(post("/task/start").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value(403));

        mockMvc.perform(post("/task/refuse").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value(403));
    }

    // ---------- 设备数据接入：证据落库 ----------
    //
    // 这一组用例不走 MQTT、也不起 broker，而是直接把消息交给处理口
    // （TelemetryHandler.handle）。理由：业务规则（去重、校验、归属、
    // 依据升级）跟「消息是 TCP 来的还是别的来的」没有关系，
    // 直接调用既快又不依赖外部进程。真实的「连 broker 收报文」另有一条
    // 端到端用例（见 MqttSubscriberTests），那条只证明链路通，不管业务规则。

    /**
     * 「开始执行」应当把执行依据落到「仅声明」。
     *
     * <p>这是证据维度的起点：飞手说他开始飞了，系统如实记下这句话，
     * 但此刻没有任何外部数据能佐证 —— 组合「执行中 + 仅声明」是合法且有意义的。
     */
    @Test
    void startShouldMarkEvidenceDeclared() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int taskId = startedTask(adminToken, "13900006001", "DRN-9801", "证据起点验证",
                "2027-06-01 09:00:00", "2027-06-01 11:00:00");

        assertThat(evidenceStateOf(taskId)).isEqualTo("declared");
        // 只有声明在，证据类型和证据时刻都还空着 —— 它们是「有依据」之后才有的东西
        assertThat(evidenceTypeOf(taskId)).isNull();
        assertThat(evidenceAtIsNull(taskId)).isTrue();
    }

    /**
     * 收到该设备的遥测后：依据抬到「已证实」，原始报文完整归档，任务状态一字不动。
     *
     * <p>这条用例是整个接入链路的核心断言，三个点缺一不可：
     * 依据升级（数据撑腰）、原文归档（证据要经得起追问）、状态不变（两个维度正交）。
     */
    @Test
    void telemetryShouldConfirmEvidenceAndArchiveRawMessage() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9802";
        int taskId = startedTask(adminToken, "13900006002", deviceCode, "证据证实验证",
                "2027-06-02 09:00:00", "2027-06-02 11:00:00");
        assertThat(evidenceStateOf(taskId)).isEqualTo("declared");

        // 报文刻意多带一个我们没有解析的字段（firmware）——
        // 它正是「存原文而不是存解析结果」的意义所在
        String raw = """
                {"deviceCode":"%s","type":"position","observedAt":"2027-06-02 09:30:00","seq":1001,
                 "payload":{"lng":116.397,"lat":39.908,"alt":120.5,"battery":87},
                 "firmware":"v3.2.1"}""".formatted(deviceCode);
        receiveTelemetry(deviceCode, raw);

        assertThat(evidenceStateOf(taskId)).isEqualTo("confirmed");
        assertThat(evidenceTypeOf(taskId)).isEqualTo("telemetry");
        assertThat(evidenceAtIsNull(taskId)).isFalse();

        // 证据是另一个维度：依据变了，业务状态一点没动
        assertThat(taskStatusOf(taskId)).isEqualTo("executing");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT task_id, device_code, type, source, seq, observed_at, valid + 0 AS valid_num, payload "
                        + "FROM task_evidence WHERE device_code = ?", deviceCode);

        assertThat(numberOf(row, "task_id")).isEqualTo(taskId);
        assertThat(row.get("device_code")).isEqualTo(deviceCode);
        assertThat(row.get("type")).isEqualTo("telemetry");
        assertThat(row.get("source")).isEqualTo("device");
        assertThat(numberOf(row, "seq")).isEqualTo(1001L);
        assertThat(numberOf(row, "valid_num")).isEqualTo(1L);
        // 设备报的时刻与我们收到的时刻是两个字段，不能混为一谈
        assertThat(jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(observed_at, '%Y-%m-%d %H:%i:%s') FROM task_evidence WHERE device_code = ?",
                String.class, deviceCode)).isEqualTo("2027-06-02 09:30:00");
        // 存的是设备发出来的那段原文，包括我们没解析的 firmware 字段
        assertThat(row.get("payload")).isEqualTo(raw);
    }

    /**
     * 同一条报文被投递两次（QoS 1 的重复投递）只应留下一条记录。
     *
     * <p>去重靠的是数据库上的唯一索引 {@code uk_evidence_dedup}，
     * 不是应用层「先查再插」—— 只有数据库能提供判断与写入的原子性。
     */
    @Test
    void duplicateTelemetryShouldBeIgnoredByUniqueKey() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9803";
        startedTask(adminToken, "13900006003", deviceCode, "重复投递验证",
                "2027-06-03 09:00:00", "2027-06-03 11:00:00");

        String raw = telemetryJson(deviceCode, 2001L, "2027-06-03 09:30:00");
        receiveTelemetry(deviceCode, raw);
        // 一模一样再发一次：seq 与 observedAt 都没变，就是重传
        receiveTelemetry(deviceCode, raw);

        assertThat(evidenceCountOf(deviceCode)).isEqualTo(1);
    }

    /**
     * 同一秒内的两条<b>不同</b>数据都要留下。
     *
     * <p>这正是去重键里必须有 seq 的原因：高频遥测完全可能在一秒内产生多条，
     * 只拿「设备 + 类型 + 时刻」当键，后一条会被误判成重复丢掉。
     */
    @Test
    void sameSecondWithDifferentSeqShouldBothBeStored() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9804";
        startedTask(adminToken, "13900006004", deviceCode, "同秒双条验证",
                "2027-06-04 09:00:00", "2027-06-04 11:00:00");

        receiveTelemetry(deviceCode, telemetryJson(deviceCode, 3001L, "2027-06-04 09:30:00"));
        receiveTelemetry(deviceCode, telemetryJson(deviceCode, 3002L, "2027-06-04 09:30:00"));

        assertThat(evidenceCountOf(deviceCode)).isEqualTo(2);
    }

    /**
     * 没有正在执行的任务时，数据照样归档，只是 task_id 为空。
     *
     * <p>试飞、转场、维护时设备也在发数据 —— 那些数据是客观事实，值得留痕，
     * 只是不参与任何任务的证据判定。这就是 {@code task_evidence.task_id} 允许为空的意义。
     */
    @Test
    void telemetryWithoutExecutingTaskShouldStillBeArchived() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9805";
        // 只登记设备、不建任务：设备存在，但没有任务在执行
        addDeviceAndGetId(adminToken, deviceCode, "空闲设备");

        String raw = telemetryJson(deviceCode, 4001L, "2027-06-05 09:30:00");
        receiveTelemetry(deviceCode, raw);

        assertThat(evidenceCountOf(deviceCode)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT task_id IS NULL FROM task_evidence WHERE device_code = ?", Integer.class, deviceCode))
                .isEqualTo(1);
    }

    /**
     * 未登记设备的数据直接丢弃。
     *
     * <p>设备档案里没有这台设备，它就不可能被派过任何任务，数据永远无法佐证任何事；
     * 而这种情况通常意味着编号填错了、或者有人拿着凭证在乱发，两种都需要人来处理。
     * 所以宁可丢数据 + 打告警，也不悄悄收下一堆来路不明的行。
     */
    @Test
    void telemetryFromUnknownDeviceShouldBeDropped() throws Exception {
        String unknownCode = "DRN-9999-NOT-REGISTERED";
        receiveTelemetry(unknownCode, telemetryJson(unknownCode, 5001L, "2027-06-06 09:30:00"));

        assertThat(evidenceCountOf(unknownCode)).isZero();
    }

    /**
     * 时间无法识别、或缺少 seq 的报文整条丢弃。
     *
     * <p>两个字段都是<b>去重键的组成部分</b>，缺了就没法判断「这是重传还是新数据」。
     * 与其存一堆无法去重的行，不如明确拒绝。
     */
    @Test
    void telemetryWithUnusableDedupKeyShouldBeDropped() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9806";
        addDeviceAndGetId(adminToken, deviceCode, "脏数据设备");

        // 时间是句人话，但不是任何一种可解析的时间格式
        receiveTelemetry(deviceCode, """
                {"type":"position","observedAt":"昨天下午","seq":6001,"payload":{}}""");
        // 缺 seq：去重键不完整
        receiveTelemetry(deviceCode, """
                {"type":"position","observedAt":"2027-06-07 09:30:00","payload":{}}""");
        // 缺 type：连这是什么消息都不知道
        receiveTelemetry(deviceCode, """
                {"observedAt":"2027-06-07 09:30:00","seq":6002,"payload":{}}""");

        assertThat(evidenceCountOf(deviceCode)).isZero();
    }

    /**
     * 设备侧时间格式要宽容：四种常见写法都应解析成功并存成同一个时刻。
     *
     * <p>时间格式不由我们决定 —— 不同厂商、不同固件发出来的都不一样。
     * 认不出来的代价是整条数据被丢，所以这里的宽容度直接决定了接入的成功率。
     */
    @Test
    void telemetryShouldAcceptCommonDeviceTimeFormats() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9807";
        addDeviceAndGetId(adminToken, deviceCode, "时间格式设备");

        LocalDateTime target = LocalDateTime.of(2027, 6, 8, 9, 30);
        long epochMillis = target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        // ① 我们文档里推荐的格式
        receiveTelemetry(deviceCode, telemetryJson(deviceCode, 7001L, "2027-06-08 09:30:00"));
        // ② epoch 毫秒（固件直接发时间戳）
        receiveTelemetry(deviceCode, telemetryJson(deviceCode, 7002L, String.valueOf(epochMillis)));
        // ③ epoch 秒（10 位，靠位数和毫秒区分）
        receiveTelemetry(deviceCode, telemetryJson(deviceCode, 7003L, String.valueOf(epochMillis / 1000)));
        // ④ 不带时区的 ISO 串（安卓 / JS 生态常见）
        receiveTelemetry(deviceCode, telemetryJson(deviceCode, 7004L, "2027-06-08T09:30:00"));
        // ⑤ 带时区偏移的 ISO 串
        receiveTelemetry(deviceCode, telemetryJson(deviceCode, 7005L,
                target.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()));

        List<String> stored = jdbcTemplate.queryForList(
                "SELECT DATE_FORMAT(observed_at, '%Y-%m-%d %H:%i:%s') FROM task_evidence "
                        + "WHERE device_code = ? ORDER BY seq", String.class, deviceCode);
        assertThat(stored).containsExactly(
                "2027-06-08 09:30:00", "2027-06-08 09:30:00", "2027-06-08 09:30:00",
                "2027-06-08 09:30:00", "2027-06-08 09:30:00");
    }

    /**
     * 声明超时未获旁证 → 标记为「声明已过期」，但<b>任务状态不动、指派关系不清</b>。
     *
     * <p>这是整个设计里最需要守住的一条边界：系统只能指出「这里对不上」，
     * 不能替人下结论说「飞手一定没飞」。设备数据没上来完全可能是图传断了、信号差。
     */
    @Test
    void staleEvidenceShouldBeMarkedWithoutTouchingTaskStatus() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9808";
        int taskId = startedTask(adminToken, "13900006008", deviceCode, "证据过期验证",
                "2027-06-09 09:00:00", "2027-06-09 11:00:00");
        // 把「开始执行」拨到 10 分钟前，等价于「声明完等了 10 分钟，一条数据都没来」
        ageTaskStartTime(taskId, 10);

        // 阈值传 0：判定线就是「现在」，上面已经拨早了，必定命中
        assertThat(taskService.markStaleEvidence(0)).isGreaterThanOrEqualTo(1);

        assertThat(evidenceStateOf(taskId)).isEqualTo("stale");
        // 状态还是「执行中」，飞手与设备一个都没被释放
        assertThat(taskStatusOf(taskId)).isEqualTo("executing");
        TaskPO reloaded = taskDao.selectById((long) taskId);
        assertThat(reloaded.getPilotId()).isNotNull();
        assertThat(reloaded.getDroneId()).isNotNull();
    }

    /**
     * 标成过期之后真数据到了，依据要能翻回「已证实」。
     *
     * <p>「过期」的判断本来就只是「暂时没等到」，不是对人的判决 ——
     * 数据来了就该翻回来。这也是为什么升级用的是
     * {@code WHERE evidence_state <> 'confirmed'} 而不是 {@code = 'declared'}。
     */
    @Test
    void telemetryShouldRecoverStaleEvidenceToConfirmed() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9809";
        int taskId = startedTask(adminToken, "13900006009", deviceCode, "过期后补证据",
                "2027-06-10 09:00:00", "2027-06-10 11:00:00");
        ageTaskStartTime(taskId, 10);

        taskService.markStaleEvidence(0);
        assertThat(evidenceStateOf(taskId)).isEqualTo("stale");

        receiveTelemetry(deviceCode, telemetryJson(deviceCode, 9001L, "2027-06-10 09:30:00"));

        assertThat(evidenceStateOf(taskId)).isEqualTo("confirmed");
    }

    /**
     * 已经证实的任务，之后的过期扫描不能把它降级回去。
     *
     * <p>定时任务每轮都会扫到所有「执行中 + 仅声明」的任务，它是<b>只降级没依据的</b>，
     * 不是无条件覆盖。这条不变式靠 SQL 里的 {@code AND evidence_state = 'declared'} 保证。
     */
    @Test
    void staleScanShouldNotDowngradeConfirmedEvidence() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9810";
        int taskId = startedTask(adminToken, "13900006010", deviceCode, "已证实不被降级",
                "2027-06-11 09:00:00", "2027-06-11 11:00:00");

        receiveTelemetry(deviceCode, telemetryJson(deviceCode, 10001L, "2027-06-11 09:30:00"));
        assertThat(evidenceStateOf(taskId)).isEqualTo("confirmed");

        // 再跑一次过期扫描：把声明时刻拨早，确保这条任务<b>确实落进了扫描范围</b> ——
        // 不然「没被降级」有可能只是因为它压根没被扫到，那条用例就等于没验
        ageTaskStartTime(taskId, 10);
        taskService.markStaleEvidence(0);
        assertThat(evidenceStateOf(taskId)).isEqualTo("confirmed");
    }

    /**
     * `event/xxx` 与 `status` 两个 topic 也订阅了，但它们<b>没有按事件类型分发的动作</b>：
     * 报文按 `telemetry` 证据归档，原始的消息类型留在 payload 里。
     *
     * <p>这条用例锁定的是「当前实现」而不是「理想设计」——
     * 把「设备在说话」当成旁证是够的，粒度还到不了「设备说了什么」。
     * 将来真做 `status=offline → stale`、`event/battery-low → 告警` 时，
     * 这条用例应该跟着改（改了才说明行为变过），文档
     * docs/MQTT与设备接入.md 的 4.13.7 也是这么写的。
     */
    @Test
    void eventAndStatusShouldBeArchivedAsTelemetry() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9811";
        int taskId = startedTask(adminToken, "13900006011", deviceCode, "事件报文归档验证",
                "2027-06-12 09:00:00", "2027-06-12 11:00:00");

        receiveOnTopic(deviceCode, "event/takeoff", """
                {"deviceCode":"%s","type":"takeoff","observedAt":"2027-06-12 09:30:00","seq":11001,
                 "payload":{"at":"2027-06-12 09:30:00"}}""".formatted(deviceCode));
        receiveOnTopic(deviceCode, "status", """
                {"deviceCode":"%s","type":"status","observedAt":"2027-06-12 09:30:01","seq":11002,
                 "payload":{"flightState":"flying","battery":81,"gpsLevel":4}}""".formatted(deviceCode));

        // 两条都被归档成 telemetry 证据，且都关联到了那条执行中的任务
        assertThat(evidenceCountOf(deviceCode)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT type FROM task_evidence WHERE device_code = ? ORDER BY seq",
                String.class, deviceCode)).containsExactly("telemetry", "telemetry");
        assertThat(jdbcTemplate.queryForList(
                "SELECT task_id FROM task_evidence WHERE device_code = ? ORDER BY seq",
                Long.class, deviceCode)).containsExactly((long) taskId, (long) taskId);
        // 原始的消息类型没丢：它在归档的原文里查得到
        assertThat(jdbcTemplate.queryForList(
                "SELECT payload FROM task_evidence WHERE device_code = ? ORDER BY seq",
                String.class, deviceCode))
                .allSatisfy(payload -> assertThat(payload).contains("\"type\":\""));
    }

    // ---------- 告警识别与落库（实施步骤第一步）----------
    // 设计见 docs/告警体系设计.md。这一轮只做「遥测类的阈值识别 + 落库」，
    // 不做通知、不做界面、不做处理闭环。

    /**
     * 电量掉到临界值：应产生一条 critical 告警，并带上「需要飞手动手」的提示。
     *
     * <p>同时断言证据照旧落库 —— 一条遥测在<b>两个维度</b>上各留一份记录：
     * 证据回答「这件事发生过吗」，告警回答「哪里不对劲」。两者都要留。
     */
    @Test
    void telemetryAlertShouldBeRaisedForCriticalBattery() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9821";
        int taskId = startedTask(adminToken, "13900006021", deviceCode, "告警落库验证",
                "2027-07-01 09:00:00", "2027-07-01 11:00:00");

        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 21001L, "2027-07-01 09:30:00",
                """
                        {"battery":10,"alt":100,"flightState":"flying"}"""));

        Map<String, Object> alert = alertRowOf(deviceCode, "bat.critical");
        assertThat(alert.get("severity")).isEqualTo("critical");
        assertThat(numberOf(alert, "task_id")).isEqualTo(taskId);
        assertThat(alert.get("status")).isEqualTo("pending");
        assertThat(alert.get("source")).isEqualTo("device");
        // 配置里给 bat.critical 配了动作提示，所以这两个字段应该有值
        assertThat(numberOf(alert, "pilot_action_required")).isEqualTo(1L);
        assertThat(alert.get("action_hint")).isEqualTo("立即返航并就近降落");
        // 文案要能看懂：带上实际值和阈值
        assertThat(String.valueOf(alert.get("message"))).contains("10").contains("15");

        // 证据与告警是两回事，两个都要有
        assertThat(evidenceCountOf(deviceCode)).isEqualTo(1);
    }

    /**
     * 同一测点的两个阈值只有最严重的那条成立时，<b>只报最严重的</b>。
     *
     * <p>电量 28% 同时满足「≤30（低）」和「不满足 ≤15（严重）」——
     * 只该出 low 一条。如果两个阈值都报，一条报文会触发两个告警，
     * 人看到会以为出了两件事。
     */
    @Test
    void alertThresholdsShouldBeMutuallyExclusive() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9822";
        startedTask(adminToken, "13900006022", deviceCode, "阈值互斥验证",
                "2027-07-02 09:00:00", "2027-07-02 11:00:00");

        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 22001L, "2027-07-02 09:30:00",
                """
                        {"battery":28}"""));

        assertThat(alertCountOf(deviceCode, "bat.low")).isEqualTo(1);
        assertThat(alertCountOf(deviceCode, "bat.critical")).isZero();
    }

    /**
     * 报文里没报的字段，<b>不许当成 0 来判</b>。
     *
     * <p>否则「设备没报电量」会被判成「电量 0」，每个架次都造出一条假告警 ——
     * 假告警比不报警更糟，因为会让人对整个告警体系失去信任。
     */
    @Test
    void alertShouldNotBeRaisedWhenFieldMissing() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9823";
        startedTask(adminToken, "13900006023", deviceCode, "缺字段不误报",
                "2027-07-03 09:00:00", "2027-07-03 11:00:00");

        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 23001L, "2027-07-03 09:30:00",
                """
                        {"alt":100,"flightState":"flying"}"""));

        assertThat(alertCountOf(deviceCode)).isZero();
        // 没告警不代表没数据 —— 证据照旧
        assertThat(evidenceCountOf(deviceCode)).isEqualTo(1);
    }

    /** 同一条报文被投递两次（设备侧按约定重报、或 QoS 1 重投），告警只该留一行 */
    @Test
    void alertShouldBeDeduplicated() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9824";
        startedTask(adminToken, "13900006024", deviceCode, "告警去重验证",
                "2027-07-04 09:00:00", "2027-07-04 11:00:00");

        String raw = telemetryJsonWithPayload(deviceCode, 24001L, "2027-07-04 09:30:00",
                """
                        {"gpsLevel":0}""");
        receiveTelemetry(deviceCode, raw);
        receiveTelemetry(deviceCode, raw);

        assertThat(alertCountOf(deviceCode, "gps.lost")).isEqualTo(1);
    }

    /**
     * 设备没有执行中的任务时，告警<b>照样要报</b>，只是 task_id 为空。
     *
     * <p>这是告警和证据共有的一个性质：设备是按自己的节奏在发数据的，
     * 试飞、转场、机库自检时报的问题，跟哪条任务都没关系，但必须记下来、必须有人管。
     */
    @Test
    void alertShouldBeRaisedEvenWithoutExecutingTask() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9825";
        // 只登记设备、不建任务
        addDeviceAndGetId(adminToken, deviceCode, "无任务设备");

        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 25001L, "2027-07-05 09:30:00",
                """
                        {"gpsLevel":0}"""));

        Map<String, Object> alert = alertRowOf(deviceCode, "gps.lost");
        assertThat(alert.get("severity")).isEqualTo("critical");
        assertThat(alert.get("task_id")).isNull();
    }

    /**
     * 高度类告警只在「明确在空中」时才判 —— 这是防误报的关键条件。
     *
     * <p>起飞和降落时飞机本来就在地面附近。不加这个条件，
     * 每个架次都会在起降阶段报「低于安全高度」。
     */
    @Test
    void altitudeAlertShouldOnlyFireWhenAirborne() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9826";
        startedTask(adminToken, "13900006026", deviceCode, "高度告警上下文验证",
                "2027-07-06 09:00:00", "2027-07-06 11:00:00");

        // 没报飞行状态 → 不知道在不在空中 → 不判
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 26001L, "2027-07-06 09:30:00",
                """
                        {"alt":1}"""));
        assertThat(alertCountOf(deviceCode, "alt.low")).isZero();

        // 明确说在地面 → 也不判
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 26002L, "2027-07-06 09:30:01",
                """
                        {"alt":1,"flightState":"on_ground"}"""));
        assertThat(alertCountOf(deviceCode, "alt.low")).isZero();

        // 明确在空中 → 判
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 26003L, "2027-07-06 09:30:02",
                """
                        {"alt":1,"flightState":"flying"}"""));
        assertThat(alertCountOf(deviceCode, "alt.low")).isEqualTo(1);
    }

    /**
     * 一条报文可以同时触发多条告警（不同维度互不相干）。
     *
     * <p>电量、速度、电机是三个不同的测点，同时出问题就该报三条。
     */
    @Test
    void multipleAlertsInOneMessageShouldAllBeRecorded() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9827";
        startedTask(adminToken, "13900006027", deviceCode, "多告警同时触发",
                "2027-07-07 09:00:00", "2027-07-07 11:00:00");

        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 27001L, "2027-07-07 09:30:00",
                """
                        {"battery":5,"speed":20,"motorState":"error"}"""));

        assertThat(alertCountOf(deviceCode)).isEqualTo(3);
        assertThat(alertCountOf(deviceCode, "bat.critical")).isEqualTo(1);
        assertThat(alertCountOf(deviceCode, "speed.over")).isEqualTo(1);
        assertThat(alertCountOf(deviceCode, "motor.fault")).isEqualTo(1);
    }

    /**
     * 事件类报文的 <b>payload 不做阈值判定</b> —— 这条最容易搞混，所以单独测。
     *
     * <p>{@code event/rth} 现在确实会产生一条告警，但那是<b>翻译 topic 里的事件类型</b>，
     * 不是扫 payload。所以即使事件正文里带了一个「会触发阈值」的字段
     * （下面塞了 {@code battery:5}），也<b>不该</b>多出一条 {@code bat.critical} ——
     * 阈值识别只对 {@code telemetry} 那条通道生效。
     *
     * <p>万一哪天把识别写成「不管什么 topic 都扫一遍 payload」，这条用例会变成 2 条告警，
     * 而现实中这种报文很容易出现：设备在事件里顺带带上当时的电量是完全合理的。
     */
    @Test
    void eventPayloadShouldNotBeScannedForThresholds() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9841";
        startedTask(adminToken, "13900006041", deviceCode, "事件正文不扫阈值",
                "2027-07-08 09:00:00", "2027-07-08 11:00:00");

        receiveOnTopic(deviceCode, "event/rth", eventJson(deviceCode, "rth", 36001L,
                "2027-07-08 09:30:00", """
                        {"battery":5,"reason":"low-battery"}"""));

        // 只有「翻译事件类型」得到的这一条；正文里的 battery=5 没有触发阈值告警
        assertThat(alertCountOf(deviceCode)).isEqualTo(1);
        assertThat(alertCountOf(deviceCode, "evt.rth")).isEqualTo(1);
        assertThat(alertCountOf(deviceCode, "bat.critical")).isZero();
        // 事件报文照样归档成证据（告警不替代证据，两者是并列的两条线）
        assertThat(evidenceCountOf(deviceCode)).isEqualTo(1);
    }

    // ---------- 状态类与事件类告警（实施步骤第四步）----------
    // 设计见 docs/告警体系设计.md 2.2 / 2.3 节。与遥测类的本质区别：
    // **事件是设备判定的，阈值的判定是我们做的**。

    /**
     * 设备离线：级别<b>随上下文变</b> —— 这是全清单里唯一一条。
     *
     * <p>设备闲着的时候掉线，可能只是关机了（warning）；
     * 设备掉线时身上还有「执行中」的任务，那是飞机失联了（critical）。
     *
     * <p>判据就是「这台设备此刻有没有执行中的任务」，
     * 这也是落库时要顺手记 {@code task_id} 的实际用途之一。
     */
    @Test
    void deviceOfflineShouldBeWarningWhenDeviceIsIdle() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9842";
        // 只登记设备、不建任务 —— 设备自己开机自检时也可能掉线
        addDeviceAndGetId(adminToken, deviceCode, "空闲离线设备");

        receiveOnTopic(deviceCode, "status", statusJson(deviceCode, 36011L, "2027-07-09 09:30:00", "offline"));

        Map<String, Object> alert = alertRowOf(deviceCode, "device.offline");
        assertThat(alert.get("severity")).isEqualTo("warning");
        assertThat(alert.get("task_id")).isNull();
        // 「设备离线」不是飞手能当场解决的事（他去不了机库），所以不做成飞手待办
        assertThat(alert.get("pilot_action_required")).isEqualTo(0);
    }

    @Test
    void deviceOfflineShouldBeCriticalWhenTaskIsExecuting() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9843";
        int taskId = startedTask(adminToken, "13900006043", deviceCode, "执行中失联",
                "2027-07-09 10:00:00", "2027-07-09 12:00:00");

        receiveOnTopic(deviceCode, "status", statusJson(deviceCode, 36021L, "2027-07-09 10:30:00", "offline"));

        Map<String, Object> alert = alertRowOf(deviceCode, "device.offline");
        assertThat(alert.get("severity")).isEqualTo("critical");
        assertThat(alert.get("task_id")).isEqualTo((long) taskId);
        assertThat(String.valueOf(alert.get("message"))).as("要说清为什么是 critical").contains("执行中的任务");
    }

    /**
     * 「设备在正常运转」的通报<b>不产生告警</b>：上线、起飞、降落。
     *
     * <p>三条理由（第三条是决定性的）：
     * ① 原始报文已经归档成证据，飞行时间线一条不少；
     * ② 每次开机、每个架次都会产生，属于会让人对通知脱敏的那一类；
     * ③ 告警的抑制规则是「同一设备同一编码还有未处理的就不再产生新行」，
     *    而 info 级<b>永远不会有人去把它关掉</b> —— 两者叠加，
     *    某台设备的第一次「起飞」会永久挂在待处理列表里，并且永远挡住后面的「起飞」。
     */
    @Test
    void narrativeStatusAndEventShouldNotRaiseAlert() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9844";
        startedTask(adminToken, "13900006044", deviceCode, "通报类不产生告警",
                "2027-07-10 09:00:00", "2027-07-10 11:00:00");

        receiveOnTopic(deviceCode, "status", statusJson(deviceCode, 36031L, "2027-07-10 09:29:00", "online"));
        receiveOnTopic(deviceCode, "event/takeoff", eventJson(deviceCode, "takeoff", 36032L,
                "2027-07-10 09:30:00", """
                        {"at":"2027-07-10 09:30:00"}"""));
        receiveOnTopic(deviceCode, "event/land", eventJson(deviceCode, "land", 36033L,
                "2027-07-10 10:30:00", """
                        {"at":"2027-07-10 10:30:00"}"""));

        assertThat(alertCountOf(deviceCode)).isZero();
        // 不产生告警 ≠ 丢掉：三份原始报文都在证据里，想复盘随时查得到
        assertThat(evidenceCountOf(deviceCode)).isEqualTo(3);
    }

    /**
     * 认不出的事件类型只记 debug，<b>不落库、不报错</b>。
     *
     * <p>设备固件可能比服务端新，会发我们还不认识的事件。
     * 把它当错误处理，「设备升级」就会变成一次线上故障；
     * 而原始报文已经归档成证据，真要排查时查得到。
     */
    @Test
    void unknownDeviceEventShouldBeIgnored() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9845";
        startedTask(adminToken, "13900006045", deviceCode, "未知事件验证",
                "2027-07-11 09:00:00", "2027-07-11 11:00:00");

        receiveOnTopic(deviceCode, "event/hover-into-sun", eventJson(deviceCode, "hover-into-sun", 36041L,
                "2027-07-11 09:30:00", """
                        {}"""));

        assertThat(alertCountOf(deviceCode)).isZero();
        assertThat(evidenceCountOf(deviceCode)).isEqualTo(1);
    }

    /**
     * 设备判定的四类事件各自翻译成一条告警，级别与「要不要飞手动手」都按清单来。
     *
     * <p>和遥测类最大的不同：这里我们<b>不判断严不严重</b>，
     * 设备说发生了，我们就记下来。所以文案是「设备上报事件：xx」，
     * 而不是像遥测那样带上一个我们自己算出来的阈值。
     */
    @Test
    void deviceEventsShouldBeTranslatedToAlerts() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9846";
        int taskId = startedTask(adminToken, "13900006046", deviceCode, "事件翻译验证",
                "2027-07-12 09:00:00", "2027-07-12 11:00:00");

        receiveOnTopic(deviceCode, "event/battery-low", eventJson(deviceCode, "battery-low", 36051L,
                "2027-07-12 09:30:00", """
                        {"battery":9,"at":"2027-07-12 09:30:00"}"""));
        receiveOnTopic(deviceCode, "event/rth", eventJson(deviceCode, "rth", 36052L,
                "2027-07-12 09:31:00", """
                        {"reason":"low-battery"}"""));
        receiveOnTopic(deviceCode, "event/mission-interrupt", eventJson(deviceCode, "mission-interrupt", 36053L,
                "2027-07-12 09:32:00", """
                        {}"""));
        receiveOnTopic(deviceCode, "event/emergency-stop", eventJson(deviceCode, "emergency-stop", 36054L,
                "2027-07-12 09:33:00", """
                        {}"""));

        assertThat(alertCountOf(deviceCode)).isEqualTo(4);

        Map<String, Object> batteryLow = alertRowOf(deviceCode, "evt.battery.low");
        assertThat(batteryLow.get("severity")).isEqualTo("warning");
        // 事件正文里的电量写进文案 —— 读日志/看列表的人不用再点进去查原始报文
        assertThat(String.valueOf(batteryLow.get("message"))).contains("9%");
        // 设备自己报的电量低，不必让飞手「动手」（设备已经判完了），所以不带动作提示
        assertThat(batteryLow.get("pilot_action_required")).isEqualTo(0);

        Map<String, Object> rth = alertRowOf(deviceCode, "evt.rth");
        assertThat(rth.get("severity")).isEqualTo("warning");
        assertThat(rth.get("pilot_action_required")).as("返航要现场确认原因").isEqualTo(1);
        assertThat(String.valueOf(rth.get("action_hint"))).contains("返航");

        Map<String, Object> interrupt = alertRowOf(deviceCode, "evt.mission.interrupt");
        assertThat(interrupt.get("severity")).isEqualTo("critical");
        assertThat(interrupt.get("pilot_action_required")).isEqualTo(1);

        Map<String, Object> emergencyStop = alertRowOf(deviceCode, "evt.emergency.stop");
        assertThat(emergencyStop.get("severity")).isEqualTo("critical");
        assertThat(emergencyStop.get("pilot_action_required")).isEqualTo(1);

        // 事件和遥测一样会关联到当时执行中的任务 —— 它本来就是「这次飞行里发生的事」
        assertThat(jdbcTemplate.queryForList(
                "SELECT task_id FROM task_alert WHERE device_code = ? ORDER BY id", Long.class, deviceCode))
                .containsExactly((long) taskId, (long) taskId, (long) taskId, (long) taskId);
    }

    // ---------- 飞手端：看告警 + 现场上报 + 照片（实施步骤第五步）----------
    // 设计见 docs/告警体系设计.md 10.2 节与第十二章。
    // 这一节测的核心是**权限边界**：飞手只能看/只操作派给自己的任务。

    /**
     * 飞手只能看<b>自己任务</b>上的告警，别人的任务一律 403。
     *
     * <p>这条边界靠的是 {@code requireMyTask}。它必须被验到，因为
     * 「把 pilotId 改成别人的」是这类接口最经典的越权手法 ——
     * 而且本项目里 {@code TaskQueryParam} 恰好有个客户端可传的 {@code pilotId} 字段，
     * 更容易让人以为「传一下就能看别人的」。
     */
    @Test
    void pilotShouldOnlySeeAlertsOfOwnTask() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9851";
        int taskId = startedTask(adminToken, "13900006051", deviceCode, "只看自己任务的告警",
                "2027-08-01 09:00:00", "2027-08-01 11:00:00");

        // 造一条挂在任务上的设备告警：电量 5% → bat.critical
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 37001L,
                "2027-08-01 09:30:00", """
                        {"battery":5}"""));

        // 本人：看得到
        String pilotToken = loginAndGetToken("13900006051", "123456");
        mockMvc.perform(get("/task/alert/list")
                        .param("taskId", String.valueOf(taskId))
                        .header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].alertCode").value(AlertCode.BAT_CRITICAL))
                .andExpect(jsonPath("$.data.list[0].severityLabel").value("严重"));

        // 另一个飞手（种子里的张伟）：403，而不是「空列表」。
        // 用 403 是刻意的：告诉调用者「你没权限」，而不是假装这条任务没有告警
        String otherPilotToken = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);
        mockMvc.perform(get("/task/alert/list")
                        .param("taskId", String.valueOf(taskId))
                        .header(AUTH_HEADER, "Bearer " + otherPilotToken))
                .andExpect(jsonPath("$.state").value(403));
    }

    /**
     * 现场上报落库的正确形状：来源是 app、<b>没有设备编号</b>、挂在任务上。
     *
     * <p>{@code device_code} 为 NULL 是这一条的重点：它表达「这不是设备说的」。
     * 顺带带来一个想要的性质 —— 抑制规则（同一设备同一编码还有未处理的就不再产生）
     * 依赖 {@code NULL = NULL} 不成立而失效，所以同一条任务上可以连着报两次同类问题。
     * 那一点由 {@link #repeatedOnsiteReportShouldNotBeSuppressed} 专门验。
     */
    @Test
    void onsiteReportShouldBePersistedAsAppSource() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9852";
        int taskId = startedTask(adminToken, "13900006052", deviceCode, "现场上报落库",
                "2027-08-02 09:00:00", "2027-08-02 11:00:00");
        String pilotToken = loginAndGetToken("13900006052", "123456");

        mockMvc.perform(post("/task/alert/report")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"alertCode":"man.device.damage","message":"右侧机臂有一道裂纹"}"""
                                .formatted(taskId)))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.alertCode").value(AlertCode.MAN_DEVICE_DAMAGE))
                // 级别按清单来：设备损伤是 critical（要停下作业、保护现场）
                .andExpect(jsonPath("$.data.severity").value(AlertSeverity.CRITICAL))
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.alertCodeLabel").value("设备损伤"))
                // 配置里给这类告警指定了「接下来该干什么」，销账时管理侧要看的就是它
                .andExpect(jsonPath("$.data.pilotActionRequired").value(true))
                .andExpect(jsonPath("$.data.actionHint").value("停止作业，保护现场并拍照留证"));

        Map<String, Object> row = onsiteRowOf(taskId, AlertCode.MAN_DEVICE_DAMAGE);
        assertThat(row.get("task_id")).isEqualTo((long) taskId);
        assertThat(row.get("device_code")).as("人工上报没有设备编号").isNull();
        assertThat(row.get("source")).isEqualTo("app");
        assertThat(row.get("message")).isEqualTo("右侧机臂有一道裂纹");
    }

    /**
     * 上报接口<b>只收人工上报类的编码</b>。
     *
     * <p>这是防伪造：{@code alertCode} 是客户端传上来的，不校验类别的话，
     * 飞手可以提交一条 {@code bat.critical} —— 它看起来与「设备实测到电量 10%」
     * 完全一样，但没有任何数据支撑。一旦允许，「设备测到的」和「人说的」
     * 在库里就再也分不开了，而整个告警体系的可信度正建立在那个区分上。
     */
    @Test
    void onsiteReportShouldRejectDeviceMeasuredCode() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9853";
        int taskId = startedTask(adminToken, "13900006053", deviceCode, "伪造设备告警应被拒",
                "2027-08-03 09:00:00", "2027-08-03 11:00:00");
        String pilotToken = loginAndGetToken("13900006053", "123456");

        mockMvc.perform(post("/task/alert/report")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"alertCode":"bat.critical","message":"我猜电量不够了"}"""
                                .formatted(taskId)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("不支持上报该类型的告警")));

        // 一条都没落库
        assertThat(onsiteCountOf(taskId)).isZero();
    }

    /**
     * 同一条任务上连报两次同类问题 → <b>两条记录</b>，不会被抑制成一条。
     *
     * <p>这条验的是设计文档 4.4 节依赖的那个性质：抑制规则的 SQL 是
     * 「{@code WHERE device_code = ? AND alert_code = ? AND status IN (...)}」，
     * 而人工上报的 {@code device_code} 是 NULL —— SQL 里 {@code NULL = NULL} 不成立，
     * 所以判断永远为真、每次都插。
     *
     * <p>业务上这也是对的：飞手一会儿报「东边有人围观」、一会儿报「西边有人围观」，
     * 是两次上报，不该合成一条。要是哪天有人「优化」成给人工上报也填设备编号，
     * 这条用例会立刻变红。
     */
    @Test
    void repeatedOnsiteReportShouldNotBeSuppressed() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9854";
        int taskId = startedTask(adminToken, "13900006054", deviceCode, "连续上报不合并",
                "2027-08-04 09:00:00", "2027-08-04 11:00:00");
        String pilotToken = loginAndGetToken("13900006054", "123456");

        for (String message : List.of("东侧有人围观，暂时不能起飞", "西侧又来了几个人")) {
            mockMvc.perform(post("/task/alert/report")
                            .header(AUTH_HEADER, "Bearer " + pilotToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"taskId":%d,"alertCode":"man.onsite.issue","message":"%s"}"""
                                    .formatted(taskId, message)))
                    .andExpect(jsonPath("$.state").value(200));
        }

        assertThat(onsiteCountOf(taskId)).isEqualTo(2);
    }

    /**
     * 任务卡片角标：只数<b>未处理</b>的，处理掉之后就该消失。
     *
     * <p>口径是刻意的：角标回答「有什么要管的」。要是把已处理的也算进去，
     * 飞手手上每条历史任务都会永远挂着一个数字，那个数字就不再是信号了。
     */
    @Test
    void myTasksShouldCarryOpenAlertCount() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9855";
        int taskId = startedTask(adminToken, "13900006055", deviceCode, "角标口径验证",
                "2027-08-05 09:00:00", "2027-08-05 11:00:00");
        String pilotToken = loginAndGetToken("13900006055", "123456");

        // 刚开始没有告警
        assertThat(myTaskAlertCount(pilotToken, taskId)).isEqualTo(0);
        assertThat(myTaskCriticalCount(pilotToken, taskId)).isZero();

        // 电量 5% → 一条 critical
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 37051L,
                "2027-08-05 09:30:00", """
                        {"battery":5}"""));
        assertThat(myTaskAlertCount(pilotToken, taskId)).isEqualTo(1);
        assertThat(myTaskCriticalCount(pilotToken, taskId)).isEqualTo(1);

        // 管理侧把它当误报关掉（ignore 允许从 pending 直接走）→ 角标归零
        long alertId = alertIdOf(deviceCode, AlertCode.BAT_CRITICAL);
        mockMvc.perform(handleAlertRequest(adminToken, alertId, "ignore", "角标用例：当作误报"))
                .andExpect(jsonPath("$.state").value(200));

        assertThat(myTaskAlertCount(pilotToken, taskId)).isZero();
    }

    /**
     * 上传：白名单外的扩展名一个字节都不该写盘。
     *
     * <p>白名单是<b>安全边界</b>而不是体验问题 —— 不设的话，传一个 {@code .sh}
     * 或 {@code .html} 上去就是隐患（将来某天目录被当成静态资源服务）。
     * 所以这里断言两件事：接口报错，且磁盘上没多出文件。
     */
    @Test
    void uploadShouldRejectDisallowedFileType() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9856";
        int taskId = startedTask(adminToken, "13900006056", deviceCode, "附件白名单",
                "2027-08-06 09:00:00", "2027-08-06 11:00:00");
        String pilotToken = loginAndGetToken("13900006056", "123456");

        // 数文件而不是「看目录在不在」：本类的任务编号是当天的序号，
        // 而用例跑完会回滚，于是**多个用例会拿到同一个 task_no**，
        // 目录因此可能早被别的用例建出来了。数文件才真的在验「这次没写盘」
        long before = countFilesUnderUploadRoot();

        mockMvc.perform(multipart("/task/attachment/upload")
                        .file(new MockMultipartFile("file", "payload.sh",
                                "application/x-sh", "rm -rf /".getBytes(StandardCharsets.UTF_8)))
                        .param("taskId", String.valueOf(taskId))
                        .header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("不支持的文件类型")));

        assertThat(attachmentCountOf(taskId)).as("被拒的上传不该留下数据库记录").isZero();
        assertThat(countFilesUnderUploadRoot()).as("被拒的上传不该写盘").isEqualTo(before);
    }

    /**
     * 上传 + 下载的完整链路，以及三个权限边界。
     *
     * <p>断言四件事：
     * <ol>
     *   <li>文件真的落在配置的根目录下，{@code sha256} 与内容一致（这是完整性对账的依据）</li>
     *   <li>本人能下、能拿到原始字节</li>
     *   <li>管理员能下（他要有 {@code task:list}，靠它看飞手报的证据）</li>
     *   <li><b>别的飞手下不了</b> —— 否则任何飞手都能翻别人的现场照片</li>
     * </ol>
     *
     * <p>最后必须<b>手工删掉磁盘上的文件</b>：数据库有 {@code @Transactional} 兜着，
     * 磁盘没有。不删的话每跑一次就在 {@code target/test-upload} 下留一个真文件。
     */
    @Test
    void uploadAndDownloadShouldRespectOwnership() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9857";
        int taskId = startedTask(adminToken, "13900006057", deviceCode, "附件上传下载",
                "2027-08-07 09:00:00", "2027-08-07 11:00:00");
        String pilotToken = loginAndGetToken("13900006057", "123456");

        byte[] content = "这是一张现场照片的占位内容".getBytes(StandardCharsets.UTF_8);
        long attachmentId = uploadAttachment(pilotToken, taskId, "现场-右臂.jpg", content);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT file_name, file_type, file_size, sha256, storage, storage_key, mime_type "
                        + "FROM task_attachment WHERE id = ?", attachmentId);
        assertThat(row.get("file_name")).isEqualTo("现场-右臂.jpg");
        assertThat(row.get("file_type")).isEqualTo(AttachmentType.IMAGE);
        assertThat(row.get("storage")).isEqualTo(LocalStorageService.NAME);
        assertThat(row.get("mime_type")).isEqualTo("image/jpeg");
        assertThat(row.get("file_size")).isEqualTo((long) content.length);

        String expectedSha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
        assertThat(row.get("sha256")).as("sha256 是算出来的，不是客户端给的").isEqualTo(expectedSha);

        String key = String.valueOf(row.get("storage_key"));
        assertThat(Files.exists(localStorage.root().resolve(key)))
                .as("文件应落在配置的根目录下：%s", localStorage.root()).isTrue();

        try {
            // 本人：拿得到原始字节
            byte[] downloaded = mockMvc.perform(get("/task/attachment/{id}/download", attachmentId)
                            .header(AUTH_HEADER, "Bearer " + pilotToken))
                    .andExpect(status().isOk())
                    // attachment 而不是 inline：就算有人把网页文件改名成 jpg 传上来，
                    // 浏览器也只会下载它、不会当场渲染
                    .andExpect(header().string("Content-Disposition", containsString("attachment")))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(downloaded).isEqualTo(content);

            // 管理员：也能下（他有 task:list）
            mockMvc.perform(get("/task/attachment/{id}/download", attachmentId)
                            .header(AUTH_HEADER, "Bearer " + adminToken))
                    .andExpect(status().isOk());

            // 别的飞手：403
            String otherPilotToken = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);
            mockMvc.perform(get("/task/attachment/{id}/download", attachmentId)
                            .header(AUTH_HEADER, "Bearer " + otherPilotToken))
                    .andExpect(jsonPath("$.state").value(403));
        } finally {
            localStorage.delete(key);
        }
    }

    /**
     * 数据库里的存储标识被人塞进 {@code ../} 时，下载必须被拦下。
     *
     * <p>这是在防「数据库不是可信输入」：那个值可能来自一次漏洞、一次手工改库、
     * 一次写错的迁移脚本。校验的成本是几行代码，漏掉的代价是<b>读到服务器上任意文件</b>
     * （比如 {@code ../../../../etc/passwd}），而且从接口上看不出异常。
     *
     * <p>这里直接改库造这个状态 —— 正常接口造不出来，因为 key 是服务端生成的。
     */
    @Test
    void downloadShouldRejectTraversalKey() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9858";
        int taskId = startedTask(adminToken, "13900006058", deviceCode, "路径穿越防护",
                "2027-08-08 09:00:00", "2027-08-08 11:00:00");
        String pilotToken = loginAndGetToken("13900006058", "123456");

        long attachmentId = uploadAttachment(pilotToken, taskId, "正常照片.png", new byte[]{1, 2, 3});
        String key = jdbcTemplate.queryForObject(
                "SELECT storage_key FROM task_attachment WHERE id = ?", String.class, attachmentId);

        jdbcTemplate.update("UPDATE task_attachment SET storage_key = ? WHERE id = ?",
                "../../../../etc/passwd", attachmentId);

        mockMvc.perform(get("/task/attachment/{id}/download", attachmentId)
                        .header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("存储标识非法")));

        // 收尾：把真文件删掉（磁盘不像数据库那样能回滚）
        localStorage.delete(key);
    }

    // ---------- 报告线：完成任务 / 标记失败 / 报告（实施步骤第一、二步）----------
    // 设计见 docs/任务收尾与执行报告.md。

    /**
     * 主线：完成一条任务，<b>三条线一起收</b>（状态 / 资源 / 交付物）。
     *
     * <p>这是整个项目里唯一一处「三线收尾」的动作，所以断言也分三组：
     * 任务状态与时间、设备与飞手、报告与流水。
     *
     * <p>其中「飞手回空闲」这一条盯的是设计文档 3.3 节那个**顺序陷阱**：
     * {@code countActiveByPilot} 统计的是「已派发 + 执行中」，
     * 如果在改状态之前统计，这条任务自己会被算进去，飞手永远回不到空闲。
     */
    @Test
    void finishShouldSettleStatusResourcesAndReport() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9861";
        int taskId = startedTask(adminToken, "13900006061", deviceCode, "完成任务主线",
                "2027-08-11 09:00:00", "2027-08-11 11:00:00");
        int droneId = droneIdOf(deviceCode);
        int pilotId = userIdOf("13900006061");
        String pilotToken = loginAndGetToken("13900006061", "123456");

        mockMvc.perform(finishRequest(pilotToken, taskId, "success", "按计划完成 12 个点位巡检，未见异常"))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.status").value("finished"));

        // ---- 线一：状态与时间 ----
        assertThat(taskStatusOf(taskId)).isEqualTo("finished");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT actual_end_time FROM `task` WHERE id = ?", LocalDateTime.class, taskId))
                .as("完成必须落下实际结束时间（这一列以前从来没有任何语句写过）").isNotNull();

        // ---- 线二：资源 ----
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM `drone` WHERE id = ?", String.class, droneId)).isEqualTo("idle");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM `user` WHERE id = ?", String.class, pilotId))
                .as("飞手手上没有别的活，应当回到空闲").isEqualTo("idle");
        // 指派关系**必须留着**：报告要写清是谁飞的、用哪架飞的。
        // 所以完成走的是另一条释放路径，不能用 clearDispatch
        Map<String, Object> task = jdbcTemplate.queryForMap(
                "SELECT pilot_id, drone_code FROM `task` WHERE id = ?", taskId);
        assertThat(task.get("pilot_id")).as("完成不能清空飞手").isNotNull();
        assertThat(task.get("drone_code")).isEqualTo(deviceCode);

        // ---- 线三：交付物与流水 ----
        Map<String, Object> report = latestReportOf(taskId);
        assertThat(report.get("version")).isEqualTo(1);
        assertThat(report.get("is_latest")).isEqualTo(1);
        assertThat(report.get("result")).isEqualTo("success");
        assertThat(report.get("summary")).isEqualTo("按计划完成 12 个点位巡检，未见异常");
        assertThat(report.get("reporter_name")).isEqualTo("证据用例飞手");
        assertThat(jdbcTemplate.queryForList(
                "SELECT action FROM task_log WHERE task_id = ? ORDER BY id", String.class, taskId))
                .as("流水里要能看出这条是「完成」而不是别的收尾方式")
                .endsWith("finish");
    }

    /**
     * 顺序陷阱的反面：飞手手上**还有别的活**时，完成一条不该把他置成空闲。
     *
     * <p>一个人可以同时背着不同时段的好几个任务（先是「已派发」还没开工的那条），
     * 完成其中一条不代表他就闲下来了。
     */
    @Test
    void finishShouldKeepPilotBusyWhenOtherTaskRemains() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9862";
        int taskId = startedTask(adminToken, "13900006062", deviceCode, "还给别的活",
                "2027-08-12 09:00:00", "2027-08-12 11:00:00");
        int pilotId = userIdOf("13900006062");

        // 再派一条给同一个飞手（不同设备、不冲突的时间段）—— 状态是「已派发」，也算占用
        int otherDevice = addDeviceAndGetId(adminToken, "DRN-9863", "另一台机");
        int otherTask = createTaskId(adminToken, "同时段外的第二条活",
                "2027-08-13 09:00:00", "2027-08-13 11:00:00");
        dispatchAs(adminToken, otherTask, pilotId, otherDevice);

        String pilotToken = loginAndGetToken("13900006062", "123456");
        mockMvc.perform(finishRequest(pilotToken, taskId, "success", "第一条先完成"))
                .andExpect(jsonPath("$.state").value(200));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM `user` WHERE id = ?", String.class, pilotId))
                .as("手上还有一条已派发的任务，飞手不该回空闲").isEqualTo("busy");
    }

    /**
     * 收尾的权限边界：只能收尾派给自己的任务。
     *
     * <p>和「开始执行」「拒绝执行」「看告警」是同一条防线（{@code requireMyTask}）——
     * 没有它，任何飞手猜到任务 id 就能把别人的任务标记成完成。
     */
    @Test
    void finishShouldRejectOtherPilotsTask() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9864";
        int taskId = startedTask(adminToken, "13900006064", deviceCode, "别人的任务不能收尾",
                "2027-08-14 09:00:00", "2027-08-14 11:00:00");

        String otherPilotToken = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);
        mockMvc.perform(finishRequest(otherPilotToken, taskId, "success", "我来帮他点一下"))
                .andExpect(jsonPath("$.state").value(403));

        assertThat(taskStatusOf(taskId)).as("任务状态不该被动过").isEqualTo("executing");
        assertThat(reportVersionCountOf(taskId)).as("也不该留下报告").isZero();
    }

    /**
     * 结论的字面值要白名单校验，而且<b>收不到 {@code failed}</b>。
     *
     * <p>「失败」必须走 {@code /task/fail} —— 那样 {@code result} 才只描述
     * 「作业完成度」、不与任务状态重叠（设计文档 12.1 那条规则）。
     * 传 {@code failed} 进来时错误信息要指路，不能只说「非法」。
     */
    @Test
    void finishShouldRejectFailedResultWithHint() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9865";
        int taskId = startedTask(adminToken, "13900006065", deviceCode, "失败要走另一个口",
                "2027-08-15 09:00:00", "2027-08-15 11:00:00");
        String pilotToken = loginAndGetToken("13900006065", "123456");

        mockMvc.perform(finishRequest(pilotToken, taskId, "failed", "其实失败了"))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("标记失败")));

        assertThat(taskStatusOf(taskId)).isEqualTo("executing");
    }

    /**
     * 标记失败：与完成同一套，只多一条「必须写原因」，状态落 {@code failed}。
     *
     * <p>没有这条出口，飞手遇到「飞机飞一半故障了」只能谎报完成，
     * 数据里就再也分不出「真完成」和「失败了但走完成」。
     */
    @Test
    void failShouldRequireReasonAndSettleAsFailed() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9866";
        int taskId = startedTask(adminToken, "13900006066", deviceCode, "失败要写原因",
                "2027-08-16 09:00:00", "2027-08-16 11:00:00");
        String pilotToken = loginAndGetToken("13900006066", "123456");

        // 不写原因 → 拒
        mockMvc.perform(post("/task/fail")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d}""".formatted(taskId)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("失败原因")));
        assertThat(taskStatusOf(taskId)).isEqualTo("executing");

        // 写了原因 → 落 failed，并且同样有一份报告（结论由服务端写死 failed）
        mockMvc.perform(post("/task/fail")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"problemLog":"起飞后电机异响，紧急降落"}"""
                                .formatted(taskId)))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.status").value("failed"));

        assertThat(taskStatusOf(taskId)).isEqualTo("failed");
        Map<String, Object> report = latestReportOf(taskId);
        assertThat(report.get("result")).as("结论由服务端写死").isEqualTo("failed");
        assertThat(report.get("problem_log")).isEqualTo("起飞后电机异响，紧急降落");
    }

    /**
     * 提交报告时，附件必须都属于这条任务。
     *
     * <p>附件 id 是客户端传上来的。不校验的话，有人能把自己任务里
     * <b>填上别人任务的附件 id</b> —— 于是别人的现场照片出现在他的报告里。
     * 这是个真实的越权点，所以校验失败要**整笔回滚**（宁可让飞手重提交一次）。
     */
    @Test
    void finishShouldRejectAttachmentOfAnotherTask() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        // 任务 A：要收尾的这条
        int taskA = startedTask(adminToken, "13900006067", "DRN-9867", "自己的任务",
                "2027-08-17 09:00:00", "2027-08-17 11:00:00");
        String pilotToken = loginAndGetToken("13900006067", "123456");

        // 任务 B：同一个飞手的另一条（附件挂在它上面）
        int deviceB = addDeviceAndGetId(adminToken, "DRN-9868", "另一架");
        int taskB = createTaskId(adminToken, "另一条任务", "2027-08-18 09:00:00", "2027-08-18 11:00:00");
        dispatchAs(adminToken, taskB, userIdOf("13900006067"), deviceB);
        long foreignAttachment = uploadAttachment(pilotToken, taskB, "别人的照片.png", new byte[]{9, 9, 9});
        String foreignKey = jdbcTemplate.queryForObject(
                "SELECT storage_key FROM task_attachment WHERE id = ?", String.class, foreignAttachment);

        try {
            mockMvc.perform(post("/task/finish")
                            .header(AUTH_HEADER, "Bearer " + pilotToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"taskId":%d,"result":"success","summary":"顺手把别的照片也带上",
                                     "attachmentIds":[%d]}""".formatted(taskA, foreignAttachment)))
                    .andExpect(jsonPath("$.state").value(500))
                    .andExpect(jsonPath("$.message", containsString("不属于这条任务")));

            assertThat(taskStatusOf(taskA)).as("整笔回滚：任务不该被完成").isEqualTo("executing");
            assertThat(reportVersionCountOf(taskA)).as("也不该留下报告").isZero();
        } finally {
            localStorage.delete(foreignKey);
        }
    }

    /**
     * 报告详情：手填的部分 + <b>系统自动汇总</b>的部分。
     *
     * <p>自动汇总那半才是报告里最可信的内容 —— 它不靠飞手填，
     * 而是从证据里现算出来的。所以用一个真发过遥测的任务来验。
     */
    @Test
    void reportShouldCarryAutoSummary() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9869";
        int taskId = startedTask(adminToken, "13900006069", deviceCode, "报告自动汇总",
                "2027-08-19 09:00:00", "2027-08-19 11:00:00");
        String pilotToken = loginAndGetToken("13900006069", "123456");

        // 两条遥测：一条正常、一条电量 12（会被算进「最低电量」）
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 38001L,
                "2027-08-19 09:30:00", """
                        {"battery":88,"alt":60}"""));
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 38002L,
                "2027-08-19 09:30:05", """
                        {"battery":12,"alt":95}"""));

        mockMvc.perform(finishRequest(pilotToken, taskId, "success", "飞完了"))
                .andExpect(jsonPath("$.state").value(200));

        // 管理侧看报告（他有 task:list）
        mockMvc.perform(get("/task/report/{taskId}", taskId)
                        .header(AUTH_HEADER, "Bearer " + adminToken))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.telemetryCount").value(2))
                .andExpect(jsonPath("$.data.minBattery").value(12))
                .andExpect(jsonPath("$.data.maxAlt").value(95))
                .andExpect(jsonPath("$.data.dataFrom").value("2027-08-19 09:30:00"))
                .andExpect(jsonPath("$.data.dataTo").value("2027-08-19 09:30:05"))
                .andExpect(jsonPath("$.data.evidenceState").value("confirmed"))
                .andExpect(jsonPath("$.data.onlyDeclared").value(false))
                .andExpect(jsonPath("$.data.resultLabel").value("成功"))
                .andExpect(jsonPath("$.data.pilotName").value("证据用例飞手"));
    }

    /**
     * 报告里必须能看出「这次执行<b>只有飞手声明</b>、没有设备数据佐证」。
     *
     * <p>完成**不强制**要求有证据（强制会把「设备数据链路不通」变成
     * 「飞手无法收工」—— 用一个大问题换一个小问题），
     * 但必须<b>让人看得见</b>：飞手要知道自己承担什么，管理员复核时要能发现。
     * 所以 {@code onlyDeclared} 是 true，界面上标红。
     */
    @Test
    void reportShouldMarkOnlyDeclaredWhenNoEvidence() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9871";
        int taskId = startedTask(adminToken, "13900006071", deviceCode, "只有声明",
                "2027-08-21 09:00:00", "2027-08-21 11:00:00");
        String pilotToken = loginAndGetToken("13900006071", "123456");

        // 一条遥测都不发，直接完成
        mockMvc.perform(finishRequest(pilotToken, taskId, "success", "我飞完了"))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.status").value("finished"));

        mockMvc.perform(get("/task/report/{taskId}", taskId)
                        .header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(jsonPath("$.data.telemetryCount").value(0))
                .andExpect(jsonPath("$.data.evidenceState").value("declared"))
                .andExpect(jsonPath("$.data.onlyDeclared").value(true))
                .andExpect(jsonPath("$.data.minBattery").doesNotExist());
    }

    /**
     * 报告详情的权限边界：不是派给自己的任务，看不了报告。
     *
     * <p>报告里有飞了多久、多少条数据、期间几条告警 —— 和任务详情一个敏感度，
     * 所以和「看任务」「下附件」用同一条规则。
     */
    @Test
    void reportShouldBeForbiddenForOtherPilot() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9872";
        int taskId = startedTask(adminToken, "13900006072", deviceCode, "报告不给别人看",
                "2027-08-22 09:00:00", "2027-08-22 11:00:00");
        String pilotToken = loginAndGetToken("13900006072", "123456");
        mockMvc.perform(finishRequest(pilotToken, taskId, "partial", "只完成了一半"))
                .andExpect(jsonPath("$.state").value(200));

        String otherPilotToken = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);
        mockMvc.perform(get("/task/report/{taskId}", taskId)
                        .header(AUTH_HEADER, "Bearer " + otherPilotToken))
                .andExpect(jsonPath("$.state").value(403));

        // 没有报告的任务：报「还没有报告」，而不是 500
        int taskWithoutReport = createTaskId(adminToken, "还没收尾的任务",
                "2027-08-23 09:00:00", "2027-08-23 11:00:00");
        mockMvc.perform(get("/task/report/{taskId}", taskWithoutReport)
                        .header(AUTH_HEADER, "Bearer " + adminToken))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("还没有报告")));
    }

    /**
     * 修订报告：<b>只动报告、不动任务状态</b>，且旧版本留着。
     *
     * <p>这是当初拍板多版本的原因 —— 报告是交付物，飞手笔误、管理员要求补充、
     * 客户提异议都会改。但「改过必须看得出来」：所以版本 +1，
     * 旧版本 {@code is_latest} 翻成 0 留在表里。
     */
    @Test
    void reviseShouldKeepOldVersionAndNotTouchTaskStatus() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9873";
        int taskId = startedTask(adminToken, "13900006073", deviceCode, "报告要能改",
                "2027-08-24 09:00:00", "2027-08-24 11:00:00");
        String pilotToken = loginAndGetToken("13900006073", "123456");

        mockMvc.perform(finishRequest(pilotToken, taskId, "success", "完成了 2 个点位巡检"))
                .andExpect(jsonPath("$.state").value(200));

        // 事后发现写错了：其实是 12 个点位，而且只完成了一部分
        mockMvc.perform(post("/task/report/revise")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"result":"partial","summary":"完成了 12 个点位巡检，其中 2 个待复检"}"""
                                .formatted(taskId)))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.result").value("partial"));

        // 版本留痕：两条都在，只有新的那条是当前版本
        assertThat(reportVersionCountOf(taskId)).as("旧版本要留着").isEqualTo(2);
        Map<String, Object> latest = latestReportOf(taskId);
        assertThat(latest.get("version")).isEqualTo(2);
        assertThat(latest.get("summary")).isEqualTo("完成了 12 个点位巡检，其中 2 个待复检");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_report WHERE task_id = ? AND is_latest = 1",
                Long.class, taskId)).as("当前版本只能有一条").isEqualTo(1L);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT summary, is_latest FROM task_report WHERE task_id = ? AND version = 1", taskId))
                .as("旧版本内容原样保留，改动看得出来")
                .containsEntry("summary", "完成了 2 个点位巡检")
                .containsEntry("is_latest", 0);

        // **任务状态不受影响**：修订改的是一份交付物，不是任务走到了哪一步
        assertThat(taskStatusOf(taskId)).as("修订不该动任务状态").isEqualTo("finished");
        assertThat(actionCountOf(taskId, "finish"))
                .as("修订不该再记一条收尾流水（流水记的是状态流转，报告修订不是流转的一环）")
                .isEqualTo(1);
    }

    /**
     * 任务详情里的「本任务期间的告警」要按 <b>task_id 或 「设备 + 时间窗」</b> 聚合。
     *
     * <p>这条用例的重点是**证明第二种口径真的有用**：设备在飞手点「开始执行」
     * <b>之前</b>就在报数据（飞机先起飞、人后点按钮；或设备按计划时间提前开机自检），
     * 那时还没有执行中的任务，落库的告警 {@code task_id} 是空的。
     * 只按 task_id 查会把这些全部漏掉 —— 而它们恰恰和这条任务有关。
     *
     * <p>同时钉住两件事：不许把「同设备但窗口之外」的告警捞进来，
     * 也不许把「别的设备」的告警捞进来。
     */
    @Test
    void taskDetailShouldIncludeAlertsByTimeWindow() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9881";
        String otherDevice = "DRN-9882";

        // 任务的计划时间定在 2027-07-20，这样「开始前」的报文能落在计划窗口内
        int pilotId = addPilotAndGetId(adminToken, "13900006081", "窗口聚合飞手");
        int droneId = addDeviceAndGetId(adminToken, deviceCode, "窗口聚合机");
        addDeviceAndGetId(adminToken, otherDevice, "无关设备");
        int taskId = createTaskId(adminToken, "期间告警窗口聚合",
                "2027-07-20 09:00:00", "2027-07-20 11:00:00");
        dispatchAs(adminToken, taskId, pilotId, droneId);

        // ① 任务还没开始（没有执行中的任务）→ 这条告警的 task_id 是空的，
        //    但它落在计划窗口内，属于「本次任务期间」
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 39001L,
                "2027-07-20 09:10:00", """
                        {"battery":5}"""));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT task_id FROM task_alert WHERE device_code = ? AND alert_code = 'bat.critical'",
                Long.class, deviceCode)).as("这时还没有执行中的任务，task_id 应当为空").isNull();

        // ② 同一台设备，但在计划窗口之外 → 不该被算进这条任务
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 39002L,
                "2027-07-19 08:00:00", """
                        {"gpsLevel":0}"""));

        // ③ 别的设备的告警 → 同样不该被算进来
        receiveTelemetry(otherDevice, telemetryJsonWithPayload(otherDevice, 39003L,
                "2027-07-20 09:30:00", """
                        {"motorState":"error"}"""));

        // ④ 飞手开始执行后报的 → 这条本来就带 task_id
        String pilotToken = loginAndGetToken("13900006081", "123456");
        mockMvc.perform(post("/task/start").header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":%d}".formatted(taskId)))
                .andExpect(jsonPath("$.state").value(200));
        // 把「实际开始」拨到计划窗口内。为什么不直接用「现在」：
        // 计划时间是写死的 2027，而现在是 2026 —— 两者差一年，
        // 而窗口取的是「计划与实际」的**并集**，于是窗口会被撑到一年宽，
        // 连前一天那条告警也落进去了（这正是这条用例第一次跑出来的结果）。
        // 真实业务里计划与实际总是很接近，所以这里把实际时间也放到 2027-07-20。
        jdbcTemplate.update("UPDATE `task` SET actual_start_time = ? WHERE id = ?",
                LocalDateTime.of(2027, 7, 20, 9, 30), taskId);

        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 39004L,
                "2027-07-20 09:40:00", """
                        {"signal":0}"""));

        // 详情页拿到的告警：① 和 ④ 在，② 与 ③ 不在
        String body = mockMvc.perform(get("/task/{id}", taskId)
                        .header(AUTH_HEADER, "Bearer " + adminToken))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString();

        List<String> codes = new ArrayList<>();
        for (var node : objectMapper.readTree(body).path("data").path("alerts")) {
            codes.add(node.path("alertCode").asText());
        }
        assertThat(codes)
                .as("窗口内的两条要在，窗口外的和别的设备的都不该在")
                .containsExactlyInAnyOrder("bat.critical", "rc.signal.lost");
    }

    // ---------- 地图第一步：任务轨迹（只展示，不动表结构）----------

    /**
     * 轨迹从证据里来，且<b>转成了 GCJ-02</b>。
     *
     * <p>三件事一起验：
     * <ol>
     *   <li>点确实是从证据的报文原文里取出来的，按时间升序</li>
     *   <li>坐标**真的转过了** —— 和送进去的 WGS84 比，偏移约 500 米。
     *       只断言「等于转换函数的结果」是不够的：万一哪里忘了转，
     *       它也会等于自己（没转就原样返回），所以要同时断言**偏移量级**</li>
     *   <li>权限：飞手没有 {@code task:list}，看不了轨迹</li>
     * </ol>
     */
    @Test
    void trackShouldComeFromEvidenceAndBeConvertedToGcj02() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9891";
        int taskId = startedTask(adminToken, "13900006091", deviceCode, "轨迹验证",
                "2027-08-26 09:00:00", "2027-08-26 11:00:00");

        // 20 个点，经度每次挪 0.0002 度（约 17 米）—— 都超过 5 米的抽稀阈值，应当全留
        for (int i = 0; i < 20; i++) {
            receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 40001L + i,
                    "2027-08-26 09:30:" + String.format("%02d", i),
                    """
                            {"lng":%s,"lat":39.9081}""".formatted(116.3972 + i * 0.0002)));
        }

        String body = mockMvc.perform(get("/task/{taskId}/track", taskId)
                        .header(AUTH_HEADER, "Bearer " + adminToken))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.coordinateSystem").value("gcj02"))
                .andExpect(jsonPath("$.data.rawCount").value(20))
                .andReturn().getResponse().getContentAsString();

        JsonNode points = objectMapper.readTree(body).path("data").path("points");
        assertThat(points).as("每个点都移动了约 17 米，超过 5 米阈值，应当一个不落").hasSize(20);

        double firstLng = points.get(0).path("lng").asDouble();
        double firstLat = points.get(0).path("lat").asDouble();
        // ① 真的转了：北京一带 GCJ-02 相对 WGS84 偏东约 500 米
        assertThat(firstLng - 116.3972).as("经度偏移量级（证明不是原样返回）").isBetween(0.005, 0.008);
        // ② 转的是**这批数据**：结果要和转换函数对得上
        double[] expected = CoordinateUtil.wgs84ToGcj02(116.3972, 39.9081);
        assertThat(firstLng).as("经度").isCloseTo(expected[0], offset(1e-6));
        assertThat(firstLat).as("纬度").isCloseTo(expected[1], offset(1e-6));

        // 按时间升序
        assertThat(points.get(0).path("observedAt").asText()).isEqualTo("2027-08-26 09:30:00");
        assertThat(points.get(19).path("observedAt").asText()).isEqualTo("2027-08-26 09:30:19");

        // 权限：飞手没有 task:list
        String pilotToken = loginAndGetToken("13900006091", "123456");
        mockMvc.perform(get("/task/{taskId}/track", taskId)
                        .header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(jsonPath("$.state").value(403));
    }

    /**
     * 密集的点会被抽稀，但<b>首尾两个点永远保留</b>。
     *
     * <p>为什么抽稀是必须的而不是优化：遥测每秒一条，两小时任务有 7000 多个点，
     * 全传给前端画折线会卡，而画出来的线一点没变细
     * （相邻点相隔不到一米，屏幕上是同一个像素）。
     */
    @Test
    void trackShouldBeSampledButKeepHeadAndTail() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9892";
        int taskId = startedTask(adminToken, "13900006092", deviceCode, "抽稀验证",
                "2027-08-27 09:00:00", "2027-08-27 11:00:00");

        // 200 个点，每次只挪 0.000002 度（约 0.17 米）—— 远小于 5 米阈值。
        // ⚠️ 时间戳必须**唯一且递增**：抽稀是按「排序后相邻两点」算距离的，
        // 时间戳一回绕（比如用 i%60 当秒），排完序相邻的点其实隔得很远，
        // 于是一个都抽不掉 —— 这条测试的第一版就是这么假绿的
        for (int i = 0; i < 200; i++) {
            receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 41001L + i,
                    "2027-08-27 09:%02d:%02d".formatted(30 + i / 60, i % 60),
                    """
                            {"lng":%s,"lat":39.9081}""".formatted(116.3972 + i * 0.000002)));
        }

        String body = mockMvc.perform(get("/task/{taskId}/track", taskId)
                        .header(AUTH_HEADER, "Bearer " + adminToken))
                .andExpect(jsonPath("$.data.rawCount").value(200))
                .andReturn().getResponse().getContentAsString();

        JsonNode points = objectMapper.readTree(body).path("data").path("points");
        assertThat(points.size()).as("密集的点应当被大幅抽掉").isLessThan(20);
        assertThat(points.size()).as("但也不能抽到只剩一个").isGreaterThan(1);

        // 首尾必须保留：起点和终点是这条轨迹最要紧的信息
        double[] expectedHead = CoordinateUtil.wgs84ToGcj02(116.3972, 39.9081);
        assertThat(points.get(0).path("lng").asDouble()).as("第一个点").isCloseTo(expectedHead[0], offset(1e-6));
        double[] expectedTail = CoordinateUtil.wgs84ToGcj02(116.3972 + 199 * 0.000002, 39.9081);
        assertThat(points.get(points.size() - 1).path("lng").asDouble())
                .as("最后一个点").isCloseTo(expectedTail[0], offset(1e-6));
    }

    /**
     * 没有坐标的证据不进轨迹；任务不存在时明确报错而不是给一条空轨迹。
     *
     * <p>「任务 id 打错了」和「这条任务没有坐标」该给的反馈完全不同 ——
     * 都返回空列表的话，排查时根本分不清是哪一种。
     */
    @Test
    void trackShouldBeEmptyWhenNoCoordinates() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9893";
        int taskId = startedTask(adminToken, "13900006093", deviceCode, "无坐标轨迹",
                "2027-08-28 09:00:00", "2027-08-28 11:00:00");

        // 只发不带坐标的遥测（电量）—— 这种证据有，但不是轨迹点
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 42001L,
                "2027-08-28 09:30:00", """
                        {"battery":88}"""));

        String body = mockMvc.perform(get("/task/{taskId}/track", taskId)
                        .header(AUTH_HEADER, "Bearer " + adminToken))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.rawCount").value(0))
                .andExpect(jsonPath("$.data.points").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).isNotNull();

        // 任务不存在 → 明确报错
        mockMvc.perform(get("/task/{taskId}/track", 99999999)
                        .header(AUTH_HEADER, "Bearer " + adminToken))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("任务不存在")));
    }

    /**
     * 坐标转换必须与公开实现逐位一致。
     *
     * <h2>为什么这条测试非要有「外部基准」</h2>
     * 自己写的转换算法拿自己算出来的结果去断言，等于什么也没测
     * （算法整体错了，测试照样绿）。所以参考值取自开源实现
     * wandergis/coordtransform 公布的转换结果 —— 那是被大量项目用了很多年的实现。
     *
     * <p>这条测试防的是什么：把 WGS84 直接丢给高德画，位置会偏 500 米左右，
     * 而且**看起来「差不多对」**（房子画到马路对面），肉眼很难发现。
     * 有了这个基准，改坏一个常数就会立刻红。
     */
    @Test
    void coordinateConversionShouldMatchTheReferenceImplementation() {
        // 参考值：wgs84togcj02(116.404, 39.915) = [116.41024449916938, 39.91640428150164]
        double[] gcj = CoordinateUtil.wgs84ToGcj02(116.404, 39.915);
        assertThat(gcj[0]).as("经度").isCloseTo(116.41024449916938, offset(1e-8));
        assertThat(gcj[1]).as("纬度").isCloseTo(39.91640428150164, offset(1e-8));

        // 偏移量级（北京一带约东西 550 米、南北 155 米）。
        // 这一条防的是「方向搞反」「把度当成米」这类错 —— 它们会让量级完全不对
        assertThat(gcj[0] - 116.404).as("经度偏移量级").isBetween(0.005, 0.008);
        assertThat(gcj[1] - 39.915).as("纬度偏移量级").isBetween(0.001, 0.002);

        // 境外不加密，原样返回（拿伦敦试）
        double[] london = CoordinateUtil.wgs84ToGcj02(-0.1276, 51.5072);
        assertThat(london[0]).isEqualTo(-0.1276);
        assertThat(london[1]).isEqualTo(51.5072);

        // 反向转换（第二步补的）：它没有公开的参考值可用，所以钉的是**往返**——
        // 「在图上点一下 → 存库 → 读出来」这条链路必须回到原处，
        // 否则每次编辑点位都会漂一点（这正是当初漏掉反向转换时会发生的事）。
        //
        // 容差 5e-6 度 ≈ 0.55 米，是实测值（实际约 2e-6 ≈ 0.2 米）留了一倍余量。
        // 这个误差来自两处，都是可接受的：
        //   ① 反算用的是公开实现里的**近似**解（在加密坐标上算偏移再减掉），
        //      不是迭代求精确解 —— 迭代出来的值和参考实现不再逐位一致，
        //      而「对不上参考实现」比这 0.2 米严重得多；
        //   ② 库里是 decimal(10,6)，本身就只有约 0.11 米的刻度。
        // 要防的是「方向搞反 / 忘了转」那种几百米的漂移，这个容差完全够
        double[] back = CoordinateUtil.gcj02ToWgs84(gcj[0], gcj[1]);
        assertThat(back[0]).as("往返后的经度").isCloseTo(116.404, offset(5e-6));
        assertThat(back[1]).as("往返后的纬度").isCloseTo(39.915, offset(5e-6));

        // 反向也要遵守同一条「境外不加密」的规则
        double[] londonBack = CoordinateUtil.gcj02ToWgs84(-0.1276, 51.5072);
        assertThat(londonBack[0]).isEqualTo(-0.1276);
        assertThat(londonBack[1]).isEqualTo(51.5072);
    }

    /**
     * 词汇表自身的完整性 —— 它得和设计文档第二章对得上。
     *
     * <p>这是「纯数据」的断言，但它防的是一个很实际的错：
     * 清单改了文档却没改代码（或者反过来），结果某条告警永远不会被识别出来，
     * 而且<b>不会有任何报错</b>。
     */
    @Test
    void alertVocabularyShouldMatchTheDesignDoc() {
        // 29 = 27 + 2 条空间类（zone.breach / zone.alt.over，2026-09-28 由你拍板加入）。
        // 那两条**暂时没有生产者**（禁飞区那两步还没做），加进来的目的是
        // 不让「文档写了 29 条、代码里只有 27 条」这种漂移发生 —— 和 device.online
        // 那批「通报类」一样，属于先把词汇表定下来
        assertThat(AlertCode.ALL).as("清单 29 条").hasSize(29);
        assertThat(AlertCode.TELEMETRY_CODES).as("遥测类 16 条").hasSize(16);
        // 每条编码都要有中文名、分类、默认级别，且级别必须是三档之一
        for (String code : AlertCode.ALL) {
            assertThat(AlertCode.label(code)).as("中文名 " + code).isNotEqualTo(code);
            assertThat(AlertCode.category(code)).as("分类 " + code).isNotNull();
            assertThat(AlertSeverity.isValid(AlertCode.defaultSeverity(code)))
                    .as("默认级别 " + code).isTrue();
        }
        // 遥测类那 16 条必须就是分类为 telemetry 的那 16 条（两张表不能各说各话）
        assertThat(AlertCode.ALL.stream().filter(c -> AlertCode.CATEGORY_TELEMETRY.equals(AlertCode.category(c))).toList())
                .containsExactlyElementsOf(AlertCode.TELEMETRY_CODES);
        // 级别分布（与设计文档 2.5 节的小结一致）：
        //   critical 9、info 5、warning 15 —— 注意最后这个 15 里**含 device.offline**：
        //   它的基准级别是 warning（可能只是关机了），只有「该设备上有执行中任务」时才升成 critical。
        //   文档 2.5 节把它单列成「随上下文变 1 条」，所以文档写的是 warning 14 + 动态 1，
        //   而代码里 defaultSeverity() 给出的是**基准级别**，因此按基准统计是 15。
        //   9 + 15 + 5 = 29 ✓（其中空间类两条：breach 是 critical、alt.over 是 warning）
        assertThat(AlertCode.ALL.stream()
                .filter(c -> AlertSeverity.CRITICAL.equals(AlertCode.defaultSeverity(c))).count())
                .as("critical 9 条").isEqualTo(9);
        assertThat(AlertCode.ALL.stream()
                .filter(c -> AlertSeverity.INFO.equals(AlertCode.defaultSeverity(c))).count())
                .as("info 5 条").isEqualTo(5);
        assertThat(AlertCode.ALL.stream()
                .filter(c -> AlertSeverity.WARNING.equals(AlertCode.defaultSeverity(c))).count())
                .as("warning 15 条（14 条固定 + device.offline 的基准级别）").isEqualTo(15);
        // device.offline 的基准级别必须是 warning 而不是 critical：
        // 设备闲着时掉线只是「可能关机了」，一上来就 critical 会天天误报
        assertThat(AlertCode.defaultSeverity(AlertCode.DEVICE_OFFLINE))
                .as("device.offline 的基准级别").isEqualTo(AlertSeverity.WARNING);
    }

    /**
     * 同一个问题<b>持续存在</b>时，只留一条告警。
     *
     * <p>这条用例来自一次真实联调：用模拟器跑了 10 秒的低电量，
     * 库里出现了 11 条告警（16~17% 时 4 条 `bat.low`、13~15% 时 7 条 `bat.critical`）——
     * 全指向同一个问题。真飞 5 分钟会刷出约 300 条。
     *
     * <p>根因是设计文档里「设备侧负责限频」那句话<b>对阈值类告警不成立</b>：
     * 设备根本不会报「我电量低了」，这个告警是服务端每条遥测算一次算出来的，
     * 而遥测是每秒一条。所以抑制责任必须在服务端 ——
     * 「只要这个问题还没被处理掉，就不再重复产生」。
     */
    @Test
    void repeatedSameProblemShouldKeepOnlyOneOpenAlert() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9829";
        startedTask(adminToken, "13900006029", deviceCode, "同一问题不重复报",
                "2027-07-09 09:00:00", "2027-07-09 11:00:00");

        // 电量一直低，连续发 3 条（observedAt 各不相同，所以唯一键挡不住 ——
        // 挡它的是「同一问题还有未处理告警」这个条件）
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 29001L, "2027-07-09 09:30:00",
                """
                        {"battery":10}"""));
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 29002L, "2027-07-09 09:30:01",
                """
                        {"battery":9}"""));
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 29003L, "2027-07-09 09:30:02",
                """
                        {"battery":8}"""));

        assertThat(alertCountOf(deviceCode, "bat.critical")).as("三条遥测只留一条告警").isEqualTo(1);
        // 但证据是一条都不少的 —— 抑制的是告警，不是数据
        assertThat(evidenceCountOf(deviceCode)).isEqualTo(3);
    }

    /**
     * 告警被处理掉之后，同一个问题<b>复发要能重新报</b>。
     *
     * <p>这与「持续存在只留一条」是一对：抑制的判据是「<b>还有未处理的</b>告警」，
     * 而不是「历史上出现过」。处理完了又出问题，那是一次新的事件，不是同一条的延续。
     */
    @Test
    void sameProblemShouldBeRaisedAgainAfterBeingResolved() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9830";
        startedTask(adminToken, "13900006030", deviceCode, "处理完可复发",
                "2027-07-10 09:00:00", "2027-07-10 11:00:00");

        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 30001L, "2027-07-10 09:30:00",
                """
                        {"gpsLevel":0}"""));
        assertThat(alertCountOf(deviceCode, "gps.lost")).isEqualTo(1);

        // 模拟管理侧把它处理掉了（处理闭环本身是实施步骤第二步，这里直接改库摆状态）
        jdbcTemplate.update("UPDATE task_alert SET status = 'resolved' WHERE device_code = ?", deviceCode);

        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 30002L, "2027-07-10 09:31:00",
                """
                        {"gpsLevel":0}"""));
        assertThat(alertCountOf(deviceCode, "gps.lost")).as("复发要重新报一条").isEqualTo(2);
    }

    // ---------- 告警查询与处理闭环（实施步骤第二步） ----------

    /**
     * 完整闭环：待处理 → 认领 → 处理中 → 标记已处理。
     *
     * <p>同时验证「处理人」记的是谁：它必须从登录态取，不接受前端传 ——
     * 否则谁都能把一条告警记成「张三处理的」。
     */
    @Test
    void alertShouldBeClaimedAndResolved() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9831";
        startedTask(adminToken, "13900006031", deviceCode, "告警闭环验证",
                "2027-08-01 09:00:00", "2027-08-01 11:00:00");
        raiseAlert(deviceCode, 31001L, "2027-08-01 09:30:00", """
                {"battery":10}""");
        long alertId = alertIdOf(deviceCode, "bat.critical");

        // 认领：待处理 → 处理中
        mockMvc.perform(handleAlertRequest(adminToken, alertId, "claim", null))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.status").value("handling"))
                .andExpect(jsonPath("$.data.statusLabel").value("处理中"));

        // 收尾：处理中 → 已处理，并记下处理人与说明
        mockMvc.perform(handleAlertRequest(adminToken, alertId, "resolve", "已通知飞手返航并检查电池"))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.status").value("resolved"))
                .andExpect(jsonPath("$.data.statusLabel").value("已处理"))
                .andExpect(jsonPath("$.data.handledByName").value("刘洋"))
                .andExpect(jsonPath("$.data.handleNote").value("已通知飞手返航并检查电池"))
                .andExpect(jsonPath("$.data.handledAt").isNotEmpty());
    }

    /**
     * 跳过认领直接「标记已处理」应当被状态机挡住。
     *
     * <p>为什么要求先认领：认领的含义是「我来负责闭环这件事」。
     * 跳过它直接关掉，等于没人对这条告警负过责；两个人同时看一条时也分不清谁在处理。
     */
    @Test
    void alertResolveShouldBeRejectedFromPending() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9832";
        startedTask(adminToken, "13900006032", deviceCode, "不能跳过认领",
                "2027-08-02 09:00:00", "2027-08-02 11:00:00");
        raiseAlert(deviceCode, 32001L, "2027-08-02 09:30:00", """
                {"gpsLevel":0}""");
        long alertId = alertIdOf(deviceCode, "gps.lost");

        mockMvc.perform(handleAlertRequest(adminToken, alertId, "resolve", "直接关掉"))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value(
                        "只有「处理中」的告警才能标记为已处理，当前状态：待处理"));
    }

    /** 收尾必须写处置说明 —— 没有说明的「已处理」和不处理没有区别 */
    @Test
    void alertResolveShouldRequireNote() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9833";
        startedTask(adminToken, "13900006033", deviceCode, "收尾必须写说明",
                "2027-08-03 09:00:00", "2027-08-03 11:00:00");
        raiseAlert(deviceCode, 33001L, "2027-08-03 09:30:00", """
                {"battery":10}""");
        long alertId = alertIdOf(deviceCode, "bat.critical");

        mockMvc.perform(handleAlertRequest(adminToken, alertId, "claim", null))
                .andExpect(jsonPath("$.state").value(200));
        // 说明留空 → 拒绝
        mockMvc.perform(handleAlertRequest(adminToken, alertId, "resolve", "   "))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("请填写处置说明"));
    }

    /**
     * 「误报」可以从「待处理」直接做（它是另一个终态，不需要先认领）。
     *
     * <p>这一条是状态机里唯一有两处来源的动作，所以单独验一次 ——
     * 它和「已处理」的允许来源不一样，容易写成一样。
     */
    @Test
    void alertShouldBeIgnoredDirectlyFromPending() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9834";
        startedTask(adminToken, "13900006034", deviceCode, "误报可直接关",
                "2027-08-04 09:00:00", "2027-08-04 11:00:00");
        raiseAlert(deviceCode, 34001L, "2027-08-04 09:30:00", """
                {"battery":10}""");
        long alertId = alertIdOf(deviceCode, "bat.critical");

        mockMvc.perform(handleAlertRequest(adminToken, alertId, "ignore", "传感器抖动，实际电量正常"))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.status").value("ignored"))
                .andExpect(jsonPath("$.data.statusLabel").value("误报"));
    }

    /** 飞手（只有 task:execute）既看不到告警列表，也不能处理 */
    @Test
    void alertShouldBeForbiddenForPilot() throws Exception {
        String pilotToken = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/alert/list").header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value(403));

        mockMvc.perform(handleAlertRequest(pilotToken, 1L, "claim", null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value(403));
    }

    /** 不带 token 一律 401（未登录） */
    @Test
    void alertShouldRequireLogin() throws Exception {
        mockMvc.perform(get("/alert/list"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.state").value(401));
    }

    /**
     * 机务能看告警 —— 顺带验证权限迁移脚本真的把权限授出去了。
     *
     * <p>这条用例的价值在于：权限点建了但忘了授权，表现是「功能做完了却没人能用」，
     * 而且不会有任何报错。只测「管理员能用」发现不了这个问题（管理员本来就该能用）。
     */
    @Test
    void alertListShouldBeAllowedForMaintainer() throws Exception {
        String maintainerToken = loginAndGetToken("13800001004", EXISTING_PASSWORD);
        mockMvc.perform(get("/alert/list").header(AUTH_HEADER, "Bearer " + maintainerToken))
                .andExpect(jsonPath("$.state").value(200));
    }

    /** 列表按级别筛选、并按「未处理在前、级别高的在前」排序 */
    @Test
    void alertListShouldFilterAndOrderByUrgency() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9835";
        startedTask(adminToken, "13900006035", deviceCode, "告警筛选与排序",
                "2027-08-05 09:00:00", "2027-08-05 11:00:00");
        // 先产生一条 warning（电量 20 只触发 low），再产生一条 critical（定位丢失）
        raiseAlert(deviceCode, 35001L, "2027-08-05 09:30:00", """
                {"battery":20}""");
        raiseAlert(deviceCode, 35002L, "2027-08-05 09:30:01", """
                {"gpsLevel":0}""");

        String body = mockMvc.perform(get("/alert/list").param("deviceCode", deviceCode)
                        .param("severity", "critical")
                        .header(AUTH_HEADER, "Bearer " + adminToken))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<String>read(body, "$.data.list[0].alertCode")).isEqualTo("gps.lost");
        // 中文名是后端给的，前端不用自己维护一份 27 条的字典
        assertThat(JsonPath.<String>read(body, "$.data.list[0].alertCodeLabel")).isEqualTo("定位丢失");
        assertThat(JsonPath.<String>read(body, "$.data.list[0].severityLabel")).isEqualTo("严重");

        // 不筛选时，critical 应排在 warning 前面（页面的第一屏要回答「现在有什么要管的」）
        String all = mockMvc.perform(get("/alert/list").param("deviceCode", deviceCode)
                        .header(AUTH_HEADER, "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<String>read(all, "$.data.list[0].severity")).isEqualTo("critical");
    }

    /** 处理不存在的告警要报错，而不是静默成功 */
    @Test
    void alertHandleShouldRejectUnknownId() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        mockMvc.perform(handleAlertRequest(adminToken, 99999999L, "claim", null))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message").value("告警不存在"));
    }

    // ---------- 上面的用例用到的辅助方法 ----------

    /** 造一条告警：走真实识别链路（发一条遥测让它被识别出来），而不是直接插库 */
    private void raiseAlert(String deviceCode, long seq, String observedAt, String payloadJson) throws Exception {
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, seq, observedAt, payloadJson));
    }

    private long alertIdOf(String deviceCode, String alertCode) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM task_alert WHERE device_code = ? AND alert_code = ?",
                Long.class, deviceCode, alertCode);
        return id == null ? -1 : id;
    }

    private MockHttpServletRequestBuilder handleAlertRequest(String token, long alertId,
                                                             String action, String note) {
        String noteJson = (note == null) ? "null" : "\"" + note + "\"";
        return post("/alert/handle")
                .header(AUTH_HEADER, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"id":%d,"action":"%s","note":%s}""".formatted(alertId, action, noteJson));
    }

    /**
     * 摆出一条「飞手已开始执行」的任务：现造飞手、设备、任务，派发并开始执行。
     *
     * <p>返回任务 ID；调用方需要自己记住传进来的 {@code deviceCode}，
     * 后面模拟设备上报时要用它。
     */
    private int startedTask(String adminToken, String username, String deviceCode, String taskName,
                            String planStart, String planEnd) throws Exception {
        int pilotId = addPilotAndGetId(adminToken, username, "证据用例飞手");
        int droneId = addDeviceAndGetId(adminToken, deviceCode, "证据用例设备");
        int taskId = createTaskId(adminToken, taskName, planStart, planEnd);
        dispatchAs(adminToken, taskId, pilotId, droneId);

        String pilotToken = loginAndGetToken(username, "123456");
        mockMvc.perform(post("/task/start").header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":%d}".formatted(taskId)))
                .andExpect(jsonPath("$.state").value(200));
        return taskId;
    }

    /**
     * 模拟「订阅器收到一条报文并交给处理器」。
     *
     * <p>刻意走一遍 Jackson 反序列化，而不是直接 new 一个信封 ——
     * 真实链路上报文是文本，{@code observedAt} 那种「用 String 接住、再由我们宽容解析」
     * 的设计正是靠这一步才有意义。少了解析这一步，测的就不是真链路了。
     *
     * @param deviceCode Topic 里解析出来的设备编号（真实链路上以它为准）
     * @param raw        报文原文，同时也是要被归档进证据表的内容
     */
    private void receiveTelemetry(String deviceCode, String raw) throws Exception {
        receiveOnTopic(deviceCode, "telemetry", raw);
    }

    /**
     * 同上，但可以指定 topic 的最后一段（telemetry / event/xxx / status）。
     *
     * <p>为什么要能指定：`drone/+/event/+` 与 `drone/+/status` 也订阅了，
     * 但它们的行为和 telemetry 不同（见 {@link #eventAndStatusShouldBeArchivedAsTelemetry}）。
     */
    private void receiveOnTopic(String deviceCode, String topicSuffix, String raw) throws Exception {
        TelemetryEnvelope envelope = objectMapper.readValue(raw, TelemetryEnvelope.class);
        telemetryHandler.handle(new DeviceMessage(
                "drone/" + deviceCode + "/" + topicSuffix, deviceCode, LocalDateTime.now(), envelope, raw));
    }

    /** 一条字段齐全的遥测报文，供「只关心去重 / 归属、不关心内容」的用例复用 */
    private String telemetryJson(String deviceCode, Long seq, String observedAt) {
        return telemetryJsonWithPayload(deviceCode, seq, observedAt,
                """
                {"lng":116.397,"lat":39.908,"alt":120.5}""");
    }

    /**
     * 同上，但 payload 由调用方给 —— 告警用例要构造各种阈值场景。
     *
     * <p>设备侧认定的字段名（见 {@code AlertDetector} 的类注释）：
     * {@code battery} / {@code batteryTemp} / {@code gpsLevel} / {@code signal} /
     * {@code alt} / {@code speed} / {@code sdFreeGb} / {@code motorState} / {@code flightState}。
     */
    private String telemetryJsonWithPayload(String deviceCode, Long seq, String observedAt, String payloadJson) {
        return """
                {"deviceCode":"%s","type":"position","observedAt":"%s","seq":%d,
                 "payload":%s}"""
                .formatted(deviceCode, observedAt, seq, payloadJson.trim());
    }

    /**
     * 一条状态报文。
     *
     * <p>{@code flightState} 在这个 topic 上<b>表示连通性</b>：
     * 连上时设备先发一条 {@code online}；正常退出时自己发一条 {@code offline}；
     * 异常掉线则由 broker 代发遗嘱 {@code offline}。
     * 遥测里的同名字段才表示飞行状态（{@code flying} / {@code hovering} / …），
     * 两者不是一回事。
     */
    private String statusJson(String deviceCode, Long seq, String observedAt, String flightState) {
        return """
                {"deviceCode":"%s","type":"status","observedAt":"%s","seq":%d,
                 "payload":{"flightState":"%s"}}"""
                .formatted(deviceCode, observedAt, seq, flightState);
    }

    /**
     * 一条事件报文。
     *
     * <p>注意 {@code eventType} 同时出现在两个地方：<b>topic 的最后一段</b>
     * 和信封的 {@code type}。识别只认 topic 那一段（见
     * {@code AlertDetector#detectEvent}），信封里的 type 只是留档。
     */
    private String eventJson(String deviceCode, String eventType, Long seq,
                             String observedAt, String payloadJson) {
        return """
                {"deviceCode":"%s","type":"%s","observedAt":"%s","seq":%d,
                 "payload":%s}"""
                .formatted(deviceCode, eventType, observedAt, seq, payloadJson.trim());
    }

    // ---------- 告警的查询辅助（第一步只有写，读都是断言用的） ----------

    private long alertCountOf(String deviceCode) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_alert WHERE device_code = ?", Long.class, deviceCode);
        return count == null ? 0 : count;
    }

    private long alertCountOf(String deviceCode, String alertCode) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_alert WHERE device_code = ? AND alert_code = ?",
                Long.class, deviceCode, alertCode);
        return count == null ? 0 : count;
    }

    // ---------- 飞手端告警与附件的辅助 ----------

    /**
     * 取一条<b>人工上报</b>的告警整行。
     *
     * <p>不能用 {@link #alertRowOf}：那个按 {@code device_code} 定位，
     * 而人工上报的 device_code 是 NULL（这正是它要验的性质，见
     * {@code onsiteReportShouldBePersistedAsAppSource}）。所以这里按 task_id 定位。
     */
    private Map<String, Object> onsiteRowOf(int taskId, String alertCode) {
        return jdbcTemplate.queryForMap(
                "SELECT task_id, device_code, source, message, severity, status, "
                        + "pilot_action_required, action_hint FROM task_alert "
                        + "WHERE task_id = ? AND alert_code = ?", taskId, alertCode);
    }

    /** 这条任务上有几条人工上报的告警 */
    private long onsiteCountOf(int taskId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_alert WHERE task_id = ? AND alert_code LIKE 'man.%'",
                Long.class, taskId);
        return count == null ? 0 : count;
    }

    private int myTaskAlertCount(String token, int taskId) throws Exception {
        return myTaskCountField(token, taskId, "alertCount");
    }

    private int myTaskCriticalCount(String token, int taskId) throws Exception {
        return myTaskCountField(token, taskId, "criticalAlertCount");
    }

    /**
     * 从 {@code /task/my} 的返回里取某条任务的角标字段。
     *
     * <p>走真实接口而不是直接查库：角标是 {@code pageMyTasks} 在 VO 上现填的
     * （数据库里根本没有这两列），只有走接口才验得到「确实填上去了」。
     */
    private int myTaskCountField(String token, int taskId, String field) throws Exception {
        String body = mockMvc.perform(get("/task/my")
                        .param("page", "1").param("pageSize", "50")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString();

        for (var node : objectMapper.readTree(body).path("data").path("list")) {
            if (node.path("id").asInt() == taskId) {
                return node.path(field).asInt();
            }
        }
        throw new IllegalStateException("这条任务不在「我的任务」里：taskId=" + taskId);
    }

    /**
     * 调上传接口并返回附件 id。
     *
     * <p>刻意走真实 multipart 请求而不是直接调 Service：
     * 「multipart 的全局大小上限配了没有」这类问题只在真实请求上才暴露
     * （Spring 默认单文件 1MB，配漏了会在进 Controller 之前就被挡下）。
     */
    private long uploadAttachment(String token, int taskId, String fileName, byte[] content) throws Exception {
        String body = mockMvc.perform(multipart("/task/attachment/upload")
                        .file(new MockMultipartFile("file", fileName, null, content))
                        .param("taskId", String.valueOf(taskId))
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    private long attachmentCountOf(int taskId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_attachment WHERE task_id = ?", Long.class, taskId);
        return count == null ? 0 : count;
    }

    /** 上传目录的第一层就是任务编号：{@code {taskNo}/{yyyyMMdd}/...} */
    private String taskNoOf(int taskId) {
        return jdbcTemplate.queryForObject("SELECT task_no FROM `task` WHERE id = ?", String.class, taskId);
    }

    // ---------- 报告线的辅助 ----------

    private MockHttpServletRequestBuilder finishRequest(String token, int taskId,
                                                        String result, String summary) {
        return post("/task/finish")
                .header(AUTH_HEADER, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"taskId":%d,"result":"%s","summary":"%s","flightHours":1.25,"flightCount":1}"""
                        .formatted(taskId, result, summary));
    }

    /**
     * 取那条任务的当前报告整行。
     *
     * <p>带 {@code is_latest = 1}：报告是多版本的，不带这个条件会查出全部历史版本 ——
     * 而 {@code queryForMap} 遇到多行会直接报错，报的是「期望 1 行实际 2 行」，
     * 那个错误信息会把人带到完全错误的方向
     */
    private Map<String, Object> latestReportOf(int taskId) {
        return jdbcTemplate.queryForMap(
                "SELECT id, version, is_latest, result, summary, problem_log, flight_hours, "
                        + "flight_count, reporter_name FROM task_report "
                        + "WHERE task_id = ? AND is_latest = 1", taskId);
    }

    private long reportVersionCountOf(int taskId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_report WHERE task_id = ?", Long.class, taskId);
        return count == null ? 0 : count;
    }

    /** 按设备编号取它的 id（断言设备状态时要用） */
    private int droneIdOf(String deviceCode) {
        Integer id = jdbcTemplate.queryForObject(
                "SELECT id FROM `drone` WHERE code = ?", Integer.class, deviceCode);
        return id == null ? -1 : id;
    }

    /** 按登录账号取用户 id（断言飞手状态时要用） */
    private int userIdOf(String username) {
        Integer id = jdbcTemplate.queryForObject(
                "SELECT id FROM `user` WHERE username = ?", Integer.class, username);
        return id == null ? -1 : id;
    }

    /** 这条任务的流水里某个动作出现了几次 */
    private long actionCountOf(int taskId, String action) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_log WHERE task_id = ? AND action = ?",
                Long.class, taskId, action);
        return count == null ? 0 : count;
    }

    /** 数上传根目录下的文件总数（用来验「被拒的上传一个字节都没写」） */
    private long countFilesUnderUploadRoot() throws Exception {
        try (var paths = Files.walk(localStorage.root())) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    /** 取某条告警整行（字段用 numberOf / get 断言，撞到多条时会报错，这本身就是断言） */
    private Map<String, Object> alertRowOf(String deviceCode, String alertCode) {
        return jdbcTemplate.queryForMap(
                "SELECT task_id, alert_code, severity, message, source, status, "
                        + "pilot_action_required, action_hint FROM task_alert "
                        + "WHERE device_code = ? AND alert_code = ?", deviceCode, alertCode);
    }

    /**
     * 把「开始执行」的时刻往前拨 {@code minutes} 分钟，用来构造「声明已经过去很久了」。
     *
     * <h2>为什么不直接 sleep</h2>
     * 一是慢；二是<b>根本不可靠</b>：{@code actual_start_time} 是 {@code datetime}（精度到秒），
     * MySQL 写入时会把小数秒<b>四舍五入</b>，所以「刚刚开始执行」存进去的值
     * 有可能比当前时间还大 1 秒。拿它和「现在」立刻比较，成败取决于那一次的毫秒数 ——
     * 这样的测试会时灵时不灵，比没有测试更糟。
     *
     * <p>拨早一段时间就彻底绕开了这个抖动：无论怎么取整，它都稳稳地早于判定线。
     * 用 JdbcTemplate 直接改库，是因为这个前置状态 API 造不出来
     * （「开始执行」只能落在当前时刻）—— 这也是本测试类里一贯的做法。
     */
    private void ageTaskStartTime(int taskId, int minutes) {
        jdbcTemplate.update("UPDATE `task` SET actual_start_time = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(minutes), taskId);
    }

    private long evidenceCountOf(String deviceCode) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_evidence WHERE device_code = ?", Long.class, deviceCode);
        return count == null ? 0 : count;
    }

    private String evidenceStateOf(int taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT evidence_state FROM `task` WHERE id = ?", String.class, taskId);
    }

    private String evidenceTypeOf(int taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT evidence_type FROM `task` WHERE id = ?", String.class, taskId);
    }

    /** 证据时刻是否为空。查「是不是 NULL」而不是查值，避免驱动对 datetime 的各种转换 */
    private boolean evidenceAtIsNull(int taskId) {
        Integer flag = jdbcTemplate.queryForObject(
                "SELECT evidence_at IS NULL FROM `task` WHERE id = ?", Integer.class, taskId);
        return flag != null && flag == 1;
    }

    private String taskStatusOf(int taskId) {
        return jdbcTemplate.queryForObject("SELECT status FROM `task` WHERE id = ?", String.class, taskId);
    }

    /** 把查询出来的数值列统一转成 long —— 不同驱动版本可能给 Integer / Long / BigInteger */
    private long numberOf(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? -1 : ((Number) value).longValue();
    }

    // ==================================================================
    // 点位库 + 航线（地图接入方案 第二步）
    // ==================================================================

    /**
     * 点位坐标：接口两端都是 GCJ-02，<b>库里存的是 WGS84</b>。
     *
     * <h2>为什么要断言「库里的值不是传进去的那个」</h2>
     * 这是这一步最容易悄悄做错的地方：<b>忘了转换</b>。
     * 忘了转的话，接口看起来完全自洽 —— 传 GCJ-02 进去、读出来还是它，
     * 页面上的表单回显一切正常。只有把库里的值拿来和输入比，
     * 才会发现存的其实是加密坐标（两者差着那 500 米）。
     *
     * <p>500 米这个偏移量级还顺带证明了「方向没搞反」：存成 GCJ-02 会往东偏、
     * 存反了（把 WGS84 当成 GCJ-02 再解一次）会往西偏同样的量级。
     */
    @Test
    void pointCoordinatesShouldBeGcj02OutsideAndWgs84Inside() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        // 参考点：WGS84 (116.404, 39.915) 对应的 GCJ-02
        double gcjLng = 116.41024449916938;
        double gcjLat = 39.91640428150164;

        String body = mockMvc.perform(post("/point/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pointBody("测试点位-坐标往返", null, gcjLng, gcjLat)))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.coordinateSystem").value("gcj02"))
                .andExpect(jsonPath("$.data.category").value("survey"))
                .andExpect(jsonPath("$.data.categoryLabel").value("巡检点"))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(body, "$.data.id");

        // ① 接口回来的就是传进去的那个（编辑表单靠这条性质才不会每次都漂一点）。
        // 容差 5e-6 度 ≈ 0.55 米：库里 decimal(10,6) 只有约 0.11 米的刻度，
        // 加上近似反算本身的误差，实测往返约 2e-6 度。**要防的是几百米的漂移**
        // （方向搞反 / 忘了转），不是这 0.2 米 —— 见坐标转换那条用例的说明
        assertThat(numberOf(body, "$.data.lng")).as("接口返回的经度").isCloseTo(gcjLng, offset(5e-6));

        // ② 库里存的是 WGS84
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT lng, lat FROM survey_point WHERE id = ?", id);
        double dbLng = ((Number) row.get("lng")).doubleValue();
        double dbLat = ((Number) row.get("lat")).doubleValue();
        assertThat(dbLng).as("库里的经度").isCloseTo(116.404, offset(1e-5));
        assertThat(dbLat).as("库里的纬度").isCloseTo(39.915, offset(1e-5));

        // ③ 而且**绝不是**传进去的那个值（差着那 500 米）
        assertThat(Math.abs(dbLng - gcjLng)).as("经度上的偏移量级").isBetween(0.004, 0.009);
        assertThat(Math.abs(dbLat - gcjLat)).as("纬度上的偏移量级").isBetween(0.0005, 0.004);
    }

    /** 点位编号唯一，但不填编号的点位可以有多个（唯一索引把多个 NULL 当不同） */
    @Test
    void pointCodeShouldBeUniqueWhileBlankCodesCanRepeat() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        addPoint(token, "测试点位-编号A", "SIM-P-001", 116.40, 39.91);

        // 同一个编号再来一次：拒
        mockMvc.perform(post("/point/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pointBody("测试点位-编号A2", "SIM-P-001", 116.401, 39.911)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("已存在")));

        // 不填编号的两个点位都要能建 —— 这是那次「唯一索引 + NULL」的验证。
        // 如果当初图省事把空编号存成空字符串，第二个就会插不进去
        addPoint(token, "测试点位-无编号1", null, 116.402, 39.912);
        addPoint(token, "测试点位-无编号2", null, 116.403, 39.913);
    }

    /** 点位的筛选、编辑、删除 */
    @Test
    void pointShouldBeFilteredUpdatedAndDeleted() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(post("/point/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"测试点位-起降点","code":"SIM-P-TAKEOFF",
                                 "lng":116.411,"lat":39.917,"category":"takeoff","alt":30}
                                """))
                .andExpect(jsonPath("$.state").value(200));

        // 按类型筛，能筛到它，而且类型中文名是后端给的
        mockMvc.perform(get("/point/list").param("category", "takeoff").param("keyword", "测试点位-起降点")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].categoryLabel").value("起降点"));

        int id = JsonPath.read(mockMvc.perform(get("/point/list")
                                .param("keyword", "测试点位-起降点")
                                .header(AUTH_HEADER, "Bearer " + token))
                        .andReturn().getResponse().getContentAsString(), "$.data.list[0].id");

        // 编辑：改名 + 换成巡检点 + 改坐标
        mockMvc.perform(post("/point/update")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":%d,"name":"测试点位-改过名","code":"SIM-P-TAKEOFF",
                                 "lng":116.412,"lat":39.918,"category":"survey"}
                                """.formatted(id)))
                .andExpect(jsonPath("$.state").value(200));

        mockMvc.perform(get("/point/" + id).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.name").value("测试点位-改过名"))
                .andExpect(jsonPath("$.data.categoryLabel").value("巡检点"))
                .andExpect(jsonPath("$.data.lng").isNumber());

        // 删除后就查不到了 —— 报的是「不存在」，不是静悄悄返回一个空对象
        mockMvc.perform(post("/point/delete").param("id", String.valueOf(id))
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200));
        mockMvc.perform(get("/point/" + id).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("点位不存在")));
    }

    /** 坐标输入这一层：缺值、0,0、超范围都要被挡住 */
    @Test
    void pointShouldRejectBadCoordinates() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        // 0,0 —— 地图没加载好就点确定时的典型表现
        mockMvc.perform(post("/point/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pointBody("测试点位-原点", null, 0, 0)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("都是 0")));

        // 只有经度
        mockMvc.perform(post("/point/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"测试点位-缺纬度","lng":116.4}
                                """))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("缺少经纬度")));

        // 类型不在白名单里
        mockMvc.perform(post("/point/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"测试点位-坏类型","lng":116.4,"lat":39.9,"category":"随便写的"}
                                """))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("点位类型")));
    }

    /**
     * 航线：顺序由数组决定（seq 服务端生成），航程与点数服务端算。
     *
     * <p>顺序是航线的一部分，而「顺序对不对」光看接口有没有报错是看不出来的 ——
     * 必须断言取回来的 seq 与坐标真的对得上。
     */
    @Test
    void routeShouldKeepPointOrderAndComputeDistance() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        // 三个点：纬度依次 +0.009（约 1 公里），经度相同 —— 好算
        String points = """
                [{"lng":116.41024449,"lat":39.91640428,"alt":50,"action":"none"},
                 {"lng":116.41024449,"lat":39.92540428,"alt":60,"action":"photo"},
                 {"lng":116.41024449,"lat":39.93440428,"alt":50,"action":"hover","hoverSeconds":15}]
                """;
        String body = mockMvc.perform(post("/route/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(null, "测试航线-顺序", points)))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.coordinateSystem").value("gcj02"))
                .andExpect(jsonPath("$.data.pointCount").value(3))
                .andExpect(jsonPath("$.data.points[0].seq").value(1))
                .andExpect(jsonPath("$.data.points[1].seq").value(2))
                .andExpect(jsonPath("$.data.points[2].seq").value(3))
                // 动作中文名由后端给（前端不再维护一份映射）
                .andExpect(jsonPath("$.data.points[1].actionLabel").value("拍照"))
                .andExpect(jsonPath("$.data.points[2].actionLabel").value("悬停"))
                .andExpect(jsonPath("$.data.points[2].hoverSeconds").value(15))
                .andReturn().getResponse().getContentAsString();

        int id = JsonPath.read(body, "$.data.id");

        // 航程：两段各约 1000 米（0.009 度纬度 ≈ 995 米），所以总共约 2000 米。
        // 用区间而不是等值 —— 验的是「量级对、两段都算进去了」，
        // 而精确值取决于那个平面近似，钉死它只会让以后换公式时白白挂掉
        int distanceM = JsonPath.read(body, "$.data.distanceM");
        assertThat(distanceM).as("总航程").isBetween(1900, 2100);

        // 顺序真的被保存住了：按 seq 取回来的纬度是递增的
        String detail = mockMvc.perform(get("/route/" + id).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString();
        double lat1 = numberOf(detail, "$.data.points[0].lat");
        double lat2 = numberOf(detail, "$.data.points[1].lat");
        double lat3 = numberOf(detail, "$.data.points[2].lat");
        assertThat(lat1).as("第 1 个点在最南边").isLessThan(lat2);
        assertThat(lat2).isLessThan(lat3);
    }

    /** 航线的输入校验：点数、名称、动作、引用的点位 */
    @Test
    void routeShouldRejectBadInput() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        // 一个点连不成线
        mockMvc.perform(post("/route/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(null, "测试航线-单点",
                                """
                                [{"lng":116.41,"lat":39.91,"action":"none"}]
                                """)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("至少")));

        // 名称必填
        mockMvc.perform(post("/route/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(null, "  ", twoPointJson())))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("航线名称")));

        // 动作不在白名单里
        mockMvc.perform(post("/route/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(null, "测试航线-坏动作",
                                """
                                [{"lng":116.41,"lat":39.91,"action":"fly"},
                                 {"lng":116.41,"lat":39.92,"action":"none"}]
                                """)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("动作")));

        // 选了悬停却没填秒数
        mockMvc.perform(post("/route/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(null, "测试航线-悬停没秒数",
                                """
                                [{"lng":116.41,"lat":39.91,"action":"hover"},
                                 {"lng":116.41,"lat":39.92,"action":"none"}]
                                """)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("悬停秒数")));

        // 引用了不存在的点位 —— 不挡的话库里会留下点不进去的引用
        mockMvc.perform(post("/route/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(null, "测试航线-坏点位引用",
                                """
                                [{"lng":116.41,"lat":39.91,"action":"none","pointId":99999999},
                                 {"lng":116.41,"lat":39.92,"action":"none"}]
                                """)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("引用的点位不存在")));
    }

    /** 编辑航线是整份替换航点：旧的点会被删掉，而不是追加 */
    @Test
    void routeUpdateShouldReplacePoints() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        int id = addRoute(token, "测试航线-替换", """
                [{"lng":116.41,"lat":39.91,"action":"none"},
                 {"lng":116.41,"lat":39.92,"action":"none"},
                 {"lng":116.41,"lat":39.93,"action":"none"}]
                """);

        // 改成两个点，且顺序与原来相反
        mockMvc.perform(post("/route/update")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(id, "测试航线-替换", """
                                [{"lng":116.41,"lat":39.93,"action":"none"},
                                 {"lng":116.41,"lat":39.91,"action":"none"}]
                                """)))
                .andExpect(jsonPath("$.state").value(200));

        String detail = mockMvc.perform(get("/route/" + id).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                // 只剩 2 个点 —— 如果是追加就会是 5 个
                .andExpect(jsonPath("$.data.pointCount").value(2))
                .andExpect(jsonPath("$.data.points.length()").value(2))
                .andExpect(jsonPath("$.data.points[0].seq").value(1))
                .andReturn().getResponse().getContentAsString();

        // 第 1 个点是原来最后那个（顺序真的被换了）
        assertThat(numberOf(detail, "$.data.points[0].lat")).isGreaterThan(numberOf(detail, "$.data.points[1].lat"));
    }

    /**
     * 任务引用航线时要存<b>快照</b>：之后改模板，历史任务的「当时航线」不能跟着变。
     *
     * <p>这是设计文档 6.5 拍板的那个取舍，也是本步唯一一处「不这么做就会埋雷」的地方：
     * 模板改了之后，所有引用过它的历史任务看上去「当时飞的就是新航线」，
     * 事后查不出真相，而且<b>不会有任何报错</b>。
     */
    @Test
    void taskShouldSnapshotRouteSoTemplateChangesDoNotAffectIt() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        int routeId = addRoute(token, "测试航线-快照", twoPointJson());

        String created = mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskName":"测试任务-航线快照","priority":"P2",
                                 "planStartTime":"2027-09-01 09:00:00","planEndTime":"2027-09-01 11:00:00",
                                 "routeId":%d}
                                """.formatted(routeId)))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.routeId").value(routeId))
                // 没自己写任务内容时，后端自动填一句摘要（否则列表上看不出这任务要干什么）
                .andExpect(jsonPath("$.data.routeInfo", containsString("测试航线-快照")))
                .andReturn().getResponse().getContentAsString();
        int taskId = JsonPath.read(created, "$.data.id");

        // 详情里能看到当时的计划航线
        mockMvc.perform(get("/task/" + taskId).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.plannedRoute.name").value("测试航线-快照"))
                .andExpect(jsonPath("$.data.plannedRoute.coordinateSystem").value("gcj02"))
                .andExpect(jsonPath("$.data.plannedRoute.points.length()").value(2));

        // ---- 把模板改掉：改名 + 多一个点 ----
        mockMvc.perform(post("/route/update")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(routeId, "测试航线-快照-改过了", """
                                [{"lng":116.41,"lat":39.91,"action":"none"},
                                 {"lng":116.41,"lat":39.92,"action":"none"},
                                 {"lng":116.41,"lat":39.93,"action":"none"}]
                                """)))
                .andExpect(jsonPath("$.state").value(200));

        // ---- 再删掉模板（已创建的任务不该受影响）----
        mockMvc.perform(post("/route/delete").param("id", String.valueOf(routeId))
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200));

        // 任务的计划航线还是**当时那份**：老名字、还是 2 个点
        mockMvc.perform(get("/task/" + taskId).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.plannedRoute.name").value("测试航线-快照"))
                .andExpect(jsonPath("$.data.plannedRoute.points.length()").value(2));
    }

    /** 起降点：传 GCJ-02、存 WGS84、回 GCJ-02；只给一半要报错 */
    @Test
    void taskTakeoffShouldBeStoredAsWgs84AndReturnedAsGcj02() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        double gcjLng = 116.41024449916938;
        double gcjLat = 39.91640428150164;

        String created = mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskName":"测试任务-起降点","priority":"P2",
                                 "planStartTime":"2027-09-02 09:00:00","planEndTime":"2027-09-02 11:00:00",
                                 "takeoffLng":%s,"takeoffLat":%s,"takeoffName":"测试起降点"}
                                """.formatted(gcjLng, gcjLat)))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.takeoffName").value("测试起降点"))
                .andReturn().getResponse().getContentAsString();
        int taskId = JsonPath.read(created, "$.data.id");

        // 接口给回来的还是 GCJ-02（地图直接画）
        assertThat(numberOf(created, "$.data.takeoffLng")).isCloseTo(gcjLng, offset(5e-6));

        // 库里是 WGS84
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT takeoff_lng, takeoff_lat FROM `task` WHERE id = ?", taskId);
        assertThat(((Number) row.get("takeoff_lng")).doubleValue()).isCloseTo(116.404, offset(1e-5));
        assertThat(Math.abs(((Number) row.get("takeoff_lng")).doubleValue() - gcjLng))
                .as("偏移量级").isBetween(0.004, 0.009);

        // 只给经度不给纬度：起降点要么给全、要么不给
        mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskName":"测试任务-半个起降点","priority":"P2",
                                 "planStartTime":"2027-09-03 09:00:00","planEndTime":"2027-09-03 11:00:00",
                                 "takeoffLng":116.41}
                                """))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("起降点")));
    }

    /**
     * 点位与航线的权限点：管理员与机务可用，飞手不可用。
     *
     * <p>带一条<b>机务能用</b>的断言，是为了验证迁移脚本真的把 {@code route:*} 授出去了：
     * 权限点建了却忘了授权，表现是「功能做完了却没人能用」，而且不会有任何报错。
     * 只测管理员发现不了（管理员本来就该能用）。
     */
    @Test
    void pointAndRoutePermissionsShouldFollowThePermissionPoints() throws Exception {
        String pilotToken = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);
        String maintainerToken = loginAndGetToken("13800001004", EXISTING_PASSWORD);

        // 飞手：看不到、也建不了 —— 点位与航线是管理侧的功能
        mockMvc.perform(get("/point/list").header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(jsonPath("$.state").value(403));
        mockMvc.perform(get("/route/list").header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(jsonPath("$.state").value(403));
        mockMvc.perform(post("/point/add")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pointBody("测试点位-飞手越权", null, 116.41, 39.91)))
                .andExpect(jsonPath("$.state").value(403));

        // 机务：能手点库与航线（他的日常活），但不能建任务 —— 这正是当初
        // 不复用 task:add 的原因
        mockMvc.perform(get("/point/list").header(AUTH_HEADER, "Bearer " + maintainerToken))
                .andExpect(jsonPath("$.state").value(200));
        mockMvc.perform(get("/route/list").header(AUTH_HEADER, "Bearer " + maintainerToken))
                .andExpect(jsonPath("$.state").value(200));
        mockMvc.perform(get("/route/actions").header(AUTH_HEADER, "Bearer " + maintainerToken))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.photo").value("拍照"));
        mockMvc.perform(get("/point/categories").header(AUTH_HEADER, "Bearer " + maintainerToken))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.takeoff").value("起降点"));
    }

    // ---------- 本段用到的请求体与前置小工具 ----------

    /** 点位新增请求体；code 传 null 表示不填编号 */
    private String pointBody(String name, String code, double lng, double lat) {
        return """
                {"name":"%s","code":%s,"lng":%s,"lat":%s}
                """.formatted(name, code == null ? "null" : "\"" + code + "\"", lng, lat);
    }

    /** 航线新增/编辑请求体；id 传 null 表示新增 */
    private String routeBody(Integer id, String name, String pointsJson) {
        return """
                {"id":%s,"name":"%s","description":"自动化测试创建","points":%s}
                """.formatted(id == null ? "null" : id.toString(), name, pointsJson);
    }

    /** 两个点的航线（纬度差 0.009 度 ≈ 995 米），多数用例只需要这个形状 */
    private String twoPointJson() {
        return """
                [{"lng":116.41024449,"lat":39.91640428,"action":"none"},
                 {"lng":116.41024449,"lat":39.92540428,"action":"none"}]
                """;
    }

    /** 新增一个点位，返回 id */
    private int addPoint(String token, String name, String code, double lng, double lat) throws Exception {
        String body = mockMvc.perform(post("/point/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pointBody(name, code, lng, lat)))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    /** 新增一条航线，返回 id */
    private int addRoute(String token, String name, String pointsJson) throws Exception {
        String body = mockMvc.perform(post("/route/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(null, name, pointsJson)))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    /**
     * 从一段 JSON 文本里按 JsonPath 取一个数值。
     *
     * <p>不能直接 {@code (double) JsonPath.read(...)}：带小数的 JSON 数字
     * 可能被解析成 Double、也可能被解析成 BigDecimal，硬转会 ClassCastException。
     */
    private double numberOf(String json, String path) {
        Object value = JsonPath.read(json, path);
        return ((Number) value).doubleValue();
    }

    // ==================================================================
    // 禁飞区（地图接入方案 第三步：静态校验）
    // ==================================================================

    /** 参考点：WGS84 (116.404, 39.915) 对应的 GCJ-02 */
    private static final double ZONE_GCJ_LNG = 116.41024449916938;
    private static final double ZONE_GCJ_LAT = 39.91640428150164;

    /**
     * 禁飞区坐标：接口两端是 GCJ-02，<b>库里存的是 WGS84</b>。
     *
     * <p>这条断言与点位那条同一个目的：防「忘了转换」。
     * 忘了转的话接口照样自洽（画出来也在原地），只有拿库里的值比才看得出来 ——
     * 而偏差是 500 米，在「有没有飞进这块区域」这个判断上正好是致命的。
     */
    @Test
    void zoneShouldKeepGcj02OutsideAndWgs84Inside() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        String body = mockMvc.perform(post("/zone/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(zoneBody("测试禁飞区-坐标", "polygon", squareRingGcj(), null, null)))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.coordinateSystem").value("gcj02"))
                .andExpect(jsonPath("$.data.shapeLabel").value("多边形"))
                .andExpect(jsonPath("$.data.sourceLabel").value("自建"))
                // 没填限高 = 完全禁飞，这两个「给人看」的字段由后端算好
                .andExpect(jsonPath("$.data.forbidden").value(true))
                .andExpect(jsonPath("$.data.altRuleLabel").value("完全禁飞"))
                .andExpect(jsonPath("$.data.points.length()").value(4))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        int zoneId = JsonPath.read(body, "$.data.id");

        // 顶点传进去是 (116.4082, 39.9144)，接口返回的应当就是它（往返不漂）
        assertThat(numberOf(body, "$.data.points[0].lng")).as("接口返回的经度")
                .isCloseTo(116.4082, offset(5e-6));

        // 库里是 WGS84，而且绝不是传进去的那个值
        String geometry = jdbcTemplate.queryForObject(
                "SELECT geometry FROM no_fly_zone WHERE id = ?", String.class, zoneId);
        assertThat(geometry).isNotNull();
        double storedLng = numberOf(geometry, "$[0][0]");
        assertThat(Math.abs(storedLng - 116.4082)).as("经度偏移量级（约 500 米）")
                .isBetween(0.004, 0.009);
        // 方向也要对：应当是把 GCJ-02 的修正量**减掉**，而不是加上去
        assertThat(storedLng).as("库里的经度")
                .isCloseTo(CoordinateUtil.gcj02ToWgs84(116.4082, 39.9144)[0], offset(1e-6));
    }

    /** 区域的筛选、编辑、启停、删除 */
    @Test
    void zoneShouldBeFilteredUpdatedToggledAndDeleted() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int zoneId = addZone(token, "测试禁飞区-日常", "circle", circleGcj(500), null);

        // 按关键字 + 只看启用的
        mockMvc.perform(get("/zone/list").param("keyword", "测试禁飞区-日常").param("enabled", "true")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].shapeLabel").value("圆"))
                .andExpect(jsonPath("$.data.list[0].radius").value(500));

        // 停用之后，「只看启用的」就查不到了 —— 停用要真的能让它退出校验范围
        mockMvc.perform(post("/zone/" + zoneId + "/toggle").param("enabled", "false")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200));
        mockMvc.perform(get("/zone/list").param("keyword", "测试禁飞区-日常").param("enabled", "true")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(0));

        // 编辑：改成限高区
        mockMvc.perform(post("/zone/update")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":%d,"name":"测试禁飞区-日常","shape":"circle","centerLng":%s,
                                 "centerLat":%s,"radius":500,"maxAlt":120,"enabled":true}
                                """.formatted(zoneId, ZONE_GCJ_LNG, ZONE_GCJ_LAT)))
                .andExpect(jsonPath("$.state").value(200));

        mockMvc.perform(get("/zone/" + zoneId).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.forbidden").value(false))
                .andExpect(jsonPath("$.data.altRuleLabel").value("限高 120 米"));

        // 删掉就查不到了
        mockMvc.perform(post("/zone/delete").param("id", String.valueOf(zoneId))
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200));
        mockMvc.perform(get("/zone/" + zoneId).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("禁飞区不存在")));
    }

    /**
     * 校验的三条规则，一条用例覆盖完：
     * <ol>
     *   <li>完全禁飞的区：点在区内就命中</li>
     *   <li>限高区：只在「作业高度已知且超过限高」时命中（高度未知不算 —— 那是编事实）</li>
     *   <li>线穿过区：起点终点都在区外也要能发现</li>
     * </ol>
     */
    @Test
    void zoneCheckShouldCatchPointsCrossingAndRespectAlt() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        // 一个完全禁飞的多边形 + 同位置的限高圆（120 米）
        addZone(token, "测试禁飞区-全面禁飞", "polygon", squareRingGcj(), null);
        addZone(token, "测试禁飞区-限高", "circle", circleGcj(600), 120);

        // ① 两个航点都在区内、高度 50 米（低于限高）
        int lowRoute = addRoute(token, "测试航线-区内低飞", """
                [{"lng":116.4100,"lat":39.9160,"alt":50,"action":"none"},
                 {"lng":116.4105,"lat":39.9165,"alt":50,"action":"none"}]
                """);
        String low = mockMvc.perform(post("/task/zone-check")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":%d}
                                """.formatted(lowRoute)))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 只有「完全禁飞」那个命中；限高区不命中（50 < 120）
        assertThat(JsonPath.read(low, "$.data.length()").toString()).isEqualTo("1");
        assertThat(JsonPath.read(low, "$.data[0].zoneName").toString()).isEqualTo("测试禁飞区-全面禁飞");
        assertThat(JsonPath.read(low, "$.data[0].pointLabel").toString()).isEqualTo("第 1 个航点");

        // ② 同样两个点，高度 300 米 → 两个区都命中
        int highRoute = addRoute(token, "测试航线-区内高飞", """
                [{"lng":116.4100,"lat":39.9160,"alt":300,"action":"none"},
                 {"lng":116.4105,"lat":39.9165,"alt":300,"action":"none"}]
                """);
        String high = mockMvc.perform(post("/task/zone-check")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":%d}
                                """.formatted(highRoute)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.read(high, "$.data.length()").toString()).as("全面禁飞 + 限高，共两条").isEqualTo("2");
        // 限高那条的说明里要写清「限高多少、实际多少」——这是最容易被质疑的一类判定
        String highJson = high;
        assertThat(highJson).contains("限高 120 米").contains("作业高度 300.0 米");

        // ③ 两端都在区外、中间横穿：只看点会漏掉
        int crossRoute = addRoute(token, "测试航线-横穿", """
                [{"lng":116.4040,"lat":39.9160,"alt":50,"action":"none"},
                 {"lng":116.4160,"lat":39.9160,"alt":50,"action":"none"}]
                """);
        String cross = mockMvc.perform(post("/task/zone-check")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"routeId":%d}
                                """.formatted(crossRoute)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.read(cross, "$.data.length()").toString()).as("横穿应当被发现").isEqualTo("1");
        assertThat(JsonPath.read(cross, "$.data[0].pointLabel").toString()).isEqualTo("航线途经点");
    }

    /** 停用的、还没生效的、已经过期的区域都不算命中 —— 否则「临时停用」这个操作就白做了 */
    @Test
    void zoneCheckShouldIgnoreDisabledAndOutOfWindowZones() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        // 三种「不算数」的区域，位置都盖住同一个起降点
        mockMvc.perform(post("/zone/add").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"测试禁飞区-已停用","shape":"polygon","points":%s,"enabled":false}
                                """.formatted(squareRingGcj())))
                .andExpect(jsonPath("$.state").value(200));
        mockMvc.perform(post("/zone/add").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"测试禁飞区-未来生效","shape":"polygon","points":%s,
                                 "effectiveFrom":"2099-01-01 00:00:00"}
                                """.formatted(squareRingGcj())))
                .andExpect(jsonPath("$.state").value(200));
        mockMvc.perform(post("/zone/add").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"测试禁飞区-已过期","shape":"polygon","points":%s,
                                 "effectiveTo":"2020-01-01 00:00:00"}
                                """.formatted(squareRingGcj())))
                .andExpect(jsonPath("$.state").value(200));

        String hits = mockMvc.perform(post("/task/zone-check")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"takeoffLng":%s,"takeoffLat":%s}
                                """.formatted(ZONE_GCJ_LNG, ZONE_GCJ_LAT)))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.read(hits, "$.data.length()").toString())
                .as("停用/未生效/已过期的都不该算命中").isEqualTo("0");
    }

    /**
     * 建任务与派发时的「软拦 + 留痕」。
     *
     * <h2>为什么这条用例是这一步的核心</h2>
     * 它同时钉住了三件容易做错的事：
     * <ol>
     *   <li>没有确认就**必须**拒绝（否则「留痕」根本不会发生）</li>
     *   <li>确认了但不写原因也拒绝（「知道了」这种等于没写，事后没法对账）</li>
     *   <li>确认 + 原因之后要**真的建成**，而且那道留痕要落进流转流水</li>
     * </ol>
     * 还要覆盖「派发时再查一次」——任务建的时候还没画区，派的时候才画上。
     */
    @Test
    void taskShouldBeSoftBlockedByZoneAtCreateAndDispatch() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        // ---- 一、创建时命中 ----
        // 起降点先放一个不在任何区里的位置建任务（此时还没有区）
        String takeoff = """
                "takeoffLng":%s,"takeoffLat":%s""".formatted(ZONE_GCJ_LNG, ZONE_GCJ_LAT);
        int taskId = createTaskWithTakeoff(token, "测试任务-禁飞区创建", takeoff);

        // 现在把区画上（同一个位置）
        addZone(token, "测试禁飞区-软拦", "polygon", squareRingGcj(), null);

        // 没确认 → 拒
        mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskName":"测试任务-禁飞区未确认","priority":"P2",
                                 "planStartTime":"2027-10-01 09:00:00","planEndTime":"2027-10-01 11:00:00",
                                 %s}
                                """.formatted(takeoff)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("禁飞区校验未通过")))
                .andExpect(jsonPath("$.message", containsString("测试禁飞区-软拦")));

        // 确认了但没写原因 → 也拒
        mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskName":"测试任务-禁飞区没写原因","priority":"P2",
                                 "planStartTime":"2027-10-01 09:00:00","planEndTime":"2027-10-01 11:00:00",
                                 "acknowledgedZones":true,%s}
                                """.formatted(takeoff)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("请填写原因")));

        // 确认 + 原因 → 建成，而且流水里留下那句留痕
        String created = mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskName":"测试任务-禁飞区已确认","priority":"P2",
                                 "planStartTime":"2027-10-01 09:00:00","planEndTime":"2027-10-01 11:00:00",
                                 "acknowledgedZones":true,"zoneReason":"今天有临时批文，已报备空管",
                                 %s}
                                """.formatted(takeoff)))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        int confirmedTaskId = JsonPath.read(created, "$.data.id");

        String createNote = jdbcTemplate.queryForObject(
                "SELECT note FROM task_log WHERE task_id = ? AND action = 'create'", String.class, confirmedTaskId);
        assertThat(createNote).as("创建流水的留痕")
                .contains("已知晓禁飞区风险")
                .contains("测试禁飞区-软拦")
                .contains("今天有临时批文");
        // 但理由要写清「为什么还是建了」，所以两半都得在
        assertThat(createNote).contains("原因：今天有临时批文，已报备空管");

        // ---- 二、派发时再查一次 ----
        // 上面那条 taskId 是「区还没画时建的」，正好用来验派发这一道
        int pilotId = addPilotAndGetId(token, "13900007001", "禁飞区测试飞手");
        int droneId = addDeviceAndGetId(token, "DRN-9701", "禁飞区测试机");

        // 预检接口也要能报出来（前端靠它把提示摆到人面前）
        String dispatchHits = mockMvc.perform(get("/task/" + taskId + "/zone-check")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.read(dispatchHits, "$.data.length()").toString()).isEqualTo("1");
        assertThat(JsonPath.read(dispatchHits, "$.data[0].pointLabel").toString()).isEqualTo("起降点");

        // 不确认 → 拒（而且此时设备还没被占住）
        mockMvc.perform(post("/task/dispatch")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(taskId, pilotId, droneId)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("禁飞区校验未通过")));
        assertDroneStatus(token, droneId, "idle");

        // 确认 + 原因 → 派发成功，留痕在派发那条流水上
        mockMvc.perform(post("/task/dispatch")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"pilotId":%d,"droneId":%d,
                                 "acknowledgedZones":true,"zoneReason":"客户场地，已确认当天无飞行活动"}
                                """.formatted(taskId, pilotId, droneId)))
                .andExpect(jsonPath("$.state").value(200));

        String dispatchNote = jdbcTemplate.queryForObject(
                "SELECT note FROM task_log WHERE task_id = ? AND action = 'dispatch'", String.class, taskId);
        assertThat(dispatchNote).as("派发流水的留痕")
                .contains("已知晓禁飞区风险").contains("客户场地，已确认当天无飞行活动");
    }

    /** 飞手看不到也改不了禁飞区；机务能看能改（顺带验证迁移脚本真的授了权） */
    @Test
    void zonePermissionShouldFollowZonePermissionPoints() throws Exception {
        String pilotToken = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);
        String maintainerToken = loginAndGetToken("13800001004", EXISTING_PASSWORD);

        mockMvc.perform(get("/zone/list").header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(jsonPath("$.state").value(403));
        mockMvc.perform(post("/zone/add")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(zoneBody("测试禁飞区-飞手越权", "polygon", squareRingGcj(), null, null)))
                .andExpect(jsonPath("$.state").value(403));

        mockMvc.perform(get("/zone/list").header(AUTH_HEADER, "Bearer " + maintainerToken))
                .andExpect(jsonPath("$.state").value(200));
    }

    // ==================================================================
    // 禁飞区：运行时判定（地图接入方案 第四步的核心）
    //   静态校验拦的是「计划」，这里判的是「此刻在哪」—— 它才拦得住已经起飞的飞机
    // ==================================================================

    /**
     * 飞进完全禁飞的区域 → 产生 {@code zone.breach}，而且**停在区内只留一条**。
     *
     * <h2>这条用例钉住的四件事</h2>
     * <ol>
     *   <li>判定用的坐标是<b>设备报的 WGS84</b>：区域是拿 GCJ-02 画的（接口约定），
     *       库里存成 WGS84，所以「设备报的原始坐标直接落在区内」这件事必须成立 ——
     *       中间要是谁混进了 GCJ-02，位置会偏 500 米，这条就会红</li>
     *   <li>级别是 critical、动作提示要飞离：这是「必须有人立刻处置」的那一类</li>
     *   <li>告警要挂在任务上（task_id）—— 推送给该任务飞手靠的就是它</li>
     *   <li>一秒一条遥测持续在区内，<b>未处理的告警仍然只有一条</b>
     *       （靠现成的抑制规则，不是靠时间窗）</li>
     * </ol>
     */
    @Test
    void zoneBreachShouldBeRaisedOnceWhileStayingInside() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9901";
        int taskId = startedTask(token, "13900009001", deviceCode, "禁飞区运行时用例",
                "2027-12-01 09:00:00", "2027-12-01 11:00:00");

        // 设备上报的位置（WGS84）。区域要盖住它 ——
        // 所以顶点先按 WGS84 算好，再转成 GCJ-02 传给接口（见 ringAroundWgs84 的注释）
        double lng = 116.3907;
        double lat = 39.9076;
        addZone(token, "测试禁飞区-运行时", "polygon", ringAroundWgs84(lng, lat, 0.002), null);

        // 飞进区内（带高度与飞行状态，和真机报的字段一致）
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 82001L,
                "2027-12-01 09:30:00", """
                        {"lng":%s,"lat":%s,"alt":60,"flightState":"flying","battery":80}"""
                        .formatted(lng, lat)));

        List<Map<String, Object>> rows = zoneAlerts(deviceCode, "zone.breach");
        assertThat(rows).as("应当产生一条 zone.breach").hasSize(1);

        Map<String, Object> alert = rows.get(0);
        assertThat(alert.get("severity")).as("级别").isEqualTo("critical");
        assertThat(alert.get("status")).as("初始状态").isEqualTo("pending");
        assertThat(numberOf(alert, "task_id")).as("要挂在执行中的任务上").isEqualTo(taskId);
        assertThat(numberOf(alert, "pilot_action_required")).as("要飞手动手").isEqualTo(1);
        assertThat(String.valueOf(alert.get("action_hint"))).as("动作提示").contains("飞离");
        assertThat(String.valueOf(alert.get("message"))).as("说明里要有区域名")
                .contains("测试禁飞区-运行时");
        // 位置用报文里的原始数字，事后能和 task_evidence 的报文原文对上
        assertThat(String.valueOf(alert.get("message"))).as("说明里要有设备报的位置")
                .contains("116.3907").contains("39.9076");

        // 持续在区内：再来两条，未处理的仍然只有一条
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 82002L,
                "2027-12-01 09:30:01", """
                        {"lng":%s,"lat":%s,"alt":60,"flightState":"flying"}""".formatted(lng, lat)));
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 82003L,
                "2027-12-01 09:30:02", """
                        {"lng":%s,"lat":%s,"alt":61,"flightState":"flying"}""".formatted(lng, lat)));

        assertThat(zoneAlerts(deviceCode, "zone.breach")).as("停在区内不该反复报警").hasSize(1);
    }

    /**
     * 限高区：只有「高度已知且超过限高」才算命中。
     *
     * <p>下面 60 米那条是最要紧的一半 —— 在限高区里正常高度飞，
     * <b>一条告警都不该有</b>。误报比漏报更伤：飞手会因此不再相信这套告警。
     */
    @Test
    void zoneAltOverShouldOnlyFireWhenOverTheLimit() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9902";
        addDeviceAndGetId(token, deviceCode, "限高区用例设备");

        double lng = 116.4007;
        double lat = 39.9106;
        addZone(token, "测试限高区-运行时", "polygon", ringAroundWgs84(lng, lat, 0.002), 120);

        // 在限高区里飞 60 米：不该有任何空间类告警
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 83001L,
                "2027-12-02 09:30:00", """
                        {"lng":%s,"lat":%s,"alt":60,"flightState":"flying"}""".formatted(lng, lat)));
        assertThat(zoneAlerts(deviceCode, "zone.breach")).as("限高区里没超高，不该报越界").isEmpty();
        assertThat(zoneAlerts(deviceCode, "zone.alt.over")).as("60 米低于限高 120 米").isEmpty();

        // 飞到 150 米：超限高，报 zone.alt.over（限高区里没有「完全禁飞」这回事）
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 83002L,
                "2027-12-02 09:30:01", """
                        {"lng":%s,"lat":%s,"alt":150,"flightState":"flying"}""".formatted(lng, lat)));
        List<Map<String, Object>> rows = zoneAlerts(deviceCode, "zone.alt.over");
        assertThat(rows).as("超限高应当报 zone.alt.over").hasSize(1);
        assertThat(rows.get(0).get("severity")).as("限高这条是 warning").isEqualTo("warning");
        assertThat(String.valueOf(rows.get(0).get("message")))
                .as("说明里要写清限高多少、实际多少")
                .contains("限高 120 米").contains("150");
        // 限高区不产生 zone.breach —— 两者是两种规则，不能混
        assertThat(zoneAlerts(deviceCode, "zone.breach")).as("限高区不该报越界").isEmpty();
    }

    /** 停用的区域不算数；区外的点也不报 —— 这两条是「绝大多数遥测要走到这里」的那条路 */
    @Test
    void zoneRuntimeCheckShouldIgnoreDisabledZonesAndOutsidePoints() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9903";
        addDeviceAndGetId(token, deviceCode, "运行时边界用例设备");

        double lng = 116.4107;
        double lat = 39.9206;

        // 一个**已停用**的区域盖住这个点：停用是排查问题时最常用的操作，它必须真的退出判定
        mockMvc.perform(post("/zone/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"测试禁飞区-停用的","shape":"polygon","points":%s,"enabled":false}
                                """.formatted(ringAroundWgs84(lng, lat, 0.002))))
                .andExpect(jsonPath("$.state").value(200));

        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 84001L,
                "2027-12-03 09:30:00", """
                        {"lng":%s,"lat":%s,"alt":60,"flightState":"flying"}""".formatted(lng, lat)));
        assertThat(zoneAlerts(deviceCode, "zone.breach")).as("停用的区域不该产生告警").isEmpty();

        // 再把区域启用起来，同一个位置就该报了 —— 否则上面那条「没报」说明不了问题
        String zoneIdText = mockMvc.perform(get("/zone/list").param("keyword", "测试禁飞区-停用的")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        int zoneId = JsonPath.read(zoneIdText, "$.data.list[0].id");
        mockMvc.perform(post("/zone/" + zoneId + "/toggle").param("enabled", "true")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200));

        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 84002L,
                "2027-12-03 09:30:01", """
                        {"lng":%s,"lat":%s,"alt":60,"flightState":"flying"}""".formatted(lng, lat)));
        assertThat(zoneAlerts(deviceCode, "zone.breach")).as("启用之后就该报了").hasSize(1);

        // 区外的点不报
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 84003L,
                "2027-12-03 09:30:02", """
                        {"lng":116.5000,"lat":40.0000,"alt":60,"flightState":"flying"}"""));
        assertThat(zoneAlerts(deviceCode, "zone.breach")).as("换了位置也不该多出告警").hasSize(1);
    }

    // ---------- 运行时用例用到的工具 ----------

    /**
     * 画一个**以某个 WGS84 坐标为中心**的正方形区域，返回接口要的顶点 JSON。
     *
     * <h2>为什么要绕这一圈</h2>
     * 区域接口收的是 <b>GCJ-02</b>，而库里存的是 <b>WGS84</b>；
     * 设备上报的是 <b>WGS84</b>。所以「让一个区域盖住设备报的那个点」这件事，
     * 必须先把 WGS84 的顶点算出来、再转成 GCJ-02 传进去 ——
     * 直接把设备的 WGS84 数字当 GCJ-02 传上去，画出来的区域会偏 500 米，
     * 设备飞进去也不会报（而且这种错看起来「差不多对」）。
     */
    private String ringAroundWgs84(double wgsLng, double wgsLat, double halfSpan) {
        double[][] corners = {
                {wgsLng - halfSpan, wgsLat - halfSpan},
                {wgsLng + halfSpan, wgsLat - halfSpan},
                {wgsLng + halfSpan, wgsLat + halfSpan},
                {wgsLng - halfSpan, wgsLat + halfSpan},
        };
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < corners.length; i++) {
            double[] gcj = CoordinateUtil.wgs84ToGcj02(corners[i][0], corners[i][1]);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"lng\":%s,\"lat\":%s}".formatted(gcj[0], gcj[1]));
        }
        return json.append(']').toString();
    }

    /** 按设备 + 编码查告警（用直接查库，避免用例依赖告警列表的筛选参数） */
    private List<Map<String, Object>> zoneAlerts(String deviceCode, String alertCode) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM task_alert WHERE device_code = ? AND alert_code = ?", deviceCode, alertCode);
    }

    // ---------- 禁飞区用例用到的请求体与小工具 ----------

    /** 一个正方形的顶点（GCJ-02），盖住参考点附近约 400 × 450 米 */
    private String squareRingGcj() {
        return """
                [{"lng":116.4082,"lat":39.9144},
                 {"lng":116.4122,"lat":39.9144},
                 {"lng":116.4122,"lat":39.9184},
                 {"lng":116.4082,"lat":39.9184}]
                """;
    }

    /** 圆心在参考点、给定半径的圆（GCJ-02） */
    private String circleGcj(double radiusMeters) {
        return """
                {"lng":%s,"lat":%s,"radius":%s}""".formatted(ZONE_GCJ_LNG, ZONE_GCJ_LAT, radiusMeters);
    }

    /** 新增区域的请求体：形状决定用哪个几何字段 */
    private String zoneBody(String name, String shape, String geometryJson, Integer maxAlt, Boolean enabled) {
        String geometryPart = "polygon".equals(shape)
                ? "\"points\":" + geometryJson
                // 圆的半径从传进来的那坨几何里取，免得调用方给了一个、请求里又写死另一个
                : "\"centerLng\":" + ZONE_GCJ_LNG + ",\"centerLat\":" + ZONE_GCJ_LAT
                        + ",\"radius\":" + JsonPath.read(geometryJson, "$.radius");
        return """
                {"name":"%s","shape":"%s",%s%s%s}
                """.formatted(name, shape, geometryPart,
                maxAlt == null ? "" : ",\"maxAlt\":" + maxAlt,
                enabled == null ? "" : ",\"enabled\":" + enabled);
    }

    /** 新增一个区域，返回 id */
    private int addZone(String token, String name, String shape, String geometryJson, Integer maxAlt) throws Exception {
        String body = mockMvc.perform(post("/zone/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(zoneBody(name, shape, geometryJson, maxAlt, null)))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.id");
    }

    /** 建一条只带起降点的任务；takeoffJson 形如 {@code "takeoffLng":...,"takeoffLat":...} */
    private int createTaskWithTakeoff(String token, String taskName, String takeoffJson) throws Exception {
        String body = mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskName":"%s","priority":"P2",
                                 "planStartTime":"2027-10-01 09:00:00","planEndTime":"2027-10-01 11:00:00",
                                 %s}
                                """.formatted(taskName, takeoffJson)))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.id");
    }

    // ==================================================================
    // 监控看板 + 到点打卡（地图接入方案 第五步）
    // ==================================================================

    /**
     * 看板要给出「谁在飞、现在在哪、刚才怎么飞」。
     *
     * <p>顺带钉住坐标系：库里的位置是 WGS84，看板给出去的是 GCJ-02 ——
     * 忘了转的话地图上会偏 500 米，而看板恰恰是**只靠眼睛**判断的地方，最难发现。
     */
    @Test
    void monitorShouldShowExecutingTaskWithPositionAndTrail() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9951";
        int taskId = startedTask(token, "13900009501", deviceCode, "看板用例任务",
                "2027-12-20 09:00:00", "2027-12-20 11:00:00");

        // 两个点：设备报的是 WGS84
        double lng = 116.3907;
        double lat = 39.9076;
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 85001L,
                "2027-12-20 09:30:00", """
                        {"lng":%s,"lat":%s,"alt":60,"flightState":"flying"}""".formatted(lng, lat)));
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 85002L,
                "2027-12-20 09:30:01", """
                        {"lng":%s,"lat":%s,"alt":61,"flightState":"flying"}""".formatted(lng + 0.001, lat)));

        String body = mockMvc.perform(get("/task/monitor").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> row = findMonitorRow(body, taskId);
        assertThat(row).as("看板上应当有这条执行中的任务").isNotNull();
        assertThat(row.get("droneCode")).as("设备编号").isEqualTo(deviceCode);
        assertThat(row.get("overdue")).as("计划时间没过，不该算超期").isEqualTo(false);
        assertThat(row.get("coordinateSystem")).as("坐标系声明").isEqualTo("gcj02");
        assertThat(JsonPath.read(row, "$.trail.length()").toString()).as("两个点都在航迹里").isEqualTo("2");

        // 当前位置 = 最后一条的坐标，而且**已经转成 GCJ-02**：
        // 期望值就用同一个转换函数算出来（转换本身在坐标转换那条用例里已经与公开实现比对过），
        // 再断言它与原始 WGS84 的差值量级 —— 两条合起来才能说明「转了、且方向对」
        double expectedGcjLng = CoordinateUtil.wgs84ToGcj02(lng + 0.001, lat)[0];
        double currentLng = ((Number) JsonPath.read(row, "$.current.lng")).doubleValue();
        assertThat(currentLng).as("当前位置应当是最后那个点的 GCJ-02 坐标")
                .isCloseTo(expectedGcjLng, offset(1e-5));
        assertThat(currentLng - (lng + 0.001)).as("经度偏移量级（约 500 米）").isBetween(0.004, 0.009);

        // 落库的是 WGS84：直接查证据里的原文，确认没被转换污染
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM task_evidence WHERE device_code = ? ORDER BY id DESC LIMIT 1",
                String.class, deviceCode);
        assertThat(payload).as("证据里存的仍是设备报的原值").contains(String.valueOf(lng + 0.001));
    }

    /** 没有坐标的任务要能显示成「暂无位置」；超过计划结束时间要算超期 */
    @Test
    void monitorShouldMarkOverdueAndMissingPosition() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String deviceCode = "DRN-9952";
        // 计划结束时间放在过去，并让它执行起来 —— 用来验「超期」
        int taskId = startedTask(token, "13900009502", deviceCode, "看板用例-超期无位置",
                "2020-01-01 09:00:00", "2020-01-01 11:00:00");

        // 只发不带坐标的遥测（只有电量）
        receiveTelemetry(deviceCode, telemetryJsonWithPayload(deviceCode, 85003L,
                "2020-01-01 09:30:00", """
                        {"battery":88,"flightState":"flying"}"""));

        String body = mockMvc.perform(get("/task/monitor").header(AUTH_HEADER, "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> row = findMonitorRow(body, taskId);
        assertThat(row).as("任务仍在执行中，看板上要有").isNotNull();

        // 没有坐标 → current 为 null（界面上显示「暂无位置」，而不是画到 (0,0)）
        assertThat(row.get("current")).as("没有带坐标的遥测时 current 应为空").isNull();
        assertThat(JsonPath.read(row, "$.trail.length()").toString()).as("航迹为空").isEqualTo("0");

        assertThat(row.get("overdue")).as("早就过了计划结束时间").isEqualTo(true);
        assertThat(((Number) row.get("overdueMinutes")).longValue()).as("超期分钟数应当很大").isGreaterThan(1000L);
    }

    /** 看板只该有执行中的任务：待派发/已完成都不上板（否则板上会一直挂着一堆死任务） */
    @Test
    void monitorShouldOnlyContainExecutingTasks() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int pendingId = createTaskId(token, "看板用例-待派发", "2027-12-21 09:00:00", "2027-12-21 11:00:00");

        String body = mockMvc.perform(get("/task/monitor").header(AUTH_HEADER, "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(findMonitorRow(body, pendingId)).as("待派发的任务不该在看板上").isNull();

        // 飞手看不了看板
        String pilotToken = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);
        mockMvc.perform(get("/task/monitor").header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(jsonPath("$.state").value(403));
    }

    /**
     * 到点打卡：偏差由服务端算、坐标进出 GCJ-02、**补传重发只落一条**。
     *
     * <p>最后那一条是离线打卡的地基：飞手在山里打卡、回到有信号的地方补传，
     * 客户端可能把同一条重发几次 —— 靠唯一键（任务+航点+时刻）挡掉重复。
     */
    @Test
    void checkinShouldComputeDeviationAndIgnoreResubmit() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int routeId = addRoute(token, "打卡用例航线", twoPointJson());

        // 航线的第一个点（WGS84）就是打卡的目标点
        String routeDetail = mockMvc.perform(get("/route/" + routeId).header(AUTH_HEADER, "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        double targetGcjLng = ((Number) JsonPath.read(routeDetail, "$.data.points[0].lng")).doubleValue();
        double targetGcjLat = ((Number) JsonPath.read(routeDetail, "$.data.points[0].lat")).doubleValue();

        String pilot = "13900009503";
        int taskId = startedTaskWithRoute(token, pilot, "DRN-9953", "打卡用例任务", routeId);

        // 打卡点：从目标点往北挪约 100 米（纬度 +0.0009）。用 GCJ-02 传，与手机定位一致
        double checkinLat = targetGcjLat + 0.0009;
        String pilotToken = loginAndGetToken(pilot, "123456");
        String first = mockMvc.perform(post("/task/checkin")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"seq":1,"lng":%s,"lat":%s,
                                 "observedAt":"2027-12-22 10:00:00","accuracyM":8,"remark":"点位在围栏外"}
                                """.formatted(taskId, targetGcjLng, checkinLat)))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.duplicated").value(false))
                .andExpect(jsonPath("$.data.coordinateSystem").value("gcj02"))
                .andExpect(jsonPath("$.data.submittedLate").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 偏差 ≈ 100 米（服务端算的，不是客户端传的）
        int deviation = ((Number) JsonPath.read(first, "$.data.deviationM")).intValue();
        assertThat(deviation).as("偏差应当在 100 米上下").isBetween(80, 120);
        assertThat(JsonPath.read(first, "$.data.farAway").toString()).as("100 米不算远").isEqualTo("false");
        // 打卡点回给前端时也是 GCJ-02（与传进去的一致）
        assertThat(((Number) JsonPath.read(first, "$.data.lat")).doubleValue())
                .as("返回的纬度").isCloseTo(checkinLat, offset(1e-5));

        // 库里存的是 WGS84（与传进来的 GCJ-02 差约 500 米）
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT lng, lat, target_lng, deviation_m FROM task_checkin WHERE task_id = ?", taskId);
        double storedLng = ((Number) stored.get("lng")).doubleValue();
        assertThat(Math.abs(storedLng - targetGcjLng)).as("经度偏移量级").isBetween(0.004, 0.009);
        assertThat(((Number) stored.get("deviation_m")).intValue()).as("落库的偏差与服务端算的一致")
                .isEqualTo(deviation);

        // ---- 同一条重发（离线补传重试）：不报错、不新增、返回 duplicated=true ----
        mockMvc.perform(post("/task/checkin")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"seq":1,"lng":%s,"lat":%s,
                                 "observedAt":"2027-12-22 10:00:00"}
                                """.formatted(taskId, targetGcjLng, checkinLat)))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.duplicated").value(true));

        assertThat(countCheckins(taskId)).as("重发不该多出一条").isEqualTo(1);

        // ---- 同一个点、另一个时刻：是另一次打卡，允许（「去过两趟」）----
        mockMvc.perform(post("/task/checkin")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"seq":1,"lng":%s,"lat":%s,
                                 "observedAt":"2027-12-22 11:00:00"}
                                """.formatted(taskId, targetGcjLng, checkinLat)))
                .andExpect(jsonPath("$.data.duplicated").value(false));
        assertThat(countCheckins(taskId)).as("换个时刻是另一次打卡").isEqualTo(2);
        // 但「已打卡的点位数」仍然按不同的航点算（进度条要的是覆盖了几个点位）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT seq) FROM task_checkin WHERE task_id = ?", Integer.class, taskId))
                .as("不同航点数还是 1").isEqualTo(1);
    }

    /**
     * 客户端可以**声明**自己给的是哪一套坐标：不传按 GCJ-02，传 {@code wgs84} 就按原值用。
     *
     * <p>为什么要这个开关：小程序定位给的是 GCJ-02，而浏览器 GPS 给的是 WGS84 ——
     * 服务端靠猜的话，猜错的表现是位置偏 500 米，而它看起来「差不多对」。
     * 这条用例就是拿「目标点的原始 WGS84 坐标 + 声明 wgs84」去打卡，
     * 偏差必须是 0（要是服务端又转了一次，就会凭空多出 500 米）。
     */
    @Test
    void checkinShouldRespectDeclaredCoordinateSystem() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int routeId = addRoute(token, "打卡用例航线-WGS84", twoPointJson());
        String pilot = "13900009508";
        int taskId = startedTaskWithRoute(token, pilot, "DRN-9958", "打卡用例-坐标系声明", routeId);
        String pilotToken = loginAndGetToken(pilot, "123456");

        // 目标点的**库里坐标（WGS84）**：航线建的时候传的是 GCJ-02（接口约定），
        // 库里存的是它换算之后的值 —— 这里用同一个换算算出期望值
        double[] targetWgs = CoordinateUtil.gcj02ToWgs84(116.41024449, 39.91640428);
        double targetWgsLng = targetWgs[0];
        double targetWgsLat = targetWgs[1];

        // 直接把原始 WGS84 报上来，并声明是 wgs84
        String body = mockMvc.perform(post("/task/checkin")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"seq":1,"lng":%s,"lat":%s,
                                 "observedAt":"2027-12-26 10:00:00","coordinateSystem":"wgs84"}
                                """.formatted(taskId, targetWgsLng, targetWgsLat)))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(((Number) JsonPath.read(body, "$.data.deviationM")).intValue())
                .as("报的就是目标点本身，偏差该是 0（转了一次就会变成 500 米）").isZero();

        // 库里存的也是原值，没有被再转一次
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT lng, lat FROM task_checkin WHERE task_id = ?", taskId);
        assertThat(((Number) stored.get("lng")).doubleValue()).as("库里经度")
                .isCloseTo(targetWgsLng, offset(1e-5));

        // 但返回给前端的仍是 GCJ-02（与别的接口一致，能直接画在地图上）
        double returnedLng = ((Number) JsonPath.read(body, "$.data.lng")).doubleValue();
        assertThat(returnedLng - targetWgsLng).as("返回值的经度偏移量级").isBetween(0.004, 0.009);

        // 试算：只算不落库（客户端拿它决定要不要弹「确定到了吗」）
        String dry = mockMvc.perform(post("/task/checkin")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"seq":2,"lng":%s,"lat":%s,
                                 "observedAt":"2027-12-26 10:30:00","coordinateSystem":"wgs84","dryRun":true}
                                """.formatted(taskId, targetWgsLng, targetWgsLat + 0.045)))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.farAway").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 试算没有落库，所以返回里没有 id（有 id 就说明它真写进去了）
        assertThat((Object) JsonPath.read(dry, "$.data.id")).as("试算不该产生记录").isNull();
        assertThat(countCheckins(taskId)).as("试算完库里还是只有那一条").isEqualTo(1);

        // 声明了不认识的坐标系：直接拒，不猜
        mockMvc.perform(post("/task/checkin")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"seq":1,"lng":%s,"lat":%s,
                                 "observedAt":"2027-12-26 11:00:00","coordinateSystem":"bd09"}
                                """.formatted(taskId, targetWgsLng, targetWgsLat)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("坐标只能是")));
    }

    /** 打偏了要标出来；补传要标出来；别人的任务打不了；没有的航点打不了 */
    @Test
    void checkinShouldFlagFarAwayLateAndRejectBadInput() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int routeId = addRoute(token, "打卡用例航线-边界", twoPointJson());
        String routeDetail = mockMvc.perform(get("/route/" + routeId).header(AUTH_HEADER, "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        double targetGcjLng = ((Number) JsonPath.read(routeDetail, "$.data.points[0].lng")).doubleValue();
        double targetGcjLat = ((Number) JsonPath.read(routeDetail, "$.data.points[0].lat")).doubleValue();

        String pilot = "13900009504";
        int taskId = startedTaskWithRoute(token, pilot, "DRN-9954", "打卡用例任务-边界", routeId);
        String pilotToken = loginAndGetToken(pilot, "123456");
        String post = "POST /task/checkin";

        // 打偏 5 公里（纬度 +0.045），而且打卡时刻在 20 分钟前 → 远 + 补传
        String far = mockMvc.perform(post("/task/checkin")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"seq":1,"lng":%s,"lat":%s,"observedAt":"%s"}
                                """.formatted(taskId, targetGcjLng, targetGcjLat + 0.045,
                                LocalDateTime.now().minusMinutes(20).format(API_TIME))))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(((Number) JsonPath.read(far, "$.data.deviationM")).intValue())
                .as("北偏 0.045 度差不多 5 公里").isBetween(4500, 5500);
        assertThat(JsonPath.read(far, "$.data.farAway").toString()).as("这么远要标出来").isEqualTo("true");
        assertThat(JsonPath.read(far, "$.data.submittedLate").toString())
                .as("打卡时刻在 20 分钟前、刚传上来 → 判为补传").isEqualTo("true");

        // 别人的任务：403（而不是「找不到」—— 不能告诉他这条任务存不存在）
        int otherTaskId = startedTask(token, "13900009505", "DRN-9955", "打卡用例-别人",
                "2027-12-23 09:00:00", "2027-12-23 11:00:00");
        mockMvc.perform(post("/task/checkin")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"seq":1,"lng":%s,"lat":%s,"observedAt":"2027-12-22 10:00:00"}
                                """.formatted(otherTaskId, targetGcjLng, targetGcjLat)))
                .andExpect(jsonPath("$.state").value(403));

        // 这条任务上没有第 99 个航点
        mockMvc.perform(post("/task/checkin")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"seq":99,"lng":%s,"lat":%s,"observedAt":"2027-12-22 10:00:00"}
                                """.formatted(taskId, targetGcjLng, targetGcjLat)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("没有第 99 个航点")));

        // 缺打卡时刻：它同时是补传去重的键，不能悄悄用「现在」顶上
        mockMvc.perform(post("/task/checkin")
                        .header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"seq":1,"lng":%s,"lat":%s}
                                """.formatted(taskId, targetGcjLng, targetGcjLat)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("缺少打卡时刻")));

        // 没有航线的任务：没有打卡目标，明确报错而不是静默记一条偏差 0 的
        int noRouteTask = startedTask(token, "13900009506", "DRN-9956", "打卡用例-无航线",
                "2027-12-24 09:00:00", "2027-12-24 11:00:00");
        String otherPilotToken = loginAndGetToken("13900009506", "123456");
        mockMvc.perform(post("/task/checkin")
                        .header(AUTH_HEADER, "Bearer " + otherPilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"seq":1,"lng":%s,"lat":%s,"observedAt":"2027-12-22 10:00:00"}
                                """.formatted(noRouteTask, targetGcjLng, targetGcjLat)))
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("没有第 1 个航点")));
        assertThat(countCheckins(noRouteTask)).as("被拒的打卡不该落库").isZero();
    }

    /** 飞手端的任务地图：一次给全航线、起降点、轨迹、打卡进度；不是我的任务 403 */
    @Test
    void myTaskMapShouldGiveRouteTakeoffTrackAndProgress() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int routeId = addRoute(token, "飞手地图用例航线", twoPointJson());
        String pilot = "13900009507";
        int taskId = startedTaskWithRoute(token, pilot, "DRN-9957", "飞手地图用例", routeId);

        // 设备飞了一段（轨迹要能出来）
        receiveTelemetry("DRN-9957", telemetryJsonWithPayload("DRN-9957", 86001L,
                "2027-12-25 09:30:00", """
                        {"lng":116.404,"lat":39.915,"alt":60,"flightState":"flying"}"""));

        String pilotToken = loginAndGetToken(pilot, "123456");
        String body = mockMvc.perform(get("/task/my/" + taskId + "/map")
                        .header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.coordinateSystem").value("gcj02"))
                .andExpect(jsonPath("$.data.plannedRoute.points.length()").value(2))
                .andExpect(jsonPath("$.data.totalPointCount").value(2))
                .andExpect(jsonPath("$.data.checkedPointCount").value(0))
                .andExpect(jsonPath("$.data.track.points.length()").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 起降点没给（这条任务建的时候没点起降点）→ 三个字段都是空，界面上不画那个标记
        // 显式转成 Object：JsonPath.read 是泛型方法，不写死类型时
        // 编译器会在 assertThat 的重载之间挑不出一个（Predicate / IntPredicate 都匹配）
        assertThat((Object) JsonPath.read(body, "$.data.takeoffLng")).isNull();

        // 另一个飞手看不了这条任务的图
        String otherToken = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);
        mockMvc.perform(get("/task/my/" + taskId + "/map").header(AUTH_HEADER, "Bearer " + otherToken))
                .andExpect(jsonPath("$.state").value(403));
    }

    // ---------- 看板与打卡用例用到的工具 ----------

    /** 从看板 JSON 里挑出某条任务那一行；没有返回 null */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findMonitorRow(String monitorJson, int taskId) {
        List<Map<String, Object>> rows = JsonPath.read(monitorJson, "$.data");
        return rows.stream()
                .filter(row -> ((Number) row.get("taskId")).longValue() == taskId)
                .findFirst().orElse(null);
    }

    private int countCheckins(int taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_checkin WHERE task_id = ?", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    /**
     * 起一条「执行中 + 引用了一条航线」的任务。
     *
     * <p>打卡与飞手地图都要有航线作依托（目标点只能来自航线快照），
     * 所以不能直接用只带起降点的 {@code startedTask}。
     */
    private int startedTaskWithRoute(String token, String pilotUsername, String deviceCode,
                                     String taskName, int routeId) throws Exception {
        int pilotId = addPilotAndGetId(token, pilotUsername, "第五步用例飞手");
        int droneId = addDeviceAndGetId(token, deviceCode, "第五步用例设备");
        String created = mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskName":"%s","priority":"P2",
                                 "planStartTime":"2027-12-20 09:00:00","planEndTime":"2027-12-20 11:00:00",
                                 "routeId":%d}
                                """.formatted(taskName, routeId)))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        int taskId = JsonPath.read(created, "$.data.id");

        dispatchAs(token, taskId, pilotId, droneId);
        String pilotToken = loginAndGetToken(pilotUsername, "123456");
        mockMvc.perform(post("/task/start").header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":%d}".formatted(taskId)))
                .andExpect(jsonPath("$.state").value(200));
        return taskId;
    }

    // ==================================================================
    // 数字大屏（见 docs/数字大屏方案.md）
    // ==================================================================

    /**
     * 总览的块按权限裁：**没权限的块整个 key 不出现**，而不是给 0。
     *
     * <p>这一条同时钉住两件事：
     * <ol>
     *   <li>管理侧与飞手侧拿到的块**确实不同** —— 而且飞手拿的是 {@code myTasks}
     *       而不是 {@code tasks}（两者口径不同，是不同的 SQL）</li>
     *   <li><b>看不到的块不能变成 0</b>：给 0 会让人以为「今天一架次都没飞」，
     *       而真相是「你没这个权限」</li>
     * </ol>
     */
    @Test
    void dashboardSummaryShouldCutBlocksByPermission() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        // 先造一条飞手的任务，确保 myTasks 有数可算
        String pilot = "13900009701";
        startedTask(adminToken, pilot, "DRN-9971", "大屏用例任务",
                "2027-12-28 09:00:00", "2027-12-28 11:00:00");

        String adminBody = mockMvc.perform(get("/dashboard/summary")
                        .header(AUTH_HEADER, "Bearer " + adminToken))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 管理侧：有全量任务、告警、设备、人员、证据；**没有 myTasks / myAlerts**
        assertThat((Object) JsonPath.read(adminBody, "$.data.tasks.total")).isNotNull();
        assertThat((Object) JsonPath.read(adminBody, "$.data.alerts.open")).isNotNull();
        assertThat((Object) JsonPath.read(adminBody, "$.data.devices.total")).isNotNull();
        assertThat((Object) JsonPath.read(adminBody, "$.data.staff.total")).isNotNull();
        assertThat((Object) JsonPath.read(adminBody, "$.data.evidence.today")).isNotNull();
        assertThat(adminBody).as("管理侧不该有「我的任务」").doesNotContain("myTasks");
        // geo 是登录即可看的（拍板）：两种身份都该有
        assertThat((Object) JsonPath.read(adminBody, "$.data.geo.points")).isNotNull();

        // 飞手侧
        String pilotToken = loginAndGetToken(pilot, "123456");
        String pilotBody = mockMvc.perform(get("/dashboard/summary")
                        .header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat((Object) JsonPath.read(pilotBody, "$.data.myTasks.total")).isEqualTo(1);
        assertThat((Object) JsonPath.read(pilotBody, "$.data.myAlerts.open")).isNotNull();
        assertThat((Object) JsonPath.read(pilotBody, "$.data.geo.zones")).isNotNull();
        // **关键**：飞手拿不到全量表 —— 而是整个 key 都不在报文里
        assertThat(pilotBody).as("飞手不该拿到全量任务块").doesNotContain("\"tasks\"");
        assertThat(pilotBody).as("飞手不该拿到全量告警块").doesNotContain("\"alerts\"");
        assertThat(pilotBody).as("飞手不该拿到人员块").doesNotContain("\"staff\"");
    }

    /** 大屏的数字必须与列表页对得上 —— 对不上会当场失去信任（「大屏说 100，列表 98」） */
    @Test
    void dashboardNumbersShouldMatchListTotals() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        String summary = mockMvc.perform(get("/dashboard/summary").header(AUTH_HEADER, "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long taskTotal = ((Number) JsonPath.read(summary, "$.data.tasks.total")).longValue();

        String list = mockMvc.perform(get("/task/list").header(AUTH_HEADER, "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long listTotal = ((Number) JsonPath.read(list, "$.data.total")).longValue();
        assertThat(taskTotal).as("大屏的任务总数与任务列表的 total").isEqualTo(listTotal);

        long deviceTotal = ((Number) JsonPath.read(summary, "$.data.devices.total")).longValue();
        String devices = mockMvc.perform(get("/device/list").header(AUTH_HEADER, "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(deviceTotal).as("大屏的设备总数与设备列表的 total")
                .isEqualTo(((Number) JsonPath.read(devices, "$.data.total")).longValue());
    }

    /** 大屏配置：刷新间隔与轮播参数要能取到（前端靠它决定怎么刷） */
    @Test
    void dashboardConfigShouldBeReadable() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        mockMvc.perform(get("/dashboard/config").header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.refreshMapSeconds").value(3))
                .andExpect(jsonPath("$.data.refreshLiveSeconds").value(10))
                .andExpect(jsonPath("$.data.refreshStatSeconds").value(60))
                .andExpect(jsonPath("$.data.carouselPages[0]").value("overview"))
                // 夜里不停刷：这是 2026-09-29 拍板的
                .andExpect(jsonPath("$.data.nightPauseEnabled").value(false));
    }

    /**
     * 大屏账号（{@code screen} 角色）签**长效** token，普通账号还是 2 小时。
     *
     * <p>为什么要这条用例：大屏挂在值班室，**没人会去重新登录** ——
     * 用交互式的 2 小时，第二天早上看到的是一块停在登录页的屏。
     * 而「有效期」这种事一旦配错，表现是「隔一天就坏」，最不适合靠人去发现。
     */
    @Test
    void screenAccountShouldGetLongLivedToken() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        // 建一个只读的大屏账号（角色 screen 由迁移脚本建好）
        mockMvc.perform(post("/user/add")
                        .header(AUTH_HEADER, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"13900009702","name":"大屏用例账号","password":"123456","roleCode":"screen"}
                                """))
                .andExpect(jsonPath("$.state").value(200));

        String screenToken = loginAndGetToken("13900009702", "123456");
        String normalToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        long screenDays = tokenHours(screenToken) / 24;
        long normalHours = tokenHours(normalToken);

        assertThat(normalHours).as("普通账号还是 2 小时").isEqualTo(2);
        assertThat(screenDays).as("大屏账号是 365 天（8760 小时）").isEqualTo(365);

        // 只读：把大屏账号拿去调「能看到全部数据」的接口是通的，但写接口一律 403
        mockMvc.perform(get("/task/list").header(AUTH_HEADER, "Bearer " + screenToken))
                .andExpect(jsonPath("$.state").value(200));
        mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + screenToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskCreateBody("大屏越权建任务")))
                .andExpect(jsonPath("$.state").value(403));
    }

    /** 从 token 里解出有效小时数（exp - iat） */
    private long tokenHours(String token) throws Exception {
        var claims = jwtUtil.parseToken(token).getPayload();
        long seconds = claims.getExpiration().toInstant().getEpochSecond()
                - claims.getIssuedAt().toInstant().getEpochSecond();
        return seconds / 3600;
    }

    /**
     * 大屏总览的短 TTL 缓存：同一身份在 TTL 内拿到的是**同一份**（不重算），过了 TTL 会重算。
     *
     * <p>判据用 `generatedAt` —— 它是「这份数据是什么时候算出来的」，
     * 重算了必然变，没重算必然一样。比去数 SQL 次数简单，也不用改生产代码。
     */
    @Test
    void dashboardSummaryShouldBeCachedWithinTtl() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        String first = mockMvc.perform(get("/dashboard/summary").header(AUTH_HEADER, "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String second = mockMvc.perform(get("/dashboard/summary").header(AUTH_HEADER, "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String firstAt = JsonPath.read(first, "$.data.generatedAt");
        assertThat((Object) JsonPath.read(second, "$.data.generatedAt"))
                .as("TTL 内应当是同一份（没有重算）").isEqualTo(firstAt);

        // 等过 TTL（测试里配成 1 秒）
        Thread.sleep(1200);
        String third = mockMvc.perform(get("/dashboard/summary").header(AUTH_HEADER, "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((Object) JsonPath.read(third, "$.data.generatedAt"))
                .as("过了 TTL 应当重算").isNotEqualTo(firstAt);
    }

    /**
     * ⚠️ 缓存**绝不能跨身份复用** —— 这条是安全断言，不是性能断言。
     *
     * <p>总览是按权限裁块的：管理员 8 块、飞手 4 块。如果缓存 key 里没有「谁在问」，
     * 紧接着管理员之后调用的飞手就会命中管理员的缓存，**看到全量任务总数**。
     * 那看起来像「缓存没命中」，实际是越权。
     */
    @Test
    void dashboardCacheMustNotLeakAcrossUsers() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String pilot = "13900009703";
        startedTask(adminToken, pilot, "DRN-9973", "缓存隔离用例任务",
                "2027-12-29 09:00:00", "2027-12-29 11:00:00");

        // 先让管理员的请求把缓存填上（TTL 1 秒内）
        String adminBody = mockMvc.perform(get("/dashboard/summary").header(AUTH_HEADER, "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(adminBody).as("管理员该拿到全量任务块").contains("\"tasks\"");

        // 紧接着用飞手身份调：必须是**飞手自己那份**，而不是命中管理员那份
        String pilotToken = loginAndGetToken(pilot, "123456");
        String pilotBody = mockMvc.perform(get("/dashboard/summary").header(AUTH_HEADER, "Bearer " + pilotToken))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(pilotBody).as("飞手该拿到自己的任务块").contains("\"myTasks\"");
        assertThat(pilotBody).as("飞手绝不能命中管理员的缓存、看到全量任务").doesNotContain("\"tasks\"");
    }

    // ---------- 待派发归因（见 docs/业务分析方案.md 2.7） ----------

    /**
     * 判定本身：候选数 → 结论的映射。纯函数，不碰库 —— 四种组合各断一条。
     */
    @Test
    void dispatchBlockReasonShouldMapCandidatesToReason() {
        assertThat(DispatchBlockReason.of(0, 0)).isEqualTo(DispatchBlockReason.BLOCKED_BOTH);
        assertThat(DispatchBlockReason.of(0, 3)).isEqualTo(DispatchBlockReason.BLOCKED_PILOT);
        assertThat(DispatchBlockReason.of(2, 0)).isEqualTo(DispatchBlockReason.BLOCKED_DRONE);
        assertThat(DispatchBlockReason.of(1, 1)).isEqualTo(DispatchBlockReason.IDLE);
    }

    /**
     * 缺飞手：候选池里一个能派的都没有 → blocked_pilot。
     *
     * <p>三种归因各写一个用例、<b>各占一个时间窗</b>，原因有二：
     * 候选池是**全局**的（派发下拉里那些人、那些待命设备），要造出「一个都不剩」
     * 只能把整个池子占干；而用例自己现造的飞手/设备会立刻回到池子里 ——
     * 混在同一个窗口里摆，前一条的前提会被后一条自己毁掉。
     * 窗口取 2030 年一个安静的时段：库里没有这个点的排班，
     * 「谁被占住」就完全由本用例插的占位行决定；那些行随事务回滚，不留在开发库。
     *
     * <p>顺带验了「同一原因不重复记」：第二轮采样必须是静默的。
     * 归因是趋势数据，同一条任务每 10 分钟记一条，一天就是 144 行重复。
     */
    @Test
    void dispatchBlockShouldReportBlockedPilotWhenNobodyCanTakeIt() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String start = "2030-06-01 09:00:00";
        String end = "2030-06-01 11:00:00";

        // 先放一台自己的待命设备：设备一定是够的，这条才只会落在「缺飞手」上
        addDeviceAndGetId(token, "DRN-9701", "归因用例待命机");
        occupyPoolPilots(start, end);
        int taskId = createTaskId(token, "归因用例-缺飞手", start, end);

        assertThat(dispatchBlockService.samplePendingTasks()).isGreaterThanOrEqualTo(1);

        DispatchBlockPO row = dispatchBlockDao.selectLatestByTaskId((long) taskId);
        assertThat(row).as("这条待派发任务该被采样到").isNotNull();
        assertThat(row.getReason()).isEqualTo(DispatchBlockReason.BLOCKED_PILOT);
        assertThat(row.getPilotCandidates()).as("一个可派飞手都没有").isZero();
        assertThat(row.getDroneCandidates()).as("设备是够的，所以不该判成 blocked_both").isGreaterThan(0);

        // 原因没变、离上一条还不到 repeat-minutes（默认 60 分钟）→ 第二轮一条都不写
        assertThat(dispatchBlockService.samplePendingTasks()).as("第二轮不该产生新记录").isZero();
        assertThat(countBlockRows(taskId)).as("同一条任务只留了一条").isEqualTo(1);
    }

    /** 缺设备：飞手有，但待命设备一个不剩 → blocked_drone */
    @Test
    void dispatchBlockShouldReportBlockedDroneWhenNoIdleDroneLeft() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String start = "2030-06-02 09:00:00";
        String end = "2030-06-02 11:00:00";

        // 先放一个自己的飞手：飞手一定是够的，这条才只会落在「缺设备」上
        addPilotAndGetId(token, "13900009702", "归因用例待命飞手");
        occupyPoolDrones(start, end);
        int taskId = createTaskId(token, "归因用例-缺设备", start, end);

        assertThat(dispatchBlockService.samplePendingTasks()).isGreaterThanOrEqualTo(1);

        DispatchBlockPO row = dispatchBlockDao.selectLatestByTaskId((long) taskId);
        assertThat(row).as("这条待派发任务该被采样到").isNotNull();
        assertThat(row.getReason()).isEqualTo(DispatchBlockReason.BLOCKED_DRONE);
        assertThat(row.getDroneCandidates()).as("一台待命设备都没有").isZero();
        assertThat(row.getPilotCandidates()).as("飞手是够的，所以不该判成 blocked_both").isGreaterThan(0);
    }

    /**
     * 资源够却没人派 → idle。
     *
     * <p>这是四种归因里最值钱的一条，也是唯一一条**不花钱就能改善**的：
     * 另外三种都指向「招人 / 买设备 / 修设备」，只有它指向「没人点派发」。
     */
    @Test
    void dispatchBlockShouldReportIdleWhenResourcesAreEnough() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String start = "2030-06-03 09:00:00";
        String end = "2030-06-03 11:00:00";

        // 不去占任何东西：现造一个飞手、一台设备，两者在这个窗口里都是空闲的
        addPilotAndGetId(token, "13900009703", "归因用例空闲飞手");
        addDeviceAndGetId(token, "DRN-9703", "归因用例空闲机");
        int taskId = createTaskId(token, "归因用例-没人派", start, end);

        assertThat(dispatchBlockService.samplePendingTasks()).isGreaterThanOrEqualTo(1);

        DispatchBlockPO row = dispatchBlockDao.selectLatestByTaskId((long) taskId);
        assertThat(row).as("这条待派发任务该被采样到").isNotNull();
        assertThat(row.getReason()).as("资源够却卡着 —— 这条指向「没人点派发」")
                .isEqualTo(DispatchBlockReason.IDLE);
        assertThat(row.getPilotCandidates()).isGreaterThan(0);
        assertThat(row.getDroneCandidates()).isGreaterThan(0);
    }

    // ---------- 业务分析：三个指标（见 docs/业务分析方案.md 2.7） ----------

    /**
     * 归因占比 / 积压时长分布 / 创建→派发中位时长，一次验完。
     *
     * <p>断言方式刻意分两种：
     * <ul>
     *   <li><b>占比与分桶</b>：先取基线再比**增量**。开发库里本来就有别人造的待派发任务，
     *       拿绝对值断言，迟早会被别人的一条数据搞挂</li>
     *   <li><b>中位数</b>：把自己的两条记录**挪进一个没人的历史窗口**（2029 年），
     *       于是区间里只剩我这两条，可以精确断言「偶数个取中间两个的平均」</li>
     * </ul>
     */
    @Test
    void analyticsShouldReportDispatchBlockStats() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        // 覆盖「现在」的区间：前后各留几天，免得刚好跨过午夜就挂
        String from = LocalDateTime.now().minusDays(3).format(API_TIME);
        String to = LocalDateTime.now().plusDays(1).format(API_TIME);

        String before = fetchDispatchBlockStats(token, from, to);

        // ---- ① 归因占比：区间内两条（idle / blocked_drone），区间外一条做对照 ----
        int pendingTask = createTaskId(token, "指标用例-待派发", "2030-07-01 09:00:00", "2030-07-01 11:00:00");
        insertBlockPoint(pendingTask, LocalDateTime.now().minusMinutes(10), "idle", 3, 2);
        insertBlockPoint(pendingTask, LocalDateTime.now().minusMinutes(20), "blocked_drone", 4, 0);
        // 10 天前那一条落在区间外：它不该被算进来 —— 这就是在验区间过滤真的生效
        insertBlockPoint(pendingTask, LocalDateTime.now().minusDays(10), "blocked_pilot", 0, 5);

        String after = fetchDispatchBlockStats(token, from, to);

        assertThat(reasonCount(after, "idle") - reasonCount(before, "idle")).isEqualTo(1L);
        assertThat(reasonCount(after, "blocked_drone") - reasonCount(before, "blocked_drone")).isEqualTo(1L);
        assertThat(reasonCount(after, "blocked_pilot") - reasonCount(before, "blocked_pilot"))
                .as("只插在区间外的那一条，不该让区间内的统计动").isZero();
        assertThat(blockPoints(after) - blockPoints(before)).as("区间外那一条不算").isEqualTo(2L);

        // 占比的分母是「有采样的点」，四个归因加起来必须凑满 100%
        List<Number> percents = JsonPath.read(after, "$.data.reasonShare[*].percent");
        assertThat(percents.stream().mapToDouble(Number::doubleValue).sum()).isBetween(99.5, 100.5);
        // 中文名由后端给（与 DispatchBlockReason.label 同一处定义），前端不重复维护一套翻译
        assertThat((Object) JsonPath.read(after, "$.data.reasonShare[0].label")).isNotNull();
        // 采样覆盖：区间是 3 天前 → 明天 0 点 = 96 小时，至少得有一个小时有采样
        assertThat(hours(after, "expectedHours")).isEqualTo(96L);
        assertThat(hours(after, "sampledHours")).isGreaterThan(0L);

        // ---- ② 积压时长分布：三个新任务分别落进三个桶 ----
        createTaskId(token, "指标用例-刚创建", "2030-07-02 09:00:00", "2030-07-02 11:00:00");
        int waitingHours = createTaskId(token, "指标用例-等了几小时", "2030-07-03 09:00:00", "2030-07-03 11:00:00");
        int waitingDays = createTaskId(token, "指标用例-等了几天", "2030-07-04 09:00:00", "2030-07-04 11:00:00");
        backdateTaskCreateTime(waitingHours, 3);
        backdateTaskCreateTime(waitingDays, 72);

        String aged = fetchDispatchBlockStats(token, from, to);
        assertThat(bucketCount(aged, "lt1h") - bucketCount(after, "lt1h")).as("刚创建的那条").isEqualTo(1L);
        assertThat(bucketCount(aged, "lt1d") - bucketCount(after, "lt1d")).as("等了 3 小时的那条").isEqualTo(1L);
        assertThat(bucketCount(aged, "ge1d") - bucketCount(after, "ge1d")).as("等了 3 天的那条").isEqualTo(1L);
        assertThat(pendingTotal(aged) - pendingTotal(after)).isEqualTo(3L);

        // ---- ③ 创建→派发中位时长：20 分钟与 40 分钟 → 中位数 30 ----
        // 一台设备只能被派一次（派出去就成「已指派」了），所以两条各用一台
        int pilotId = addPilotAndGetId(token, "13900009704", "指标用例飞手");
        int droneA = addDeviceAndGetId(token, "DRN-9704", "指标用例设备甲");
        int droneB = addDeviceAndGetId(token, "DRN-9705", "指标用例设备乙");
        dispatchAndBackdate(token, pilotId, droneA, "指标用例-等了20分钟",
                LocalDateTime.of(2029, 1, 1, 9, 0), 20);
        dispatchAndBackdate(token, pilotId, droneB, "指标用例-等了40分钟",
                LocalDateTime.of(2029, 1, 2, 9, 0), 40);

        String history = fetchDispatchBlockStats(token, "2029-01-01 00:00:00", "2029-01-03 00:00:00");
        assertThat(samples(history)).as("这个窗口里只有我这两条").isEqualTo(2);
        assertThat(hours(history, "medianMinutes")).as("20 与 40 的中位数取中间两个的平均").isEqualTo(30L);
    }

    /**
     * 区间参数要挡住明显不合理的两种：起点不早于终点、区间超过一年。
     *
     * <p>为什么要挡「超过一年」：一年以上就该走预聚合日表（第三步），
     * 实时扫明细出来的数没有意义，而归因点本身也只攒一年（见 8.3）。
     */
    @Test
    void analyticsShouldRejectInvalidRange() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/analytics/dispatch-block")
                        .param("from", "2026-09-30 00:00:00")
                        .param("to", "2026-09-01 00:00:00")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("起点必须早于终点")));

        mockMvc.perform(get("/analytics/dispatch-block")
                        .param("from", "2020-01-01 00:00:00")
                        .param("to", "2026-01-01 00:00:00")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(500))
                .andExpect(jsonPath("$.message", containsString("最长")));
    }

    /** 积压归因答的是管理动作的问题（task:list），飞手看不到 */
    @Test
    void analyticsDispatchBlockShouldBeForbiddenForPilot() throws Exception {
        String pilotToken = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/analytics/dispatch-block").header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value(403));
    }

    // ---------- 业务分析：任务与告警概览（docs/业务分析方案.md 第一步） ----------

    /**
     * 任务侧的三个口径 + 趋势的补零。
     *
     * <p>用「一个没人的历史窗口」（2032-03）把四条任务摆成四种结局，断言才能精确：
     * 完成率 = 完成 ÷（完成 + 失败）、**取消的不参与**（它从没执行，也就没有结束时刻）、
     * 按时率只看「有计划结束时间」的已完成任务、平均时长只看有实际起止时间的。
     */
    @Test
    void analyticsOverviewShouldReportTaskOutcome() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        int onTime = createTaskId(token, "概览用例-按时完成", "2032-04-01 09:00:00", "2032-04-01 11:00:00");
        int late = createTaskId(token, "概览用例-超时完成", "2032-04-02 09:00:00", "2032-04-02 11:00:00");
        int failed = createTaskId(token, "概览用例-失败", "2032-04-03 09:00:00", "2032-04-03 11:00:00");
        int cancelled = createTaskId(token, "概览用例-取消", "2032-04-04 09:00:00", "2032-04-04 11:00:00");

        // 直接摆终态：走接口要把任务真的飞一遍，跟这里要验的口径无关
        setTaskOutcome(onTime, "finished", "2032-03-01 09:00:00", "2032-03-01 10:00:00", "2032-03-01 11:00:00");
        setTaskOutcome(late, "finished", "2032-03-02 09:00:00", "2032-03-02 12:00:00", "2032-03-02 11:00:00");
        setTaskOutcome(failed, "failed", null, "2032-03-03 09:00:00", "2032-03-03 11:00:00");
        // 取消：没有实际结束时间，也没有实际开始时间（撤销的活从没执行过）
        jdbcTemplate.update("UPDATE `task` SET status = 'cancelled' WHERE id = ?", (long) cancelled);

        String body = fetchOverview(token, "2032-03-01 00:00:00", "2032-03-05 00:00:00");

        List<String> days = JsonPath.read(body, "$.data.days");
        assertThat(days).as("横轴是区间内的每一天（终点不含）")
                .containsExactly("2032-03-01", "2032-03-02", "2032-03-03", "2032-03-04");

        assertThat(intAt(body, "$.data.tasks.finished")).isEqualTo(2);
        assertThat(intAt(body, "$.data.tasks.failed")).as("取消的不算失败").isEqualTo(1);
        assertThat(doubleAt(body, "$.data.tasks.finishRate")).as("2 ÷ (2+1)，保留一位小数").isEqualTo(66.7);

        assertThat(intAt(body, "$.data.tasks.onTimeBase")).as("按时率的样本是有计划结束时间的已完成任务").isEqualTo(2);
        assertThat(doubleAt(body, "$.data.tasks.onTimeRate")).as("两条里只有第一条不晚于计划").isEqualTo(50.0);

        assertThat(intAt(body, "$.data.tasks.durationBase")).as("失败那条没有实际开始时间，不进时长").isEqualTo(2);
        assertThat(intAt(body, "$.data.tasks.avgMinutes")).as("(60 + 180) ÷ 2").isEqualTo(120);

        // 那几天当然没有新建任务，但趋势要补成 0（缺项会让折线断在中间）
        List<Number> created = JsonPath.read(body, "$.data.tasks.created");
        assertThat(created).hasSize(4);
        assertThat(created).allMatch(value -> value.longValue() == 0L);
    }

    /**
     * 告警侧：趋势按「天 × 级别」、MTTR 只算已处置的、未处置的单独给一个快照数。
     *
     * <p>同样是 2032-04 的干净窗口，所以可以拿绝对值断言。
     */
    @Test
    void analyticsOverviewShouldReportAlertTrendAndMttr() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int taskId = createTaskId(token, "概览用例-告警", "2032-04-05 09:00:00", "2032-04-05 11:00:00");

        insertAlert(taskId, "bat.critical", "critical", "2032-04-01 09:00:00", "2032-04-01 09:30:00", "resolved");
        insertAlert(taskId, "gps.lost", "warning", "2032-04-02 10:00:00", "2032-04-02 11:00:00", "resolved");
        insertAlert(taskId, "signal.low", "warning", "2032-04-02 12:00:00", null, "pending");

        String body = fetchOverview(token, "2032-04-01 00:00:00", "2032-04-06 00:00:00");

        assertThat(intAt(body, "$.data.alerts.total")).isEqualTo(3);
        assertThat(intAt(body, "$.data.alerts.critical")).isEqualTo(1);
        assertThat(intAt(body, "$.data.alerts.handled")).as("MTTR 的样本：已处置的那两条").isEqualTo(2);
        assertThat(intAt(body, "$.data.alerts.mttrMinutes")).as("(30 + 60) ÷ 2").isEqualTo(45);
        assertThat(intAt(body, "$.data.alerts.openNow"))
                .as("未处置是快照、不看区间，至少含刚插的那条").isGreaterThanOrEqualTo(1);

        List<Map<String, Object>> trend = JsonPath.read(body, "$.data.alerts.trend");
        assertThat(trend).as("三个级别各一条线").hasSize(3);
        trend.forEach(series -> assertThat((List<?>) series.get("values"))
                .as("每条线的长度都要与横轴一致，没数据的日子补 0").hasSize(5));
        assertThat(trendValues(trend, "critical")).containsExactly(1L, 0L, 0L, 0L, 0L);
        assertThat(trendValues(trend, "warning")).containsExactly(0L, 2L, 0L, 0L, 0L);
        assertThat(trendValues(trend, "info")).containsExactly(0L, 0L, 0L, 0L, 0L);
        // 中文名由后端给（与 AlertSeverity.label 同一处定义），前端不重复维护一套翻译
        assertThat((Object) JsonPath.read(body, "$.data.alerts.trend[0].label")).isNotNull();
    }

    /**
     * 「按天」要落在**应用侧的日界**上，而不是 mysqld 的日界。
     *
     * <p>{@code create_time} 是 mysqld 用自己的时钟（这台是 UTC）填的，
     * 直接 {@code DATE()} 出来的是「mysqld 的那一天」。这里塞一条 20:00 的记录 ——
     * 它在东八区其实是**第二天凌晨 4 点**，所以必须落在第二天那一格。
     *
     * <p>⚠️ 期望值用的是与实现同一个公式（契约就是这条公式，没法独立推导出来），
     * 它的价值是钉住「平移确实被应用了」：谁把它删掉，这条就会红。
     */
    @Test
    void analyticsOverviewShouldBucketTaskTrendByLocalDay() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        long serverOffset = jdbcTemplate.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())", Long.class);
        long appOffset = ZoneId.systemDefault().getRules().getOffset(Instant.now()).getTotalSeconds();
        long shift = appOffset - serverOffset;

        int taskId = createTaskId(token, "概览用例-日界", "2031-01-05 09:00:00", "2031-01-05 11:00:00");
        // 直接写 create_time：它存的是「mysqld 时钟」的字面量，
        // 所以「应用侧的那一天」= 这个字面量 + shift
        jdbcTemplate.update("UPDATE `task` SET create_time = ? WHERE id = ?",
                LocalDateTime.of(2031, 1, 5, 20, 0), (long) taskId);

        String body = fetchOverview(token, "2031-01-05 00:00:00", "2031-01-07 00:00:00");
        List<String> days = JsonPath.read(body, "$.data.days");
        List<Number> created = JsonPath.read(body, "$.data.tasks.created");

        String expectedDay = LocalDateTime.of(2031, 1, 5, 20, 0).plusSeconds(shift).toLocalDate().toString();
        int index = days.indexOf(expectedDay);
        assertThat(index).as("平移后的那一天应当在横轴上：" + expectedDay).isGreaterThanOrEqualTo(0);
        assertThat(created.get(index).longValue()).as("那一格应当有 1 条").isEqualTo(1L);

        if (shift != 0) {
            assertThat(days.indexOf("2031-01-05")).as("不该落在未平移的那一格").isNotEqualTo(index);
        }
    }

    /** 概览与积压归因同一个权限点（task:list），飞手都看不到 */
    @Test
    void analyticsOverviewShouldBeForbiddenForPilot() throws Exception {
        String pilotToken = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/analytics/overview").header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value(403));
    }

    // ---------- 业务分析：飞手与设备效率（docs/业务分析方案.md 第二步） ----------

    /**
     * 飞手侧的工作量与质量 + 设备侧的利用率与故障率，一次验完。
     *
     * <p>两个榜单都列**全部**在册飞手与设备（别人的行也在），所以断言一律按 id/编号
     * 找自己那一行，不能按下标取。窗口取 2033-06 这个干净时段。
     */
    @Test
    void analyticsEfficiencyShouldReportWorkloadQualityAndUtilization() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String from = "2033-06-01 00:00:00";
        String to = "2033-06-11 00:00:00";

        int pilotId = addPilotAndGetId(token, "13900009711", "效率用例飞手");
        int deviceId = addDeviceAndGetId(token, "DRN-9711", "效率用例设备");

        // 三条任务：按时完成 / 超时完成 / 还在飞
        int onTime = createTaskId(token, "效率用例-按时", "2033-07-01 09:00:00", "2033-07-01 11:00:00");
        int late = createTaskId(token, "效率用例-超时", "2033-07-02 09:00:00", "2033-07-02 11:00:00");
        int flying = createTaskId(token, "效率用例-在飞", "2033-07-03 09:00:00", "2033-07-03 11:00:00");
        setTaskMetrics(onTime, pilotId, deviceId, "finished", "confirmed",
                "2033-06-01 09:00:00", "2033-06-01 10:00:00", "2033-06-01 11:00:00");
        setTaskMetrics(late, pilotId, deviceId, "finished", "declared",
                "2033-06-02 09:00:00", "2033-06-02 12:00:00", "2033-06-02 11:00:00");
        // 还在飞：它进「开过的活」（无旁证占比的分母），但**不进**「结束的活」（工作量）
        setTaskMetrics(flying, pilotId, deviceId, "executing", "stale",
                "2033-06-03 09:00:00", null, "2033-06-03 11:00:00");

        insertReport(onTime, 1.5, 2);
        insertDeviceAlert("DRN-9711", "critical", "2033-06-02 09:30:00");
        insertDeviceAlert("DRN-9711", "warning", "2033-06-03 09:30:00");

        String body = fetchEfficiency(token, from, to);
        List<Map<String, Object>> pilots = JsonPath.read(body, "$.data.pilots");
        Map<String, Object> pilot = rowWith(pilots, "pilotId", pilotId);
        assertThat(pilot).as("自己的飞手必须在榜上").isNotNull();

        assertThat(intOf(pilot, "taskCount")).as("结束的活 2 条（在飞的那条不算）").isEqualTo(2);
        assertThat(intOf(pilot, "finished")).isEqualTo(2);
        assertThat(doubleOf(pilot, "onTimeRate")).as("2 条里 1 条按时").isEqualTo(50.0);
        assertThat(intOf(pilot, "started")).as("开过的活 3 条 —— 含在飞的那条").isEqualTo(3);
        assertThat(intOf(pilot, "noProof")).as("declared + stale").isEqualTo(2);
        assertThat(doubleOf(pilot, "noProofRate")).as("2 ÷ 3").isEqualTo(66.7);
        assertThat(doubleOf(pilot, "hours")).as("报告里填的飞行时长").isEqualTo(1.5);
        assertThat(intOf(pilot, "sorties")).isEqualTo(2);
        assertThat(pilot.get("refuseRate")).as("一次没派过 → null，不是 0%").isNull();
        assertThat(pilots.get(0).get("pilotId")).as("干得多的排前面").isEqualTo(pilotId);
        assertThat(pilots.stream().anyMatch(row -> intOf(row, "taskCount") == 0))
                .as("这段时间没活的人也要在榜上（不然看不出谁闲）").isTrue();

        List<Map<String, Object>> devices = JsonPath.read(body, "$.data.devices");
        Map<String, Object> device = rowWith(devices, "code", "DRN-9711");
        assertThat(device).as("自己的设备必须在榜上").isNotNull();
        assertThat(intOf(device, "inServiceDays")).as("没写采购日期 → 按整段 10 天算").isEqualTo(10);
        assertThat(doubleOf(device, "flyableHours")).as("10 天 × 每天 8 小时").isEqualTo(80.0);
        assertThat(doubleOf(device, "utilization")).as("1.5 ÷ 80").isEqualTo(1.88);
        assertThat(intOf(device, "alerts")).isEqualTo(2);
        assertThat(intOf(device, "criticalAlerts")).isEqualTo(1);
        assertThat(doubleOf(device, "faultRatePer100h")).as("2 条 ÷ 1.5 小时 × 100").isEqualTo(133.33);
        assertThat(devices.stream().anyMatch(row -> intOf(row, "alerts") == 0
                        && Double.valueOf(0.0).equals(row.get("utilization"))))
                .as("一直没飞的那几台也在榜上，利用率是 0%").isTrue();
    }

    /**
     * 拒绝率：分母是「派给他的次数」，**必须把他拒掉的算回来**。
     *
     * <p>拒绝会走 releaseAssignment，把任务的 pilot_id 一并清空 ——
     * 所以只按任务当前的指派去数派发，会得出「拒得越多、分母越小、拒绝率越高」这种怪结论。
     * 这条用例正是钉住那个修法：一个全新飞手被派了 1 次、拒了 1 次，拒绝率必须是 100%。
     */
    @Test
    void analyticsEfficiencyShouldCountRefuseAgainstOffers() throws Exception {
        String adminToken = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        String pilot = "13900009712";
        int pilotId = addPilotAndGetId(adminToken, pilot, "拒绝率用例飞手");
        int droneId = addDeviceAndGetId(adminToken, "DRN-9712", "拒绝率用例设备");

        int taskId = createTaskId(adminToken, "效率用例-拒绝", "2033-08-01 09:00:00", "2033-08-01 11:00:00");
        dispatchAs(adminToken, taskId, pilotId, droneId);

        String pilotToken = loginAndGetToken(pilot, "123456");
        mockMvc.perform(post("/task/refuse").header(AUTH_HEADER, "Bearer " + pilotToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":%d,"reason":"拒绝率用例"}
                                """.formatted(taskId)))
                .andExpect(jsonPath("$.state").value(200));

        // 区间取「今天」：派发与拒绝的流水就是刚刚写的
        String from = LocalDate.now().atStartOfDay().format(API_TIME);
        String to = LocalDate.now().plusDays(1).atStartOfDay().format(API_TIME);
        String body = fetchEfficiency(adminToken, from, to);

        Map<String, Object> row = rowWith(JsonPath.read(body, "$.data.pilots"), "pilotId", pilotId);
        assertThat(row).as("飞手必须在榜上").isNotNull();
        assertThat(intOf(row, "refused")).isEqualTo(1);
        assertThat(intOf(row, "offers")).as("拒掉的那次也算「派给他过」").isEqualTo(1);
        assertThat(doubleOf(row, "refuseRate")).isEqualTo(100.0);
    }

    /** 两个榜单同样是管理侧的口径（task:list），飞手看不到 */
    @Test
    void analyticsEfficiencyShouldBeForbiddenForPilot() throws Exception {
        String pilotToken = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/analytics/efficiency").header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value(403));
    }

    /** 调一次效率榜接口 */
    private String fetchEfficiency(String token, String from, String to) throws Exception {
        return mockMvc.perform(get("/analytics/efficiency")
                        .param("from", from)
                        .param("to", to)
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 按某个键找榜单里自己那一行（榜单里还有别人的行，不能按下标取） */
    private Map<String, Object> rowWith(List<Map<String, Object>> rows, String key, Object value) {
        return rows.stream()
                .filter(row -> String.valueOf(row.get(key)).equals(String.valueOf(value)))
                .findFirst().orElse(null);
    }

    private int intOf(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private double doubleOf(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    /** 直接摆一条任务的分派关系、执行依据与起止时间 */
    private void setTaskMetrics(int taskId, int pilotId, int droneId, String status, String evidenceState,
                                String actualStart, String actualEnd, String planEnd) {
        jdbcTemplate.update("""
                        UPDATE `task`
                        SET pilot_id = ?, drone_id = ?, status = ?, evidence_state = ?,
                            actual_start_time = ?, actual_end_time = ?, plan_end_time = ?
                        WHERE id = ?
                        """,
                (long) pilotId, (long) droneId, status, evidenceState,
                actualStart == null ? null : LocalDateTime.parse(actualStart, API_TIME),
                actualEnd == null ? null : LocalDateTime.parse(actualEnd, API_TIME),
                LocalDateTime.parse(planEnd, API_TIME), (long) taskId);
    }

    /** 插一条任务报告：飞行时长与架次是工作量、利用率、故障率三个数的来源 */
    private void insertReport(int taskId, double hours, int sorties) {
        jdbcTemplate.update("""
                        INSERT INTO task_report (task_id, version, is_latest, result, flight_hours, flight_count)
                        VALUES (?, 1, 1, 'success', ?, ?)
                        """, (long) taskId, hours, sorties);
    }

    /** 插一条带设备编号的告警（设备故障率是按编号关联的） */
    private void insertDeviceAlert(String deviceCode, String severity, String observedAt) {
        jdbcTemplate.update("""
                        INSERT INTO task_alert (device_code, alert_code, severity, message, source,
                                                observed_at, status)
                        VALUES (?, 'bat.critical', ?, '效率用例告警', 'device', ?, 'pending')
                        """, deviceCode, severity, LocalDateTime.parse(observedAt, API_TIME));
    }

    // ---------- 业务分析：预聚合日表（docs/业务分析方案.md 第三步 / 4.3 ③） ----------

    /**
     * 重算某一天：按「天 × 设备」写粒度行 + 写一行日级的「活跃设备数」，
     * 而且**重算多少次结果都一样**（补算任务敢每半小时跑一次就靠这个）。
     */
    @Test
    void statDailyShouldRebuildDayIdempotently() throws Exception {
        LocalDate day = LocalDate.of(2035, 1, 10);         // 一个没人的历史日子
        String date = day + " 09:00:00";

        insertEvidence("SIM-9101", date, 1);
        insertEvidence("SIM-9101", day + " 09:00:01", 2);
        insertEvidence("SIM-9101", day + " 09:00:02", 3);
        insertEvidence("SIM-9102", day + " 09:05:00", 1);

        int rows = statDailyService.rebuildDay(day);
        assertThat(rows).as("2 台设备的粒度行 + 1 行活跃设备数").isEqualTo(3);

        assertThat(evidenceCountOf("SIM-9101", day)).isEqualTo(3L);
        assertThat(evidenceCountOf("SIM-9102", day)).isEqualTo(1L);
        assertThat(activeDevicesOf(day)).isEqualTo(2L);
        assertThat(statDailyRowsOf(day)).isEqualTo(3);

        // 重算一遍：行数不变、值不变（唯一键 + upsert + 先删后插）
        statDailyService.rebuildDay(day);
        assertThat(statDailyRowsOf(day)).as("重算不会插重").isEqualTo(3);
        assertThat(evidenceCountOf("SIM-9101", day)).isEqualTo(3L);
        assertThat(activeDevicesOf(day)).isEqualTo(2L);

        // 删掉一台设备那天的证据再重算：它的行必须消失（先删后插要真的起作用）
        jdbcTemplate.update("DELETE FROM task_evidence WHERE device_code = ? AND observed_at >= ? AND observed_at < ?",
                "SIM-9102", day.atStartOfDay(), day.plusDays(1).atStartOfDay());
        statDailyService.rebuildDay(day);
        assertThat(evidenceCountOf("SIM-9102", day)).as("不再有数据的设备不该留幽灵行").isNull();
        assertThat(activeDevicesOf(day)).isEqualTo(1L);
    }

    /**
     * 补算只补「缺的天」，而且**空数据的那天也算算过了** ——
     * 否则读的时候会把「那天真的没数据」当成「那天没算」，每次都重新实时算一遍。
     *
     * <p>⚠️ 这一条**不能假设 stat_daily 是空的**：这个功能真跑起来之后，
     * 开发库里已经有补算任务写好的数据了（最多 30 天）。所以用例先把窗口里那几天的行
     * 删掉，摆出「缺」的状态再验 —— 删的是派生数据，而且在本类的 @Transactional 里，跑完就回滚。
     */
    @Test
    void statDailyShouldBackfillOnlyMissingDays() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate withEvidence = today.minusDays(2);
        LocalDate emptyDayInWindow = today.minusDays(4);

        insertEvidence("SIM-9103", withEvidence + " 08:30:00", 1);
        // 把窗口里那几天的派生行清掉，摆出「都还没算」的状态（本类事务回滚，不会影响开发库）
        // ⚠️ 必须走 DAO 删，不能用 JdbcTemplate：**MyBatis 的一级缓存是会话级的**，
        //    同一条事务里第二次调「哪些天算过」会拿到上一次的结果（JdbcTemplate 绕过 MyBatis、
        //    缓存不知道数据变了）。走 DAO 的 delete 会顺带把会话缓存清掉。
        for (int i = 5; i >= 1; i--) {
            statDailyDao.deleteByDay(today.minusDays(i), 1);
        }
        // 回看 5 天：全缺 → 补 5 天（值取自刚插进去的那条证据，所以下面能精确断言）
        int rebuilt = statDailyService.ensureUpToDate(today, 5);
        assertThat(rebuilt).as("回看 5 天（今天之前）应当全补上").isEqualTo(5);
        assertThat(evidenceCountOf("SIM-9103", withEvidence)).isEqualTo(1L);

        // 再补一次：一天都不缺了
        assertThat(statDailyService.ensureUpToDate(today, 5)).as("第二次没有可补的天").isZero();

        // 中间挖掉一天，再补：只补那一天（这才是「只补缺的天」的真正含义）
        statDailyDao.deleteByDay(emptyDayInWindow, 1);
        assertThat(statDailyService.ensureUpToDate(today, 5)).as("只补挖掉的那一天").isEqualTo(1);

        // 补过的每一天都要留下标记行（这一天算过了）——
        // 注意别假设「哪一天没有证据」：开发库里本来就可能有别人造的数据
        int markers = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM stat_daily
                WHERE stat_date BETWEEN ? AND ? AND metric = 'active_devices' AND dimension = ''
                """, Integer.class, today.minusDays(5), today.minusDays(1));
        assertThat(markers).as("补过的 5 天，每天都要有一行标记").isEqualTo(5);

        // 完全没有证据的那一天：标记行的值是 0，而不是「干脆没有这一行」——
        // 否则读的时候分不清「没算」与「算了、那天确实是空的」，会每次都实时重算
        LocalDate emptyDay = LocalDate.of(2035, 2, 1);
        statDailyService.rebuildDay(emptyDay);
        assertThat(activeDevicesOf(emptyDay)).as("空数据的那天也要留一行（值 0）").isZero();
        assertThat(statDailyRowsOf(emptyDay)).as("那天只有标记行，没有粒度行").isEqualTo(1);
    }

    /**
     * 趋势：历史那几天读预聚合，没算的那几天（今天及以后）现场补 ——
     * 报文里还要说清各有几天，界面据此提示数据新不新。
     */
    @Test
    void evidenceTrendShouldReadPrecomputedThenFallBackToLive() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        LocalDate today = LocalDate.now();
        // 躲开两件事：开发库里已有的那几天证据，以及补算任务回看的那 30 天
        LocalDate precomputedDay = today.minusDays(40);

        insertEvidence("SIM-9104", precomputedDay + " 10:00:00", 1);
        insertEvidence("SIM-9104", precomputedDay + " 10:00:01", 2);
        insertEvidence("SIM-9105", precomputedDay + " 10:10:00", 1);
        statDailyService.rebuildDay(precomputedDay);

        // 今天再落一条：它没有预聚合数据，应当由实时补算接上
        insertEvidence("SIM-9104", today + " 09:00:00", 3);

        String body = fetchEvidenceTrend(token,
                precomputedDay.atStartOfDay().format(API_TIME),
                today.plusDays(1).atStartOfDay().format(API_TIME));

        List<String> days = JsonPath.read(body, "$.data.days");
        List<Number> counts = JsonPath.read(body, "$.data.evidenceCounts");
        List<Number> devices = JsonPath.read(body, "$.data.activeDevices");

        assertThat(days).hasSize(41);
        assertThat(days.get(0)).isEqualTo(precomputedDay.toString());
        assertThat(days.get(40)).isEqualTo(today.toString());

        // ⚠️ 不断言「有几天来自预聚合」的绝对值：开发库里已经有补算任务写的真数据了。
        // 只断言两条**恒成立**的性质：① 两段加起来就是横轴长度（不会漏天）
        // ② 「今天」一定是实时补的（补算任务只算到昨天，这是它的设计）
        int precomputedDays = intAt(body, "$.data.precomputedDays");
        int liveDays = intAt(body, "$.data.liveDays");
        assertThat(precomputedDays + liveDays).as("预聚合 + 实时 = 横轴天数").isEqualTo(41);
        assertThat(precomputedDays).as("我 rebuild 过的那天也算进预聚合").isGreaterThanOrEqualTo(1);
        assertThat(liveDays).as("今天一定是实时补的").isGreaterThanOrEqualTo(1);

        assertThat(counts.get(0).longValue()).as("我 rebuild 的那天：证据条数").isEqualTo(3L);
        assertThat(devices.get(0).longValue()).as("那天 2 台设备").isEqualTo(2L);
        assertThat(counts.get(1).longValue()).as("中间没有数据的那天是 0，不是缺项").isZero();
        assertThat(counts.get(40).longValue()).as("今天那条是实时补出来的").isGreaterThanOrEqualTo(1L);
    }

    /** 趋势接口同样是管理侧的口径（task:list） */
    @Test
    void analyticsEvidenceShouldBeForbiddenForPilot() throws Exception {
        String pilotToken = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/analytics/evidence").header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value(403));
    }

    /** 调一次证据趋势接口 */
    private String fetchEvidenceTrend(String token, String from, String to) throws Exception {
        return mockMvc.perform(get("/analytics/evidence")
                        .param("from", from)
                        .param("to", to)
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 插一条证据（只给 NOT NULL 的几列；seq 各给各的，避开去重唯一键） */
    private void insertEvidence(String deviceCode, String observedAt, int seq) {
        jdbcTemplate.update("""
                        INSERT INTO task_evidence (device_code, type, source, payload, seq, observed_at, valid)
                        VALUES (?, 'telemetry', 'device', '{}', ?, ?, 1)
                        """, deviceCode, (long) seq, LocalDateTime.parse(observedAt, API_TIME));
    }

    /** 预聚合里某天某台设备的证据条数；**没有这一行时返回 null**（「没算」与「算出来是 0」要分得清） */
    private Long evidenceCountOf(String deviceCode, LocalDate day) {
        List<Long> values = jdbcTemplate.queryForList("""
                SELECT value FROM stat_daily
                WHERE stat_date = ? AND metric = 'evidence_count' AND dimension = ?
                """, Long.class, day, deviceCode);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 预聚合里某天的活跃设备数 */
    private Long activeDevicesOf(LocalDate day) {
        return jdbcTemplate.queryForObject("""
                SELECT value FROM stat_daily
                WHERE stat_date = ? AND metric = 'active_devices' AND dimension = ''
                """, Long.class, day);
    }

    private int statDailyRowsOf(LocalDate day) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stat_daily WHERE stat_date = ?",
                Integer.class, day);
    }

    // ---------- 第四步：下钻的前提（列表页时间区间）与覆盖率 / 导出 ----------

    /**
     * 下钻的硬前提：任务列表要能按**实际结束时间**筛。
     *
     * <p>为什么要按这一列：分析页的完成率/按时率/平均时长都是按它落窗的，
     * 列表按别的列筛，下钻过去的条数就会和那个数对不上 ——
     * 而「对得上」是下钻唯一的验收标准（见文档三点五）。
     */
    @Test
    void taskListShouldFilterByActualEndTime() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int taskId = createTaskId(token, "下钻用例-按结束时间筛", "2036-01-01 09:00:00", "2036-01-01 11:00:00");
        setTaskOutcome(taskId, "finished", "2036-01-01 09:00:00", "2036-01-01 10:00:00", "2036-01-01 11:00:00");

        String in = mockMvc.perform(get("/task/list")
                        .param("status", "finished")
                        .param("endFrom", "2036-01-01 00:00:00")
                        .param("endTo", "2036-01-02 00:00:00")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<Integer>>read(in, "$.data.list[*].id"))
                .as("窗口内的那条要能查到").contains(taskId);

        String out = mockMvc.perform(get("/task/list")
                        .param("status", "finished")
                        .param("endFrom", "2037-01-01 00:00:00")
                        .param("endTo", "2037-01-02 00:00:00")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<Integer>>read(out, "$.data.list[*].id"))
                .as("窗口外就不该出现").doesNotContain(taskId);
    }

    /** 同理：告警中心要能按**发生时间**筛（MTTR 与告警趋势用的就是这一列） */
    @Test
    void alertListShouldFilterByObservedTime() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);
        int taskId = createTaskId(token, "下钻用例-告警时间窗", "2036-02-01 09:00:00", "2036-02-01 11:00:00");
        insertAlert(taskId, "bat.critical", "critical", "2036-02-01 09:30:00", null, "pending");

        mockMvc.perform(get("/alert/list")
                        .param("observedFrom", "2036-02-01 00:00:00")
                        .param("observedTo", "2036-02-02 00:00:00")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(get("/alert/list")
                        .param("observedFrom", "2037-02-01 00:00:00")
                        .param("observedTo", "2037-02-02 00:00:00")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    /**
     * 覆盖率：计划点来自**任务快照**，到过按**打卡偏差 ≤ 半径**判断。
     *
     * <p>用一条真实链路造出来的任务（有航线、已起飞），再把它摆进我自己的时间窗，
     * 然后给两个航点各插一条打卡：一个在半径内、一个远得离谱。
     */
    @Test
    void coverageShouldJudgeVisitedByCheckin() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        // 建一个真点位，再让航线的第一个航点关联它 —— 这样才验到「点位名从点位库查出来」这条
        String pointName = "覆盖率用例点位";
        int pointId = addPointAndGetId(token, pointName, "SIM-COV-001", 116.41024449, 39.91640428);
        String pointsJson = "[{\"pointId\":" + pointId + ",\"lng\":116.41024449,\"lat\":39.91640428,"
                + "\"action\":\"none\"},"
                + "{\"lng\":116.41124449,\"lat\":39.91740428,\"action\":\"none\"}]";
        int routeId = addRoute(token, "覆盖率用例航线", pointsJson);
        int taskId = startedTaskWithRoute(token, "13900009720", "DRN-9720", "覆盖率用例任务", routeId);
        setTaskOutcome(taskId, "finished", "2036-03-01 09:00:00", "2036-03-01 10:00:00", "2036-03-01 11:00:00");

        insertCheckin(taskId, 1, 10);      // 偏差 10 米 → 算到过
        insertCheckin(taskId, 2, 5000);    // 偏差 5 公里 → 不算

        String body = fetchCoverage(token, "2036-03-01 00:00:00", "2036-03-02 00:00:00");
        assertThat(intAt(body, "$.data.plannedPoints")).as("这条航线 2 个点").isEqualTo(2);
        assertThat(intAt(body, "$.data.visitedPoints")).as("只有第一个点在半径内").isEqualTo(1);
        assertThat(doubleAt(body, "$.data.coverageRate")).isEqualTo(50.0);
        assertThat(intAt(body, "$.data.tasksWithoutRoute")).as("这条任务配了航线").isZero();
        assertThat((Object) JsonPath.read(body, "$.data.coordinateSystem")).isEqualTo("gcj02");
        assertThat(intAt(body, "$.data.radiusMeters")).isEqualTo(100);
        assertThat(intAt(body, "$.data.taskCount")).isEqualTo(1);

        List<Map<String, Object>> points = JsonPath.read(body, "$.data.points");
        assertThat(points).hasSize(2);
        Map<String, Object> visited = points.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("covered"))).findFirst().orElse(null);
        assertThat(visited).as("应当有一个点到过").isNotNull();
        assertThat(((Number) visited.get("visitedTimes")).intValue()).isEqualTo(1);
        assertThat(String.valueOf(visited.get("name"))).as("点位名要从点位库查出来").isEqualTo(pointName);
        assertThat(((Number) visited.get("pointId")).longValue()).as("关联的点位 id 要带出来").isEqualTo(pointId);
        // 没关联点位库的那个航点：如实显示成「航点 坐标」，而不是假装它是某个点位
        Map<String, Object> plain = points.stream()
                .filter(row -> !Boolean.TRUE.equals(row.get("covered"))).findFirst().orElseThrow();
        assertThat(String.valueOf(plain.get("name"))).startsWith("（航点 ");
        // 没到过的排前面：这一屏是给「这个月还差哪些点」用的
        assertThat(points.get(0).get("covered")).isEqualTo(false);
    }

    /** 导出：拿到的必须是一个真的 xlsx（zip 的魔数 PK，且不是空文件） */
    @Test
    void coverageExportShouldReturnRealXlsx() throws Exception {
        String token = loginAndGetToken(ADMIN_USERNAME, EXISTING_PASSWORD);

        byte[] bytes = mockMvc.perform(get("/analytics/coverage/export")
                        .param("from", "2026-09-01 00:00:00")
                        .param("to", "2026-10-01 00:00:00")
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(bytes).as("xlsx 不该是空的").hasSizeGreaterThan(500);
        assertThat((char) bytes[0]).as("zip 魔数第一位").isEqualTo('P');
        assertThat((char) bytes[1]).as("zip 魔数第二位").isEqualTo('K');
    }

    /** 覆盖率与导出都是管理侧的口径（task:list） */
    @Test
    void analyticsCoverageShouldBeForbiddenForPilot() throws Exception {
        String pilotToken = loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);

        mockMvc.perform(get("/analytics/coverage").header(AUTH_HEADER, "Bearer " + pilotToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value(403));
    }

    /** 调一次覆盖率接口 */
    private String fetchCoverage(String token, String from, String to) throws Exception {
        return mockMvc.perform(get("/analytics/coverage")
                        .param("from", from)
                        .param("to", to)
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 插一条打卡：只需要几列 NOT NULL，偏差由参数给（覆盖率就是按它判「到过」的） */
    private void insertCheckin(int taskId, int seq, int deviationM) {
        jdbcTemplate.update("""
                        INSERT INTO task_checkin (task_id, seq, target_lng, target_lat, lng, lat,
                                                  deviation_m, observed_at)
                        VALUES (?, ?, 116.41, 39.91, 116.41, 39.91, ?, ?)
                        """,
                (long) taskId, seq, deviationM, LocalDateTime.parse("2036-03-01 09:30:00", API_TIME));
    }

    /** 新建一个点位并返回它的 id（覆盖率的点位名要靠它验） */
    private int addPointAndGetId(String token, String name, String code, double lng, double lat) throws Exception {
        String body = mockMvc.perform(post("/point/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pointBody(name, code, lng, lat)))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.id");
    }

    /** 调一次概览接口 */
    private String fetchOverview(String token, String from, String to) throws Exception {
        return mockMvc.perform(get("/analytics/overview")
                        .param("from", from)
                        .param("to", to)
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private int intAt(String body, String path) {
        return ((Number) JsonPath.read(body, path)).intValue();
    }

    private double doubleAt(String body, String path) {
        return ((Number) JsonPath.read(body, path)).doubleValue();
    }

    /** 从趋势里挑出某个级别那条线，转成 List<Long>（JsonPath 给的是 Integer） */
    private List<Long> trendValues(List<Map<String, Object>> trend, String severity) {
        return trend.stream()
                .filter(series -> severity.equals(series.get("severity")))
                .flatMap(series -> ((List<Number>) series.get("values")).stream())
                .map(Number::longValue)
                .toList();
    }

    /** 直接把一条任务摆成终态（见 analyticsOverviewShouldReportTaskOutcome 的说明） */
    private void setTaskOutcome(int taskId, String status, String actualStart, String actualEnd, String planEnd) {
        jdbcTemplate.update("""
                        UPDATE `task`
                        SET status = ?, actual_start_time = ?, actual_end_time = ?, plan_end_time = ?
                        WHERE id = ?
                        """,
                status,
                actualStart == null ? null : LocalDateTime.parse(actualStart, API_TIME),
                LocalDateTime.parse(actualEnd, API_TIME),
                LocalDateTime.parse(planEnd, API_TIME),
                (long) taskId);
    }

    /** 插一条告警：只要 NOT NULL 的那几列，时间全由 Java 给，好造出确定的 MTTR */
    private void insertAlert(int taskId, String alertCode, String severity, String observedAt,
                             String handledAt, String status) {
        jdbcTemplate.update("""
                        INSERT INTO task_alert (task_id, alert_code, severity, message, source,
                                                observed_at, status, handled_at)
                        VALUES (?, ?, ?, ?, 'device', ?, ?, ?)
                        """,
                (long) taskId, alertCode, severity, "概览用例：" + alertCode,
                LocalDateTime.parse(observedAt, API_TIME), status,
                handledAt == null ? null : LocalDateTime.parse(handledAt, API_TIME));
    }

    /**
     * 造一条「在某个历史时刻被派发、等了 waitMinutes 分钟」的任务。
     *
     * <p>为什么要挪时间：派发流水的时间只能是「现在」，而接口一按区间过滤，
     * 记录就跟着墙上时间走 —— 想精确断言中位数，只能让自己的两条记录
     * **独占一个没有别人的历史窗口**（改动随事务回滚，不会留在开发库）。
     */
    private void dispatchAndBackdate(String token, int pilotId, int droneId, String taskName,
                                     LocalDateTime dispatchedAt, int waitMinutes) throws Exception {
        String day = dispatchedAt.toLocalDate().toString();
        int taskId = createTaskId(token, taskName, day + " 09:00:00", day + " 11:00:00");
        dispatchAs(token, taskId, pilotId, droneId);

        jdbcTemplate.update("UPDATE `task` SET create_time = ? WHERE id = ?",
                dispatchedAt.minusMinutes(waitMinutes), (long) taskId);
        jdbcTemplate.update("UPDATE `task_log` SET create_time = ? WHERE task_id = ? AND action = 'dispatch'",
                dispatchedAt, (long) taskId);
    }

    /** 调一次积压归因接口；留成原始报文是为了用 JsonPath 直接取嵌套字段 */
    private String fetchDispatchBlockStats(String token, String from, String to) throws Exception {
        return mockMvc.perform(get("/analytics/dispatch-block")
                        .param("from", from)
                        .param("to", to)
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long blockPoints(String body) {
        return ((Number) JsonPath.read(body, "$.data.blockPoints")).longValue();
    }

    private long pendingTotal(String body) {
        return ((Number) JsonPath.read(body, "$.data.pendingTotal")).longValue();
    }

    /** 区间相关的三个数字（expectedHours / sampledHours / medianMinutes） */
    private long hours(String body, String field) {
        return ((Number) JsonPath.read(body, "$.data." + field)).longValue();
    }

    private int samples(String body) {
        return ((Number) JsonPath.read(body, "$.data.dispatchedSamples")).intValue();
    }

    /** 某个归因的条数；后端没给这个归因时返回 -1（能区分「没给」和「给了 0」） */
    private long reasonCount(String body, String reason) {
        List<Number> counts = JsonPath.read(body,
                "$.data.reasonShare[?(@.reason == '" + reason + "')].count");
        return counts.isEmpty() ? -1L : counts.get(0).longValue();
    }

    private long bucketCount(String body, String key) {
        List<Number> counts = JsonPath.read(body,
                "$.data.backlogAge[?(@.key == '" + key + "')].count");
        return counts.isEmpty() ? -1L : counts.get(0).longValue();
    }

    /** 直接插一个归因点（采样任务在测试里被关掉了，只能自己造） */
    private void insertBlockPoint(int taskId, LocalDateTime checkedAt, String reason,
                                  int pilotCandidates, int droneCandidates) {
        jdbcTemplate.update("""
                        INSERT INTO `task_dispatch_block` (task_id, checked_at, reason,
                                                         pilot_candidates, drone_candidates)
                        VALUES (?, ?, ?, ?, ?)
                        """, (long) taskId, checkedAt, reason, pilotCandidates, droneCandidates);
    }

    /**
     * 把任务的创建时间往前挪 hours 小时，摆出「已经等了很久」的积压。
     *
     * <p>⚠️ 用 {@code NOW() - INTERVAL ? HOUR} 而不是 Java 传一个时刻：
     * {@code create_time} 正常是 mysqld 用自己的时钟填的，而分桶的 SQL 也拿 {@code NOW()} 比。
     * 用例若拿 Java 的「三小时前」去写，等于往这条列里掺了另一个时钟，
     * 测出来的就不是产品实际的行为（第一次就是这么写的，桶全错）。
     */
    private void backdateTaskCreateTime(int taskId, int hours) {
        jdbcTemplate.update("UPDATE `task` SET create_time = NOW() - INTERVAL ? HOUR WHERE id = ?",
                hours, (long) taskId);
    }

    /** 把派发下拉里的飞手全占住：每个人插一条时段重叠的「已派发」任务 */
    private void occupyPoolPilots(String planStart, String planEnd) {
        for (TaskDispatchOptionVO pilot : taskDao.selectDispatchablePilots()) {
            insertOccupyingTask(true, pilot.getId(), planStart, planEnd);
        }
    }

    /** 把待命设备全占住：每台插一条时段重叠的「已派发」任务 */
    private void occupyPoolDrones(String planStart, String planEnd) {
        for (TaskDispatchOptionVO drone : taskDao.selectDispatchableDrones()) {
            insertOccupyingTask(false, drone.getId(), planStart, planEnd);
        }
    }

    /**
     * 直接插一条占位任务，把某个飞手/设备在时间窗内占住。
     *
     * <p>为什么不走 {@code /task/dispatch}：那个接口一次只能派一个人一台设备，
     * 而这里要的是「池子里一个不剩」这个整体状态；而且它会顺带改飞手和设备的在线/工作状态，
     * 那是对现有数据的副作用。直接插只碰 task 一张表，且随本类事务回滚。
     *
     * <p>只填 NOT NULL 的几列（其余走默认值）——判定只读
     * {@code status / pilot_id / drone_id / plan_start_time / plan_end_time}，别的一概不看。
     */
    private void insertOccupyingTask(boolean forPilot, Long ownerId, String planStart, String planEnd) {
        jdbcTemplate.update("""
                        INSERT INTO `task` (task_no, task_name, status, pilot_id, drone_id,
                                            plan_start_time, plan_end_time)
                        VALUES (?, ?, 'dispatched', ?, ?, ?, ?)
                        """,
                // task_no 有唯一索引：拼上 nanoTime 防止同一个 owner 在不同用例里撞号
                "TDB-" + (forPilot ? "p" : "d") + ownerId + "-" + (System.nanoTime() % 1_000_000_000L),
                "归因用例占位-" + ownerId,
                forPilot ? ownerId : null,
                forPilot ? null : ownerId,
                LocalDateTime.parse(planStart, API_TIME),
                LocalDateTime.parse(planEnd, API_TIME));
    }

    /** 某条任务已经落了几条归因点 */
    private int countBlockRows(int taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_dispatch_block WHERE task_id = ?", Integer.class, (long) taskId);
        return count == null ? 0 : count;
    }

    /** 构造一个合法的任务创建请求体；priority 固定 P1，时间固定在当天下午 */
    private String taskCreateBody(String taskName) {
        return """
                {"taskName":"%s","priority":"P1",
                 "planStartTime":"2026-09-18 09:00:00","planEndTime":"2026-09-18 11:00:00",
                 "routeInfo":"A-01 → B-02 → C-03","description":"自动化测试创建"}
                """.formatted(taskName);
    }

    /** 建一个任务并返回它的 id；时间窗可定制，供派发与冲突用例使用 */
    private int createTaskId(String token, String taskName, String planStart, String planEnd) throws Exception {
        String body = mockMvc.perform(post("/task/create")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskName":"%s","priority":"P1",
                                 "planStartTime":"%s","planEndTime":"%s"}
                                """.formatted(taskName, planStart, planEnd)))
                .andExpect(jsonPath("$.state").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return JsonPath.read(body, "$.data.id");
    }

    /** 派发请求体 */
    private String dispatchBody(int taskId, int pilotId, int droneId) {
        return """
                {"taskId":%d,"pilotId":%d,"droneId":%d}
                """.formatted(taskId, pilotId, droneId);
    }

    /** 派发并断言成功。只是「前提步骤」的用例用它，省掉层层 .andExpect 的噪音 */
    private void dispatchAs(String token, int taskId, int pilotId, int droneId) throws Exception {
        mockMvc.perform(post("/task/dispatch").header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchBody(taskId, pilotId, droneId)))
                .andExpect(jsonPath("$.state").value(200));
    }

    /**
     * 断言设备的工作状态。
     *
     * <p>走接口而不是直接查 DAO：这样连「VO 有没有把这个字段返给前端」一起验了 ——
     * 状态改对了但接口不带出去，页面上照样看不到变化。
     */
    private void assertDroneStatus(String token, int droneId, String expected) throws Exception {
        mockMvc.perform(get("/device/" + droneId).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.status").value(expected));
    }

    /** 断言用户的在线状态（空闲 / 作业中 / 已停飞） */
    private void assertUserState(String token, int userId, String expected) throws Exception {
        mockMvc.perform(get("/user/" + userId).header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.state").value(expected));
    }

    /**
     * 新建一个飞手账号并返回它的 id。
     *
     * <p>用它而不是去用库里「碰巧存在的另一个飞手」：依赖种子数据里多余的账号，
     * 等哪天有人清理数据，测试就会莫名其妙地挂掉。自己造的数据跑完自动回滚，最省心。
     */
    private int addPilotAndGetId(String token, String username, String name) throws Exception {
        mockMvc.perform(post("/user/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","name":"%s","password":"123456","roleCode":"pilot"}
                                """.formatted(username, name)))
                .andExpect(jsonPath("$.state").value(200));

        return findUserId(token, username);
    }

    /** 新建一台设备并返回它的 id，新建出来的设备是「待命」状态 */
    private int addDeviceAndGetId(String token, String code, String name) throws Exception {
        mockMvc.perform(post("/device/add")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"%s","type":"multi-rotor"}
                                """.formatted(code, name)))
                .andExpect(jsonPath("$.state").value(200));

        String body = mockMvc.perform(get("/device/list").param("keyword", code)
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Integer id = JsonPath.read(body, "$.data.list[0].id");
        return id;
    }

    /** 按登录账号找用户 ID */
    private int findUserId(String token, String username) throws Exception {
        String body = mockMvc.perform(get("/user/list").param("keyword", username)
                        .header(AUTH_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Integer id = JsonPath.read(body, "$.data.list[0].id");
        return id;
    }

    /** 启用 / 禁用账号 */
    private void toggleUser(String token, int userId, String status) throws Exception {
        mockMvc.perform(post("/user/toggle")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":%d,"status":"%s"}
                                """.formatted(userId, status)))
                .andExpect(jsonPath("$.state").value(200));
    }

    /** 改设备的工作状态，用于构造「维护中」这类场景 */
    private void updateDeviceStatus(String token, int deviceId, String status) throws Exception {
        mockMvc.perform(post("/device/update")
                        .header(AUTH_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":%d,"status":"%s"}
                                """.formatted(deviceId, status)))
                .andExpect(jsonPath("$.state").value(200));
    }

    /** 构造一个含 2 行数据的员工导入文件：一行合法、一行角色编码不存在 */
    private byte[] buildImportExcel() {
        UserImportDTO valid = new UserImportDTO();
        valid.setUsername("13900000001");
        valid.setName("测试员工");
        valid.setPassword("123456");
        valid.setRoleCode("pilot");

        UserImportDTO invalid = new UserImportDTO();
        invalid.setUsername("13900000002");
        invalid.setName("角色错误");
        invalid.setPassword("123456");
        invalid.setRoleCode("not-exist-role");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out, UserImportDTO.class).sheet("员工").doWrite(List.of(valid, invalid));
        return out.toByteArray();
    }

    private String loginBody(String username, String password) {
        return """
                {"username":"%s","password":"%s"}
                """.formatted(username, password);
    }

    private String loginAndGetToken() throws Exception {
        return loginAndGetToken(EXISTING_USERNAME, EXISTING_PASSWORD);
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$.data.token");
    }
}
