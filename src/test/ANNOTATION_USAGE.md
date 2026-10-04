# 注解使用与差异测试索引

本索引覆盖 `api/annotation` 中全部 34 个公开注解。每个注解的 KDoc 提供 Kotlin 用法、相近注解的差异及行为测试入口；测试中的 Mixin 声明是可执行示例。以下入口均验证实际注册、字节码转换或运行结果，不以只读取注解元数据作为用法覆盖。

测试简称：

- **D**：[AnnotationUsageDifferencesTest](kotlin/kim/der/asm/AnnotationUsageDifferencesTest.kt)，对同一目标比较返回值、调用次数、槽位写回及结构差异；目标见 [Java 夹具](resources/test/java/kim/der/asm/fixture/AnnotationUsageFixtures.java) 与 [Kotlin 夹具](resources/test/kotlin/kim/der/asm/fixture/KotlinAnnotationUsageFixtures.kt)。调用行为的 13 种对照在两种语言编译的目标上各执行一次。
- **R**：[FrameworkReliabilityTest](kotlin/kim/der/asm/FrameworkReliabilityTest.kt)，包含定位、签名、命中数、边界及异常路径；下表方法名可在该文件中直接定位，部分位于嵌套测试类。
- **M**：[MemberMappingContractTest](kotlin/kim/der/asm/MemberMappingContractTest.kt)，验证复制体的成员映射与普通 handler 的状态归属。

## 类与成员结构

下列注解的 KDoc 位于 [AsmMixin.kt](../main/kotlin/kim/der/asm/api/annotation/AsmMixin.kt)，`AsmDelete` 位于 [AsmDelete.kt](../main/kotlin/kim/der/asm/api/annotation/AsmDelete.kt)。

| 注解 | 可执行用法入口 | 断言的关键差异 |
| --- | --- | --- |
| `AsmMixin` | `R.registryOrdersExactMixinsByPriorityBeforeRegistrationOrder` | 规则容器按优先级再按注册顺序应用，本身不替换方法 |
| `Group` | `R.groupedModifyConstantAllowsFallbackCandidate` | 允许单个版本候选不命中，以整个组的命中数约束是否成功 |
| `AddInterface` | `D.interfaceDeclarationsDoNotCreateOrDeleteImplementation` | 添加类型关系，使用目标已有的实现方法 |
| `RemoveInterface` | `D.interfaceDeclarationsDoNotCreateOrDeleteImplementation` | 删除类型关系后，原实现方法仍能调用 |
| `ReplaceAllMethods` | `D.replaceAllWritesStructuralChangesWithoutOrdinaryMethods`；`R.overwriteCanReplaceMethodAfterReplaceAllMethodsInSameMixin` | 批量替换普通方法，构造器保留；仅类/字段标志变化也需写回，后续 Overwrite 可恢复局部实现 |
| `RedirectAllMethods` | `R.redirectAllMethodsDoesNotRequireExplicitMethodTarget` | 在全部普通方法中寻找 Redirect 操作点，保留外围方法体 |
| `Overwrite` | `D.copyUniqueAndOverwriteHandleConflictsDifferently` | 覆盖目标已有方法实现 |
| `Copy` | `D.copyUniqueAndOverwriteHandleConflictsDifferently` | 同签名冲突时保留已有方法，复制体调用已有实现 |
| `Unique` | `D.copyUniqueAndOverwriteHandleConflictsDifferently`；`D.uniqueWithoutConflictKeepsPublicCopy` | 与 Copy 组合时仅冲突方法改名并变为 private synthetic；无冲突仍 public |
| `Shadow` | `M.copiedBodiesResolveExplicitShadowFieldAndMethodAliases` | 引用已有字段/方法并绑定别名，不创建新字段 |
| `Accessor` | `R.instanceFieldSetterUpdatesTargetState`；`D.interfaceAccessorGetterWorksButSetterFailsDuringTransform`；`D.removeFieldAndAccessorInferAcronymsDifferently` | 生成字段读写桥接；接口只允许 getter；名称推断保留 URL 等连续大写前缀 |
| `Invoker` | `R.accessorAndInvokerBridgePrivateMembersInTestClass`；`R.invokerCanGenerateConstructorFactoryMethod` | 生成方法调用或构造工厂桥接，不覆盖原实现 |
| `Mutable` | `M.reusedAddedFieldAppliesMutableBeforeWriting`；`D.mutableDoesNotInvalidateFieldsOnTargetInterface` | 移除目标自身字段 final，但保留接口字段必须的 final |
| `Final` | `D.finalWinsWhenCombinedWithMutable`；`M.finalRejectsVolatileFieldDuringTransform` | 添加 final；与 Mutable 同用时 Final 优先，与 volatile 冲突时拒绝 |
| `AddField` | `M.copiedBodiesAccessAddedFieldsOnEachTarget`；`M.ordinaryHandlerRetainsMixinState` | 复制字段声明而不复制初始化；复制体访问目标字段，普通 handler 使用 Mixin 状态 |
| `RemoveField` | `R.removeFieldInfersTargetFieldFromAccessorStyleMethodNames`；`D.removeFieldAndAccessorInferAcronymsDifferently` | 删除字段声明；removeURL 推断为 uRL，与 Accessor 的规则不同 |
| `RemoveMethod` | `R.removeMethodRemovesTargetMethod`；`R.asmDeleteRunsAfterInPlaceMethodTransformations` | 删除方法声明，区别于 Overwrite 替换实现及 AsmDelete 最后统一删除 |
| `RemoveSynchronized` | `R.removeSynchronizedInTestClassRemovesFlagsAndKeepsBusinessState` | 保留业务逻辑，只移除同步标志和 monitor 语义 |
| `AsmDelete` | `R.asmDeleteRemovesExplicitTargetMethod`；`R.asmDeleteRunsAfterInPlaceMethodTransformations` | 预检删除冲突，同一 Mixin 的原位改写结束后统一删除 |

## 注入、值修改与定位

KDoc 位于 [AsmMixin.kt](../main/kotlin/kim/der/asm/api/annotation/AsmMixin.kt)、[AsmInject.kt](../main/kotlin/kim/der/asm/api/annotation/AsmInject.kt) 和 [Local.kt](../main/kotlin/kim/der/asm/api/annotation/Local.kt)。

| 注解 | 可执行用法入口 | 断言的关键差异 |
| --- | --- | --- |
| `AsmInject` | `D.callAnnotationsHaveDifferentSideEffects`；`M.ordinaryHandlerRetainsMixinState` | 普通 RETURN handler 返回值被丢弃；CallbackInfo 可改目标返回值；普通 handler 保留 Mixin 状态 |
| `ModifyArg` | `D.callAnnotationsHaveDifferentSideEffects` | 只修改选中的一个调用实参，receiver 和其他实参不变 |
| `ModifyArgs` | `D.callAnnotationsHaveDifferentSideEffects` | Args 同时修改多个实参，不含 receiver，原调用仍执行 |
| `ModifyReceiver` | `D.callAnnotationsHaveDifferentSideEffects` | 实参不变，原操作在新 receiver 上执行 |
| `WrapOperation` | `D.callAnnotationsHaveDifferentSideEffects` | 可以跳过或重复执行一个操作，外围方法继续运行 |
| `WrapMethod` | `D.callAnnotationsHaveDifferentSideEffects`；`R.wrapMethodOperationCallReusesBoundReceiverAndDoesNotReenterWrapper` | 包裹整个方法；跳过原方法也跳过外围副作用；Operation 已绑定 this |
| `WrapWithCondition` | `D.callAnnotationsHaveDifferentSideEffects` | INVOKE false 跳过调用；INVOKE_ASSIGN false 只丢弃结果，调用副作用已经发生 |
| `ModifyExpressionValue` | `D.callAnnotationsHaveDifferentSideEffects`；`D.localAnnotationsDifferInSlotWriteback`；`D.storeAnnotationsObserveDifferentSlotTiming` | 保留原调用；LOAD 只改单次读取；STORE handler 在写入槽位前运行 |
| `ModifyVariable` | `D.localAnnotationsDifferInSlotWriteback`；`D.storeAnnotationsObserveDifferentSlotTiming` | LOAD 修改后写回槽位；STORE handler 在原写入之后运行 |
| `ModifyReturnValue` | `D.callAnnotationsHaveDifferentSideEffects` | 保留方法副作用，直接消费 handler 返回值 |
| `ModifyConstant` | `R.modifyConstantInTestB0RewritesStaticFinalStringLiteralOnly` | 修改字节码常量加载，不按运行期相同值截获任意表达式 |
| `Redirect` | `D.callAnnotationsHaveDifferentSideEffects` | 替换原操作及其副作用；不提供 Operation 句柄 |
| `At` | `R.modifyArgAtInvokeArgsLdcFiltersDirectStringCallArgument`；`R.fieldInjectByMovesHandlerForwardFromMatchedFieldRead`；`D.unsupportedAtOffsetsFailDuringTransform` | 筛选候选操作；by 只在支持的普通指令点注入中移动，其他实际定位路径拒绝非零偏移 |
| `Slice` | `R.modifyConstantSliceLimitsConstantsBetweenFromAndTo` | 限定候选搜索区间，区间外相同常量保持原值 |
| `Local` | `D.localAnnotationsDifferInSlotWriteback`；`R.asmInjectReturnCanCaptureLocalByNameInTestClassWithoutModifyingReturn` | 只读捕获当前作用域槽位，不写回，可与目标参数混排 |

## 运行

测试自动执行 `prepareMixinFixtures`：Java/Kotlin 源码先编译到 build 暂存目录，再把原始 `.class` 复制到各自源码目录（保持包路径）。这样编译器清理输出时不会误删源码。

| 语言 | 源码与原始 class | ASM 转换产物 |
| --- | --- | --- |
| Java | `src/test/resources/test/java/` | `src/test/resources/out/java/<场景>/<类路径>.class` |
| Kotlin | `src/test/resources/test/kotlin/` | `src/test/resources/out/kotlin/<场景>/<类路径>.class` |

差异测试读取上述原始资源，写出转换产物，再读取产物字节定义 JVM 类并断言。原有 `Test.java` 也由相同流程自动编译；`TestMixin` 的产物保存到 `out/java/Test_<场景>.class`。生成的 class 无需手工维护或提交。

集中运行差异对照：

```bash
gradle test --tests kim.der.asm.AnnotationUsageDifferencesTest -PdisableGraalVmAgent=true --console=plain
```

验证本索引对应的全部既有行为与错误路径：

```bash
gradle test -PdisableGraalVmAgent=true --console=plain
```

新增注解或改变行为时，同时更新实际行为测试、注解 KDoc 和本索引。`remap`、`ModifyReturnValue.at` 等明确标为预留的参数仍以 KDoc 声明的实现范围为准。
