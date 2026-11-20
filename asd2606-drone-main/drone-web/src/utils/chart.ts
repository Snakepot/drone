/**
 * 图表的取色。
 *
 * <h2>为什么要有这么一个文件</h2>
 * ECharts 画在 canvas 上，**读不到 CSS 变量** —— 而本项目的主题有深/浅两套
 * （见 `assets/theme.css`），把颜色写死在图表配置里，切到浅色就会发飘、看不清。
 * 所以每次画图前从 `:root` 现读一次变量的值交给 ECharts。
 *
 * <p>唯一的不足是：切主题后已经画出来的那张图不会自己重画（没有主题变更事件可监听），
 * 需要刷新页面 —— 主题切换本来就是低频动作，为它引一套响应式不值得。
 */

export interface ChartTokens {
  /** 正文色：坐标轴刻度、图例文字 */
  text: string
  /** 次要色：说明性文字 */
  muted: string
  /** 分隔线：网格线、坐标轴 */
  line: string
  /** 面板底色：图元之间描边用，免得相邻扇区糊成一块 */
  panel: string
  cyan: string
  blue: string
  violet: string
  green: string
  amber: string
  red: string
}

function read(style: CSSStyleDeclaration, name: string, fallback: string): string {
  return style.getPropertyValue(name).trim() || fallback
}

export function chartTokens(): ChartTokens {
  const style = getComputedStyle(document.documentElement)
  return {
    text: read(style, '--text-2', 'rgba(230, 241, 255, 0.72)'),
    muted: read(style, '--muted', 'rgba(230, 241, 255, 0.48)'),
    line: read(style, '--line', 'rgba(120, 190, 255, 0.14)'),
    panel: read(style, '--panel', 'rgba(10, 22, 44, 0.62)'),
    cyan: read(style, '--cyan', '#22d3ee'),
    blue: read(style, '--blue', '#3b82f6'),
    violet: read(style, '--violet', '#8b5cf6'),
    green: read(style, '--green', '#34d399'),
    amber: read(style, '--amber', '#fbbf24'),
    red: read(style, '--red', '#f87171'),
  }
}
