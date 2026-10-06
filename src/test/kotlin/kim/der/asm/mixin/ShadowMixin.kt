/*
 * Copyright 2020-2026 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.Mutable
import kim.der.asm.api.annotation.Shadow

/**
 * Shadow 声明示例：校验目标 Test 的实例/静态成员，并移除目标静态字段的 final 标志。
 *
 * 字段类型、方法描述符及 static 属性必须与目标一致。实例成员放在普通 class 中；
 * companion 的字段编译为外部类的静态字段，静态方法需要用 @JvmStatic 生成外部类桥接。
 *
 * 只有 Overwrite、Copy 或 inline AsmInject 复制体中的直接成员引用会绑定到目标。
 * 普通 AsmInject 的 this 仍是 Mixin，Shadow 不会将普通回调转换为目标实例方法。
 *
 * 与 Accessor 结合使用：
 * - Shadow 在复制方法体内部提供目标成员引用
 * - Accessor 为外部代码提供访问接口（生成 getter/setter）
 * - 可以在同一个 Mixin 中同时使用两者：
 *   ```kotlin
 *   @AsmMixin("Test")
 *   class MyMixin {
 *       @Shadow
 *       private val dynamicString: String? = null
 *
 *       @Accessor("dynamicString")
 *       fun getDynamicString(): String { ... }     // 供外部代码使用
 *   }
 *   ```
 *
 * 示例：
 * ```kotlin
 * @AsmMixin("Test")
 * class MyMixin {
 *     @Shadow
 *     private val dynamicString: String? = null
 *
 *     @AsmInject(method = "testA0()Ljava/lang/String;", target = InjectionPoint.HEAD, inline = true)
 *     fun injectHead() {
 *         val value = dynamicString  // 同类直接字段指令会被改写到目标
 *         println("Current value: $value")
 *     }
 * }
 * ```
 *
 * 注意：
 * - @Shadow() 使用声明名；@Shadow("actual") 使用显式别名；@Shadow("shadow_actual") 去掉前缀
 * - 字段初始值只是 Mixin 占位状态，不会复制到目标；可空引用是此示例的选择，并非强制要求
 * - Shadow 方法体仅是编译占位，只有复制体内重写后的调用才会转向目标方法
 * - @Mutable 只移除目标自身字段的 final，不改变 static 属性或继承成员
 * - 本 fixture 主要测试声明与修饰符；直接读写和调用由 MemberMappingContractTest 覆盖
 */
@AsmMixin("Test")
class ShadowMixin {
    /**
     * Shadow 字段示例 - 实例字段
     *
     * 在目标类中，这个字段会被映射到：
     * ```java
     * private String dynamicString = "DynamicString";
     * ```
     *
     * 在复制到目标的 Mixin 方法体中可以使用：
     * ```kotlin
     * val value = dynamicString  // 访问目标类的字段
     * ```
     */
    @Shadow()
    private val dynamicString: String? = null

    /**
     * Shadow 方法示例 - 实例方法
     *
     * 在目标类中，这个方法会被映射到：
     * ```java
     * public String testA0() {
     *     return dynamicString;
     * }
     * ```
     *
     * 在复制到目标的 Mixin 方法体中可以调用：
     * ```kotlin
     * val result = testA0()  // 调用目标类的方法
     * ```
     */
    @Shadow()
    private fun testA0(): String = throw UnsupportedOperationException("Shadow method should not be called directly")

    /**
     * Shadow 方法示例 - 带参数的方法
     *
     * 在目标类中，这个方法会被映射到：
     * ```java
     * public String testC0(String string) {
     *     String dynamicString = string + "testC0";
     *     return dynamicString;
     * }
     * ```
     */
    @Shadow()
    private fun testC0(string: String): String = throw UnsupportedOperationException("Shadow method should not be called directly")

    /** 静态占位声明与 Test 的静态成员保持一致；不通过 companion 的实例访问器引用目标。 */
    companion object {
        /** 编译到 ShadowMixin 的静态字段，对应 Test.staticString。 */
        @Shadow
        private val staticString: String? = null

        /** 移除 Test.STATIC_FINAL_STRING 的 final 标志，保持其 static 属性。 */
        @Shadow
        @Mutable
        private val STATIC_FINAL_STRING: String? = null

        /** 生成外部类的静态 Shadow 桥接，对应无参数的 Test.testB0。 */
        @JvmStatic
        @Shadow
        fun testB0(): String = throw UnsupportedOperationException("Shadow method should not be called directly")

        /** 保留 String 参数和返回类型，对应静态 Test.testC1。 */
        @JvmStatic
        @Shadow
        fun testC1(string: String): String = throw UnsupportedOperationException("Shadow method should not be called directly")
    }
}
