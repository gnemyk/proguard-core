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
import proguard.classfile.util.InstructionSequenceMatcher
import proguard.classfile.visitor.NamedMethodVisitor
import proguard.evaluation.ExecutingInvocationUnit
import proguard.evaluation.PartialEvaluator
import proguard.evaluation.ParticularReferenceValueFactory
import proguard.evaluation.executor.model.ClassModelExecutor
import proguard.evaluation.value.ArrayReferenceValueFactory
import proguard.evaluation.value.ParticularValueFactory
import proguard.evaluation.value.`object`.model.ClassModel
import proguard.testutils.ClassPoolBuilder
import proguard.testutils.JavaSource
import proguard.testutils.RequiresJavaVersion

@RequiresJavaVersion(9)
class ReflectiveModelExecutorJava9Test : BehaviorSpec({
    Given("A method which uses various ways to access class details") {
        val (programClassPool, libraryClassPool) = ClassPoolBuilder.fromSource(
            JavaSource(
                "Foo.java",
                """
                package com.example;
                public class Foo {
                    public static void main(String[] args) throws Exception {
                        // getPackageName()
                        Class.forName(Foo.class.getPackageName() + "." + Foo.class.getSimpleName()).getDeclaredConstructors();
                    }
                }
                """.trimIndent(),
            ),
            javacArguments = listOf("-source", "1.8", "-target", "1.8"),
        )

        When("It is partially evaluated with a ClassModel executor") {
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
                        .addExecutor(ClassModelExecutor.Builder(programClassPool, libraryClassPool))
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
})
