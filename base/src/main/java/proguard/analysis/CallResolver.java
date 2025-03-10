/*
 * ProGuardCORE -- library to process Java bytecode.
 *
 * Copyright (c) 2002-2021 Guardsquare NV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package proguard.analysis;

import static proguard.exception.ErrorId.ANALYSIS_CALL_RESOLVER_SELECTIVE_PARAMETER_RECONSTRUCTION_MISSING_PARAMS;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import proguard.analysis.Metrics.MetricType;
import proguard.analysis.datastructure.CodeLocation;
import proguard.analysis.datastructure.Location;
import proguard.analysis.datastructure.callgraph.Call;
import proguard.analysis.datastructure.callgraph.CallGraph;
import proguard.analysis.datastructure.callgraph.ConcreteCall;
import proguard.analysis.datastructure.callgraph.SymbolicCall;
import proguard.classfile.AccessConstants;
import proguard.classfile.ClassConstants;
import proguard.classfile.ClassPool;
import proguard.classfile.Clazz;
import proguard.classfile.Method;
import proguard.classfile.MethodSignature;
import proguard.classfile.ProgramClass;
import proguard.classfile.ProgramMethod;
import proguard.classfile.TypeConstants;
import proguard.classfile.attribute.Attribute;
import proguard.classfile.attribute.CodeAttribute;
import proguard.classfile.attribute.visitor.AllAttributeVisitor;
import proguard.classfile.attribute.visitor.AttributeVisitor;
import proguard.classfile.constant.AnyMethodrefConstant;
import proguard.classfile.constant.Constant;
import proguard.classfile.constant.InvokeDynamicConstant;
import proguard.classfile.constant.NameAndTypeConstant;
import proguard.classfile.instruction.ConstantInstruction;
import proguard.classfile.instruction.Instruction;
import proguard.classfile.instruction.visitor.InstructionVisitor;
import proguard.classfile.util.ClassUtil;
import proguard.classfile.visitor.ClassVisitor;
import proguard.classfile.visitor.LineNumberFinder;
import proguard.evaluation.BasicInvocationUnit;
import proguard.evaluation.ExecutingInvocationUnit;
import proguard.evaluation.InvocationUnit;
import proguard.evaluation.PartialEvaluator;
import proguard.evaluation.ParticularReferenceValueFactory;
import proguard.evaluation.TracedStack;
import proguard.evaluation.TracedVariables;
import proguard.evaluation.exception.EmptyCodeAttributeException;
import proguard.evaluation.exception.ExcessiveComplexityException;
import proguard.evaluation.value.ArrayReferenceValueFactory;
import proguard.evaluation.value.DetailedArrayValueFactory;
import proguard.evaluation.value.MultiTypedReferenceValue;
import proguard.evaluation.value.MultiTypedReferenceValueFactory;
import proguard.evaluation.value.ParticularValueFactory;
import proguard.evaluation.value.TypedReferenceValue;
import proguard.evaluation.value.Value;
import proguard.evaluation.value.ValueFactory;
import proguard.exception.ProguardCoreException;
import proguard.util.PartialEvaluatorUtils;

/**
 * Collects all method invocations inside the analyzed methods.
 *
 * <p>All method invocation instructions that appear in the bytecode are inspected, and their actual
 * target method is calculated. Java has several invocation instructions, performing virtual,
 * static, dynamic, interface and special calls. While most of these instructions have a constant
 * operand specifying a method name, the actual method that will be called at runtime depends on
 * multiple factors. Sometimes, e.g. when using virtual calls, the invocation target depends on the
 * specific type of the first parameter on the stack, the so-called <code>this</code> pointer.
 *
 * <p>This call analyzer performs a lookup process that adheres to the Java Virtual Machine
 * specification. Being a static analysis, 100% precision cannot be guaranteed, as the specific type
 * of variables at a specific program point is not always known in advance. But using the {@link
 * PartialEvaluator} in combination with intraprocedural possible type analysis of {@link
 * MultiTypedReferenceValue} objects, the resulting call graph should be a superset of the actual
 * calls happening at runtime. This makes it a complete but potentially unsound analysis.
 *
 * <p>In addition to resolving the call target, this analyzer also reconstructs the corresponding
 * arguments and the return value. All the collected information is wrapped in a {@link Call} object
 * and passed to subscribed {@link CallHandler}s.
 */
public class CallResolver implements AttributeVisitor, ClassVisitor, InstructionVisitor {

  private static final Logger log = LogManager.getLogger(CallResolver.class);
  /** Used to fill the {@link Call#controlFlowDependent} flag. */
  private final DominatorCalculator dominatorCalculator;

  private final ClassPool programClassPool;
  private final ClassPool libraryClassPool;
  private final CallGraph callGraph;
  private final boolean clearCallValuesAfterVisit;
  private final boolean useDominatorAnalysis;
  private final List<CallHandler> callHandlers;

  /**
   * Calculates concrete values that are created by the bytecode and stored in variables or on the
   * stack. Needed to reconstruct the actual arguments and return value of method calls.
   */
  private final PartialEvaluator particularValueEvaluator;

  private boolean particularValueEvaluationSuccessful;
  /**
   * Only calculates the type of values on the stack or in variables, but is capable of handling
   * cases where this type may be different depending on the actual control flow path taken during
   * runtime. Needed for resolving all possible call targets of virtual calls that depend on the
   * type of this pointer during runtime.
   */
  private final PartialEvaluator multiTypeValueEvaluator;

  private boolean multiTypeEvaluationSuccessful;
  private final Supplier<Boolean> shouldAnalyzeNextCodeAttribute;
  private final boolean skipIncompleteCalls;

  private final boolean selectiveParameterReconstruction;
  private final Set<MethodSignature> interestingMethods;
  private final Set<Predicate<Call>> interestingCallPredicates;

  private CurrentClazzMethodAttribute currentClazzMethodAttribute;

  /**
   * Lightweight utility method to resolve the target of an invocation instruction on demand,
   * without having to run a full scale analysis. This means the following for the different
   * invocation types:
   *
   * <ul>
   *   <li><b>invokestatic:</b> Full target resolution possible.
   *   <li><b>invokevirtual, invokespecial, invokeinterface:</b> Method name and descriptor.
   *   <li><b>invokedynamic:</b> Only descriptor.
   * </ul>
   *
   * @param instruction The invocation instruction to analyze.
   * @param clazz The {@link ProgramClass} containing this instruction.
   * @return A {@link MethodSignature} containing as much information about the invocation target as
   *     we can confidently know without needing more in-depth analysis.
   */
  public static MethodSignature quickResolve(Instruction instruction, ProgramClass clazz) {
    if (!(instruction instanceof ConstantInstruction)) {
      return MethodSignature.UNKNOWN;
    }

    Constant constant = clazz.getConstant(((ConstantInstruction) instruction).constantIndex);
    if (instruction.opcode == Instruction.OP_INVOKEDYNAMIC
        && constant instanceof InvokeDynamicConstant) {
      // While we don't know what exact method is going to be executed
      // (the bootstrap method can decide this), we do get a name and a descriptor.
      // The name is arbitrary and doesn't need to correspond with the name of the
      // actually executed method, but the descriptor is not going to change.
      InvokeDynamicConstant invokeDynamicConstant = (InvokeDynamicConstant) constant;
      return new MethodSignature(null, null, invokeDynamicConstant.getType(clazz));
    }

    if (!(constant instanceof AnyMethodrefConstant)) {
      return MethodSignature.UNKNOWN;
    }
    AnyMethodrefConstant methodRef = (AnyMethodrefConstant) constant;
    switch (instruction.opcode) {
      case Instruction.OP_INVOKESTATIC:
        // This is always clear, so we can just return it directly.
        return new MethodSignature(
            methodRef.getClassName(clazz), methodRef.getName(clazz), methodRef.getType(clazz));
      case Instruction.OP_INVOKESPECIAL:
      case Instruction.OP_INVOKEVIRTUAL:
      case Instruction.OP_INVOKEINTERFACE:
        // Virtual method invocation targets will always have the expected name and descriptor,
        // but to know the exact target class we would need more in-depth analysis.
        return new MethodSignature(null, methodRef.getName(clazz), methodRef.getType(clazz));
    }

    return MethodSignature.UNKNOWN;
  }

  /**
   * Create a new call resolver.
   *
   * @param programClassPool {@link ClassPool} containing the classes whose calls should be
   *     analyzed.
   * @param libraryClassPool Auxiliary {@link ClassPool} containing framework classes. Their calls
   *     are not resolved, but the class structure information (i.e. contained methods) is needed
   *     when resolving calls whose target lies in such a library class.
   * @param callGraph The {@link CallGraph} to fill with all discovered {@link Call}s.
   * @param clearCallValuesAfterVisit If true, {@link Call#clearValues()} will be called after
   *     {@link CallHandler#handleCall(Call, TracedStack, TracedVariables)}. This makes it possible
   *     to analyze arguments and the return value of calls while still adding them to a {@link
   *     CallGraph} afterward, as call graph analysis itself usually only requires the call
   *     locations and their targets, not the arguments or return value.
   * @param useDominatorAnalysis If true, a dominator analysis is carried out using the {@link
   *     DominatorCalculator} for each method, in order to be able to fill the {@link
   *     Call#controlFlowDependent} flag.
   * @param evaluateAllCode See {@link PartialEvaluator.Builder#setEvaluateAllCode(boolean)}.
   * @param includeSubClasses If true, virtual calls on class fields, parameters and return values
   *     of other methods will take all possible subclasses into account. This is necessary for a
   *     more complete call graph, because the runtime type of these objects is not controlled by
   *     the current method. E.g. a method that declares its return type to be of type A might also
   *     return an object of type B in case B extends A. The same is true for class fields and
   *     parameters, so in order to really find all potential calls, this circumstance needs to be
   *     modeled. For objects of declared type {@link java.lang.Object} this will be skipped, as the
   *     fact that every single Java class is a subclass of object would lead to an immense blow-up
   *     of the call graph.
   * @param maxPartialEvaluations See {@link
   *     PartialEvaluator.Builder#stopAnalysisAfterNEvaluations(int)}.
   * @param shouldAnalyzeNextCodeAttribute If returns true, the next code attribute will be
   *     analyzed. Otherwise, the code attribute will be skipped.
   * @param skipIncompleteCalls If true, any discovered call that would return true for {@link
   *     Call#hasIncompleteTarget()} will be discarded and not be forwarded to {@link
   *     CallHandler#handleCall(Call, TracedStack, TracedVariables)}.
   * @param callHandlers {@link CallHandler}s that are interested in the results of this analysis.
   */
  public CallResolver(
      ClassPool programClassPool,
      ClassPool libraryClassPool,
      CallGraph callGraph,
      boolean clearCallValuesAfterVisit,
      boolean useDominatorAnalysis,
      boolean evaluateAllCode,
      boolean includeSubClasses,
      int maxPartialEvaluations,
      Supplier<Boolean> shouldAnalyzeNextCodeAttribute,
      boolean skipIncompleteCalls,
      ValueFactory arrayValueFactory,
      boolean ignoreExceptions,
      ExecutingInvocationUnit.Builder executingInvocationUnitBuilder,
      boolean selectiveParameterReconstruction,
      Set<MethodSignature> interestingMethods,
      Set<Predicate<Call>> interestingCallPredicates,
      CallHandler... callHandlers) {
    this.programClassPool = programClassPool;
    this.libraryClassPool = libraryClassPool;
    this.callGraph = callGraph;
    this.clearCallValuesAfterVisit = clearCallValuesAfterVisit;
    this.useDominatorAnalysis = useDominatorAnalysis;
    this.shouldAnalyzeNextCodeAttribute = shouldAnalyzeNextCodeAttribute;
    this.skipIncompleteCalls = skipIncompleteCalls;
    this.selectiveParameterReconstruction = selectiveParameterReconstruction;
    if (selectiveParameterReconstruction
        && (interestingMethods == null || interestingCallPredicates == null)) {
      throw new ProguardCoreException.Builder(
              "Using selectiveParameterReconstruction requires interestingMethods and interestingCallPredicates.",
              ANALYSIS_CALL_RESOLVER_SELECTIVE_PARAMETER_RECONSTRUCTION_MISSING_PARAMS)
          .build();
    }
    this.interestingMethods = interestingMethods;
    this.interestingCallPredicates = interestingCallPredicates;
    this.callHandlers = Arrays.asList(callHandlers);
    dominatorCalculator = new DominatorCalculator(ignoreExceptions);

    // Initialize the multitype evaluator.
    ValueFactory multiTypeValueFactory =
        includeSubClasses
            ? new MultiTypedReferenceValueFactory(
                true, this.programClassPool, this.libraryClassPool)
            : new MultiTypedReferenceValueFactory();
    InvocationUnit multiTypeValueInvocationUnit = new BasicInvocationUnit(multiTypeValueFactory);
    multiTypeValueEvaluator =
        PartialEvaluator.Builder.create()
            .setValueFactory(multiTypeValueFactory)
            .setInvocationUnit(multiTypeValueInvocationUnit)
            .setEvaluateAllCode(evaluateAllCode)
            .stopAnalysisAfterNEvaluations(maxPartialEvaluations)
            .build();

    // Initialize the particular value evaluator.
    ValueFactory particularValueFactory =
        new ParticularValueFactory(arrayValueFactory, new ParticularReferenceValueFactory());
    InvocationUnit particularValueInvocationUnit =
        executingInvocationUnitBuilder.build(particularValueFactory);
    particularValueEvaluator =
        PartialEvaluator.Builder.create()
            .setValueFactory(particularValueFactory)
            .setInvocationUnit(particularValueInvocationUnit)
            .setEvaluateAllCode(evaluateAllCode)
            .stopAnalysisAfterNEvaluations(maxPartialEvaluations)
            .build();
  }

  @Override
  public void visitAnyClass(Clazz clazz) {
    // Only interested in program classes.
  }

  @Override
  public void visitProgramClass(ProgramClass programClass) {
    programClass.accept(new AllAttributeVisitor(true, this));
  }

  @Override
  public void visitAnyAttribute(Clazz clazz, Attribute attribute) {
    // Only interested in code attributes.
  }

  @Override
  public void visitCodeAttribute(Clazz clazz, Method method, CodeAttribute codeAttribute) {
    // Check whether this code attribute should be analyzed.
    if (!shouldAnalyzeNextCodeAttribute.get()) {
      return;
    }

    currentClazzMethodAttribute = new CurrentClazzMethodAttribute(clazz, method, codeAttribute);

    // Exceptions while executing the partial evaluators are fine, the virtual
    // call resolving and argument/return value reconstruction handle these
    // cases gracefully.
    try {
      // Evaluate the code.
      multiTypeEvaluationSuccessful = false;
      multiTypeValueEvaluator.visitCodeAttribute0(clazz, method, codeAttribute);
      multiTypeEvaluationSuccessful = true;
    } catch (ExcessiveComplexityException e) {
      Metrics.increaseCount(MetricType.PARTIAL_EVALUATOR_EXCESSIVE_COMPLEXITY);
    } catch (EmptyCodeAttributeException e) {
      log.info(e);
    } catch (Exception e) {
      log.warn("Unexpected exception during multi type analysis", e);
    }

    if (useDominatorAnalysis) {
      dominatorCalculator.visitCodeAttribute(clazz, method, codeAttribute);
    }

    codeAttribute.instructionsAccept(clazz, method, this);
  }

  private void runValueReconstruction(Clazz clazz, Method method, CodeAttribute codeAttribute) {
    try {
      particularValueEvaluationSuccessful = false;
      particularValueEvaluator.visitCodeAttribute0(clazz, method, codeAttribute);
      particularValueEvaluationSuccessful = true;
    } catch (ExcessiveComplexityException e) {
      Metrics.increaseCount(MetricType.PARTIAL_EVALUATOR_EXCESSIVE_COMPLEXITY);
    } catch (EmptyCodeAttributeException e) {
      log.info(e);
    } catch (Exception e) {
      log.warn("Unexpected exception during particular value analysis", e);
    }
    currentClazzMethodAttribute.evaluated = true;
  }

  @Override
  public void visitAnyInstruction(
      Clazz clazz,
      Method method,
      CodeAttribute codeAttribute,
      int offset,
      Instruction instruction) {
    // Only interested in ConstantInstructions.
  }

  @Override
  public void visitConstantInstruction(
      Clazz clazz,
      Method method,
      CodeAttribute codeAttribute,
      int offset,
      ConstantInstruction constantInstruction) {
    // Get the line number.
    LineNumberFinder lineNumberFinder = new LineNumberFinder(offset);
    codeAttribute.attributesAccept(clazz, method, lineNumberFinder);
    CodeLocation location = new CodeLocation(clazz, method, offset, lineNumberFinder.lineNumber);

    Constant constant = ((ProgramClass) clazz).getConstant(constantInstruction.constantIndex);
    if (constantInstruction.opcode == Instruction.OP_INVOKEDYNAMIC
        && constant instanceof InvokeDynamicConstant) {
      handleInvokeDynamic(location, constantInstruction, (InvokeDynamicConstant) constant);
    } else if (constant instanceof AnyMethodrefConstant) {
      AnyMethodrefConstant ref = (AnyMethodrefConstant) constant;
      switch (constantInstruction.opcode) {
        case Instruction.OP_INVOKESTATIC:
          handleInvokeStatic(location, constantInstruction, (AnyMethodrefConstant) constant);
          break;
        case Instruction.OP_INVOKEVIRTUAL:
        case Instruction.OP_INVOKEINTERFACE:
          handleVirtualMethods(location, constantInstruction, ref);
          break;
        case Instruction.OP_INVOKESPECIAL:
          handleInvokeSpecial(location, constantInstruction, ref);
          break;
        default:
          Metrics.increaseCount(MetricType.UNSUPPORTED_OPCODE);
          log.warn("Unsupported invocation opcode {} at {}", constantInstruction.opcode, location);
      }
    }
  }

  private void addCall(
      CodeLocation location,
      String targetClass,
      String targetMethod,
      String targetDescriptor,
      int throwsNullptr,
      Instruction instruction,
      boolean runtimeTypeDependent) {
    if (skipIncompleteCalls
        && (targetClass == null || targetMethod == null || targetDescriptor == null)) {
      Metrics.increaseCount(MetricType.INCOMPLETE_CALL_SKIPPED);
      return;
    }

    boolean alwaysInvoked = true;
    if (useDominatorAnalysis) {
      alwaysInvoked =
          dominatorCalculator.dominates(location.offset, DominatorCalculator.EXIT_NODE_OFFSET);
    }
    Call call =
        instantiateCall(
            location,
            targetClass,
            targetMethod,
            targetDescriptor,
            throwsNullptr,
            instruction,
            !alwaysInvoked,
            runtimeTypeDependent);

    if (shouldReconstructParameters(call)) {
      if (!currentClazzMethodAttribute.evaluated) {
        runValueReconstruction(
            currentClazzMethodAttribute.clazz,
            currentClazzMethodAttribute.method,
            currentClazzMethodAttribute.codeAttribute);
      }
      initArgumentsAndReturnValue(call);
      callHandlers.forEach(
          d ->
              d.handleCall(
                  call,
                  particularValueEvaluator.getStackBefore(call.caller.offset),
                  particularValueEvaluator.getVariablesBefore(call.caller.offset)));
      if (clearCallValuesAfterVisit) {
        call.clearValues();
      }
    }
    if (callGraph != null) {
      callGraph.addCall(call);
    }
  }

  private boolean shouldReconstructParameters(Call call) {
    if (!selectiveParameterReconstruction) {
      return true;
    }
    return interestingMethods.contains(call.getTarget())
        || interestingCallPredicates.stream().anyMatch(p -> p.test(call));
  }

  /**
   * Creates the appropriate object for the requested call ({@link ConcreteCall} in case the target
   * method is already present in the class pool, otherwise a {@link SymbolicCall}).
   */
  private Call instantiateCall(
      CodeLocation location,
      String targetClass,
      String targetMethod,
      String targetDescriptor,
      int throwsNullptr,
      Instruction instruction,
      boolean controlFlowDependent,
      boolean runtimeTypeDependent) {
    if (targetClass != null && targetMethod != null && targetDescriptor != null) {
      Clazz containingClass = programClassPool.getClass(targetClass);
      if (containingClass == null) {
        containingClass = libraryClassPool.getClass(targetClass);
      }

      if (containingClass != null) {
        Method method = containingClass.findMethod(targetMethod, targetDescriptor);
        if (method != null) {
          Metrics.increaseCount(MetricType.CONCRETE_CALL);
          if ((method.getAccessFlags() & AccessConstants.ABSTRACT) != 0) {
            Metrics.increaseCount(MetricType.CALL_TO_ABSTRACT_METHOD);
          }
          if (method instanceof ProgramMethod
              && Arrays.stream(((ProgramMethod) method).attributes)
                  .noneMatch(a -> a instanceof CodeAttribute)) {
            Metrics.increaseCount(MetricType.CONCRETE_CALL_NO_CODE_ATTRIBUTE);
          }

          return new ConcreteCall(
              location,
              containingClass,
              method,
              throwsNullptr,
              instruction,
              controlFlowDependent,
              runtimeTypeDependent);
        }
      }
    }

    Metrics.increaseCount(MetricType.SYMBOLIC_CALL);
    return new SymbolicCall(
        location,
        new MethodSignature(targetClass, targetMethod, targetDescriptor),
        throwsNullptr,
        instruction,
        controlFlowDependent,
        runtimeTypeDependent);
  }

  private void initArgumentsAndReturnValue(Call call) {
    MethodSignature target = call.getTarget();
    List<Value> arguments = getArguments(call.caller, target, call.isStatic());
    if (!call.isStatic() && !arguments.isEmpty()) {
      // Handle the instance pointer separately.
      call.setInstance(arguments.remove(0));
    }

    call.setArguments(arguments);

    if (target.descriptor.getReturnType().charAt(0) != TypeConstants.VOID
        && particularValueEvaluationSuccessful) {
      call.setReturnValue(
          PartialEvaluatorUtils.getStackValue(
              particularValueEvaluator.getStackAfter(call.caller.offset), 0));
    }
  }

  private List<Value> getArguments(
      CodeLocation location, MethodSignature invokedMethodSig, boolean isStaticCall) {
    if (invokedMethodSig.descriptor.getArgumentTypes() == null) {
      log.warn("Argument types list of {} is null!", invokedMethodSig);
      return Collections.emptyList();
    }

    List<Value> args = new ArrayList<>();
    int stackOffset = 0;
    for (int argNumber = invokedMethodSig.descriptor.getArgumentTypes().size() - 1;
        argNumber >= 0;
        argNumber--) {
      String argType = invokedMethodSig.descriptor.getArgumentTypes().get(argNumber);
      // Usually we are interested in concrete values for the arguments, i.e. we take them
      // from the particular value evaluator. But it can happen that this evaluator doesn't
      // know the argument value because it depends on some control flow specifics. Still,
      // we might at least know what type(s) the argument can have, which is better than nothing.
      // In that case the multitype evaluator needs to be consulted. Thus, we first ask the
      // multitype evaluator if the argument is known to have more than one possible type.
      // If this is the case, we can already assume that there is no known particular value
      // for it. Otherwise, we get the particular value as initially planned.
      Value stackTop =
          PartialEvaluatorUtils.getStackBefore(
              multiTypeValueEvaluator, location.offset, stackOffset);
      if (!(stackTop instanceof MultiTypedReferenceValue
          && ((MultiTypedReferenceValue) stackTop).getPotentialTypes().size() > 1)) {
        stackTop =
            PartialEvaluatorUtils.getStackBefore(
                particularValueEvaluator, location.offset, stackOffset);
      }
      // Make sure to reverse the parameter ordering.
      args.add(0, stackTop);
      stackOffset += ClassUtil.internalTypeSize(argType);
    }

    if (!isStaticCall) {
      // For virtual calls we have the instance pointer as a first argument.
      Value instance =
          PartialEvaluatorUtils.getStackBefore(
              multiTypeValueEvaluator, location.offset, stackOffset);
      if (!(instance instanceof MultiTypedReferenceValue
          && ((MultiTypedReferenceValue) instance).getPotentialTypes().size() > 1)) {
        instance =
            PartialEvaluatorUtils.getStackBefore(
                particularValueEvaluator, location.offset, stackOffset);
      }
      args.add(0, instance);
    }

    return args;
  }

  /**
   * Resolve <code>invokedynamic</code> instructions. See <a
   * href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.invokedynamic">JVM
   * spec §6.5.invokedynamic</a>.
   *
   * @param location The {@link Location} of the instruction.
   * @param instruction The <code>invokedynamic</code> instruction.
   * @param constant The {@link InvokeDynamicConstant} (JVM spec: "symbolic reference R") containing
   *     the index to the corresponding bootstrap method that will be called to identify the actual
   *     call target. Additionally, it gives more details about that method through an {@link
   *     NameAndTypeConstant}.
   */
  private void handleInvokeDynamic(
      CodeLocation location, Instruction instruction, InvokeDynamicConstant constant) {
    // The actual target of the call is unknown, as the bootstrap method that is executed
    // the first time this instruction is encountered, is able to freely determine what call
    // site will be used for invocation. This can even be a completely new method that is
    // injected at runtime, and the only thing we know about it for sure is the descriptor.
    addCall(
        location, null, null, constant.getType(location.clazz), Value.NEVER, instruction, false);
  }

  /**
   * Resolve <code>invokestatic</code> instructions. See <a
   * href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.invokestatic">JVM
   * spec §6.5.invokestatic</a>
   *
   * @param location The {@link Location} of the instruction.
   * @param instruction The <code>invokestatic</code> instruction.
   * @param constant The {@link AnyMethodrefConstant} specifying the exact method to be invoked.
   */
  private void handleInvokeStatic(
      CodeLocation location, Instruction instruction, AnyMethodrefConstant constant) {
    addCall(
        location,
        constant.getClassName(location.clazz),
        constant.getName(location.clazz),
        constant.getType(location.clazz),
        Value.NEVER,
        instruction,
        false);
  }

  /**
   * Resolve <code>invokespecial</code> instructions. They are always used for <code>super.x()
   * </code> calls and constructor invocations. According to the <a
   * href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.invokespecial">JVM
   * spec §6.5.invokespecial</a>, this opcode is also sometimes used for private method calls, but
   * so far I haven't seen that in the wild.
   *
   * @param location The {@link Location} of the instruction.
   * @param instruction The <code>invokespecial</code> instruction.
   * @param ref The {@link AnyMethodrefConstant} specifying name and descriptor of the method to be
   *     invoked.
   */
  private void handleInvokeSpecial(
      CodeLocation location, Instruction instruction, AnyMethodrefConstant ref) {
    Set<String> targets = resolveInvokeSpecial(location.clazz, ref);
    if (targets.isEmpty()) {
      Metrics.increaseCount(MetricType.MISSING_METHODS);
    } else {
      String name = ref.getName(location.clazz);
      String descriptor = ref.getType(location.clazz);
      for (String target : targets) {
        addCall(location, target, name, descriptor, Value.NEVER, instruction, false);
      }
    }
  }

  /**
   * The <code>invokespecial</code> resolution algorithm, annotated with JVM spec citations where
   * appropriate, so that the specified lookup process can easily be compared to this
   * implementation.
   *
   * @param callingClass JVM spec: "current class".
   * @param ref The {@link AnyMethodrefConstant} specifying name and descriptor of the method to be
   *     invoked.
   * @return The fully qualified names of potential call target classes (usually just one, but see
   *     {@link CallUtil#resolveFromSuperinterfaces(Clazz, String, String)} for details on when
   *     there might be multiple).
   */
  private Set<String> resolveInvokeSpecial(Clazz callingClass, AnyMethodrefConstant ref) {
    String name = ref.getName(callingClass);
    String descriptor = ref.getType(callingClass);

    // "If all the following are true, let C be the direct superclass of the current class:"
    Clazz c;
    // ACC_SUPER flag is set (should always be the case, legacy flag). See JVM Spec §4.1.
    if ((callingClass.getAccessFlags() & AccessConstants.SUPER) != 0
        && !name.equals(ClassConstants.METHOD_NAME_INIT) // Not an instance initialization method.
        && ref.referencedClass != null // Referenced class available.
        && (ref.referencedClass.getAccessFlags() & AccessConstants.INTERFACE)
            == 0 // Not an interface reference.
        && callingClass.extends_(ref.referencedClass)
        && !callingClass
            .getName()
            .equals(
                ref.referencedClass
                    .getName())) // Referenced class is strictly a superclass of the current class.
    {
      c = callingClass.getSuperClass();
    } else {
      // "Otherwise, let C be the class or interface named by the symbolic reference".
      c = ref.referencedClass;
    }
    if (c == null) {
      // In this case, we don't have the referenced class in our class pool, so we can't be more
      // specific here.
      String className = ref.getClassName(callingClass);
      Metrics.increaseCount(MetricType.MISSING_CLASS);
      return Collections.singleton(className);
    }

    // 1. (lookup in C directly)
    if (c.findMethod(name, descriptor) != null) {
      return Collections.singleton(c.getName());
    }

    Optional<String> target = Optional.empty();
    if ((c.getAccessFlags() & AccessConstants.INTERFACE) == 0) {
      // 2. (C is a class -> check superclasses transitively)
      target = CallUtil.resolveFromSuperclasses(c, name, descriptor);
    } else {
      // 3. (C is an interface -> Check if java.lang.Object has a suitable method)
      for (java.lang.reflect.Method m : Object.class.getMethods()) {
        if ((m.getModifiers() & Modifier.PUBLIC) != 0
            && m.getName().equals(name)
            && getDescriptor(m).equals(descriptor)) {
          target = Optional.of(ClassConstants.NAME_JAVA_LANG_OBJECT);
          break;
        }
      }
    }
    // 4. (Otherwise find maximally specific method from superinterfaces)
    return target
        .map(Collections::singleton)
        .orElseGet(() -> CallUtil.resolveFromSuperinterfaces(c, name, descriptor));
  }

  /** Get the Descriptor of a {@link java.lang.reflect.Method}. */
  public static String getDescriptor(java.lang.reflect.Method m) {
    List<String> parameters =
        Arrays.stream(m.getParameterTypes()).map(Class::getName).collect(Collectors.toList());
    return ClassUtil.internalMethodDescriptor(m.getReturnType().getName(), parameters);
  }

  /**
   * Handle <code>invokevirtual</code> and <code>invokeinterface</code> instructions, as they use
   * more or less the same lookup process. They are used for "normal" method calls, i.e. any
   * instance method. The actual invocation target depends on the type of the <code>this</code>
   * pointer during runtime (first/bottom stack parameter). In order to get a good estimate of this
   * type, the lookup process depends on the analysis by a {@link PartialEvaluator} that yields
   * {@link MultiTypedReferenceValue} elements.
   *
   * @param location The {@link Location} of this instruction.
   * @param instruction The invocation instruction.
   * @param ref The {@link AnyMethodrefConstant} specifying name and descriptor of the method to be
   *     invoked.
   */
  private void handleVirtualMethods(
      CodeLocation location, Instruction instruction, AnyMethodrefConstant ref) {
    String name = ref.getName(location.clazz);
    String descriptor = ref.getType(location.clazz);

    // Estimate the runtime type of this pointer: Intraprocedural analysis performed by the
    // partial evaluator.
    int argumentCount = ClassUtil.internalMethodParameterSize(descriptor, false);
    Value thisPtr =
        PartialEvaluatorUtils.getStackBefore(
            multiTypeValueEvaluator, location.offset, argumentCount - 1);
    if (!(thisPtr instanceof MultiTypedReferenceValue)) {
      // If the partial evaluation has not finished, this is to be expected and does not warrant an
      // error message.
      if (multiTypeEvaluationSuccessful) {
        Metrics.increaseCount(MetricType.PARTIAL_EVALUATOR_VALUE_IMPRECISE);
      }
    } else {
      MultiTypedReferenceValue multiTypeThisPtr = (MultiTypedReferenceValue) thisPtr;

      for (TypedReferenceValue possibleType : multiTypeThisPtr.getPotentialTypes()) {
        if (possibleType.isNull() == Value.ALWAYS) {
          // This will always throw a NullPointerException, but we still want this info in the call
          // graph.
          addCall(
              location,
              ref.getClassName(location.clazz),
              ref.getName(location.clazz),
              ref.getType(location.clazz),
              Value.ALWAYS,
              instruction,
              multiTypeThisPtr.getPotentialTypes().size() > 1);
          continue;
        }

        Clazz referencedClass;
        if (ClassUtil.isInternalArrayType(possibleType.getType())) {
          // If anybody wants to call methods on arrays, we need to check java.lang.Object.
          referencedClass = libraryClassPool.getClass(ClassConstants.NAME_JAVA_LANG_OBJECT);
        } else {
          referencedClass = possibleType.getReferencedClass();
          // Sometimes the type doesn't have a reference to the class yet.
          // In this case we should try to look it up manually in both class pools.
          if (referencedClass == null) {
            referencedClass =
                programClassPool.getClass(
                    ClassUtil.internalClassNameFromClassType(possibleType.getType()));
          }
          if (referencedClass == null) {
            referencedClass =
                libraryClassPool.getClass(
                    ClassUtil.internalClassNameFromClassType(possibleType.getType()));
          }
        }

        if (referencedClass == null) {
          // Class still wasn't found, add it to the missing classes.
          Metrics.increaseCount(MetricType.MISSING_CLASS);
        }

        Set<String> targetClasses = CallUtil.resolveVirtual(location.clazz, referencedClass, ref);
        if (targetClasses.isEmpty()) {
          if (referencedClass != null) {
            Metrics.increaseCount(MetricType.MISSING_METHODS);
          }
          targetClasses =
              Collections.singleton(
                  ClassUtil.internalClassNameFromClassType(possibleType.getType()));
        }

        for (String targetClass : targetClasses) {
          addCall(
              location,
              targetClass,
              name,
              descriptor,
              multiTypeThisPtr.isNull(),
              instruction,
              multiTypeThisPtr.getPotentialTypes().size() > 1);
        }
      }
    }
  }

  private static class CurrentClazzMethodAttribute {
    public final Clazz clazz;
    public final Method method;
    public final CodeAttribute codeAttribute;
    public boolean evaluated = false;

    public CurrentClazzMethodAttribute(Clazz clazz, Method method, CodeAttribute codeAttribute) {
      this.clazz = clazz;
      this.method = method;
      this.codeAttribute = codeAttribute;
    }
  }

  public static class Builder {

    private final ClassPool programClassPool;
    private final ClassPool libraryClassPool;
    private final CallGraph callGraph;
    private final CallHandler[] callHandlers;
    private boolean clearCallValuesAfterVisit = true;
    private boolean useDominatorAnalysis = false;
    private boolean evaluateAllCode = false;
    private boolean includeSubClasses = false;
    private int maxPartialEvaluations = 50;
    private Supplier<Boolean> shouldAnalyzeNextCodeAttribute = () -> true;
    private boolean skipIncompleteCalls = true;
    private ValueFactory arrayValueFactory = new ArrayReferenceValueFactory();
    private boolean ignoreExceptions = true;
    private boolean selectiveParameterReconstruction = false;
    private Set<MethodSignature> interestingMethods;
    private Set<Predicate<Call>> interestingCallPredicates;

    private ExecutingInvocationUnit.Builder executingInvocationUnitBuilder;

    public Builder(
        ClassPool programClassPool,
        ClassPool libraryClassPool,
        CallGraph callGraph,
        CallHandler... visitors) {
      this.programClassPool = Objects.requireNonNull(programClassPool);
      this.libraryClassPool = Objects.requireNonNull(libraryClassPool);
      this.executingInvocationUnitBuilder =
          new ExecutingInvocationUnit.Builder(programClassPool, libraryClassPool);
      this.callGraph = callGraph;
      this.callHandlers = visitors;
    }

    /**
     * If true, {@link Call#clearValues()} will be called after {@link CallHandler#handleCall(Call,
     * TracedStack, TracedVariables)}. This makes it possible to analyze arguments and the return
     * value of calls while still adding them to a {@link CallGraph} afterward, as call graph
     * analysis itself usually only requires the call locations and their targets, not the arguments
     * or return value.
     */
    public Builder setClearCallValuesAfterVisit(boolean clearCallValuesAfterVisit) {
      this.clearCallValuesAfterVisit = clearCallValuesAfterVisit;
      return this;
    }

    /**
     * If true, a dominator analysis is carried out using the {@link DominatorCalculator} for each
     * method, in order to be able to fill the {@link Call#controlFlowDependent} flag.
     */
    public Builder setUseDominatorAnalysis(boolean useDominatorAnalysis) {
      this.useDominatorAnalysis = useDominatorAnalysis;
      return this;
    }

    /** See {@link PartialEvaluator.Builder#setEvaluateAllCode(boolean)}. */
    public Builder setEvaluateAllCode(boolean evaluateAllCode) {
      this.evaluateAllCode = evaluateAllCode;
      return this;
    }

    /**
     * If true, virtual calls on class fields, parameters and return values of other methods will
     * take all possible subclasses into account.
     *
     * <p>This is necessary for a more complete call graph, because the runtime type of these
     * objects is not controlled by the current method. E.g. a method that declares its return type
     * to be of type A might also return an object of type B in case B extends A. The same is true
     * for class fields and parameters, so in order to really find all potential calls, this
     * circumstance needs to be modeled. For objects of declared type {@link java.lang.Object} this
     * will be skipped, as the fact that every single Java class is a subclass of object would lead
     * to an immense blow-up of the call graph.
     */
    public Builder setIncludeSubClasses(boolean includeSubClasses) {
      this.includeSubClasses = includeSubClasses;
      return this;
    }

    /** See {@link PartialEvaluator.Builder#stopAnalysisAfterNEvaluations(int)}. */
    public Builder setMaxPartialEvaluations(int maxPartialEvaluations) {
      this.maxPartialEvaluations = maxPartialEvaluations;
      return this;
    }

    /**
     * If returns true, the next code attribute will be analyzed. Otherwise, the code attribute will
     * be skipped.
     */
    public Builder setShouldAnalyzeNextCodeAttribute(
        Supplier<Boolean> shouldAnalyzeNextCodeAttribute) {
      this.shouldAnalyzeNextCodeAttribute = shouldAnalyzeNextCodeAttribute;
      return this;
    }

    /**
     * If true, any discovered call that would return true for {@link Call#hasIncompleteTarget()}
     * will be discarded and not be forwarded to {@link CallHandler#handleCall(Call, TracedStack,
     * TracedVariables)}.
     */
    public Builder setSkipIncompleteCalls(boolean skipIncompleteCalls) {
      this.skipIncompleteCalls = skipIncompleteCalls;
      return this;
    }

    /**
     * @param arrayValueFactory should be set either with {@link DetailedArrayValueFactory} or
     *     {@link ArrayReferenceValueFactory}
     * @return {@link Builder} object
     */
    public Builder setArrayValueFactory(ValueFactory arrayValueFactory) {
      this.arrayValueFactory = arrayValueFactory;
      return this;
    }

    /** If false, exceptions will be taken into account during control flow analysis. */
    public Builder setIgnoreExceptions(boolean ignoreExceptions) {
      this.ignoreExceptions = ignoreExceptions;
      return this;
    }

    /**
     * When used allows parameter reconstruction based on the {@link PartialEvaluator} to only be
     * executed for the calls that match (1) the <code>interestingMethods</code> signatures OR (2)
     * the <code>interestingCallPredicates</code>.
     */
    public Builder useSelectiveParameterReconstruction(
        Set<MethodSignature> interestingMethods, Set<Predicate<Call>> interestingCallPredicates) {
      this.selectiveParameterReconstruction = true;
      this.interestingMethods = interestingMethods;
      this.interestingCallPredicates = interestingCallPredicates;
      return this;
    }

    /**
     * @param executingInvocationUnitBuilder a builder for the invocation unit used for particular
     *     value analysis.
     * @return {@link Builder} object
     */
    public Builder setExecutingInvocationUnitBuilder(
        ExecutingInvocationUnit.Builder executingInvocationUnitBuilder) {
      this.executingInvocationUnitBuilder = executingInvocationUnitBuilder;
      return this;
    }

    public CallResolver build() {
      return new CallResolver(
          programClassPool,
          libraryClassPool,
          callGraph,
          clearCallValuesAfterVisit,
          useDominatorAnalysis,
          evaluateAllCode,
          includeSubClasses,
          maxPartialEvaluations,
          shouldAnalyzeNextCodeAttribute,
          skipIncompleteCalls,
          arrayValueFactory,
          ignoreExceptions,
          executingInvocationUnitBuilder,
          selectiveParameterReconstruction,
          interestingMethods,
          interestingCallPredicates,
          callHandlers);
    }
  }
}
