<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

interface Particle {
  left: string
  top: string
  size: string
  opacity: number
  duration: string
  delay: string
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const particles = ref<Particle[]>([])
const showPassword = ref(false)
const submitting = ref(false)
const panelRef = ref<HTMLElement>()

const form = reactive({
  username: '',
  password: '',
  remember: true,
})

onMounted(() => {
  const count = window.innerWidth < 768 ? 16 : 34
  particles.value = Array.from({ length: count }, () => ({
    left: `${Math.random() * 100}%`,
    top: `${70 + Math.random() * 30}%`,
    size: `${1.5 + Math.random() * 2}px`,
    opacity: 0.25 + Math.random() * 0.6,
    duration: `${9 + Math.random() * 12}s`,
    delay: `${-Math.random() * 14}s`,
  }))
})

function shake() {
  panelRef.value?.animate(
    [
      { transform: 'translateX(0)' },
      { transform: 'translateX(-8px)' },
      { transform: 'translateX(8px)' },
      { transform: 'translateX(-5px)' },
      { transform: 'translateX(0)' },
    ],
    { duration: 340, easing: 'ease-in-out' },
  )
}

async function handleSubmit() {
  if (submitting.value) return

  if (!form.username.trim() || !form.password) {
    shake()
    return
  }

  submitting.value = true
  try {
    await auth.login({ username: form.username.trim(), password: form.password })
    const redirect = route.query.redirect
    await router.replace(typeof redirect === 'string' ? redirect : '/')
  } catch {
    // 失败提示由 axios 响应拦截器统一处理
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="backdrop">
      <div class="scan"></div>
      <div class="glow glow--cyan"></div>
      <div class="glow glow--violet"></div>
      <span
        v-for="(p, i) in particles"
        :key="i"
        class="particle"
        :style="{
          left: p.left,
          top: p.top,
          width: p.size,
          height: p.size,
          opacity: p.opacity,
          animationDuration: p.duration,
          animationDelay: p.delay,
        }"
      />
    </div>

    <main class="shell">
      <section class="brand">
        <div class="brand__logo">
          <div class="logo-mark">
            <svg viewBox="0 0 24 24" fill="none" stroke="#67e8f9" stroke-width="1.6" stroke-linecap="round">
              <path d="M12 12 5 5M12 12l7-7M12 12l-7 7M12 12l7 7" />
              <circle cx="5" cy="5" r="2.6" /><circle cx="19" cy="5" r="2.6" />
              <circle cx="5" cy="19" r="2.6" /><circle cx="19" cy="19" r="2.6" />
              <rect x="9.6" y="9.6" width="4.8" height="4.8" rx="1.6" />
            </svg>
          </div>
          <div class="logo-text">
            <strong>无人机管理平台</strong>
            <span>Drone Management Platform</span>
          </div>
        </div>

        <h1 class="brand__title">
          智能调度 · 全域感知<br />
          <em>自主巡检 · 数据驱动</em>
        </h1>

        <p class="brand__desc">
          融合 AI 视觉识别与实时航迹分析，为无人机机队提供航线规划、任务编排、
          风险预警与飞行数据洞察的一体化管控能力。
        </p>

        <div class="metrics">
          <div class="metric"><b>129</b><span>在线设备</span></div>
          <div class="metric"><b>99.2%</b><span>AI 识别准确率</span></div>
          <div class="metric"><b>46</b><span>今日任务</span></div>
        </div>

        <div class="drone-stage">
          <div class="radar">
            <div class="radar__sweep"></div>
          </div>
          <svg class="drone" viewBox="0 0 200 200" fill="none" stroke-linecap="round">
            <defs>
              <linearGradient id="armGrad" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stop-color="#67e8f9" />
                <stop offset="100%" stop-color="#8b5cf6" />
              </linearGradient>
              <filter id="soft">
                <feGaussianBlur stdDeviation="2.6" result="b" />
                <feMerge><feMergeNode in="b" /><feMergeNode in="SourceGraphic" /></feMerge>
              </filter>
            </defs>

            <g stroke="url(#armGrad)" stroke-width="2.4" filter="url(#soft)">
              <path d="M100 100 46 46M100 100l54-54M100 100 46 154M100 100l54 54" />
            </g>

            <g stroke="rgba(103, 232, 249, 0.85)" stroke-width="2">
              <circle cx="46" cy="46" r="22" stroke-dasharray="7 6" class="ring ring--r2" />
              <circle cx="154" cy="46" r="22" stroke-dasharray="7 6" class="ring" />
              <circle cx="46" cy="154" r="22" stroke-dasharray="7 6" class="ring" />
              <circle cx="154" cy="154" r="22" stroke-dasharray="7 6" class="ring ring--r2" />
            </g>
            <g stroke="rgba(103, 232, 249, 0.3)" stroke-width="1">
              <circle cx="46" cy="46" r="11" /><circle cx="154" cy="46" r="11" />
              <circle cx="46" cy="154" r="11" /><circle cx="154" cy="154" r="11" />
            </g>

            <g filter="url(#soft)">
              <rect x="80" y="80" width="40" height="40" rx="13"
                    fill="rgba(10, 30, 58, 0.9)" stroke="url(#armGrad)" stroke-width="2.2" />
              <circle cx="100" cy="100" r="7" fill="#67e8f9" class="drone__led" filter="url(#soft)" />
            </g>
          </svg>
        </div>
      </section>

      <section ref="panelRef" class="panel">
        <div class="ai-badge"><i></i>AI 智能巡检引擎已就绪</div>

        <h2>欢迎回来</h2>
        <p class="panel__sub">请登录以进入机队管控中心</p>

        <form @submit.prevent="handleSubmit">
          <div class="field">
            <label for="username">账号</label>
            <div class="control">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
                <circle cx="12" cy="8" r="3.6" />
                <path d="M4.8 20c0-3.7 3.3-6.2 7.2-6.2s7.2 2.5 7.2 6.2" />
              </svg>
              <input
                id="username"
                v-model="form.username"
                type="text"
                placeholder="请输入用户名"
                autocomplete="username"
              />
            </div>
          </div>

          <div class="field">
            <label for="password">密码</label>
            <div class="control">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
                <rect x="4.5" y="10" width="15" height="10" rx="3" />
                <path d="M8 10V7.4a4 4 0 0 1 8 0V10" />
              </svg>
              <input
                id="password"
                v-model="form.password"
                :type="showPassword ? 'text' : 'password'"
                placeholder="请输入密码"
                autocomplete="current-password"
              />
              <button
                type="button"
                class="toggle-eye"
                :class="{ 'toggle-eye--on': showPassword }"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
                  <path d="M2.6 12S6.2 5.8 12 5.8 21.4 12 21.4 12 17.8 18.2 12 18.2 2.6 12 2.6 12Z" />
                  <circle cx="12" cy="12" r="2.9" />
                </svg>
              </button>
            </div>
          </div>

          <div class="row">
            <label class="remember">
              <input v-model="form.remember" type="checkbox" />
              <i></i>
              记住登录状态
            </label>
            <a class="link" href="#">忘记密码？</a>
          </div>

          <button class="submit" type="submit" :data-loading="submitting">
            {{ submitting ? '验 证 中' : '登 录' }}
          </button>
        </form>

        <div class="ai-tip">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
            <path d="M12 3.2 13.8 9l5.8 1.8-5.8 1.8L12 18.4l-1.8-5.8L4.4 10.8 10.2 9 12 3.2Z" />
          </svg>
          <span>检测到安全网络环境，已启用 AI 行为验证。连续 3 次登录失败将自动锁定账号并通知安全中心。</span>
        </div>

        <div class="panel__foot">© 2026 无人机管理平台 · DroneOps v0.0.1</div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.login-page {
  --cyan: #22d3ee;
  --blue: #3b82f6;
  --violet: #8b5cf6;
  --text: #e6f1ff;
  --muted: rgba(230, 241, 255, 0.52);
  --line: rgba(120, 190, 255, 0.14);

  position: relative;
  height: 100%;
  overflow: hidden;
  color: var(--text);
  background: #03060f;
}

/* ---------- 背景层 ---------- */
.backdrop {
  position: fixed;
  inset: 0;
  background:
    radial-gradient(120% 90% at 15% 8%, rgba(34, 211, 238, 0.12), transparent 55%),
    radial-gradient(100% 80% at 88% 92%, rgba(139, 92, 246, 0.14), transparent 55%),
    linear-gradient(160deg, #061024, #03060f 70%);
}

.backdrop::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(var(--line) 1px, transparent 1px),
    linear-gradient(90deg, var(--line) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(90% 70% at 50% 45%, #000 20%, transparent 78%);
  -webkit-mask-image: radial-gradient(90% 70% at 50% 45%, #000 20%, transparent 78%);
  animation: gridDrift 24s linear infinite;
}

@keyframes gridDrift {
  to { background-position: 56px 56px, 56px 56px; }
}

.glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.5;
}

.glow--cyan {
  width: 460px; height: 460px;
  top: -140px; left: -80px;
  background: rgba(34, 211, 238, 0.35);
  animation: float 14s ease-in-out infinite;
}

.glow--violet {
  width: 520px; height: 520px;
  bottom: -200px; right: -120px;
  background: rgba(139, 92, 246, 0.3);
  animation: float 18s ease-in-out infinite reverse;
}

@keyframes float {
  50% { transform: translate3d(30px, -24px, 0) scale(1.08); }
}

.scan {
  position: absolute;
  left: 0; right: 0; height: 180px;
  background: linear-gradient(180deg, transparent, rgba(34, 211, 238, 0.06) 45%, transparent);
  animation: scanMove 9s ease-in-out infinite;
}

@keyframes scanMove {
  0%, 100% { top: -20%; opacity: 0; }
  45% { opacity: 1; }
  50% { top: 90%; opacity: 0.9; }
}

.particle {
  position: absolute;
  border-radius: 50%;
  background: rgba(34, 211, 238, 0.85);
  box-shadow: 0 0 6px rgba(34, 211, 238, 0.9);
  animation-name: rise;
  animation-timing-function: linear;
  animation-iteration-count: infinite;
}

@keyframes rise {
  from { transform: translateY(0); opacity: 0; }
  15% { opacity: 1; }
  85% { opacity: 1; }
  to { transform: translateY(-110vh); opacity: 0; }
}

/* ---------- 布局 ---------- */
.shell {
  position: relative;
  z-index: 2;
  height: 100%;
  display: grid;
  grid-template-columns: 1.15fr 460px;
  align-items: center;
  gap: 56px;
  max-width: 1320px;
  margin: 0 auto;
  padding: 48px 56px;
}

/* ---------- 左侧品牌区 ---------- */
.brand { display: flex; flex-direction: column; gap: 34px; }

.brand__logo { display: flex; align-items: center; gap: 14px; }

.logo-mark {
  width: 46px; height: 46px;
  display: grid; place-items: center;
  border-radius: 13px;
  background: linear-gradient(140deg, rgba(34, 211, 238, 0.22), rgba(139, 92, 246, 0.22));
  border: 1px solid rgba(34, 211, 238, 0.45);
  box-shadow: 0 0 26px rgba(34, 211, 238, 0.32), inset 0 0 18px rgba(34, 211, 238, 0.14);
}

.logo-mark svg { width: 26px; height: 26px; }

.logo-text strong {
  display: block;
  font-size: 17px;
  letter-spacing: 2px;
  font-weight: 600;
}

.logo-text span {
  font-size: 10px;
  letter-spacing: 3.4px;
  color: var(--muted);
  text-transform: uppercase;
}

.brand__title {
  font-size: 44px;
  line-height: 1.24;
  font-weight: 700;
  letter-spacing: 1px;
}

.brand__title em {
  font-style: normal;
  background: linear-gradient(100deg, var(--cyan), var(--blue) 55%, var(--violet));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.brand__desc {
  max-width: 460px;
  font-size: 14px;
  line-height: 1.9;
  color: var(--muted);
}

.metrics { display: flex; gap: 14px; flex-wrap: wrap; }

.metric {
  flex: 1;
  min-width: 138px;
  padding: 16px 18px;
  border-radius: 14px;
  background: rgba(10, 24, 48, 0.5);
  border: 1px solid var(--line);
  backdrop-filter: blur(8px);
  transition: border-color 0.3s, transform 0.3s;
}

.metric:hover { border-color: rgba(34, 211, 238, 0.5); transform: translateY(-3px); }

.metric b {
  display: block;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 0.5px;
  background: linear-gradient(100deg, #7dd3fc, var(--cyan));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.metric span { font-size: 12px; color: var(--muted); letter-spacing: 0.6px; }

.drone-stage {
  position: relative;
  height: 190px;
  display: grid;
  place-items: center;
}

.radar {
  position: absolute;
  width: 190px; height: 190px;
  border-radius: 50%;
  border: 1px solid rgba(34, 211, 238, 0.22);
}

.radar::before,
.radar::after {
  content: '';
  position: absolute;
  border-radius: 50%;
  border: 1px solid rgba(34, 211, 238, 0.16);
}

.radar::before { inset: 28px; }
.radar::after { inset: 56px; }

.radar__sweep {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: conic-gradient(from 0deg, rgba(34, 211, 238, 0.34), transparent 26%);
  animation: sweep 4s linear infinite;
}

@keyframes sweep { to { transform: rotate(360deg); } }

.drone { position: relative; width: 150px; height: 150px; }
.drone .ring { transform-origin: center; animation: spin 3.4s linear infinite; }
.drone .ring--r { animation-direction: reverse; animation-duration: 4.6s; }
.drone .ring--r2 { animation-duration: 2.6s; }

@keyframes spin { to { transform: rotate(360deg); } }

.drone__led { animation: blink 1.8s ease-in-out infinite; }

@keyframes blink { 50% { opacity: 0.15; } }

/* ---------- 右侧登录卡片 ---------- */
.panel {
  position: relative;
  padding: 40px 38px 34px;
  border-radius: 22px;
  background: rgba(10, 22, 44, 0.62);
  border: 1px solid rgba(120, 190, 255, 0.2);
  backdrop-filter: blur(22px);
  -webkit-backdrop-filter: blur(22px);
  box-shadow:
    0 30px 70px rgba(0, 0, 0, 0.55),
    inset 0 1px 0 rgba(255, 255, 255, 0.06);
  animation: panelIn 0.7s cubic-bezier(0.22, 1, 0.36, 1);
}

@keyframes panelIn {
  from { opacity: 0; transform: translateY(22px); }
}

.panel::before,
.panel::after {
  content: '';
  position: absolute;
  width: 22px; height: 22px;
  border: 2px solid rgba(34, 211, 238, 0.6);
}

.panel::before {
  top: -1px; left: -1px;
  border-right: 0; border-bottom: 0;
  border-radius: 22px 0 0 0;
}

.panel::after {
  bottom: -1px; right: -1px;
  border-left: 0; border-top: 0;
  border-radius: 0 0 22px 0;
}

.ai-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 5px 12px;
  margin-bottom: 20px;
  border-radius: 999px;
  font-size: 11px;
  letter-spacing: 0.8px;
  color: #a5f3fc;
  background: rgba(34, 211, 238, 0.1);
  border: 1px solid rgba(34, 211, 238, 0.32);
}

.ai-badge i {
  width: 6px; height: 6px;
  border-radius: 50%;
  background: var(--cyan);
  box-shadow: 0 0 8px var(--cyan);
  animation: blink 1.6s ease-in-out infinite;
}

.panel h2 { font-size: 25px; font-weight: 600; letter-spacing: 0.6px; }

.panel__sub {
  margin: 9px 0 30px;
  font-size: 13px;
  color: var(--muted);
}

.field { margin-bottom: 18px; }

.field label {
  display: block;
  margin-bottom: 9px;
  font-size: 12px;
  letter-spacing: 0.8px;
  color: rgba(230, 241, 255, 0.72);
}

.control {
  position: relative;
  display: flex;
  align-items: center;
  border-radius: 11px;
  background: rgba(4, 12, 28, 0.72);
  border: 1px solid rgba(120, 190, 255, 0.18);
  transition: border-color 0.25s, box-shadow 0.25s, background 0.25s;
}

.control:focus-within {
  border-color: rgba(34, 211, 238, 0.75);
  background: rgba(6, 18, 38, 0.85);
  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.13), 0 0 22px rgba(34, 211, 238, 0.2);
}

.control > svg {
  width: 18px; height: 18px;
  margin-left: 14px;
  flex: none;
  color: rgba(140, 190, 240, 0.75);
  transition: color 0.25s;
}

.control:focus-within > svg { color: var(--cyan); }

.control input {
  flex: 1;
  width: 100%;
  padding: 14px 14px 14px 11px;
  font-size: 14px;
  font-family: inherit;
  color: var(--text);
  background: none;
  border: 0;
  outline: none;
}

.control input::placeholder { color: rgba(230, 241, 255, 0.3); }

.toggle-eye {
  display: grid;
  place-items: center;
  width: 42px;
  align-self: stretch;
  color: rgba(140, 190, 240, 0.7);
  background: none;
  border: 0;
  cursor: pointer;
  transition: color 0.2s;
}

.toggle-eye:hover,
.toggle-eye--on { color: var(--cyan); }

.toggle-eye svg { width: 17px; height: 17px; }

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 4px 0 26px;
  font-size: 12.5px;
}

.remember {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  cursor: pointer;
  user-select: none;
}

.remember input { display: none; }

.remember i {
  width: 15px; height: 15px;
  border-radius: 4px;
  border: 1px solid rgba(120, 190, 255, 0.4);
  display: grid; place-items: center;
  transition: all 0.2s;
}

.remember input:checked + i {
  background: linear-gradient(140deg, var(--cyan), var(--blue));
  border-color: transparent;
  box-shadow: 0 0 12px rgba(34, 211, 238, 0.55);
}

.remember input:checked + i::after {
  content: '';
  width: 7px; height: 4px;
  border-left: 1.6px solid #041020;
  border-bottom: 1.6px solid #041020;
  transform: rotate(-45deg) translateY(-1px);
}

.link {
  color: rgba(140, 200, 255, 0.85);
  text-decoration: none;
  transition: color 0.2s;
}

.link:hover { color: var(--cyan); }

.submit {
  position: relative;
  width: 100%;
  padding: 15px;
  font-size: 15px;
  font-family: inherit;
  font-weight: 600;
  letter-spacing: 5px;
  text-indent: 5px;
  color: #03121f;
  background: linear-gradient(120deg, var(--cyan), var(--blue) 62%, var(--violet));
  border: 0;
  border-radius: 11px;
  cursor: pointer;
  overflow: hidden;
  box-shadow: 0 10px 30px rgba(34, 211, 238, 0.28);
  transition: transform 0.25s, box-shadow 0.25s, filter 0.25s;
}

.submit:hover {
  transform: translateY(-2px);
  box-shadow: 0 16px 38px rgba(34, 211, 238, 0.42);
  filter: brightness(1.06);
}

.submit:active { transform: translateY(0); }

.submit::after {
  content: '';
  position: absolute;
  top: 0; left: -60%;
  width: 45%; height: 100%;
  background: linear-gradient(100deg, transparent, rgba(255, 255, 255, 0.55), transparent);
  transform: skewX(-22deg);
  animation: shine 3.4s ease-in-out infinite;
}

@keyframes shine {
  0%, 60% { left: -60%; }
  100% { left: 130%; }
}

.submit[data-loading='true'] { pointer-events: none; opacity: 0.82; }

.ai-tip {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  margin-top: 22px;
  padding: 13px 15px;
  border-radius: 11px;
  font-size: 12px;
  line-height: 1.7;
  color: rgba(190, 225, 255, 0.78);
  background: linear-gradient(100deg, rgba(34, 211, 238, 0.08), rgba(139, 92, 246, 0.08));
  border-left: 2px solid rgba(34, 211, 238, 0.65);
}

.ai-tip svg {
  width: 15px; height: 15px;
  margin-top: 2px;
  flex: none;
  color: var(--cyan);
}

.panel__foot {
  margin-top: 26px;
  padding-top: 18px;
  border-top: 1px solid rgba(120, 190, 255, 0.12);
  text-align: center;
  font-size: 11.5px;
  letter-spacing: 0.5px;
  color: rgba(230, 241, 255, 0.32);
}

/* 入场错峰 */
.brand__logo,
.brand__title,
.brand__desc,
.metrics,
.drone-stage {
  animation: fadeUp 0.7s ease both;
}

.brand__title { animation-delay: 0.08s; }
.brand__desc { animation-delay: 0.16s; }
.metrics { animation-delay: 0.24s; }
.drone-stage { animation-delay: 0.32s; }

@keyframes fadeUp {
  from { opacity: 0; transform: translateY(16px); }
}

/* ---------- 响应式 ---------- */
@media (max-width: 1080px) {
  .login-page { overflow-y: auto; }
  .shell {
    grid-template-columns: 1fr;
    gap: 34px;
    padding: 40px 28px;
    height: auto;
    min-height: 100%;
  }
  .drone-stage { display: none; }
  .brand__title { font-size: 34px; }
}

@media (max-width: 560px) {
  .panel { padding: 30px 22px 26px; }
  .brand__title { font-size: 27px; }
  .metrics { gap: 10px; }
  .metric { min-width: 0; padding: 12px 14px; }
  .metric b { font-size: 19px; }
}
</style>
