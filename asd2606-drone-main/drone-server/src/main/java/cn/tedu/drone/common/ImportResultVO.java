package cn.tedu.drone.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 导入结果（用户、设备等模块通用）。
 *
 * <p>导入<b>不会因为某几行有问题就整体失败</b> —— 成功的照常入库，
 * 失败的逐条记下来告诉用户哪一行出了什么问题，他改完重新导那几行即可。
 * 这也是一般 Excel 导入的通行做法：批量操作要有「部分成功」的概念。
 *
 * <p>之所以放在 common 而不是各业务模块各写一份：结构完全一样，
 * 复制粘贴只会带来改一处漏一处的问题。
 */
@Data
@Accessors(chain = true)
@Schema(description = "导入结果")
public class ImportResultVO implements Serializable {

    @Schema(description = "文件中的数据总行数")
    private int total;

    @Schema(description = "成功导入条数")
    private int success;

    @Schema(description = "失败条数")
    private int failed;

    @Schema(description = "失败明细，形如「第 5 行：编号已存在」")
    private List<String> errors = new ArrayList<>();

    public void addError(int rowNum, String reason) {
        errors.add("第 " + rowNum + " 行：" + reason);
    }
}
