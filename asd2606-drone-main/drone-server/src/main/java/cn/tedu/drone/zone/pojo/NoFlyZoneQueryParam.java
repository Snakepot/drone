package cn.tedu.drone.zone.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 禁飞区查询条件。
 *
 * <p>比别的模块多一个「只看启用的」：管理员排查「为什么这条任务被拦了」时，
 * 往往只想看还生效的那些区域。
 */
@Data
@Accessors(chain = true)
@Schema(description = "禁飞区查询条件")
public class NoFlyZoneQueryParam implements Serializable {

    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 单页最大条数：区域数量本来就不多，但画区时可能想一次看全 */
    public static final int MAX_PAGE_SIZE = 200;

    @Schema(description = "关键字，模糊匹配区域名称")
    private String keyword;

    @Schema(description = "来源 official / custom")
    private String source;

    @Schema(description = "是否只看启用的")
    private Boolean enabled;

    @Schema(description = "页码，从 1 开始")
    private Integer page = 1;

    @Schema(description = "每页条数，默认 10，最大 200")
    private Integer pageSize = DEFAULT_PAGE_SIZE;

    /** SQL 的 OFFSET；页码非法时兜底为第 1 页 */
    public int getOffset() {
        return (normalizedPage() - 1) * normalizedPageSize();
    }

    public int getLimit() {
        return normalizedPageSize();
    }

    private int normalizedPage() {
        return (page == null || page < 1) ? 1 : page;
    }

    private int normalizedPageSize() {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
