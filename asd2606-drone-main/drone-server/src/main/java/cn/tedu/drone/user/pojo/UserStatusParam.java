package cn.tedu.drone.user.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 员工启用 / 禁用参数。
 *
 * <p>传的是「目标状态」而不是「翻转」：调用方明确说要启用还是禁用，
 * 服务端不猜。这样即使用户连点两次，结果也是确定的（都是禁用），
 * 不会出现「点两下又变回启用」这种诡异情况。
 */
@Data
@Accessors(chain = true)
@Schema(description = "员工启停用参数")
public class UserStatusParam implements Serializable {

    @Schema(description = "用户 ID")
    private Long id;

    @Schema(description = "目标状态：1 启用，0 禁用")
    private String status;
}
