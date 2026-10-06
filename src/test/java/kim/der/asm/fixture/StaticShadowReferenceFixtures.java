/*
 * Copyright 2020-2026 Dr (dr@der.kim) and contributors.
 */
package kim.der.asm.fixture;

import kim.der.asm.api.annotation.AsmInject;
import kim.der.asm.api.annotation.AsmMixin;
import kim.der.asm.api.annotation.Copy;
import kim.der.asm.api.annotation.Overwrite;
import kim.der.asm.api.annotation.Shadow;

/**
 * 生成直接的静态 Shadow 字段与方法指令，验证 static 契约校验不会误拒绝合法引用。
 * Java fixture 避免 Kotlin companion 实例访问器介入；占位方法若被错误执行会立即失败。
 */
public final class StaticShadowReferenceFixtures {
    /** 仅容纳 fixture，测试无需构造外部容器。 */
    private StaticShadowReferenceFixtures() {}

    /** Overwrite 中的静态方法别名和字段别名应同时指向目标静态成员。 */
    @AsmMixin("MemberTarget")
    public static class StaticOverwrite {
        @Shadow(method = "actual") public static int alias;

        /** 该占位必须改写为 MemberTarget.actualMethod()。 */
        @Shadow(method = "actualMethod")
        public static int aliasMethod() { throw new AssertionError("placeholder"); }

        /** 直接执行 INVOKESTATIC 与 PUTSTATIC，不使用反射访问目标。 */
        @Overwrite(method = "run()V")
        public void replace() { alias = aliasMethod(); }
    }

    /** Copy 中静态 Shadow 的调用和字段写入也必须使用目标 owner。 */
    @AsmMixin("MemberTarget")
    public static class StaticCopy {
        @Shadow(method = "actual") public static int alias;

        /** 与 Overwrite fixture 使用相同别名和描述符。 */
        @Shadow(method = "actualMethod")
        public static int aliasMethod() { throw new AssertionError("placeholder"); }

        /** 被复制的方法内部同时读写目标静态成员。 */
        @Copy
        public void copied() { alias = aliasMethod(); }

        /** 通过目标入口调用复制的 helper，确保其方法体实际执行。 */
        @Overwrite(method = "run()V")
        public void replace() { copied(); }
    }

    /** inline 路径保留目标原入口，同时插入静态 Shadow 调用和赋值。 */
    @AsmMixin("MemberTarget")
    public static class StaticInline {
        @Shadow(method = "actual") public static int alias;

        /** 占位方法体不会随 inline handler 自动复制。 */
        @Shadow(method = "actualMethod")
        public static int aliasMethod() { throw new AssertionError("placeholder"); }

        /** 目标静态字段必须得到目标静态方法的返回值。 */
        @AsmInject(method = "run()V", inline = true)
        public void before() { alias = aliasMethod(); }
    }
}
