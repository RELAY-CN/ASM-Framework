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
 * 实现方需要保证返回值能赋给 `type` 对应的目标类型；异常会沿目标方法调用链向外传播。
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
         * @param value 固定返回值
         * @return 总是返回 [value] 的替换器
         *
         * @author Dr (dr@der.kim)
         * @date 2025-11-24
         */
        fun of(value: Any?): RedirectionReplace = RedirectionReplace { _: Any, _: String, _: Class<*>, _: Array<out Any?> -> value }

        /**
         * 类型转换重定向描述符前缀。
         */
        const val CAST_PREFIX = "<cast> "

        /**
         * 替换器桥接方法名。
         */
        const val METHOD_NAME = "invoke"

        /**
         * 忽略模式替换器桥接方法名。
         */
        const val METHOD_SPACE_NAME = "invokeIgnore"

        /**
         * 替换器桥接方法描述符。
         */
        const val METHOD_DESC = "(Ljava/lang/Object;Ljava/lang/String;" + "Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;"
    }
}
