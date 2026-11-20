<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'

onLaunch(() => {
  // 这里不做「有没有 token」的判断 —— 判断放在各自的页面里：
  // 启动页是登录页，任务页自己会在拿到 401 时退回登录，
  // 省掉一次「启动即跳转」的闪烁。
  console.log('飞手端启动')
})
</script>

<style lang="scss">
/**
 * 全局样式。
 *
 * App.vue 的样式不带 scoped，编译后进 app.wxss / 全局样式表，
 * 所以这里是放**跨页面复用**的类的地方（标签、卡片、按钮），
 * 页面私有的布局写在各自页面的 scoped 样式里。
 *
 * 配色值来自 uni.scss 里的 $pilot-* 变量（uni-app 会自动把它注入每个样式块，
 * 不需要手动 import）。
 */

/* ---------- 页面底色：与登录页、后台端同一套深色科技风 ---------- */
page {
  background: linear-gradient(160deg, $pilot-page-from, $pilot-page-to 70%);
  /* 渐变不够高时（真机上 100% 会按视口算，这里兜一层纯色） */
  background-color: $pilot-page-to;
  min-height: 100%;
  color: $pilot-text;
  font-size: 28rpx;
  -webkit-font-smoothing: antialiased;
}

/* ---------- 标签 ---------- */
.tag {
  display: inline-block;
  padding: 2rpx 14rpx;
  border-radius: 999rpx;
  font-size: 22rpx;
  line-height: 1.7;
  white-space: nowrap;
}

.tag--cyan {
  color: $pilot-tag-cyan-fg;
  background: $pilot-tag-cyan-bg;
  border: 1rpx solid $pilot-tag-cyan-bd;
}

.tag--green {
  color: $pilot-tag-green-fg;
  background: $pilot-tag-green-bg;
  border: 1rpx solid $pilot-tag-green-bd;
}

.tag--amber {
  color: $pilot-tag-amber-fg;
  background: $pilot-tag-amber-bg;
  border: 1rpx solid $pilot-tag-amber-bd;
}

.tag--red {
  color: $pilot-tag-red-fg;
  background: $pilot-tag-red-bg;
  border: 1rpx solid $pilot-tag-red-bd;
}

.tag--violet {
  color: $pilot-tag-violet-fg;
  background: $pilot-tag-violet-bg;
  border: 1rpx solid $pilot-tag-violet-bd;
}

.tag--grey {
  color: $pilot-tag-grey-fg;
  background: $pilot-tag-grey-bg;
  border: 1rpx solid $pilot-tag-grey-bd;
}

/* ---------- 卡片 ---------- */
.pilot-card {
  margin: 24rpx;
  padding: 28rpx;
  border-radius: 20rpx;
  background: $pilot-panel;
  border: 1rpx solid $pilot-line;
}

/* ---------- 按钮 ---------- */
.pilot-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 88rpx;
  border-radius: 16rpx;
  font-size: 30rpx;
  border: none;

  /* 去掉小程序 button 默认的边框和背景 */
  &::after {
    border: none;
  }
}

/* 主按钮：青色渐变，和后台端的 btn--primary 一个观感 */
.pilot-btn--primary {
  color: $pilot-btn-fg;
  font-weight: 600;
  background: linear-gradient(100deg, $pilot-cyan, #38bdf8);
}

/* 次要按钮：只描边 */
.pilot-btn--ghost {
  color: $pilot-text-2;
  background: $pilot-ghost-bg;
  border: 1rpx solid $pilot-line-strong;
}

.pilot-btn[disabled] {
  opacity: 0.5;
}

/* ---------- 文本工具类 ---------- */
.pilot-muted {
  color: $pilot-muted;
}

.pilot-code {
  color: $pilot-code;
  font-size: 24rpx;
}
</style>
