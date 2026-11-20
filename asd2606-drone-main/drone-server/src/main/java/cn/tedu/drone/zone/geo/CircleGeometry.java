package cn.tedu.drone.zone.geo;

/**
 * 圆的几何 —— <b>只是 {@code no_fly_zone.geometry} 那一列在 shape=circle 时的 JSON 形状</b>。
 *
 * <p>单独一个 record 是为了让「写进去」和「读出来」共用同一份定义：
 * 用 Map 拼 JSON 的话，字段名是两份字符串字面量，改了一处忘了另一处，
 * 结果是<b>解析出一堆 null 而不报错</b>。
 *
 * <p>坐标是 WGS84（和库里其它坐标一致）。
 *
 * @param lng    圆心经度
 * @param lat    圆心纬度
 * @param radius 半径（米）—— 无所谓坐标系，米就是米
 */
public record CircleGeometry(double lng, double lat, double radius) {
}
