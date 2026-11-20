import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/**
 * 大屏的等比缩放外壳：按 1920×1080 设计，实际屏幕多大就整体缩放多少。
 *
 * <h2>为什么不做响应式断点</h2>
 * 大屏的物理尺寸是**已知且固定**的（那块电视），而内容又多 ——
 * 用断点就变成「每个宽度一套布局」，改一处要调四遍。
 * 写死一套 + 整体缩放，换到 2560×1440 的屏上只是放大，比例不变。
 *
 * <h2>为什么用 ResizeObserver 而不是 window.resize</h2>
 * 大屏可能被嵌在 iframe 里、也可能被别的东西改变容器大小 ——
 * 那些情况下窗口没变，但容器变了。监听 window 会漏掉这些。
 */
export const DESIGN_WIDTH = 1920
export const DESIGN_HEIGHT = 1080

export function useScreenScale(): { scale: Ref<number>; shellStyle: Ref<Record<string, string>> } {
  const scale = ref(1)
  const shellStyle = ref<Record<string, string>>({})
  let observer: ResizeObserver | null = null

  function measure(container: HTMLElement) {
    const width = container.clientWidth
    const height = container.clientHeight
    if (width <= 0 || height <= 0) {
      return
    }
    // 取小的那个比例：宽高都装得下，宁可留黑边也不拉伸
    const next = Math.min(width / DESIGN_WIDTH, height / DESIGN_HEIGHT)
    scale.value = next
    shellStyle.value = {
      width: `${DESIGN_WIDTH}px`,
      height: `${DESIGN_HEIGHT}px`,
      transform: `scale(${next})`,
      // 缩放基点固定在左上角，再用下面那个容器把整块居中
      transformOrigin: 'left top',
    }
  }

  onMounted(() => {
    const container = document.getElementById('screen-viewport')
    if (!container) {
      console.warn('大屏容器 #screen-viewport 不存在，缩放外壳没生效')
      return
    }
    measure(container)
    observer = new ResizeObserver(() => measure(container))
    observer.observe(container)
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
  })

  return { scale, shellStyle }
}
