/*
 * ProGuardCORE -- library to process Java bytecode.
 *
 * Copyright (c) 2002-2023 Guardsquare NV
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

package proguard.evaluation.executor;

import java.util.Set;
import proguard.classfile.MethodSignature;
import proguard.evaluation.ExecutingInvocationUnit;
import proguard.evaluation.MethodResult;
import proguard.evaluation.ValueCalculator;
import proguard.evaluation.value.Value;
import proguard.evaluation.value.object.model.Model;

/**
 * This abstract class specifies a modular component which can be added to a {@link
 * ExecutingInvocationUnit} in order to extend its capabilities. An {@link Executor} specifies which
 * method calls it supports, what methods return their own instance and how a method result is
 * calculated.
 */
public interface Executor {

  /**
   * Calculate the result of a given method. This is the return value for a non-constructor method
   * (<c>null</c> if it returns <c>void</c>) and the instantiated object for a constructor.
   *
   * @param methodData Information about the called method.
   * @param valueCalculator a function mapping the result of a method invocation (can be an Object
   *     with the result if the executor calculates a real value or a {@link Model}) to the
   *     appropriate {@link Value} used by the analysis. Should also be used to create values of
   *     unknown value since the executor might be able to provide additional information on them
   *     even if the value itself is not known (e.g., on the identifier of the returned value).
   * @return The result of the method call, including information on the returned value or values or
   *     arguments on which side effects happened during the method execution. If the executor is
   *     not able to provide any additional information for any reason the returned value should be
   *     {@link MethodResult#invalidResult()}, so that the caller can know that execution failed and
   *     just provide its best approximation of the result.
   */
  MethodResult getMethodResult(MethodExecutionInfo methodData, ValueCalculator valueCalculator);

  /**
   * Get a list of method signatures that indicate which methods are supported by this executor.
   *
   * <p>The returned methods should be exactly the ones that the executor is expected to be able to
   * handle. The invocation unit does not make any additional assumption on the executors available
   * to execute a method and will match them iff the signature of the target method matches exactly.
   *
   * <p>Only full method signatures should be returned, the invocation unit does not support
   * wildcards.
   *
   * @return a set of methods the executor can handle.
   */
  Set<MethodSignature> getSupportedMethodSignatures();

  /**
   * A builder for the executor. It's important for each concrete executor to provide one in order
   * to be able to construct a fresh copy of the executor when needed
   *
   * <p>For example this happens in {@link proguard.evaluation.ExecutingInvocationUnit.Builder},
   * since each invocation unit should use different executors in order to avoid undesired side
   * effects.
   */
  interface Builder<T extends Executor> {

    /**
     * Build an executor. If the executor keeps internal state this method needs to guarantee that
     * no data is shared between the different implementations (unless needed by design, e.g., for
     * caching).
     */
    T build();
  }
}
