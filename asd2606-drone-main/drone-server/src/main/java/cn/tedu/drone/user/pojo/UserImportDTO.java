package cn.tedu.drone.user.pojo;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 员工导入的 Excel 行映射。
 *
 * <p>{@code @ExcelProperty} 的值就是表头文字，**必须与导入模版严格一致** ——
 * 用户如果把表头改成了「账号」而不是「登录账号」，这一列就读不到值（表现为整列为空）。
 *
 * <p>为什么要单独一个 DTO 而不直接复用 UserPO？因为 Excel 里的列和数据库字段
 * 不是一一对应：Excel 里是「角色编码」这种便于人工填写的文本，
 * 数据库里存的却是 user_role 关联表的角色 ID，中间需要一次转换。
 *
 * <p><b>注意：这个类不要加 {@code @Accessors(chain = true)}。</b>
 * 链式 setter 的返回值是 {@code this} 而不是 {@code void}，
 * EasyExcel 反射赋值时按「set 开头且返回 void」找方法，会找不到，
 * 结果是每一行读进来所有字段都是 null —— 表现为「导入时提示登录账号不能为空」。
 * 这种问题排查起来很费劲，因为编译、运行都不报错，只是数据悄悄丢了。
 */
@Data
public class UserImportDTO {

    @ExcelProperty("登录账号")
    private String username;

    @ExcelProperty("姓名")
    private String name;

    @ExcelProperty("密码")
    private String password;

    @ExcelProperty("手机号")
    private String phone;

    @ExcelProperty("邮箱")
    private String email;

    @ExcelProperty("年龄")
    private Integer age;

    @ExcelProperty("性别")
    private String gender;

    @ExcelProperty("角色编码")
    private String roleCode;
}
