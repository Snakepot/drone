import { computed, ref, watch } from 'vue'

export type ThemeName = 'dark' | 'light'

/** localStorage 键名 */
const STORAGE_KEY = 'theme'

function readStoredTheme(): ThemeName {
  return localStorage.getItem(STORAGE_KEY) === 'light' ? 'light' : 'dark'
}

/**
 * 主题状态。
 *
 * <p>放在模块级是为了让所有组件共享同一份状态 —— 顶栏的切换按钮改了它，
 * 别的地方（如果以后有）读到的也是同一个值。
 */
const theme = ref<ThemeName>(readStoredTheme())

// 主题变化时同步到 <html data-theme> 和 localStorage。
// CSS 变量在 theme.css 里按 [data-theme='light'] 覆盖，所以这里只要切属性即可。
watch(theme, (name) => {
  document.documentElement.dataset.theme = name
  localStorage.setItem(STORAGE_KEY, name)
})

// 首次进入时应用一次，避免刷新后先闪一下默认色
document.documentElement.dataset.theme = theme.value

export function useTheme() {
  function toggle() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
  }

  return {
    theme,
    isDark: computed(() => theme.value === 'dark'),
    toggle,
  }
}
