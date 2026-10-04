/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */
package kim.der.asm.fixture

/** Kotlin 目标夹具：与 Java 目标共用 receiver 类型，让同一组 handler 比较两种编译产物。 */
class KotlinAnnotationUsageFixtures {
    class Calls {
        @JvmField val receiver = AnnotationUsageFixtures.Receiver("")
        @JvmField var before = 0
        @JvmField var after = 0

        fun run(): String {
            before++
            val result = receiver.join("a", "b")
            after++
            return result
        }

        // 可空参数避免编译器在入口插入额外 ALOAD 空值检查，首个读取即业务读取。
        fun twice(value: String?): String = value + ":" + value
    }
}
