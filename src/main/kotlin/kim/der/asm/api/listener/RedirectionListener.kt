/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.listener

/**
 * 重定向监听器接口。
 *
 * 监听器由转换器注入到目标调用点附近，用于观察目标调用而不直接替换返回值。
 * 实现方可以在调用前或调用后执行副作用逻辑；异常会沿注入调用链向外传播。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
fun interface RedirectionListener {
    /**
     * 执行监听回调。
     *
     * @param obj 调用所属对象；静态调用场景下可能为占位对象
     * @param desc 调用点描述符，格式为 `Lowner;name(desc)return`
     * @param args 原调用参数或调用结果上下文，具体顺序由注入点决定
     * @throws Throwable 监听器执行失败时透出给调用方
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @Throws(Throwable::class)
    operator fun invoke(
        obj: Any,
        desc: String,
        vararg args: Any?,
    )

    companion object {
        /**
         * 监听器桥接方法名。
         */
        const val METHOD_NAME = "invoke"

        /**
         * 监听器桥接方法描述符。
         */
        const val METHOD_DESC = "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)V"
    }
}
