package cn.tedu.drone;

import cn.tedu.drone.common.GeoDistance;
import cn.tedu.drone.zone.geo.ZoneGeometry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * 禁飞区几何判定的单元测试 —— <b>纯计算，不起 Spring，也不连库</b>。
 *
 * <h2>为什么这部分一定要有单测，而不是只测接口</h2>
 * 几何判定的错法都很安静：射线法写成「数错了边」，凹多边形会被当成凸的；
 * 外接矩形换算写错 {@code cos}，圆边上的点会被漏掉。这些错<b>都不报错</b>，
 * 只是偶尔不拦或者偶尔误拦 —— 靠接口测试很难覆盖到边界上那几个点。
 *
 * <p>所以这里测的是「性质」，不是「某个样例的输出值」：
 * 比如「外接矩形绝不能拒绝一个确实在区内的点」用几百个网格点去撞，
 * 换一种实现照样能过 —— 那才是真正要守住的东西。
 */
class ZoneGeometryTests {

    /** 北京一带的一个正方形区域（边长约 0.01 度 ≈ 850 米 × 1110 米） */
    private static final List<double[]> SQUARE = List.of(
            new double[]{116.400, 39.900},
            new double[]{116.410, 39.900},
            new double[]{116.410, 39.910},
            new double[]{116.400, 39.910});

    /**
     * L 形区域：右下角那块是「缺口」。
     * 底下一横条（纬度 39.900~39.905，经度 116.400~116.410）
     * 加上左边一竖条（经度 116.400~116.405，纬度 39.905~39.910）。
     */
    private static final List<double[]> L_SHAPE = List.of(
            new double[]{116.400, 39.900},
            new double[]{116.410, 39.900},
            new double[]{116.410, 39.905},
            new double[]{116.405, 39.905},
            new double[]{116.405, 39.910},
            new double[]{116.400, 39.910});

    @Test
    void ringShouldTellInsideFromOutside() {
        assertThat(ZoneGeometry.inRing(116.405, 39.905, SQUARE)).as("正中心").isTrue();
        assertThat(ZoneGeometry.inRing(116.4005, 39.9005, SQUARE)).as("贴着西南角、但在里面").isTrue();
        assertThat(ZoneGeometry.inRing(116.399, 39.905, SQUARE)).as("西边外面").isFalse();
        assertThat(ZoneGeometry.inRing(116.405, 39.911, SQUARE)).as("北边外面").isFalse();
        assertThat(ZoneGeometry.inRing(116.411, 39.911, SQUARE)).as("东北角外面").isFalse();
    }

    /**
     * 凹多边形：缺口里的点必须判成<b>外面</b>。
     *
     * <p>这条用例专门防「图省事用外接矩形或凸包」—— 那样缺口会被判成禁飞区内部，
     * 而更危险的反向错误（把凹进去的部分当成可以飞）也是同一类问题。
     */
    @Test
    void ringShouldHandleConcaveShape() {
        assertThat(ZoneGeometry.inRing(116.4075, 39.9075, L_SHAPE)).as("缺口里（外面）").isFalse();
        assertThat(ZoneGeometry.inRing(116.4025, 39.9075, L_SHAPE)).as("左边那竖条里").isTrue();
        assertThat(ZoneGeometry.inRing(116.4075, 39.9025, L_SHAPE)).as("底下那横条里").isTrue();

        // 如果按外接矩形判，缺口里这个点会被误判成「在区内」——
        // 所以顺带断言一下矩形确实包含它，证明上面那条不是白测的
        ZoneGeometry.Bounds bounds = ZoneGeometry.boundsOfRing(L_SHAPE);
        assertThat(bounds).isNotNull();
        assertThat(bounds.contains(116.4075, 39.9075)).as("外接矩形确实包含缺口里的点").isTrue();
    }

    @Test
    void ringShouldRejectDegenerateShapes() {
        assertThat(ZoneGeometry.boundsOfRing(List.of())).isNull();
        assertThat(ZoneGeometry.boundsOfRing(List.of(new double[]{116.40, 39.90}))).isNull();
        assertThat(ZoneGeometry.inRing(116.40, 39.90, List.of())).isFalse();
        assertThat(ZoneGeometry.boundsOfRing(null)).isNull();
    }

    @Test
    void boundsShouldContainEveryVertex() {
        ZoneGeometry.Bounds bounds = ZoneGeometry.boundsOfRing(SQUARE);
        assertThat(bounds).isNotNull();
        for (double[] p : SQUARE) {
            assertThat(bounds.contains(p[0], p[1])).as("顶点 " + p[0] + "," + p[1]).isTrue();
        }
    }

    /**
     * 外接矩形必须是「宁可放过、不可错杀」：<b>任何确实在区内的点，都必须能通过预筛</b>。
     *
     * <p>用网格扫一遍来验，而不是挑几个点 —— 挑点只能证明挑到的那几个没问题。
     * 一旦有人把预筛改成「比真实范围小」（比如圆的经度跨度忘了乘 cos），
     * 这条会立刻红，而且能报出是哪个点被错杀了。
     */
    @Test
    void boundsShouldNeverRejectAPointThatIsInside() {
        assertBoundsNeverRejectsPoints(SQUARE, ZoneGeometry.boundsOfRing(SQUARE));
        assertBoundsNeverRejectsPoints(L_SHAPE, ZoneGeometry.boundsOfRing(L_SHAPE));
    }

    private void assertBoundsNeverRejectsPoints(List<double[]> ring, ZoneGeometry.Bounds bounds) {
        assertThat(bounds).isNotNull();
        int checked = 0;
        for (int i = 0; i <= 60; i++) {
            for (int j = 0; j <= 60; j++) {
                double lng = 116.395 + i * 0.00025;
                double lat = 39.895 + j * 0.00025;
                if (ZoneGeometry.inRing(lng, lat, ring)) {
                    checked++;
                    assertThat(bounds.contains(lng, lat))
                            .as("点在区内却被预筛错杀：" + lng + "," + lat)
                            .isTrue();
                }
            }
        }
        // 网格里总得有一些点真的落在区内，否则这条用例等于没跑
        assertThat(checked).as("落在区内的网格点数").isGreaterThan(50);
    }

    @Test
    void circleShouldUseRealDistanceNotDegrees() {
        double lng = 116.405;
        double lat = 39.905;
        double radius = 1000;

        // 正北 800 米（纬度 +0.0072 ≈ 796 米）→ 在圈内
        assertThat(ZoneGeometry.inCircle(lng, lat + 800 / 110540.0, lng, lat, radius)).isTrue();
        // 正北 1200 米 → 在圈外
        assertThat(ZoneGeometry.inCircle(lng, lat + 1200 / 110540.0, lng, lat, radius)).isFalse();
        // 正东 800 米（经度要按 cos 折算）→ 在圈内；这条同时证明东西方向也用了真实距离
        double dLng800 = 800 / (GeoDistance.METERS_PER_DEGREE_LNG * Math.cos(Math.toRadians(lat)));
        assertThat(ZoneGeometry.inCircle(lng + dLng800, lat, lng, lat, radius)).isTrue();
    }

    /**
     * 圆的外接矩形在高纬度上不能「缩水」。
     *
     * <p>这就是那个最容易写反的地方：一圈是 {@code 111320 × cos(纬度)} 米，
     * 所以同样的米数在纬度 40 度对应<b>更大</b>的度数。
     * 忘了乘 cos（或乘反）会让矩形的经度跨度偏小，圆东西两侧的点被预筛错杀 ——
     * 而且不报错、只是偶尔不拦。
     */
    @Test
    void circleBoundsShouldNotShrinkAtHigherLatitude() {
        double lat = 40.0;
        double radius = 1000;
        ZoneGeometry.Bounds bounds = ZoneGeometry.boundsOfCircle(116.40, lat, radius);

        // 圆最东边那个点，必须落在矩形内
        double eastEdge = 116.40 + radius / (GeoDistance.METERS_PER_DEGREE_LNG * Math.cos(Math.toRadians(lat)));
        assertThat(bounds.contains(eastEdge, lat)).as("圆最东边在矩形内").isTrue();
        assertThat(ZoneGeometry.inCircle(eastEdge, lat, 116.40, lat, radius)).as("它确实是内点（边界含）").isTrue();

        // 而矩形不该大到离谱：在纬度 40 度，1000 米对应的经度跨度约 0.0117 度
        assertThat(bounds.maxLng() - 116.40).isBetween(0.011, 0.013);
        // 纬度方向是固定的 110540
        assertThat(bounds.maxLat() - lat).isCloseTo(radius / 110540.0, offset(1e-9));
    }

    @Test
    void samplePolylineShouldFillLongSegments() {
        List<double[]> line = List.of(
                new double[]{116.400, 39.900},
                new double[]{116.430, 39.900});

        assertThat(GeoDistance.meters(116.400, 39.900, 116.430, 39.900)).as("这段线约有 2.5 公里").isBetween(2000.0, 3000.0);

        List<double[]> sampled = ZoneGeometry.samplePolyline(line, 50.0);
        // 2.5 公里按 50 米切 → 五十上下
        assertThat(sampled.size()).isBetween(40, 70);

        // 首尾保留
        assertThat(sampled.get(0)[0]).isEqualTo(116.400);
        assertThat(sampled.get(sampled.size() - 1)[0]).isEqualTo(116.430);

        // 相邻两个采样点的距离不超过一个步长（这是「没有跳空」的可验证说法）
        for (int i = 1; i < sampled.size(); i++) {
            double d = GeoDistance.meters(sampled.get(i - 1)[0], sampled.get(i - 1)[1],
                    sampled.get(i)[0], sampled.get(i)[1]);
            assertThat(d).as("第 " + i + " 段").isLessThanOrEqualTo(60.0);
        }
    }

    @Test
    void samplePolylineShouldKeepShortSegmentsIntact() {
        List<double[]> line = List.of(
                new double[]{116.400, 39.900},
                new double[]{116.4001, 39.900});
        // 约 8.5 米，比步长小 → 只有原来的两个点
        assertThat(ZoneGeometry.samplePolyline(line, 50.0)).hasSize(2);

        // 少于两个点的折线原样返回（一个点没法插值，但它本身还是要被判定）
        assertThat(ZoneGeometry.samplePolyline(List.of(new double[]{116.4, 39.9}), 50.0)).hasSize(1);
        assertThat(ZoneGeometry.samplePolyline(List.of(), 50.0)).isEmpty();
        assertThat(ZoneGeometry.samplePolyline(null, 50.0)).isEmpty();
    }

    /**
     * 「线穿过区」要被发现 —— 两个端点都在区外，中间那段横穿过去。
     *
     * <p>如果只判端点（很多系统就是这么漏的），这条航线会被放行。
     */
    @Test
    void polylineCrossingShouldBeDetected() {
        List<double[]> line = List.of(
                new double[]{116.395, 39.905},
                new double[]{116.415, 39.905});

        assertThat(ZoneGeometry.inRing(116.395, 39.905, SQUARE)).as("起点在区外").isFalse();
        assertThat(ZoneGeometry.inRing(116.415, 39.905, SQUARE)).as("终点在区外").isFalse();

        boolean crossed = ZoneGeometry.samplePolyline(line, 50.0).stream()
                .anyMatch(p -> ZoneGeometry.inRing(p[0], p[1], SQUARE));
        assertThat(crossed).as("插值之后应当能发现它穿过了").isTrue();
    }
}
