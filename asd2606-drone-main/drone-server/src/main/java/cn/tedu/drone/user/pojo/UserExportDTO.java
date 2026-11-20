package cn.tedu.drone.user.pojo;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 员工导出的 Excel 列映射。
 *
 * <p>表头顺序由字段声明顺序决定。刻意<b>不含密码</b> —— 导出的文件会被四处转发、
 * 存在本地磁盘，把密码写进去等于批量泄露。
 */
@Data
@Accessors(chain = true)
public class UserExportDTO {

    @ExcelProperty("登录账号")
    private String username;

    @ExcelProperty("姓名")
    private String name;

    @ExcelProperty("手机号")
    private String phone;

    @ExcelProperty("邮箱")
    private String email;

    @ExcelProperty("年龄")
    private Integer age;

    @ExcelProperty("性别")
    private String gender;

    @ExcelProperty("角色")
    private String roleNames;

    @ExcelProperty("账号状态")
    private String statusText;

    @ExcelProperty("创建时间")
    private String createTime;
}
