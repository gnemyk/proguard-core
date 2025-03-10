package proguard.evaluation.executor

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import proguard.classfile.Clazz
import proguard.classfile.Method
import proguard.classfile.attribute.Attribute.CODE
import proguard.classfile.attribute.CodeAttribute
import proguard.classfile.attribute.visitor.AllAttributeVisitor
import proguard.classfile.attribute.visitor.AttributeNameFilter
import proguard.classfile.attribute.visitor.MultiAttributeVisitor
import proguard.classfile.editor.InstructionSequenceBuilder
import proguard.classfile.instruction.Instruction
import proguard.classfile.instruction.visitor.AllInstructionVisitor
import proguard.classfile.instruction.visitor.InstructionVisitor
import proguard.classfile.util.ClassReferenceInitializer
import proguard.classfile.util.InstructionSequenceMatcher
import proguard.classfile.visitor.NamedMethodVisitor
import proguard.evaluation.ExecutingInvocationUnit
import proguard.evaluation.PartialEvaluator
import proguard.evaluation.ParticularReferenceValueFactory
import proguard.evaluation.executor.model.ClassLoaderModelExecutor
import proguard.evaluation.executor.model.ClassModelExecutor
import proguard.evaluation.value.ArrayReferenceValueFactory
import proguard.evaluation.value.ParticularReferenceValue
import proguard.evaluation.value.ParticularValueFactory
import proguard.evaluation.value.`object`.model.ClassModel
import proguard.testutils.AssemblerSource
import proguard.testutils.ClassPoolBuilder
import proguard.testutils.JavaSource
import proguard.testutils.PartialEvaluatorUtil
import proguard.util.BasicHierarchyProvider

class ReflectiveModelExecutorTest : BehaviorSpec({
    Given("Test ClassModel executor") {
        val (programClassPool, libraryClassPool) = ClassPoolBuilder.fromSource(
            JavaSource(
                "Foo.java",
                """
                package com.example;
                public class Foo {
                    public static void main(String[] args) throws Exception {
                        // getSuperclass()
                        Class<?> clz = SubFoo.class.getSuperclass();
                    }
                }

                class SubFoo extends Foo {
                }

                """.trimIndent(),
            ),
            javacArguments = listOf("-g", "-source", "1.8", "-target", "1.8"),
        )

        When("It is partially evaluated with a ClassModel executor") {
            val particularValueFactory = ParticularValueFactory(
                ArrayReferenceValueFactory(),
                ParticularReferenceValueFactory(),
            )

            val executorBuilder = ReflectiveModelExecutor
                .Builder(BasicHierarchyProvider(programClassPool, libraryClassPool))
                .addSupportedModel(ReflectiveModelExecutor.SupportedModelInfo(ClassModel::class.java, false))

            val particularValueEvaluator = PartialEvaluator.Builder.create()
                .setValueFactory(particularValueFactory)
                .setInvocationUnit(
                    ExecutingInvocationUnit.Builder(programClassPool, libraryClassPool)
                        .setEnableSameInstanceIdApproximation(true)
                        .useDefaultStringReflectionExecutor(true)
                        .addExecutor(executorBuilder)
                        .build(particularValueFactory),
                )
                .setEvaluateAllCode(true)
                .stopAnalysisAfterNEvaluations(50)
                .build()

            val (instructions, variableTable) = PartialEvaluatorUtil.evaluate(
                "com/example/Foo",
                "main",
                "([Ljava/lang/String;)V",
                programClassPool,
                particularValueEvaluator,
            )

            val (instruction, _) = instructions.last()
            val clz = particularValueEvaluator.getVariablesBefore(instruction).getValue(variableTable["clz"]!!)

            Then("Then the retrieved class is the super class") {
                clz.shouldBeInstanceOf<ParticularReferenceValue>()
                val fooValue = clz.referenceValue().value.modeledValue
                fooValue.shouldBeInstanceOf<ClassModel>()
                fooValue.clazz shouldBe programClassPool.getClass("com/example/Foo")
            }
        }
    }

    Given("A method which uses various ways to access class details") {
        val (programClassPool, libraryClassPool) = ClassPoolBuilder.fromSource(
            JavaSource(
                "Foo.java",
                """
                package com.example;
                public class Foo {
                    public static void main(String[] args) throws Exception {
                        // ClassLoader
                        Foo.class.getClassLoader().loadClass("com.example.Foo").getDeclaredConstructors();
                        // getClass()
                        new Foo().getClass().getDeclaredConstructors();
                        // getSimpleName()
                        Class.forName("com.example." + Foo.class.getSimpleName()).getDeclaredConstructors();
                        Class.forName("com.example." + new Foo().getClass().getSimpleName()).getDeclaredConstructors();
                        // getTypeName()
                        Class.forName(Foo.class.getTypeName()).getDeclaredConstructors();
                        Class.forName(new Foo().getClass().getTypeName()).getDeclaredConstructors();
                        // getSuperclass()
                        SubFoo.class.getSuperclass().getDeclaredConstructors();
                        new SubFoo().getClass().getSuperclass().getDeclaredConstructors();
                        // getCanonicalName()
                        Class.forName(SubFoo.InnerSubFoo.class.getCanonicalName()
                            // This regex reverts the canonical name to the original name.
                            .replaceAll("\\.(?=[^\\.]*${'$'})", "\\${'$'}"))
                            .getSuperclass().getDeclaredConstructors();
                        // StringBuilder & newInstance
                        ((Foo)Class.forName(new StringBuilder().append("com.example.Foo").toString()).newInstance()).getClass().getDeclaredConstructors();
                    }
                }

                class SubFoo extends Foo {
                    public class InnerSubFoo extends Foo {}
                }

                """.trimIndent(),
            ),
            javacArguments = listOf("-source", "1.8", "-target", "1.8"),
        )

        When("It is partially evaluated using executors for the relevant Models") {
            val particularValueFactory = ParticularValueFactory(
                ArrayReferenceValueFactory(),
                ParticularReferenceValueFactory(),
            )

            val particularValueEvaluator = PartialEvaluator.Builder.create()
                .setValueFactory(particularValueFactory)
                .setInvocationUnit(
                    ExecutingInvocationUnit.Builder(programClassPool, libraryClassPool)
                        .setEnableSameInstanceIdApproximation(true)
                        .useDefaultStringReflectionExecutor(true)
                        .addExecutor(ObjectGetClassExecutor.Builder(programClassPool, libraryClassPool))
                        .addExecutor(ClassModelExecutor.Builder(programClassPool, libraryClassPool))
                        .addExecutor(ClassLoaderModelExecutor.Builder(programClassPool, libraryClassPool))
                        .build(particularValueFactory),
                )
                .setEvaluateAllCode(true)
                .stopAnalysisAfterNEvaluations(50)
                .build()

            // We'll also collect the instruction offsets of where we expect ClassModels to be on the stack.
            val getDeclaredConstructorOffsets = ArrayList<Int>()
            val builder = InstructionSequenceBuilder().invokevirtual(
                "java/lang/Class",
                "getDeclaredConstructors",
                "()[Ljava/lang/reflect/Constructor;",
            )
            val matcher = InstructionSequenceMatcher(builder.constants(), builder.instructions())
            val getDeclaredConstructorOffsetCollector: InstructionVisitor = object : InstructionVisitor {
                override fun visitAnyInstruction(
                    clazz: Clazz,
                    method: Method,
                    codeAttribute: CodeAttribute,
                    offset: Int,
                    instruction: Instruction,
                ) {
                    instruction.accept(clazz, method, codeAttribute, offset, matcher)
                    if (matcher.isMatching) getDeclaredConstructorOffsets.add(offset)
                }
            }

            programClassPool.classesAccept(
                "com/example/Foo",
                NamedMethodVisitor(
                    "main",
                    "([Ljava/lang/String;)V",
                    AllAttributeVisitor(
                        AttributeNameFilter(
                            CODE,
                            MultiAttributeVisitor(
                                AllInstructionVisitor(getDeclaredConstructorOffsetCollector),
                                particularValueEvaluator,
                            ),
                        ),
                    ),
                ),
            )

            Then("Then the retrieved classes should all be modeled") {
                getDeclaredConstructorOffsets.forEach { offset ->
                    val stackBeforeGetDeclaredConstructor = particularValueEvaluator.getStackBefore(offset)
                    val fooValue = stackBeforeGetDeclaredConstructor.getTop(0).referenceValue().value.modeledValue
                    fooValue.shouldBeInstanceOf<ClassModel>()
                    fooValue.clazz shouldBe programClassPool.getClass("com/example/Foo")
                }
            }
        }
    }

    Given("A method which calls ClassLoader.findLoadedClass") {
        val (programClassPool, libraryClassPool) = ClassPoolBuilder.fromSource(
            AssemblerSource(
                "Test.jbc",
                """
          version 1.8;
          public final class Foo {
              private static final void foo() {
                invokestatic java.lang.ClassLoader#java.lang.ClassLoader getSystemClassLoader()
                ldc "Foo"
                invokevirtual java.lang.ClassLoader#java.lang.Class findLoadedClass(java.lang.String)
                invokevirtual java.lang.Class#java.lang.reflect.Constructor[] getDeclaredConstructors()
                pop
                return
              }
          }                    
                """.trimIndent(),
            ),
        )

        When("It is partially evaluated with a ClassLoaderModel executor") {
            val particularValueFactory = ParticularValueFactory(
                ArrayReferenceValueFactory(),
                ParticularReferenceValueFactory(),
            )

            val particularValueEvaluator = PartialEvaluator.Builder.create()
                .setValueFactory(particularValueFactory)
                .setInvocationUnit(
                    ExecutingInvocationUnit.Builder(programClassPool, libraryClassPool)
                        .setEnableSameInstanceIdApproximation(true)
                        .useDefaultStringReflectionExecutor(true)
                        .addExecutor(ClassLoaderModelExecutor.Builder(programClassPool, libraryClassPool))
                        .build(particularValueFactory),
                )
                .setEvaluateAllCode(true)
                .stopAnalysisAfterNEvaluations(50)
                .build()

            // We'll also collect the instruction offsets of where we expect ClassModels to be on the stack.
            val getDeclaredConstructorOffsets = ArrayList<Int>()
            val builder = InstructionSequenceBuilder().invokevirtual(
                "java/lang/Class",
                "getDeclaredConstructors",
                "()[Ljava/lang/reflect/Constructor;",
            )
            val matcher = InstructionSequenceMatcher(builder.constants(), builder.instructions())
            val getDeclaredConstructorOffsetCollector: InstructionVisitor = object : InstructionVisitor {
                override fun visitAnyInstruction(
                    clazz: Clazz,
                    method: Method,
                    codeAttribute: CodeAttribute,
                    offset: Int,
                    instruction: Instruction,
                ) {
                    instruction.accept(clazz, method, codeAttribute, offset, matcher)
                    if (matcher.isMatching) getDeclaredConstructorOffsets.add(offset)
                }
            }

            // ClassLoader.findLoadedClass has protected access, so we need to ignore access rules during initialization.
            programClassPool.classesAccept(ClassReferenceInitializer(programClassPool, libraryClassPool, false))
            programClassPool.classesAccept(
                "Foo",
                NamedMethodVisitor(
                    "foo",
                    "()V",
                    AllAttributeVisitor(
                        AttributeNameFilter(
                            CODE,
                            MultiAttributeVisitor(
                                AllInstructionVisitor(getDeclaredConstructorOffsetCollector),
                                particularValueEvaluator,
                            ),
                        ),
                    ),
                ),
            )

            Then("Then the retrieved classes should all be modeled") {
                getDeclaredConstructorOffsets.forEach { offset ->
                    val stackBeforeGetDeclaredConstructor = particularValueEvaluator.getStackBefore(offset)
                    val fooValue = stackBeforeGetDeclaredConstructor.getTop(0).referenceValue().value.modeledValue
                    fooValue.shouldBeInstanceOf<ClassModel>()
                    fooValue.clazz shouldBe programClassPool.getClass("Foo")
                }
            }
        }
    }
})
