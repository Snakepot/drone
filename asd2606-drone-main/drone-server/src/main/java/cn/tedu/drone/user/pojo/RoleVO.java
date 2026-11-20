package cn.tedu.drone.user.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 角色视图对象，返回给前端。
 *
 * <h2>它和 {@link RolePO} 的区别</h2>
 * RolePO 是「role 表长什么样」，跟着表结构走；
 * RoleVO 是「接口承诺给前端什么」，跟着页面需求走。
 * 之前 {@code /system/role/list}、{@code /user/role-options} 直接把 RolePO 吐出去，
 * 结果是表里加一列（比如以后加个 {@code remark} 内部备注），接口就跟着一起暴露了 ——
 * 改表结构不该牵动接口契约。
 *
 * <p>字段与前端声明的 {@code RoleVO}（见 drone-web/src/api/rbac.ts）保持一致，
 * 所以这次改动对前端是完全无感的。
 */
@Data
@Accessors(chain = true)
@Schema(description = "角色视图对象")
public class RoleVO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "角色编码，代码里引用它，如 admin")
    private String code;

    @Schema(description = "角色名称，如 管理员")
    private String name;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "状态：1 启用，0 禁用")
    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
