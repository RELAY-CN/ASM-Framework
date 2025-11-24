/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.*

/**
 * Accessor 和 Shadow 结合使用示例
 *
 * 这个示例展示了如何同时使用 Shadow 和 Accessor：
 * 1. 使用 Shadow 在 Mixin 类中引用目标类的字段和方法
 * 2. 使用 Accessor 为这些字段生成访问器方法（供外部代码使用）
 * 3. 在 Inject 方法中使用 Shadow 字段和方法
 *
 * 使用场景：
 * - 在注入方法中需要访问和修改目标类的私有字段
 * - 同时需要为外部代码提供访问这些字段的接口
 * - 在注入方法中需要调用目标类的私有方法
 *
 * 注意：
 * - Shadow 字段和方法在 class 中声明（不是 object）
 * - Accessor 方法在 object 中声明（需要 @JvmStatic 用于静态字段）
 * - 可以在同一个 Mixin 类中同时使用 Shadow 和 Accessor
 */
@AsmMixin("Test")
class AccessorShadowMixin {
    /**
     * Shadow 字段：在 Mixin 类中引用目标类的字段
     * 这样可以在注入方法中直接访问这个字段
     *
     * 注意：Shadow 字段必须在 class 中声明，不能在 object 中
     */
    @Shadow()
    private val dynamicString: String? = null

    /**
     * Shadow 方法：在 Mixin 类中引用目标类的方法
     * 这样可以在注入方法中调用这个方法
     */
    @Shadow()
    private fun testA0(): String = throw UnsupportedOperationException("Shadow method should not be called directly")

    /**
     * Accessor：为 Shadow 字段生成访问器
     *
     * Accessor 会在目标类（Test）中生成 getter/setter 方法，允许外部代码访问私有字段。
     *
     * 生成的代码示例：
     * ```java
     * // 在目标类 Test 中会生成以下方法：
     * public String getDynamicString() {
     *     return this.dynamicString;
     * }
     *
     * public void setDynamicString(String value) {
     *     this.dynamicString = value;
     * }
     * ```
     *
     * 外部代码可以这样使用：
     * ```java
     * Test instance = new Test();
     * String value = instance.getDynamicString();  // 通过 Accessor 访问
     * instance.setDynamicString("NewValue");      // 通过 Accessor 修改
     * ```
     *
     * 注意：
     * - Accessor 方法会在目标类中生成，而不是在 Mixin 类中
     * - 这些方法在 Mixin 类中的实现（throw Exception）不会被使用
     * - Accessor 和 Shadow 可以同时使用：Shadow 用于 Mixin 内部访问，Accessor 用于外部访问
     */
    @Accessor("dynamicString")
    fun getDynamicString(): String = throw UnsupportedOperationException("Accessor should not be called directly")

    @Accessor("dynamicString")
    fun setDynamicString(value: String): Unit = throw UnsupportedOperationException("Accessor should not be called directly")

    /**
     * 示例：在 Inject 方法中使用 Shadow 字段
     *
     * 这个注入方法会在 testA0() 方法返回前执行，
     * 可以访问和修改 Shadow 字段的值
     */
    @AsmInject(
        method = "testA0()Ljava/lang/String;",
        target = InjectionPoint.RETURN,
    )
    fun injectReturnUsingShadow(callback: CallbackInfo) {
        // 使用 Shadow 字段访问目标类的字段值
        // 注意：在 Kotlin 中，Shadow 字段的访问会被 ASM 转换为对目标类字段的直接访问
        val currentValue = dynamicString
        println("Shadow field value: $currentValue")

        // 可以基于 Shadow 字段的值修改返回值
        if (currentValue != null && currentValue.contains("Dynamic")) {
            callback.setReturnValue("Modified via Shadow: $currentValue")
        }
    }

    /**
     * 示例：在 Inject 方法中使用 Shadow 方法
     *
     * 这个注入方法展示了如何调用 Shadow 方法
     */
    @AsmInject(
        method = "testC0(Ljava/lang/String;)Ljava/lang/String;",
        target = InjectionPoint.HEAD,
    )
    fun injectHeadUsingShadowMethod(callback: CallbackInfo) {
        // 可以调用 Shadow 方法
        // 注意：在实际生成的代码中，这会被转换为对目标类方法的直接调用
        // val result = testA0()  // 调用目标类的 testA0() 方法
        println("Can call shadow method: testA0()")
    }
}

/**
 * 总结：Accessor 和 Shadow 的结合使用
 *
 * 在这个示例中：
 * 1. Shadow 字段（dynamicString）：在 Mixin 类内部使用，用于在注入方法中访问目标类的字段
 * 2. Accessor 方法（getDynamicString/setDynamicString）：在目标类中生成，供外部代码使用
 *
 * 生成的代码结构：
 * ```java
 * public class Test {
 *     private String dynamicString = "DynamicString";
 *
 *     // Accessor 生成的方法（供外部使用）
 *     public String getDynamicString() {
 *         return this.dynamicString;
 *     }
 *
 *     public void setDynamicString(String value) {
 *         this.dynamicString = value;
 *     }
 *
 *     // 注入方法中使用 Shadow 字段
 *     public String testA0() {
 *         String var1 = this.dynamicString;
 *         // ... 注入的代码会使用 Shadow 字段 ...
 *         return var1;
 *     }
 * }
 * ```
 *
 * 使用方式：
 * - 在 Mixin 的注入方法中：使用 Shadow 字段（如 `dynamicString`）
 * - 在外部代码中：使用 Accessor 方法（如 `instance.getDynamicString()`）
 */
