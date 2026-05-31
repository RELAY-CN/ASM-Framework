/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.replace

/**
 * 重定向替换接口。
 *
 * 该接口是 `@Redirect` 与全方法替换链路的运行期调用契约。转换器会把原调用点的对象、描述符、
 * 返回类型与参数数组传入实现，并使用返回值替代原调用返回值。
 *
 * ## 调用边界
 *
 * - [desc] 使用框架内部调用点描述符，普通调用通常形如 `Lowner;name(desc)return`
 * - [type] 是原调用点返回类型；基础类型会以对应 Java primitive [Class] 传入
 * - [args] 按原调用参数顺序传入，不包含实例方法 receiver
 *
 * 实现方需要保证返回值能赋给 [type] 对应的目标类型；`void` 调用可返回 `null`。
 * 替换实现抛出的异常会沿目标方法调用链向外传播，不会被框架吞掉。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
fun interface RedirectionReplace {
    /**
     * 执行替换调用。
     *
     * @param obj 调用所属对象；静态调用场景下可能为占位对象
     * @param desc 调用点描述符，格式为 `Lowner;name(desc)return`
     * @param type 原调用返回类型
     * @param args 原调用参数，按调用栈顺序传入
     * @return 替换后的返回值；void 调用可返回 `null`
     * @throws Throwable 替换逻辑执行失败时透出给调用方
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @Throws(Throwable::class)
    operator fun invoke(
        obj: Any,
        desc: String,
        type: Class<*>,
        vararg args: Any?,
    ): Any?

    companion object {
        /**
         * 创建固定返回值替换器。
         *
         * 该替换器不检查 [value] 与目标返回类型是否兼容，调用方需要保证它能赋给调用点的返回类型。
         *
         * @param value 固定返回值
         * @return 总是返回 [value] 的替换器
         *
         * @author Dr (dr@der.kim)
         * @date 2025-11-24
         */
        fun of(value: Any?): RedirectionReplace = RedirectionReplace { _: Any, _: String, _: Class<*>, _: Array<out Any?> -> value }

        /**
         * 类型转换重定向描述符前缀。
         *
         * 以该前缀开头的描述符会被默认管理器视为类型转换替换，并走专用 cast fallback。
         * 该值会出现在生成后的调用点描述符中，属于框架内部协议的一部分。
         *
         * @author Dr (dr@der.kim)
         * @date 2026-05-31
         */
        const val CAST_PREFIX = "<cast> "

        /**
         * 替换器桥接方法名。
         *
         * 转换器生成字节码时会按该名称调用 [RedirectionReplaceApi.invoke]，不要在不同步更新注入器的情况下修改。
         *
         * @author Dr (dr@der.kim)
         * @date 2026-05-31
         */
        const val METHOD_NAME = "invoke"

        /**
         * 忽略模式替换器桥接方法名。
         *
         * 转换器生成忽略模式字节码时会按该名称调用 [RedirectionReplaceApi.invokeIgnore]。
         * 该入口主要服务全方法替换链路，用于跳过普通用户替换分派。
         *
         * @author Dr (dr@der.kim)
         * @date 2026-05-31
         */
        const val METHOD_SPACE_NAME = "invokeIgnore"

        /**
         * 替换器桥接方法描述符。
         *
         * 该描述符是运行期 ABI 的一部分，必须与 [RedirectionReplaceApi.invoke] 和 [RedirectionReplaceApi.invokeIgnore]
         * 的 JVM 签名保持一致。
         *
         * @author Dr (dr@der.kim)
         * @date 2026-05-31
         */
        const val METHOD_DESC = "(Ljava/lang/Object;Ljava/lang/String;" + "Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;"
    }
}
