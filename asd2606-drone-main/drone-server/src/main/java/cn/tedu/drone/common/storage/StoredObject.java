package cn.tedu.drone.common.storage;

/**
 * 一次存储的结果。
 *
 * <p>为什么不只返回一个 key：这三样东西是在**同一次写盘过程中**顺带算出来的
 * （{@code DigestInputStream} 边写边算摘要），分开再算一遍就要把文件重读一次。
 * 而 {@code size} 与 {@code sha256} 都是要落库的元信息，调用方本来就需要它们。
 *
 * @param key    存储内的 key。本地存储时是相对路径、对象存储时是对象 key ——
 *               两者在业务代码眼里是同一个东西：**给 {@link StorageService} 用来定位文件**。
 *               刻意不叫 path、也不叫 url：URL 会变、会过期，路径是本地时代的概念，
 *               key 才是那个稳定的东西
 * @param size   字节数
 * @param sha256 内容的 SHA-256（十六进制小写）
 */
public record StoredObject(String key, long size, String sha256) {
}
