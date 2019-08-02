// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.optimize;

import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.graph.AccessFlags;
import com.debughelper.tools.r8.graph.AppInfoWithSubtyping;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.GraphLense;
import com.debughelper.tools.r8.ir.analysis.type.TypeEnvironment;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionIterator;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.Invoke;
import com.debughelper.tools.r8.ir.code.InvokeMethod;
import com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.debughelper.tools.r8.ir.code.Position;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.ir.code.ValueNumberGenerator;
import com.debughelper.tools.r8.ir.conversion.CallSiteInformation;
import com.debughelper.tools.r8.ir.conversion.IRConverter;
import com.debughelper.tools.r8.ir.conversion.LensCodeRewriter;
import com.debughelper.tools.r8.ir.conversion.OptimizationFeedback;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.shaking.Enqueuer;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class Inliner {
  private static final int INITIAL_INLINING_INSTRUCTION_ALLOWANCE = 1500;

  protected final Enqueuer.AppInfoWithLiveness appInfo;
  private final com.debughelper.tools.r8.graph.GraphLense graphLense;
  final com.debughelper.tools.r8.utils.InternalOptions options;

  // State for inlining methods which are known to be called twice.
  private boolean applyDoubleInlining = false;
  private final Set<com.debughelper.tools.r8.graph.DexEncodedMethod> doubleInlineCallers = Sets.newIdentityHashSet();
  private final Set<com.debughelper.tools.r8.graph.DexEncodedMethod> doubleInlineSelectedTargets = Sets.newIdentityHashSet();
  private final Map<com.debughelper.tools.r8.graph.DexEncodedMethod, com.debughelper.tools.r8.graph.DexEncodedMethod> doubleInlineeCandidates = new HashMap<>();

  private final Set<com.debughelper.tools.r8.graph.DexMethod> blackList = Sets.newIdentityHashSet();

  public Inliner(
      Enqueuer.AppInfoWithLiveness appInfo,
      com.debughelper.tools.r8.graph.GraphLense graphLense,
      com.debughelper.tools.r8.utils.InternalOptions options) {
    this.appInfo = appInfo;
    this.graphLense = graphLense;
    this.options = options;
    fillInBlackList(appInfo);
  }

  private void fillInBlackList(Enqueuer.AppInfoWithLiveness appInfo) {
    blackList.add(appInfo.dexItemFactory.kotlin.intrinsics.throwParameterIsNullException);
    blackList.add(appInfo.dexItemFactory.kotlin.intrinsics.throwNpe);
  }

  public boolean isBlackListed(com.debughelper.tools.r8.graph.DexMethod method) {
    return blackList.contains(method);
  }

  private Constraint instructionAllowedForInlining(
          com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.Instruction instruction) {
    Constraint result = instruction.inliningConstraint(appInfo, method.method.holder);
    if ((result == Constraint.NEVER) && instruction.isDebugInstruction()) {
      return Constraint.ALWAYS;
    }
    return result;
  }

  public Constraint computeInliningConstraint(com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    Constraint result = Constraint.ALWAYS;
    com.debughelper.tools.r8.ir.code.InstructionIterator it = code.instructionIterator();
    while (it.hasNext()) {
      com.debughelper.tools.r8.ir.code.Instruction instruction = it.next();
      Constraint state = instructionAllowedForInlining(method, instruction);
      result = Constraint.min(result, state);
      if (result == Constraint.NEVER) {
        break;
      }
    }
    return result;
  }

  boolean hasInliningAccess(com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.graph.DexEncodedMethod target) {
    if (!isVisibleWithFlags(target.method.holder, method.method.holder, target.accessFlags)) {
      return false;
    }
    // The class needs also to be visible for us to have access.
    com.debughelper.tools.r8.graph.DexClass targetClass = appInfo.definitionFor(target.method.holder);
    return isVisibleWithFlags(target.method.holder, method.method.holder, targetClass.accessFlags);
  }

  private boolean isVisibleWithFlags(com.debughelper.tools.r8.graph.DexType target, com.debughelper.tools.r8.graph.DexType context, com.debughelper.tools.r8.graph.AccessFlags flags) {
    if (flags.isPublic()) {
      return true;
    }
    if (flags.isPrivate()) {
      return target == context;
    }
    if (flags.isProtected()) {
      return context.isSubtypeOf(target, appInfo) || target.isSamePackage(context);
    }
    // package-private
    return target.isSamePackage(context);
  }

  synchronized boolean isDoubleInliningTarget(
          com.debughelper.tools.r8.ir.conversion.CallSiteInformation callSiteInformation, com.debughelper.tools.r8.graph.DexEncodedMethod candidate) {
    return callSiteInformation.hasDoubleCallSite(candidate)
        || doubleInlineSelectedTargets.contains(candidate);
  }

  synchronized com.debughelper.tools.r8.graph.DexEncodedMethod doubleInlining(com.debughelper.tools.r8.graph.DexEncodedMethod method,
                                                                              com.debughelper.tools.r8.graph.DexEncodedMethod target) {
    if (!applyDoubleInlining) {
      if (doubleInlineeCandidates.containsKey(target)) {
        // Both calls can be inlined.
        doubleInlineCallers.add(doubleInlineeCandidates.get(target));
        doubleInlineCallers.add(method);
        doubleInlineSelectedTargets.add(target);
      } else {
        // First call can be inlined.
        doubleInlineeCandidates.put(target, method);
      }
      // Just preparing for double inlining.
      return null;
    } else {
      // Don't perform the actual inlining if this was not selected.
      if (!doubleInlineSelectedTargets.contains(target)) {
        return null;
      }
    }
    return target;
  }

  public synchronized void processDoubleInlineCallers(
          IRConverter converter, OptimizationFeedback feedback) {
    if (doubleInlineCallers.size() > 0) {
      applyDoubleInlining = true;
      List<com.debughelper.tools.r8.graph.DexEncodedMethod> methods = doubleInlineCallers
          .stream()
          .sorted(com.debughelper.tools.r8.graph.DexEncodedMethod::slowCompare)
          .collect(Collectors.toList());
      for (com.debughelper.tools.r8.graph.DexEncodedMethod method : methods) {
        converter.processMethod(method, feedback, x -> false, com.debughelper.tools.r8.ir.conversion.CallSiteInformation.empty(),
            Outliner::noProcessing);
        assert method.isProcessed();
      }
    }
  }

  /**
   * Encodes the constraints for inlining a method's instructions into a different context.
   * <p>
   * This only takes the instructions into account and not whether a method should be inlined or
   * what reason for inlining it might have. Also, it does not take the visibility of the method
   * itself into account.
   */
  public enum Constraint {
    // The ordinal values are important so please do not reorder.
    NEVER,     // Never inline this.
    SAMECLASS, // Only inline this into methods with same holder.
    PACKAGE,   // Only inline this into methods with holders from same package.
    SUBCLASS,  // Only inline this into methods with holders from a subclass.
    ALWAYS;    // No restrictions for inlining this.

    static {
      assert NEVER.ordinal() < SAMECLASS.ordinal();
      assert SAMECLASS.ordinal() < PACKAGE.ordinal();
      assert PACKAGE.ordinal() < SUBCLASS.ordinal();
      assert SUBCLASS.ordinal() < ALWAYS.ordinal();
    }

    public static Constraint deriveConstraint(
        com.debughelper.tools.r8.graph.DexType contextHolder,
        com.debughelper.tools.r8.graph.DexType targetHolder,
        AccessFlags flags,
        com.debughelper.tools.r8.graph.AppInfoWithSubtyping appInfo) {
      if (flags.isPublic()) {
        return ALWAYS;
      } else if (flags.isPrivate()) {
        return targetHolder == contextHolder ? SAMECLASS : NEVER;
      } else if (flags.isProtected()) {
        if (targetHolder.isSamePackage(contextHolder)) {
          // Even though protected, this is visible via the same package from the context.
          return PACKAGE;
        } else if (contextHolder.isSubtypeOf(targetHolder, appInfo)) {
          return SUBCLASS;
        }
        return NEVER;
      } else {
        /* package-private */
        return targetHolder.isSamePackage(contextHolder) ? PACKAGE : NEVER;
      }
    }

    public static Constraint classIsVisible(com.debughelper.tools.r8.graph.DexType context, com.debughelper.tools.r8.graph.DexType clazz,
                                            com.debughelper.tools.r8.graph.AppInfoWithSubtyping appInfo) {
      if (clazz.isArrayType()) {
        return classIsVisible(context, clazz.toArrayElementType(appInfo.dexItemFactory), appInfo);
      }

      if (clazz.isPrimitiveType()) {
        return ALWAYS;
      }

      DexClass definition = appInfo.definitionFor(clazz);
      return definition == null ? NEVER
          : deriveConstraint(context, clazz, definition.accessFlags, appInfo);
    }

    public static Constraint min(Constraint one, Constraint other) {
      return one.ordinal() < other.ordinal() ? one : other;
    }
  }

  /**
   * Encodes the reason why a method should be inlined.
   * <p>
   * This is independent of determining whether a method can be inlined, except for the FORCE state,
   * that will inline a method irrespective of visibility and instruction checks.
   */
  public enum Reason {
    FORCE,         // Inlinee is marked for forced inlining (bridge method or renamed constructor).
    ALWAYS,        // Inlinee is marked for inlining due to alwaysinline directive.
    SINGLE_CALLER, // Inlinee has precisely one caller.
    DUAL_CALLER,   // Inlinee has precisely two callers.
    SIMPLE,        // Inlinee has simple code suitable for inlining.
  }

  static public class InlineAction {

    public final com.debughelper.tools.r8.graph.DexEncodedMethod target;
    public final com.debughelper.tools.r8.ir.code.Invoke invoke;
    final Reason reason;

    InlineAction(com.debughelper.tools.r8.graph.DexEncodedMethod target, Invoke invoke, Reason reason) {
      this.target = target;
      this.invoke = invoke;
      this.reason = reason;
    }

    boolean ignoreInstructionBudget() {
      return reason != Reason.SIMPLE;
    }

    public com.debughelper.tools.r8.ir.code.IRCode buildInliningIR(
        ValueNumberGenerator generator,
        AppInfoWithSubtyping appInfo,
        GraphLense graphLense,
        InternalOptions options,
        com.debughelper.tools.r8.ir.code.Position callerPosition) {
      // Build the IR for a yet not processed method, and perform minimal IR processing.
      Origin origin = appInfo.originFor(target.method.holder);
      com.debughelper.tools.r8.ir.code.IRCode code = target.buildInliningIR(appInfo, options, generator, callerPosition, origin);
      if (!target.isProcessed()) {
        new LensCodeRewriter(graphLense, appInfo, options).rewrite(code, target);
      }
      return code;
    }
  }

  final int numberOfInstructions(com.debughelper.tools.r8.ir.code.IRCode code) {
    int numOfInstructions = 0;
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      numOfInstructions += block.getInstructions().size();
    }
    return numOfInstructions;
  }

  boolean legalConstructorInline(com.debughelper.tools.r8.graph.DexEncodedMethod method,
                                 com.debughelper.tools.r8.ir.code.InvokeMethod invoke, com.debughelper.tools.r8.ir.code.IRCode code) {

    // In the Java VM Specification section "4.10.2.4. Instance Initialization Methods and
    // Newly Created Objects" it says:
    //
    // Before that method invokes another instance initialization method of myClass or its direct
    // superclass on this, the only operation the method can perform on this is assigning fields
    // declared within myClass.

    // Allow inlining a constructor into a constructor of the same class, as the constructor code
    // is expected to adhere to the VM specification.
    com.debughelper.tools.r8.graph.DexType callerMethodHolder = method.method.holder;
    boolean callerMethodIsConstructor = method.isInstanceInitializer();
    com.debughelper.tools.r8.graph.DexType calleeMethodHolder = invoke.asInvokeMethod().getInvokedMethod().holder;
    // Calling a constructor on the same class from a constructor can always be inlined.
    if (callerMethodIsConstructor && callerMethodHolder == calleeMethodHolder) {
      return true;
    }

    // We cannot invoke <init> on other values than |this| on Dalvik 4.4.4. Compute whether
    // the receiver to the call was the this value at the call-site.
    boolean receiverOfInnerCallIsThisOfOuter = invoke.asInvokeDirect().getReceiver().isThis();

    // Don't allow inlining a constructor into a non-constructor if the first use of the
    // un-initialized object is not an argument of an invoke of <init>.
    // Also, we cannot inline a constructor if it initializes final fields, as such is only allowed
    // from within a constructor of the corresponding class.
    // Lastly, we can only inline a constructor, if its own <init> call is on the method's class. If
    // we inline into a constructor, calls to super.<init> are also OK if the receiver of the
    // super.<init> call is the this argument.
    InstructionIterator iterator = code.instructionIterator();
    com.debughelper.tools.r8.ir.code.Instruction instruction = iterator.next();
    // A constructor always has the un-initialized object as the first argument.
    assert instruction.isArgument();
    Value unInitializedObject = instruction.outValue();
    boolean seenSuperInvoke = false;
    while (iterator.hasNext()) {
      instruction = iterator.next();
      if (instruction.inValues().contains(unInitializedObject)) {
        if (instruction.isInvokeDirect() && !seenSuperInvoke) {
          DexMethod target = instruction.asInvokeDirect().getInvokedMethod();
          seenSuperInvoke = appInfo.dexItemFactory.isConstructor(target);
          boolean callOnConstructorThatCallsConstructorSameClass =
              calleeMethodHolder == target.holder;
          boolean callOnSupertypeOfThisInConstructor =
              callerMethodHolder.isImmediateSubtypeOf(target.holder)
                  && instruction.asInvokeDirect().getReceiver() == unInitializedObject
                  && receiverOfInnerCallIsThisOfOuter
                  && callerMethodIsConstructor;
          if (seenSuperInvoke
              // Calls to init on same class than the called constructor are OK.
              && !callOnConstructorThatCallsConstructorSameClass
              // If we are inlining into a constructor, calls to superclass init are only OK on the
              // |this| value in the outer context.
              && !callOnSupertypeOfThisInConstructor) {
            return false;
          }
        }
        if (!seenSuperInvoke) {
          return false;
        }
      }
      if (instruction.isInstancePut()) {
        // Fields may not be initialized outside of a constructor.
        if (!callerMethodIsConstructor) {
          return false;
        }
        DexField field = instruction.asInstancePut().getField();
        DexEncodedField target = appInfo.lookupInstanceTarget(field.getHolder(), field);
        if (target != null && target.accessFlags.isFinal()) {
          return false;
        }
      }
    }
    return true;
  }

  public static class InliningInfo {
    public final com.debughelper.tools.r8.graph.DexEncodedMethod target;
    public final com.debughelper.tools.r8.graph.DexType receiverType; // null, if unknown

    public InliningInfo(com.debughelper.tools.r8.graph.DexEncodedMethod target, com.debughelper.tools.r8.graph.DexType receiverType) {
      this.target = target;
      this.receiverType = receiverType;
    }
  }

  public void performForcedInlining(
      com.debughelper.tools.r8.graph.DexEncodedMethod method,
      com.debughelper.tools.r8.ir.code.IRCode code,
      Map<InvokeMethodWithReceiver, InliningInfo> invokesToInline) {

    ForcedInliningOracle oracle = new ForcedInliningOracle(method, invokesToInline);
    performInliningImpl(oracle, oracle, method, code);
  }

  public void performInlining(
      com.debughelper.tools.r8.graph.DexEncodedMethod method,
      com.debughelper.tools.r8.ir.code.IRCode code,
      TypeEnvironment typeEnvironment,
      Predicate<com.debughelper.tools.r8.graph.DexEncodedMethod> isProcessedConcurrently,
      CallSiteInformation callSiteInformation) {

    DefaultInliningOracle oracle =
        new DefaultInliningOracle(
            this,
            method,
            code,
            typeEnvironment,
            callSiteInformation,
            isProcessedConcurrently,
            options.inliningInstructionLimit,
            INITIAL_INLINING_INSTRUCTION_ALLOWANCE - numberOfInstructions(code));

    performInliningImpl(oracle, oracle, method, code);
  }

  private void performInliningImpl(
          InliningStrategy strategy, InliningOracle oracle, com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.IRCode code) {
    if (strategy.exceededAllowance()) {
      return;
    }

    List<com.debughelper.tools.r8.ir.code.BasicBlock> blocksToRemove = new ArrayList<>();
    ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blockIterator = code.listIterator();
    while (blockIterator.hasNext() && !strategy.exceededAllowance()) {
      com.debughelper.tools.r8.ir.code.BasicBlock block = blockIterator.next();
      if (blocksToRemove.contains(block)) {
        continue;
      }
      InstructionListIterator iterator = block.listIterator();
      while (iterator.hasNext() && !strategy.exceededAllowance()) {
        Instruction current = iterator.next();
        if (current.isInvokeMethod()) {
          com.debughelper.tools.r8.ir.code.InvokeMethod invoke = current.asInvokeMethod();
          InlineAction result = invoke.computeInlining(oracle, method.method.holder);
          if (result != null) {
            com.debughelper.tools.r8.graph.DexEncodedMethod target = result.target;
            com.debughelper.tools.r8.ir.code.Position invokePosition = invoke.getPosition();
            if (invokePosition.method == null) {
              assert invokePosition.isNone();
              invokePosition = Position.noneWithMethod(method.method, null);
            }
            assert invokePosition.callerPosition == null
                || invokePosition.getOutermostCaller().method == method.method;

            IRCode inlinee =
                result.buildInliningIR(
                    code.valueNumberGenerator, appInfo, graphLense, options, invokePosition);
            if (inlinee != null) {
              // TODO(64432527): Get rid of this additional check by improved inlining.
              if (block.hasCatchHandlers() && inlinee.computeNormalExitBlocks().isEmpty()) {
                continue;
              }

              // If this code did not go through the full pipeline, apply inlining to make sure
              // that force inline targets get processed.
              strategy.ensureMethodProcessed(target, inlinee);

              // Make sure constructor inlining is legal.
              assert !target.isClassInitializer();
              if (!strategy.isValidTarget(invoke, target, inlinee)) {
                continue;
              }
              com.debughelper.tools.r8.graph.DexType downcast = createDowncastIfNeeded(strategy, invoke, target);
              // Inline the inlinee code in place of the invoke instruction
              // Back up before the invoke instruction.
              iterator.previous();
              strategy.markInlined(inlinee);
              if (!strategy.exceededAllowance() || result.ignoreInstructionBudget()) {
                BasicBlock invokeSuccessor =
                    iterator.inlineInvoke(code, inlinee, blockIterator, blocksToRemove, downcast);
                blockIterator = strategy.
                    updateTypeInformationIfNeeded(inlinee, blockIterator, block, invokeSuccessor);

                // If we inlined the invoke from a bridge method, it is no longer a bridge method.
                if (method.accessFlags.isBridge()) {
                  method.accessFlags.unsetSynthetic();
                  method.accessFlags.unsetBridge();
                }

                method.copyMetadataFromInlinee(target);
              }
            }
          }
        }
      }
    }
    oracle.finish();
    code.removeBlocks(blocksToRemove);
    code.removeAllTrivialPhis();
    assert code.isConsistentSSA();
  }

  private com.debughelper.tools.r8.graph.DexType createDowncastIfNeeded(
          InliningStrategy strategy, InvokeMethod invoke, DexEncodedMethod target) {
    if (invoke.isInvokeMethodWithReceiver()) {
      // If the invoke has a receiver but the actual type of the receiver is different
      // from the computed target holder, inlining requires a downcast of the receiver.
      DexType assumedReceiverType = strategy.getReceiverTypeIfKnown(invoke);
      if (assumedReceiverType == null) {
        // In case we don't know exact type of the receiver we use declared
        // method holder as a fallback.
        assumedReceiverType = invoke.getInvokedMethod().getHolder();
      }
      if (assumedReceiverType != target.method.getHolder()) {
        return target.method.getHolder();
      }
    }
    return null;
  }
}
