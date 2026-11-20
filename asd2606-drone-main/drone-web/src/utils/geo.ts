/**
 * 地图组件的公共类型。
 *
 * <h2>为什么不放在 `GeoMap.vue` 里</h2>
 * Vue 的 `<script setup>` **不允许 `export`**（编译期就会报
 * 「`<script setup>` cannot contain ES module exports」），
 * 所以要想让页面也用到这几个类型，只能单独放一个 .ts 文件。
 *
 * <p>所有坐标都是 **GCJ-02**（后端转好的），前端不再做任何坐标系转换。
 */
export interface GeoPoint {
  lng: number
  lat: number
}

export interface GeoLine {
  path: GeoPoint[]
  /** 颜色，默认蓝色 */
  color?: string
  /** 虚线：计划航线画虚线、实际轨迹画实线，一眼能分开 */
  dashed?: boolean
  weight?: number
}

export interface GeoMarker {
  lng: number
  lat: number
  /** 图上显示的小标签（点位名、航点序号） */
  label?: string
  /**
   * 语义色；用语义而不是传颜色，避免同一个东西在几个页面里颜色不一样。
   *
   * <p>{@code alert}（有严重告警）与 {@code warn}（超期）是看板用的：
   * 颜色在这里定一次，页面只回答「这条是什么状态」。
   */
  tone?: 'start' | 'end' | 'plan' | 'takeoff' | 'point' | 'alert' | 'warn'
}

/**
 * 一块区域（目前只有禁飞区在用）。
 *
 * <p>形状决定看哪一组字段，与后端 {@code NoFlyZoneVO} 一致：
 * 多边形看 `points`，圆看 `center` + `radius`。
 */
export interface GeoZone {
  shape: 'polygon' | 'circle'
  /** 多边形顶点（shape=polygon 时用） */
  points?: GeoPoint[]
  /** 圆心（shape=circle 时用） */
  center?: GeoPoint
  /** 半径（米） */
  radius?: number
  /** 完全禁飞 = 红色；限高区 = 琥珀色 */
  forbidden?: boolean
  /**
   * 是不是「正在画、还没保存」的草稿。
   *
   * <p>草稿画成虚线描边、填充更淡：画到一半的形状和已经生效的禁飞区
   * 在图上必须能一眼分开 —— 否则用户会以为那个区已经存在了。
   */
  draft?: boolean
  /** 图上显示的区域名 */
  label?: string
}

/**
 * 两点间的近似距离（米）。
 *
 * <h2>它和后端那份是什么关系</h2>
 * 后端 {@code common/GeoDistance} 也有一个同名的公式，用途完全不同：
 * <ul>
 *   <li><b>后端那份是判定依据</b>（航线里程、禁飞区圆判定都依赖它）</li>
 *   <li><b>前端这份只是交互提示</b>：用户先点圆心、再点圆边上一点时，
 *       把这两下的距离算出来填进半径输入框，让他看得见自己画了多大</li>
 * </ul>
 * 半径最终是**用户确认过的那个数字**，后端不再反算 —— 所以这里算得差几米
 * 不影响任何判定，只是显示得更顺手。
 *
 * <p>公式与后端一致（纬度按 110540 米/度、经度乘 cos(纬度)），
 * 免得同一个圆在两处显示的半径不一样、让人以为存错了。
 */
export function distanceMeters(a: GeoPoint, b: GeoPoint): number {
  const latAvg = ((a.lat + b.lat) / 2) * (Math.PI / 180)
  const dx = (b.lng - a.lng) * 111320 * Math.cos(latAvg)
  const dy = (b.lat - a.lat) * 110540
  return Math.sqrt(dx * dx + dy * dy)
}

/**
 * 把一串坐标点转成一条折线。
 *
 * <p>几个页面都要做这件事（航线预览、计划航线、实际轨迹），
 * 而在各自页面里手写一遍 `<GeoLine>` 字面量，改字段时就得四处改。
 */
export function toLine(
  points: GeoPoint[] | undefined,
  options: { color?: string; dashed?: boolean; weight?: number } = {},
): GeoLine {
  return { path: points || [], ...options }
}
