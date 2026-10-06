/*
 * Copyright 2020-2026 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.Accessor
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Shadow

/**
 * Accessor 和 Shadow 结合使用示例
 *
 * 这个示例展示了如何同时使用 Shadow 和 Accessor：
 * 1. 使用 Shadow 在 Mixin 类中引用目标类的字段和方法
 * 2. 使用 Accessor 为这些字段生成访问器方法（供外部代码使用）
 * 3. 在 Overwrite、Copy 或 inline AsmInject 复制体中使用 Shadow 字段和方法
 *
 * 使用场景：
 * - 在复制方法体中需要访问和修改目标类的私有字段
 * - 同时需要为外部代码提供访问这些字段的接口
 * - 在复制方法体中需要调用目标类的私有方法
 *
 * 注意：
 * - 本例引用实例字段，因此使用普通 class；Shadow 成员的 static 属性必须与目标一致
 * - Accessor 可以声明在 class 中；访问静态字段时方法需要 Java static 或 @JvmStatic
 * - 可以在同一个 Mixin 类中同时使用 Shadow 和 Accessor
 * - 普通非内联 handler 不会因 Shadow 自动切换到目标 receiver
 */
@AsmMixin("Test")
class AccessorShadowMixin {
    /**
     * Shadow 字段：在 Mixin 类中引用目标类的字段
     * 在复制到目标的方法体内，直接字段指令会映射到目标字段
     *
     * 本例目标是实例字段，因此声明也必须是实例字段
     */
    @JvmField
    @Shadow
    var dynamicString: String? = null

    /**
     * Shadow 方法：在 Mixin 类中引用目标类的方法
     * 复制方法体中的直接调用会映射到目标；普通 handler 仍会调用这个占位方法
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
     * 内联 HEAD 直接读取并写回目标字段，随后原方法读取新状态。
     *
     * @JvmField 保证生成 GETFIELD/PUTFIELD；inline 保证 owner 和 this 绑定到目标。
     * 普通 handler 或 Kotlin 属性访问器调用不能替代这条字段映射路径。
     */
    @AsmInject(
        method = "testA0()Ljava/lang/String;",
        target = InjectionPoint.HEAD,
        inline = true,
    )
    fun injectHeadUsingShadowField() {
        // 原方法稍后读取同一目标字段；不通过反射或外部 handler 绕过成员映射。
        val currentValue = dynamicString
        if (currentValue != null && currentValue.startsWith("Dynamic")) {
            dynamicString = "Modified via Shadow: $currentValue"
        }
    }

    /**
     * 示例：在 Inject 方法中使用 Shadow 方法
     *
     * 直接调用占位方法 testA0；内联后必须改写为目标方法，否则占位实现会抛异常。
     */
    @AsmInject(
        method = "testC0(Ljava/lang/String;)Ljava/lang/String;",
        target = InjectionPoint.HEAD,
        inline = true,
    )
    fun injectHeadUsingShadowMethod() {
        // 保留 testC0 的原返回值，以目标字段变化证明 Shadow 调用确实执行。
        dynamicString = "Shadow method: " + testA0()
    }
}

/**
 * 总结：Accessor 和 Shadow 的结合使用
 *
 * 在这个示例中：
 * 1. Shadow 字段（dynamicString）：内联方法体中的直接字段指令绑定到目标实例
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
 * - 在 Mixin 的内联注入方法中：直接使用 Shadow 字段（如 `dynamicString`）
 * - 在外部代码中：使用 Accessor 方法（如 `instance.getDynamicString()`）
 */
