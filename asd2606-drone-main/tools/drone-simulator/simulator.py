#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
无人机设备模拟器 —— 扮演「厂商侧」，往 EMQX 发遥测、事件与状态。

为什么要它：没有真飞机的时候，联调需要一个**会按节奏持续发数据、还能模拟异常**
的设备。手工用 MQTTX 粘报文只能一条一条发（`docs/MQTT与设备接入.md` 4.13 那 16 条就是
干这个的），做不出「每秒一条持续几分钟」和「突然掉线」这两种最有价值的场景。

设计见 `docs/端到端联调方案.md` 第三章。用法：

    # 正常飞行 60 秒
    python3 tools/drone-simulator/simulator.py --device UAV-001 --duration 60

    # 电量告警：从 20% 开始快速往下掉
    python3 tools/drone-simulator/simulator.py --device UAV-001 --scenario battery-low --battery-start 20

    # 定位丢失 10 秒
    python3 tools/drone-simulator/simulator.py --device UAV-001 --scenario gps-loss

    # 只停发数据、但连接还在（用来验后端的「声明过期」扫描）
    python3 tools/drone-simulator/simulator.py --device UAV-001 --scenario link-lost --link-loss-seconds 30

    # 突然掉线（不发离线声明就死掉，让 broker 代发遗嘱）
    python3 tools/drone-simulator/simulator.py --device UAV-001 --scenario sudden-kill --kill-after 10

    # 设备自己判定出问题：连发 返航 → 任务中断 → 紧急停止 三个事件，然后正常退出
    python3 tools/drone-simulator/simulator.py --device UAV-001 --scenario fault --fault-after 8

    # **飞进禁飞区**：10 秒后抵达目标点，之后一直停在区内（用来验 zone.breach）
    python3 tools/drone-simulator/simulator.py --device UAV-001 --scenario zone-breach \
        --zone-lng 116.3970 --zone-lat 39.9090

    # 飞进「限高 120 米」的区域、而且飞在 150 米：应当报 zone.alt.over（还会顺带报通用的 alt.over）
    python3 tools/drone-simulator/simulator.py --device UAV-001 --scenario zone-breach \
        --zone-lng 116.3970 --zone-lat 39.9090 --cruise-alt 150

**前提**：`--device` 那台设备必须已经在设备档案里登记过，否则后端第 ③ 步查不到、
数据会被丢掉（日志里会有 `【MQTT 处理 ③ 找设备】收到未登记设备的数据，已丢弃`）。

依赖：`paho-mqtt`（本机已装 2.1.0）。**注意 paho 2.x 的 API 与 1.x 不一样** ——
构造客户端时必须显式传 `CallbackAPIVersion`，回调的签名也多了参数。
网上多数教程还是 1.x 的写法，照抄会直接报错。
"""

import argparse
import json
import math
import os
import random
import signal
import sys
import time
from datetime import datetime
from urllib.parse import urlparse

import paho.mqtt.client as mqtt

# ---------------------------------------------------------------------------
# 报文格式约定（与后端 AlertDetector 读的字段一一对应）
# ---------------------------------------------------------------------------
# payload 里的字段名就是设备侧契约。后端**缺字段就跳过那条告警**（不猜、不当 0），
# 所以少报一个字段的后果是「那条告警永远不会触发」，而且不会有任何报错 ——
# 这是对接时最容易踩的坑，务必按这份清单来。
#
#   lng / lat    经纬度            → zone.breach / zone.alt.over（禁飞区判定）
#   battery      电量百分比        → bat.low / bat.critical
#   batteryTemp  电池温度（℃）      → bat.temp.high / bat.temp.critical
#   gpsLevel     定位等级（0 = 丢失）→ gps.weak / gps.lost
#   signal       图传/遥控信号等级   → rc.signal.weak / rc.signal.lost
#   alt          相对起飞点高度（米）→ alt.over / alt.low
#   speed        速度（米/秒）      → speed.over
#   sdFreeGb     可用存储（GB）     → storage.low
#   motorState   电机状态（normal） → motor.fault
#   flightState  飞行状态          → 判断「是否在空中」要看它
#
# flightState 的取值约定（这一条很要紧）：
#   taking_off / landing —— 起降阶段，飞机本来就在地面附近
#   flying / hovering    —— 明确在空中，后端才判高度类告警
# 如果起降阶段也报 flying，后端会在每个架次开头报一条「低于安全高度」——
# 假告警比不报警更糟，因为它会让人对整个告警体系失去信任。

TELEMETRY_QOS = 0   # 高频遥测：丢一帧无所谓，下一帧马上就来
EVENT_QOS = 1       # 事件与状态：比重复更怕丢
EVENT_TOPIC = "event"

# 巡航参数（模拟一条普通的巡检航线）
CRUISE_ALT = 60.0          # 巡航高度（米）
CLIMB_STEP = 12.0          # 每个周期爬升多少米
CRUISE_SPEED = 8.0         # 巡航速度（米/秒）
BASE_LNG, BASE_LAT = 116.397, 39.908

# 电池热模型：从起飞温度向「飞行稳定温度」逼近，而不是一直线性上升。
#
# 一开始写的是 `38 + tick * 0.3`（每秒涨 0.3℃、永不收敛），结果是
# 跑满 60 秒后 38+18=56℃，正好越过后端 55℃ 的阈值 →
# **一次完全正常的飞行被报了一条「电池温度偏高」**。
# 那是我把模拟器写错了，不是后端判错了：真实电池是升温后趋于平稳的。
#
# 现在的模型：温度 = 起飞温度 + (稳定温度 − 起飞温度) × 升温进度，
# 60 秒后约 44℃，离 55℃ 有距离 —— 「正常飞行零告警」这个预期才站得住。
BATTERY_TEMP_START = 32.0    # 起飞时的电池温度（℃）
BATTERY_TEMP_CRUISE = 45.0   # 飞行一段后的稳定温度（℃）
BATTERY_TEMP_TAU = 40.0      # 升温时间常数（秒）：越大升得越慢

# 摘要日志里打哪些字段、怎么显示。
# **这份清单必须覆盖「后端会判定告警的所有字段」**（见文件头的字段约定）——
# 少打一个，那条告警在日志里就成了无头案。
LOG_FIELDS = [
    # 经纬度必须打：禁飞区那条告警就是拿它们判的（见文件头的字段约定）——
    # 不打的话，「飞进禁飞区」这条告警在模拟器日志里就成了无头案
    ("lng", "经度", ""),
    ("lat", "纬度", ""),
    ("battery", "电量", "%"),
    ("batteryTemp", "温度", "℃"),
    ("gpsLevel", "定位", ""),
    ("signal", "信号", ""),
    ("alt", "高度", "m"),
    ("speed", "速度", "m/s"),
    ("sdFreeGb", "存储", "GB"),
    ("motorState", "电机", ""),
    ("flightState", "状态", ""),
]


class Simulator:
    def __init__(self, args):
        self.args = args
        self.device = args.device
        # seq 从「当前时间戳」起算而不是从 1 开始：
        #   ① 设备真实的序号本来就是断开重连后继续递增的
        #   ② 每次跑都是一个新的起点，不会跟上一轮的去重键撞上
        self.seq = int(time.time())

        # 电量与存储只减不增，模拟一次飞行
        self.battery = args.battery_start
        self.sd_free_gb = 12.0

        self.tick = 0
        # 飞进禁飞区那个场景用：目标点与「几秒后抵达」
        self.zone_target = None
        if args.scenario == "zone-breach":
            if args.zone_lng is None or args.zone_lat is None:
                # 没有目标点就没法「飞进去」。直接报错比闷头飞一条不相干的路线强 ——
                # 后者会让人以为「怎么没报警」，其实是压根没飞到
                raise SystemExit("zone-breach 场景必须给 --zone-lng 与 --zone-lat（禁飞区里的一个点），"
                                 "例如：--zone-lng 116.3970 --zone-lat 39.9090")
            # 传进来的是**地图上读到的** GCJ-02，飞的时候要用设备那套 WGS84
            wgs_lng, wgs_lat = gcj_to_wgs84(args.zone_lng, args.zone_lat)
            self.zone_target = (wgs_lng, wgs_lat)
            print(f"目标点：地图坐标 ({args.zone_lng}, {args.zone_lat}) → 上报用坐标 "
                  f"({wgs_lng:.6f}, {wgs_lat:.6f})")
            self.zone_reached_announced = False
        self.gps_lost_announced = False
        self.battery_event_sent = False
        self.fault_emitted = False
        self.took_off = False
        self.landed = False

        self.client = mqtt.Client(
            mqtt.CallbackAPIVersion.VERSION2,   # paho 2.x 必传，1.x 的写法在 2.x 直接报错
            client_id=self.device,             # 设备用**自己的编号**当 clientId（真实设备就是这么做的）
            clean_session=True,
            protocol=mqtt.MQTTv311,
        )
        self.client.on_connect = self._on_connect
        self.client.on_disconnect = self._on_disconnect

    # ------------------------------------------------------------------
    # 连接与遗嘱
    # ------------------------------------------------------------------

    def _status_topic(self):
        return f"drone/{self.device}/status"

    def _event_topic(self, event_type):
        return f"drone/{self.device}/{EVENT_TOPIC}/{event_type}"

    def connect(self):
        """
        连 broker。

        **遗嘱必须在 connect() 之前设好** —— 协议规定它是 CONNECT 报文的一部分，
        连上之后再设是无效的（而且不会有任何报错，只会「遗嘱永远不发」）。

        **遗嘱里的 observedAt 只能是「连接时写的那个时刻」**，这是 MQTT 的固有性质：
        报文内容在 CONNECT 里就定死了，而设备什么时候死是定不下来的。
        所以后端收到遗嘱时，那条记录的 `observedAt` 会偏早（本次实测：连接于 15:10:53、
        实际死亡于 15:11:00，遗嘱里写的还是 15:10:53）。
        判断「设备是什么时候离线的」该看**我们收到的时刻**（`reported_at`），
        而不是遗嘱里那个 `observedAt` —— 这一点在做「离线告警」（实施步骤第四步）时要留意。
        """
        will_payload = self._envelope("status", {"flightState": "offline"})
        self.client.will_set(self._status_topic(), will_payload, qos=EVENT_QOS, retain=True)

        host, port = parse_broker(self.args.broker)
        print(f"连接 broker {host}:{port}，clientId={self.device}，"
              f"keepalive={self.args.keepalive}s，遗嘱=已设置")
        self.client.connect(host, port, keepalive=self.args.keepalive)
        # loop_start 起一个后台线程处理收发与心跳，
        # 主线程就只管按节奏发数据（不然还得自己周期性调 client.loop()）
        self.client.loop_start()

    def _on_connect(self, client, userdata, flags, reason_code, properties=None):
        if reason_code == 0:
            print(f"已连上 broker；下面开始按 {self.args.interval}s 一条的节奏发数据")
            # 上线用**保留消息**：这样后来才订阅的人也能立刻知道「这台设备现在在线」
            self.publish_status("online")
        else:
            print(f"连接被拒绝：{reason_code}", file=sys.stderr)

    def _on_disconnect(self, client, userdata, flags, reason_code, properties=None):
        # 正常退出时会走到这里（我们自己调了 disconnect）
        if reason_code != 0:
            print(f"与 broker 的连接断开（意外）：{reason_code}", file=sys.stderr)

    # ------------------------------------------------------------------
    # 发报文
    # ------------------------------------------------------------------

    def _next_seq(self):
        self.seq += 1
        return self.seq

    @staticmethod
    def _now():
        # 后端认得的格式（`yyyy-MM-dd HH:mm:ss`）。故意用本地时间，
        # 和后端 application.yml 里的 time-zone 保持一致
        return datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    def _envelope(self, msg_type, payload):
        """统一的报文信封：{deviceCode, type, observedAt, seq, payload}"""
        return json.dumps({
            "deviceCode": self.device,
            "type": msg_type,
            "observedAt": self._now(),
            "seq": self._next_seq(),
            "payload": payload,
        }, ensure_ascii=False)

    def publish_telemetry(self, payload):
        raw = self._envelope("position", payload)
        self.client.publish(f"drone/{self.device}/telemetry", raw, qos=TELEMETRY_QOS)
        self._log("遥测", payload, raw)

    def publish_event(self, event_type, payload=None):
        raw = self._envelope(event_type, payload or {})
        self.client.publish(self._event_topic(event_type), raw, qos=EVENT_QOS)
        self._log(f"事件/{event_type}", payload or {}, raw)

    def publish_status(self, state):
        raw = self._envelope("status", {"flightState": state})
        # 状态是**状态**而不是事件：用保留消息，让新订阅者立刻知道当前状态
        self.client.publish(self._status_topic(), raw, qos=EVENT_QOS, retain=True)
        self._log(f"状态/{state}", {"flightState": state}, raw)

    def _log(self, kind, payload, raw):
        stamp = datetime.now().strftime("%H:%M:%S")
        if self.args.verbose:
            print(f"[{stamp}] {kind:16s} {raw}")
            return

        # 默认打「所有可能触发告警的字段」，一条不漏。
        #
        # 原来只挑了电量/高度/定位/信号/速度/状态，**漏了电池温度**——
        # 结果一次实跑里后端报出「电池温度 55.1℃ 超过阈值」，
        # 而模拟器日志里根本没有温度这一项，看日志完全对不上是哪条数据触发的。
        # 教训：摘要日志的字段清单必须和「后端会判定告警的字段」一一对应，
        # 少打一个就等于把那条告警的线索藏起来了。
        #
        # 字段顺序固定（不按字典顺序），方便人眼一列一列地对比前后两条
        brief = []
        for field, label, unit in LOG_FIELDS:
            if field not in payload:
                continue
            value = payload[field]
            if isinstance(value, float):
                # 整数就不显示小数点，省得「电量=100.0%」这种看着别扭
                value = int(value) if value == int(value) else value
            brief.append(f"{label}={value}{unit}")
        print(f"[{stamp}] seq={self.seq:<6d} {kind:16s} {' '.join(brief)}")

    # ------------------------------------------------------------------
    # 一条遥测长什么样（按场景改几个字段）
    # ------------------------------------------------------------------

    def position(self):
        """
        当前经纬度。

        <h2>两种走法</h2>
        <ul>
          <li><b>normal 及其它场景</b>：从起飞点往东北方向匀速飞 ——
              一条普通的巡检航线，飞出去才有轨迹可看</li>
          <li><b>zone-breach</b>：朝<b>目标点</b>直线飞，<b>--zone-reach-after</b> 秒后抵达，
              然后一直停在那儿（加一点点抖动，像悬停）。
              之所以要「到了就停」：禁飞区告警靠「同一设备同一编码还有未处理的就不再产生」
              抑制重复，停在区内正好能验证它 —— 一秒一条遥测，但只该有一条待处理的告警</li>
        </ul>
        """
        if self.zone_target is None:
            return BASE_LNG + self.tick * 0.0002, BASE_LAT + self.tick * 0.0001

        target_lng, target_lat = self.zone_target
        reach_after = max(1, self.args.zone_reach_after)
        # 进度按 tick 线性推进：1.0 之后一直保持 1.0（= 停在目标点）
        progress = min(1.0, self.tick / reach_after)
        lng = BASE_LNG + (target_lng - BASE_LNG) * progress
        lat = BASE_LAT + (target_lat - BASE_LAT) * progress

        if progress >= 1.0:
            # 悬停抖动：真实的 GPS 不会给出一个一动不动的点，
            # 而且轨迹图上一个光点也看不出「它停在这儿」
            lng += random.uniform(-1.5e-5, 1.5e-5)
            lat += random.uniform(-1.5e-5, 1.5e-5)
            if not self.zone_reached_announced:
                self.zone_reached_announced = True
                print(f"\n>>> 已抵达目标区域（{target_lng}, {target_lat}）—— 此后一直停在区内。"
                      f"后端应当在抵达那一刻产生一条 zone.breach（若该区域是限高区、"
                      f"且巡航高度超过限高，则是 zone.alt.over）\n")
        return lng, lat

    def build_payload(self):
        self.tick += 1

        # 爬升阶段：高度从 0 涨到巡航高度。**在离地之前报 taking_off 而不是 flying**，
        # 否则后端会在这个阶段判出「低于安全高度」（见文件头的字段约定）
        if not self.took_off:
            alt = min(self.args.cruise_alt, self.tick * CLIMB_STEP)
            flight_state = "taking_off" if alt < 5 else "flying"
            if alt >= 5:
                self.took_off = True
        else:
            alt = self.args.cruise_alt + random.uniform(-2.0, 2.0)
            flight_state = "flying"

        # 电量：正常慢降，battery-low 场景快降
        drain = 0.4 if self.args.scenario == "battery-low" else 0.05
        self.battery = max(0.0, self.battery - drain)
        self.sd_free_gb = max(0.0, self.sd_free_gb - 0.01)

        lng, lat = self.position()
        payload = {
            "lng": round(lng, 6),
            "lat": round(lat, 6),
            "alt": round(alt, 1),
            "speed": round(CRUISE_SPEED + random.uniform(-0.5, 0.5), 1),
            "battery": round(self.battery, 1),
            # 电池温度：向稳定温度逼近（见文件头热模型的说明）。
            # 起飞前几秒升温快、之后趋平，符合真实电池的表现
            "batteryTemp": round(
                BATTERY_TEMP_START
                + (BATTERY_TEMP_CRUISE - BATTERY_TEMP_START) * (1 - math.exp(-self.tick / BATTERY_TEMP_TAU)),
                1),
            "gpsLevel": 4,
            "signal": 4,
            "sdFreeGb": round(self.sd_free_gb, 2),
            "motorState": "normal",
            "flightState": flight_state,
        }

        # ---- 场景：定位丢失 ----
        if self.args.scenario == "gps-loss" and self.args.gps_loss_after <= self.tick \
                <= self.args.gps_loss_after + self.args.gps_loss_seconds:
            payload["gpsLevel"] = 0
            if not self.gps_lost_announced:
                self.gps_lost_announced = True
                print(f"  ── 场景：定位丢失 {self.args.gps_loss_seconds} 秒 ──")

        # ---- 场景：电量告警（设备自己也上报一条事件）----
        if self.args.scenario == "battery-low" and self.battery <= 15 and not self.battery_event_sent:
            self.battery_event_sent = True
            self.publish_event("battery-low", {
                "battery": round(self.battery, 1),
                "at": self._now(),
            })

        return payload

    # ------------------------------------------------------------------
    # 主循环
    # ------------------------------------------------------------------

    def run(self):
        self.connect()
        time.sleep(0.5)   # 等连接真正建立（connect 只是把 CONNECT 报文交出去）

        self.publish_event("takeoff", {"at": self._now()})

        started = time.time()
        while True:
            elapsed = time.time() - started

            # ---- 场景：设备自己判定出问题（事件类告警的联调入口）----
            # 三个事件**连发**，是因为它们的严重级别不同（warning → critical → critical），
            # 一次跑完就能同时验证「翻译对了没」和「级别对不对」。
            # 事件类型（rth / mission-interrupt / emergency-stop）在 topic 的最后一段，
            # 与后端 AlertDetector 的 EVENT_CODES 表一一对应。
            if self.args.scenario == "fault" and not self.fault_emitted \
                    and elapsed >= self.args.fault_after:
                self.fault_emitted = True
                print("  ── 场景：设备上报三个问题事件（返航 → 任务中断 → 紧急停止）──")
                self.publish_event("rth", {"reason": "low-battery"})
                self.publish_event("mission-interrupt", {"at": self._now()})
                self.publish_event("emergency-stop", {"at": self._now()})
                # 发完就结束本轮：真实情况里飞机这时已经要落地了。
                # 收尾走的是正常退出路径（补一条 land 与 offline），
                # 所以「紧急停止之后又降落」这一点与真实略有出入 ——
                # 模拟器这么做只是为了把一次联调跑成完整的一条时间线。
                break

            # ---- 场景：突然掉线（不发离线声明就死掉）----
            # 用 os._exit 而不是 sys.exit：后者会走正常的清理流程（包括断开连接），
            # 那样 broker 认为我们是「正常离线」，遗嘱就不会发 —— 这个场景就白测了
            if self.args.scenario == "sudden-kill" and elapsed >= self.args.kill_after:
                print(f"  ── 场景：模拟突然掉线（os._exit，{self.args.keepalive}s 后 broker 会代发遗嘱）──")
                sys.stdout.flush()
                os._exit(1)

            # ---- 场景：只停发数据、连接还在 ----
            link_lost = (self.args.scenario == "link-lost"
                         and self.args.link_loss_after <= self.tick
                         < self.args.link_loss_after + self.args.link_loss_seconds)
            if link_lost:
                if self.args.link_loss_after == self.tick:
                    print(f"  ── 场景：停止发数据 {self.args.link_loss_seconds} 秒（但连接保持）──")
                    self.tick += 1
                time.sleep(self.args.interval)
                continue

            self.publish_telemetry(self.build_payload())
            time.sleep(self.args.interval)

            if self.args.duration > 0 and elapsed >= self.args.duration:
                print(f"  ── 到达 duration={self.args.duration}s，正常结束 ──")
                break

    def shutdown(self):
        """
        正常收尾：先声明离线，再断开。

        **顺序不能反**：先 disconnect 的话，「离线声明」就发不出去了，
        别人只能靠遗嘱或心跳超时来判断 —— 而正常退出本来可以是一条明确的话。
        """
        if self.landed:
            return
        self.landed = True
        try:
            self.publish_event("land", {"at": self._now()})
            self.publish_status("offline")
        except Exception as exc:      # 网络已经断了的话，尽力而为
            print(f"发离线声明失败（忽略）：{exc}", file=sys.stderr)
        self.client.loop_stop()
        self.client.disconnect()


def gcj_to_wgs84(lng, lat):
    """
    GCJ-02 → WGS84（近似反解）。

    <h2>为什么模拟器里要有这个</h2>
    **地图上读到的坐标是 GCJ-02，而设备上报的是 GPS 原始坐标 WGS84**，两者差约 500 米。
    如果不换算，用户照着地图上的区域中心填 `--zone-lng/--zone-lat`，模拟器就会
    飞到那块区域<b>东边 500 米</b>的地方去 —— 然后「怎么没报警？」，
    实际上压根没飞进去。这类「参数填对了却测不出来」最容易让人怀疑功能有问题。

    <p>所以这两个参数按**用户在地图上看到的那种坐标**收，进来就换成 WGS84。
    真机不需要这一步：它报的本来就是 WGS84。

    <p>算法与后端 `CoordinateUtil.gcj02ToWgs84` 一致（同一个公开实现的近似反解）——
    它只影响「模拟器往哪飞」，不影响任何判定，所以这里不做逐位对齐的保证。
    """
    import math
    PI = 3.1415926535897932384626
    A, EE = 6378245.0, 0.00669342162296594323

    # 境外不加密，原样返回（与后端同一条规则）
    if not (73.66 < lng < 135.05 and 3.86 < lat < 53.55):
        return lng, lat

    def _tlat(x, y):
        r = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
        r += (20 * math.sin(6 * x * PI) + 20 * math.sin(2 * x * PI)) * 2 / 3
        r += (20 * math.sin(y * PI) + 40 * math.sin(y / 3 * PI)) * 2 / 3
        r += (160 * math.sin(y / 12 * PI) + 320 * math.sin(y * PI / 30)) * 2 / 3
        return r

    def _tlng(x, y):
        r = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
        r += (20 * math.sin(6 * x * PI) + 20 * math.sin(2 * x * PI)) * 2 / 3
        r += (20 * math.sin(x * PI) + 40 * math.sin(x / 3 * PI)) * 2 / 3
        r += (150 * math.sin(x / 12 * PI) + 300 * math.sin(x / 30 * PI)) * 2 / 3
        return r

    dlat = _tlat(lng - 105, lat - 35)
    dlng = _tlng(lng - 105, lat - 35)
    rad = lat / 180 * PI
    magic = 1 - EE * math.sin(rad) ** 2
    sq = math.sqrt(magic)
    dlat = (dlat * 180) / ((A * (1 - EE)) / (magic * sq) * PI)
    dlng = (dlng * 180) / (A / sq * math.cos(rad) * PI)
    return lng - dlng, lat - dlat


def parse_broker(broker):
    """支持 `mqtt://host:port` 与裸的 `host:port` / `host` 三种写法"""
    if "://" in broker:
        parsed = urlparse(broker)
        return parsed.hostname or "localhost", parsed.port or 1883
    if ":" in broker:
        host, _, port = broker.partition(":")
        return host, int(port)
    return broker, 1883


def parse_args(argv=None):
    p = argparse.ArgumentParser(
        description="无人机设备模拟器：往 MQTT broker 发遥测/事件/状态，用来联调与验证告警",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--broker", default="mqtt://localhost:1883",
                   help="broker 地址，支持 mqtt://host:port 或 host:port（默认 mqtt://localhost:1883）")
    p.add_argument("--device", required=True,
                   help="设备编号，**必须已在设备档案里登记**，否则后端会丢弃（如 UAV-001）")
    p.add_argument("--scenario", default="normal",
                   choices=["normal", "battery-low", "gps-loss", "link-lost", "sudden-kill", "fault",
                            "zone-breach"],
                   help="场景（默认 normal）。fault = 连发返航/任务中断/紧急停止三个事件；"
                        "zone-breach = 飞进指定禁飞区并停在区内")
    p.add_argument("--interval", type=float, default=1.0, help="遥测发送间隔（秒，默认 1.0）")
    p.add_argument("--duration", type=float, default=0,
                   help="发多久后正常退出（秒，0 = 一直发，默认 0）")
    p.add_argument("--battery-start", type=float, default=100.0, help="起始电量百分比（默认 100）")
    p.add_argument("--keepalive", type=int, default=5,
                   help="心跳周期（秒，默认 5）。**刻意设小**：遗嘱要等约 1.5 倍 keepalive "
                        "才由 broker 代发，用默认的 60 秒要等一分半，联调没法看")
    p.add_argument("--gps-loss-after", type=int, default=3, help="第几个周期开始定位丢失（默认 3）")
    p.add_argument("--gps-loss-seconds", type=int, default=10, help="定位丢失持续几个周期（默认 10）")
    p.add_argument("--link-loss-after", type=int, default=3, help="第几个周期开始停发数据（默认 3）")
    p.add_argument("--link-loss-seconds", type=int, default=30, help="停发数据持续几个周期（默认 30）")
    p.add_argument("--kill-after", type=float, default=10, help="sudden-kill 场景跑多少秒后强杀（默认 10）")
    p.add_argument("--fault-after", type=float, default=8, help="fault 场景跑多少秒后连发问题事件（默认 8）")
    p.add_argument("--cruise-alt", type=float, default=CRUISE_ALT,
                   help=f"巡航高度（米，默认 {int(CRUISE_ALT)}）。想验「限高区里超高」就调大它，"
                        "比如区域限高 120 米时传 150")
    p.add_argument("--zone-lng", type=float, default=None,
                   help="zone-breach 场景的目标经度：**禁飞区里的一个点，填地图上读到的那个坐标**"
                        "（GCJ-02；模拟器会自己换算成设备上报用的 WGS84）")
    p.add_argument("--zone-lat", type=float, default=None,
                   help="zone-breach 场景的目标纬度（同上，填地图上读到的）")
    p.add_argument("--zone-reach-after", type=float, default=10,
                   help="zone-breach 场景：起飞后几秒抵达目标点（默认 10），之后停在区内")
    p.add_argument("--verbose", action="store_true", help="每条报文都打完整 JSON（默认只打摘要）")
    return p.parse_args(argv)


def main():
    args = parse_args()
    sim = Simulator(args)

    # Ctrl-C 要当成「正常退出」处理：走到 shutdown 里那条
    # 「先声明离线、再断开」的路径，而不是让进程直接死掉
    def on_sigint(signum, frame):
        print("\n收到中断，正在正常收尾（发离线声明并断开）…")
        sim.shutdown()
        sys.exit(0)

    signal.signal(signal.SIGINT, on_sigint)

    try:
        sim.run()
    finally:
        sim.shutdown()
        print("已退出")


if __name__ == "__main__":
    main()
