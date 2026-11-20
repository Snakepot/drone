<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/useTheme'
import { useTaskSocket } from '@/composables/useTaskSocket'
import AgentChatPanel from '@/components/AgentChatPanel.vue'
import TaskNotificationCenter from '@/components/TaskNotificationCenter.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { isDark, toggle: toggleTheme } = useTheme()

/**
 * 任务通知的实时连接。
 *
 * <p>连在布局层而不是某个页面里：飞手可能在任何管理页面上操作，
 * 通知不该因为「管理员当时不在任务页」就丢掉。
 */
const { connected: realtimeConnected, start: startRealtime, stop: stopRealtime } = useTaskSocket()

// 登录后的所有页面都在这个布局里，所以在这里进连、走断
onMounted(startRealtime)
onUnmounted(stopRealtime)

/** 「系统设置」父菜单的展开状态，默认展开便于发现下面的子项 */
const systemOpen = ref(true)

/**
 * 智能助手面板的展开状态。
 *
 * <p>放在布局层而不是某个页面里：它跟着顶栏走，在任何管理页面上都能开合。
 * 入口按 agent:chat 权限显示 —— 权限码由 drone-server 的 RBAC 提供，
 * 前端只决定「显不显示」，真正的边界在 drone-agent 的拦截器上。
 */
const chatOpen = ref(false)

/**
 * 「业务管理」下有几个菜单可见。
 *
 * <p>用它决定要不要显示「暂无可用功能」的提示 ——
 * 之前是手写一长串 && 判断，每加一个业务菜单就得回来改表达式，
 * 漏改就会出现「已经有菜单了、却还提示暂无功能」的怪现象。
 */
const hasBusinessMenu = computed(
  () =>
    auth.hasPermission('staff:list') ||
    auth.hasPermission('device:list') ||
    auth.hasPermission('task:list') ||
    auth.hasPermission('alert:list'),
)

async function handleUserCommand(command: string) {
  if (command !== 'logout') return

  try {
    await ElMessageBox.confirm('退出后需要重新输入账号密码，确认退出吗？', '退出登录', {
      confirmButtonText: '确认退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    // 用户点了取消
    return
  }

  await auth.logout()
  ElMessage.success('已退出登录')
  router.replace('/login')
}
</script>

<template>
  <div class="admin">
    <!-- 背景层：网格 + 双色光晕，与登录页一致 -->
    <div class="backdrop">
      <div class="glow glow--cyan"></div>
      <div class="glow glow--violet"></div>
    </div>

    <div class="app">
      <!-- ============ 左侧菜单 ============ -->
      <aside class="sidebar">
        <div class="brand">
          <div class="brand__mark">
            <svg viewBox="0 0 24 24" fill="none" stroke="#67e8f9" stroke-width="1.6" stroke-linecap="round">
              <path d="M12 12 5 5M12 12l7-7M12 12l-7 7M12 12l7 7" />
              <circle cx="5" cy="5" r="2.6" /><circle cx="19" cy="5" r="2.6" />
              <circle cx="5" cy="19" r="2.6" /><circle cx="19" cy="19" r="2.6" />
              <rect x="9.6" y="9.6" width="4.8" height="4.8" rx="1.6" />
            </svg>
          </div>
          <div class="brand__text">
            <strong>无人机管理平台</strong>
            <span>DroneOps</span>
          </div>
        </div>

        <nav class="menu">
          <div class="menu__group">业务管理</div>

          <RouterLink
            v-if="auth.hasPermission('device:list')"
            class="menu__item"
            :class="{ 'is-active': route.path === '/screen' }"
            to="/screen"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
              <rect x="3" y="4.5" width="18" height="12" rx="2" />
              <path d="M8.5 20h7M12 16.5V20M7 9.5h4M7 12.5h7" />
            </svg>
            <span>数字大屏</span>
          </RouterLink>

          <RouterLink
            v-if="auth.hasPermission('staff:list')"
            class="menu__item"
            :class="{ 'is-active': route.path === '/staff' }"
            to="/staff"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
              <circle cx="12" cy="8" r="3.6" />
              <path d="M4.8 20c0-3.7 3.3-6.2 7.2-6.2s7.2 2.5 7.2 6.2" />
            </svg>
            <span>员工管理</span>
          </RouterLink>

          <RouterLink
            v-if="auth.hasPermission('device:list')"
            class="menu__item"
            :class="{ 'is-active': route.path === '/device' }"
            to="/device"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
              <rect x="7.5" y="7.5" width="9" height="9" rx="2.4" />
              <path d="M10 4h4M10 20h4M4 10v4M20 10v4M9.2 4.6 7.5 7.5M14.8 4.6l1.7 2.9M9.2 19.4l-1.7-2.9M14.8 19.4l1.7-2.9" />
            </svg>
            <span>设备管理</span>
          </RouterLink>

          <RouterLink
            v-if="auth.hasPermission('task:list')"
            class="menu__item"
            :class="{ 'is-active': route.path === '/task' }"
            to="/task"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
              <rect x="4" y="4.5" width="16" height="15" rx="2.6" />
              <path d="M8 9.5h8M8 13h8M8 16.5h5" />
            </svg>
            <span>任务管理</span>
          </RouterLink>

          <RouterLink
            v-if="auth.hasPermission('task:list')"
            class="menu__item"
            :class="{ 'is-active': route.path === '/monitor' }"
            to="/monitor"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
              <path d="M4 6.5h16M4 12h16M4 17.5h16" />
              <circle cx="9" cy="6.5" r="1.9" />
              <circle cx="15" cy="12" r="1.9" />
              <circle cx="11" cy="17.5" r="1.9" />
            </svg>
            <span>监控看板</span>
          </RouterLink>

          <RouterLink
            v-if="auth.hasPermission('route:list')"
            class="menu__item"
            :class="{ 'is-active': route.path === '/point' }"
            to="/point"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
              <path d="M12 21s6.4-5.3 6.4-10.2A6.4 6.4 0 0 0 5.6 10.8C5.6 15.7 12 21 12 21Z" />
              <circle cx="12" cy="10.5" r="2.3" />
            </svg>
            <span>点位库</span>
          </RouterLink>

          <RouterLink
            v-if="auth.hasPermission('route:list')"
            class="menu__item"
            :class="{ 'is-active': route.path === '/route' }"
            to="/route"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
              <path d="M5 19c0-4 3-5.5 7-5.5S19 11.5 19 7.5" />
              <circle cx="5" cy="19" r="1.8" />
              <circle cx="19" cy="7.5" r="1.8" />
              <path d="M12 13.5v0" />
            </svg>
            <span>航线管理</span>
          </RouterLink>

          <RouterLink
            v-if="auth.hasPermission('zone:list')"
            class="menu__item"
            :class="{ 'is-active': route.path === '/zone' }"
            to="/zone"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
              <path d="M4.5 19V5.5a1 1 0 0 1 1-1H19" />
              <path d="M4.5 12.5h7M16 8.5l3.5 3.5L16 15.5" />
              <circle cx="11.5" cy="15.5" r="2.2" />
            </svg>
            <span>禁飞区</span>
          </RouterLink>

          <p v-if="!hasBusinessMenu" class="menu__empty">
            当前账号暂无可用功能，请联系管理员分配权限
          </p>

          <!-- 系统设置：可展开的父菜单 -->
          <template v-if="auth.hasPermission('system:rbac:list')">
            <button
              class="menu__item menu__item--parent"
              :class="{ 'is-open': systemOpen }"
              type="button"
              @click="systemOpen = !systemOpen"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
                <path d="M4 7h9M18 7h2M4 12h4M13 12h7M4 17h11M20 17h0" />
                <circle cx="15.5" cy="7" r="2.2" />
                <circle cx="10.5" cy="12" r="2.2" />
                <circle cx="17.5" cy="17" r="2.2" />
              </svg>
              <span>系统设置</span>
              <svg class="menu__arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                <path d="m6 9 6 6 6-6" />
              </svg>
            </button>

            <div v-show="systemOpen" class="menu__sub">
              <RouterLink
                class="menu__item menu__item--sub"
                :class="{ 'is-active': route.path === '/system/rbac' }"
                to="/system/rbac"
              >
                <span>权限管理</span>
              </RouterLink>
              <RouterLink
                class="menu__item menu__item--sub"
                :class="{ 'is-active': route.path === '/agent/bills' }"
                to="/agent/bills"
              >
                <span>助手账单</span>
              </RouterLink>
            </div>
          </template>

          <RouterLink
            v-if="auth.hasPermission('alert:list')"
            class="menu__item"
            :class="{ 'is-active': route.path === '/alert' }"
            to="/alert"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
              <path d="M12 4.5a5.2 5.2 0 0 1 5.2 5.2c0 3.6 1.3 5 1.3 5H5.5s1.3-1.4 1.3-5A5.2 5.2 0 0 1 12 4.5Z" />
              <path d="M10.2 17.6a1.9 1.9 0 0 0 3.6 0" />
            </svg>
            <span>告警中心</span>
          </RouterLink>

          <RouterLink
            v-if="auth.hasPermission('task:list')"
            class="menu__item"
            :class="{ 'is-active': route.path === '/analytics' }"
            to="/analytics"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
              <path d="M4.5 19V9.5M9.8 19V4.5M15.2 19v-7M20.5 19v-11" />
            </svg>
            <span>业务分析</span>
          </RouterLink>
        </nav>

        <div class="sidebar__foot">
          <div class="sidebar__status"><i></i>AI 巡检引擎运行中</div>
          DroneOps v0.0.1
        </div>
      </aside>

      <!-- ============ 主区域 ============ -->
      <div class="main">
        <header class="topbar">
          <div class="topbar__crumbs">
            <span class="prev">业务管理</span>
            <span class="sep">/</span>
            <span class="now">{{ route.meta.title }}</span>
          </div>

          <div class="topbar__right">
            <!--
              实时连接状态：连上才说明「飞手一操作我就知道」。
              看不到这个标的话，管理员没法判断自己是不是漏了通知。
            -->
            <span class="conn" :class="{ 'is-on': realtimeConnected }">
              <i></i>{{ realtimeConnected ? '实时通知已连接' : '实时通知未连接' }}
            </span>

            <span class="ai-chip"><i></i>AI 智能巡检引擎已就绪</span>

            <!-- 智能助手入口：点击在右侧展开对话面板 -->
            <button
              v-if="auth.hasPermission('agent:chat')"
              class="btn btn--ghost agent-trigger"
              :class="{ 'is-open': chatOpen }"
              type="button"
              title="智能助手"
              aria-label="智能助手"
              @click="chatOpen = !chatOpen"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
                <path d="M12 3.5 13.6 9l5.4 1.7-5.4 1.7L12 18l-1.6-5.6L5 10.7 10.4 9 12 3.5Z" />
                <path d="M18.4 15.6l.6 2 2 .6-2 .6-.6 2-.6-2-2-.6 2-.6.6-2Z" />
              </svg>
              <span>智能助手</span>
            </button>

            <!-- 深浅色切换：深色时显示太阳（点了变浅），浅色时显示月亮 -->
            <button
              class="btn btn--icon"
              type="button"
              :title="isDark ? '切换到浅色主题' : '切换到深色主题'"
              :aria-label="isDark ? '切换到浅色主题' : '切换到深色主题'"
              @click="toggleTheme"
            >
              <svg v-if="isDark" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
                <circle cx="12" cy="12" r="4.2" />
                <path d="M12 3.2v2.2M12 18.6v2.2M3.2 12h2.2M18.6 12h2.2M5.7 5.7l1.6 1.6M16.7 16.7l1.6 1.6M18.3 5.7l-1.6 1.6M7.3 16.7l-1.6 1.6" />
              </svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
                <path d="M20 14.6A8.6 8.6 0 1 1 9.4 4a6.9 6.9 0 0 0 10.6 10.6Z" />
              </svg>
            </button>

            <el-dropdown trigger="click" @command="handleUserCommand">
              <span class="user">
                <span class="user__avatar">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
                    <circle cx="12" cy="8" r="3.6" />
                    <path d="M4.8 20c0-3.7 3.3-6.2 7.2-6.2s7.2 2.5 7.2 6.2" />
                  </svg>
                </span>
                <span class="user__name">{{ auth.displayName }}</span>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="logout">退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </header>

        <main class="content">
          <RouterView v-slot="{ Component }">
            <Transition name="page-fade" mode="out-in">
              <component :is="Component" />
            </Transition>
          </RouterView>
        </main>
      </div>
    </div>

    <!-- 任务通知浮层：固定在右上角，任何管理页面都能看到 -->
    <TaskNotificationCenter />

    <!-- 智能助手：右侧展开的对话面板 -->
    <AgentChatPanel v-model="chatOpen" />
  </div>
</template>

<style scoped>
.admin {
  position: relative;
  height: 100%;
  color: var(--text);
}

/* ---------- 背景 ---------- */
.backdrop {
  position: fixed;
  inset: 0;
  z-index: 0;
}

.backdrop::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(110% 80% at 8% 0%, rgba(34, 211, 238, 0.1), transparent 55%),
    radial-gradient(100% 80% at 95% 100%, rgba(139, 92, 246, 0.12), transparent 55%),
    linear-gradient(160deg, var(--page-from), var(--page-to) 70%);
}

.backdrop::after {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(var(--line) 1px, transparent 1px),
    linear-gradient(90deg, var(--line) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(100% 80% at 50% 20%, #000 15%, transparent 80%);
  -webkit-mask-image: radial-gradient(100% 80% at 50% 20%, #000 15%, transparent 80%);
}

.glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(100px);
  opacity: 0.42;
}

.glow--cyan {
  width: 420px;
  height: 420px;
  top: -160px;
  left: -100px;
  background: var(--glow-cyan);
}

.glow--violet {
  width: 460px;
  height: 460px;
  bottom: -200px;
  right: -140px;
  background: var(--glow-violet);
}

/* ---------- 骨架 ---------- */
.app {
  position: relative;
  z-index: 1;
  display: flex;
  height: 100%;
}

/* ---------- 侧边栏 ---------- */
.sidebar {
  width: 236px;
  flex: none;
  display: flex;
  flex-direction: column;
  background: var(--sidebar-bg);
  border-right: 1px solid var(--line);
  backdrop-filter: blur(18px);
}

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 22px 20px;
  border-bottom: 1px solid var(--line);
}

.brand__mark {
  width: 38px;
  height: 38px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 11px;
  background: linear-gradient(140deg, rgba(34, 211, 238, 0.22), rgba(139, 92, 246, 0.22));
  border: 1px solid var(--accent-hover);
  box-shadow: 0 0 20px rgba(34, 211, 238, 0.28), inset 0 0 14px rgba(34, 211, 238, 0.12);
}

.brand__mark svg {
  width: 22px;
  height: 22px;
}

.brand__text strong {
  display: block;
  font-size: 14.5px;
  letter-spacing: 1.4px;
  font-weight: 600;
}

.brand__text span {
  font-size: 9.5px;
  letter-spacing: 2.6px;
  color: var(--muted);
  text-transform: uppercase;
}

.menu {
  flex: 1;
  padding: 16px 12px;
  overflow-y: auto;
}

.menu__group {
  padding: 14px 10px 8px;
  font-size: 10.5px;
  letter-spacing: 2px;
  color: var(--muted);
  text-transform: uppercase;
}

.menu__item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 11px 13px;
  margin-bottom: 4px;
  border-radius: 10px;
  color: var(--text-2);
  font-size: 13.5px;
  text-decoration: none;
  cursor: pointer;
  border: 1px solid transparent;
  transition: background 0.22s, color 0.22s, border-color 0.22s;
}

.menu__item svg {
  width: 17px;
  height: 17px;
  flex: none;
  opacity: 0.85;
}

.menu__item:hover {
  background: rgba(34, 211, 238, 0.07);
  color: var(--text);
  border-color: var(--line-strong);
}

/* 当前选中：左侧发光条 + 渐变底 */
.menu__item.is-active {
  color: var(--cyan);
  background: linear-gradient(100deg, rgba(34, 211, 238, 0.18), rgba(59, 130, 246, 0.08));
  border-color: var(--accent-hover);
  box-shadow: 0 0 20px rgba(34, 211, 238, 0.16), inset 0 0 18px rgba(34, 211, 238, 0.06);
}

.menu__item.is-active::before {
  content: '';
  position: absolute;
  left: -12px;
  top: 50%;
  width: 3px;
  height: 20px;
  margin-top: -10px;
  border-radius: 0 3px 3px 0;
  background: linear-gradient(180deg, var(--cyan), var(--violet));
  box-shadow: 0 0 12px rgba(34, 211, 238, 0.9);
}

.menu__item.is-active svg {
  opacity: 1;
}

.menu__empty {
  padding: 10px 13px;
  margin-bottom: 4px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
}

/* ---------- 可展开的父菜单 ---------- */
.menu__item--parent {
  width: 100%;
  background: none;
  font-family: inherit;
  font-size: 13.5px;
  text-align: left;
}

.menu__arrow {
  margin-left: auto;
  width: 14px;
  height: 14px;
  flex: none;
  opacity: 0.7;
  transition: transform 0.22s;
}

.menu__item--parent.is-open .menu__arrow {
  transform: rotate(180deg);
}

/* 子菜单：左侧一条细线表示层级 */
.menu__sub {
  margin: 0 0 4px 22px;
  padding-left: 10px;
  border-left: 1px solid var(--line);
}

.menu__item--sub {
  padding: 9px 12px;
  font-size: 13px;
}

.sidebar__foot {
  padding: 14px 20px 18px;
  border-top: 1px solid var(--line);
  font-size: 11px;
  line-height: 1.7;
  color: var(--muted);
}

.sidebar__status {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 5px;
  color: var(--cyan);
}

.sidebar__status i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--cyan);
  box-shadow: 0 0 8px var(--cyan);
  animation: blink 1.8s ease-in-out infinite;
}

@keyframes blink {
  50% {
    opacity: 0.2;
  }
}

/* ---------- 主区域 ---------- */
.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.topbar {
  height: 64px;
  flex: none;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 24px;
  background: var(--topbar-bg);
  border-bottom: 1px solid var(--line);
  backdrop-filter: blur(14px);
}

.topbar__crumbs {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 13px;
}

.topbar__crumbs .sep {
  color: var(--muted);
}

.topbar__crumbs .now {
  color: var(--text);
  font-weight: 500;
}

.topbar__crumbs .prev {
  color: var(--muted);
}

.topbar__right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}

.ai-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 13px;
  border-radius: 999px;
  font-size: 11.5px;
  letter-spacing: 0.6px;
  color: var(--cyan);
  background: rgba(34, 211, 238, 0.1);
  border: 1px solid var(--line-strong);
}

.ai-chip i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--cyan);
  box-shadow: 0 0 8px var(--cyan);
  animation: blink 1.6s ease-in-out infinite;
}

/* 智能助手入口：展开时保持高亮，让人一眼看出面板是从这里开的 */
.agent-trigger.is-open {
  color: var(--cyan);
  border-color: var(--accent-hover);
  background: rgba(34, 211, 238, 0.12);
}

/* 实时通知的连接状态：连上是绿的，断开是灰的 */
.conn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 13px;
  border-radius: 999px;
  font-size: 11.5px;
  letter-spacing: 0.6px;
  color: var(--muted);
  background: var(--ghost-bg);
  border: 1px solid var(--line);
}

.conn i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--muted);
}

.conn.is-on {
  color: var(--green);
  background: rgba(52, 211, 153, 0.1);
  border-color: var(--tag-green-bd);
}

.conn.is-on i {
  background: var(--green);
  box-shadow: 0 0 8px var(--green);
  animation: blink 1.8s ease-in-out infinite;
}

.user {
  display: flex;
  align-items: center;
  gap: 9px;
  padding-left: 14px;
  border-left: 1px solid var(--line);
  cursor: pointer;
  color: var(--text-2);
  outline: none;
  transition: color 0.2s;
}

.user:hover {
  color: var(--cyan);
}

.user__avatar {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: 9px;
  background: linear-gradient(140deg, rgba(34, 211, 238, 0.2), rgba(139, 92, 246, 0.2));
  border: 1px solid var(--line-strong);
}

.user__avatar svg {
  width: 16px;
  height: 16px;
  color: var(--cyan);
}

.user__name {
  font-size: 13.5px;
}

.content {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

/* 页面切换过渡：子组件根元素会继承父组件的 scoped 属性，所以这里能命中 */
.page-fade-enter-active,
.page-fade-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}

.page-fade-enter-from {
  opacity: 0;
  transform: translateY(10px);
}

.page-fade-leave-to {
  opacity: 0;
}

/* ---------- 响应式：小屏收起为图标条 ---------- */
@media (max-width: 900px) {
  .sidebar {
    width: 68px;
  }

  .brand {
    justify-content: center;
    padding: 18px 10px;
  }

  .brand__text,
  .menu__group,
  .sidebar__foot,
  .menu__item span {
    display: none;
  }

  .menu__item {
    justify-content: center;
    padding: 12px 0;
  }

  .ai-chip {
    display: none;
  }

  /* 窄屏只留图标，省出顶栏宽度 */
  .agent-trigger span {
    display: none;
  }
}
</style>
