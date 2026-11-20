import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '@/layouts/AdminLayout.vue'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      // 业务页面统一套在 AdminLayout（左侧菜单 + 顶栏）里
      path: '/',
      component: AdminLayout,
      children: [
        { path: '', redirect: '/staff' },
        {
          path: 'staff',
          name: 'staff',
          component: () => import('@/views/StaffView.vue'),
          meta: { title: '员工管理', permission: 'staff:list' },
        },
        {
          path: 'device',
          name: 'device',
          component: () => import('@/views/DeviceView.vue'),
          meta: { title: '设备管理', permission: 'device:list' },
        },
        {
          // 任务管理：权限码是 task:list —— 飞手只有 task:execute，看不到这张总表。
          // 飞手看自己那批任务用的是另一个接口（GET /task/my，见 TaskController），
          // 入口在飞手端小程序 drone-app 里，**不在管理端**，所以这里没有飞手的入口。
          path: 'task',
          name: 'task',
          component: () => import('@/views/TaskView.vue'),
          meta: { title: '任务管理', permission: 'task:list' },
        },
        {
          // 数字大屏：给「站着看的人」用的（值班室/会议室那台电视），
          // 1920×1080 写死 + 整体缩放，自动刷新 + 自动轮播，页面上几乎没有可点的东西。
          // 权限门槛取 device:list：这是三种角色里最低的公共权限，
          // 「具体能看哪几块」由后端按权限裁（见 docs/数字大屏方案.md 4.3）
          path: 'screen',
          name: 'screen',
          component: () => import('@/views/ScreenView.vue'),
          meta: { title: '数字大屏', permission: 'device:list' },
        },
        {
          // 监控看板：只装「执行中」的任务 —— 它回答的是「现场怎么样了」，
          // 而不是「有哪些任务」（那是任务管理页的事）
          path: 'monitor',
          name: 'monitor',
          component: () => import('@/views/MonitorView.vue'),
          meta: { title: '监控看板', permission: 'task:list' },
        },
        {
          // 点位库与航线：同一件事的两半（在地图上规划「去哪飞」），
          // 共用 route:list 权限点，菜单里也挨着放
          path: 'point',
          name: 'point',
          component: () => import('@/views/PointView.vue'),
          meta: { title: '点位库', permission: 'route:list' },
        },
        {
          path: 'route',
          name: 'route',
          component: () => import('@/views/RouteView.vue'),
          meta: { title: '航线管理', permission: 'route:list' },
        },
        {
          // 禁飞区：独立权限点，不复用 route:*
          // 「维护点位/航线」是日常作业，「划定禁飞区」是改动一条安全控制线
          path: 'zone',
          name: 'zone',
          component: () => import('@/views/ZoneView.vue'),
          meta: { title: '禁飞区', permission: 'zone:list' },
        },
        {
          // 告警中心：管理员与机务有 alert:list，飞手没有。
          // 「谁能处理」是另一个权限点 alert:handle，页面里的处理按钮按它显示。
          path: 'alert',
          name: 'alert',
          component: () => import('@/views/AlertView.vue'),
          meta: { title: '告警中心', permission: 'alert:list' },
        },
        {
          // 业务分析：给「管人管设备的人」看的（见 docs/业务分析方案.md）。
          // 权限取 task:list —— 它答的是「活攒着为什么没派出去」，那是管理动作的问题，
          // 与「任务管理」那张总表是同一批人；飞手不该看到全公司的积压归因。
          // 它不追实时（按天/周看），所以不做轮询，进来算一次即可
          path: 'analytics',
          name: 'analytics',
          component: () => import('@/views/AnalyticsView.vue'),
          meta: { title: '业务分析', permission: 'task:list' },
        },
        {
          path: 'system/rbac',
          name: 'rbac',
          component: () => import('@/views/system/RbacView.vue'),
          meta: { title: '权限管理', permission: 'system:rbac:list' },
        },
        {
          // 助手账单：只给管理员看（权限码与权限管理一致，目前只有 admin 持有）。
          // 后端 scope=all 还会再判一次角色，前端隐藏入口不是边界。
          path: 'agent/bills',
          name: 'agentBills',
          component: () => import('@/views/AgentBillsView.vue'),
          meta: { title: '助手账单', permission: 'system:rbac:list' },
        },
        {
          path: 'no-access',
          name: 'noAccess',
          component: () => import('@/views/NoAccessView.vue'),
          meta: { title: '暂无可用功能' },
        },
      ],
    },
  ],
})

/**
 * 路由守卫，按顺序做三件事：
 *
 * <ol>
 *   <li><b>没登录</b>：非公开页面一律送去登录页，并带上 redirect 方便登录后跳回</li>
 *   <li><b>有登录但用户信息还没加载</b>（例如刚刷新页面）：先拉一次 /user/me 拿到角色与权限</li>
 *   <li><b>权限校验</b>：目标页面声明了 permission 而当前用户没有，就送到他有权限的首页</li>
 * </ol>
 *
 * 第 3 步是为了解决一个实际体验问题：菜单已经按权限隐藏了「员工管理」，
 * 但如果首页仍然固定跳到 /staff，没有该权限的用户一登录就撞上空白页面。
 * <p>注意前端拦截只是体验优化 —— 真正的安全边界在服务端，
 * 就算有人绕过前端直接调接口，依然会被拦截器按同一套权限码拦下（403）。
 */
router.beforeEach(async (to) => {
  const token = localStorage.getItem('token')

  if (!token) {
    return to.meta.public ? true : { name: 'login', query: { redirect: to.fullPath } }
  }

  const auth = useAuthStore()

  // 刷新页面后 Pinia store 是空的，先补一次用户信息（含权限），否则判断不了权限
  if (!auth.user) {
    try {
      await auth.fetchCurrentUser()
    } catch {
      // token 失效时 axios 拦截器已经清理登录痕迹并跳转登录页，这里只需中止本次导航
      return false
    }
  }

  // 已登录还想去登录页，直接送回首页
  if (to.name === 'login') {
    return { path: auth.homePath }
  }

  const required = to.meta.permission
  if (required && !auth.hasPermission(required)) {
    return { path: auth.homePath }
  }

  return true
})

export default router
