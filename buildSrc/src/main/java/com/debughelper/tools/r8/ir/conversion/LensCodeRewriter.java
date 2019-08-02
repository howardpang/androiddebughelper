// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.conversion;

import com.debughelper.tools.r8.graph.DexMethodHandle;
import com.debughelper.tools.r8.graph.DexMethodHandle.MethodHandleType;
import com.debughelper.tools.r8.graph.DexValue;
import com.debughelper.tools.r8.graph.DexValue.DexValueMethodHandle;
import com.debughelper.tools.r8.ir.code.Invoke;
import com.debughelper.tools.r8.ir.code.Invoke.Type;
import com.debughelper.tools.r8.graph.AppInfoWithSubtyping;
import com.debughelper.tools.r8.graph.DexCallSite;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.GraphLense;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.CheckCast;
import com.debughelper.tools.r8.ir.code.ConstClass;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.InstanceGet;
import com.debughelper.tools.r8.ir.code.InstanceOf;
import com.debughelper.tools.r8.ir.code.InstancePut;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.InvokeCustom;
import com.debughelper.tools.r8.ir.code.InvokeMethod;
import com.debughelper.tools.r8.ir.code.InvokeNewArray;
import com.debughelper.tools.r8.ir.code.NewArrayEmpty;
import com.debughelper.tools.r8.ir.code.NewInstance;
import com.debughelper.tools.r8.ir.code.StaticGet;
import com.debughelper.tools.r8.ir.code.StaticPut;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.utils.InternalOptions;

import java.util.List;
import java.util.ListIterator;
import java.util.stream.Collectors;

public class LensCodeRewriter {

  private final com.debughelper.tools.r8.graph.GraphLense graphLense;
  private final com.debughelper.tools.r8.graph.AppInfoWithSubtyping appInfo;
  private final com.debughelper.tools.r8.utils.InternalOptions options;

  public LensCodeRewriter(
          GraphLense graphLense, AppInfoWithSubtyping appInfo, InternalOptions options) {
    this.graphLense = graphLense;
    this.appInfo = appInfo;
    this.options = options;
  }

  private com.debughelper.tools.r8.ir.code.Value makeOutValue(com.debughelper.tools.r8.ir.code.Instruction insn, com.debughelper.tools.r8.ir.code.IRCode code) {
    if (insn.outValue() == null) {
      return null;
    } else {
      return code.createValue(insn.outType(), insn.getLocalInfo());
    }
  }

  /**
   * Replace invoke targets and field accesses with actual definitions.
   */
  public void rewrite(IRCode code, com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blocks = code.blocks.listIterator();
    while (blocks.hasNext()) {
      BasicBlock block = blocks.next();
      InstructionListIterator iterator = block.listIterator();
      while (iterator.hasNext()) {
        Instruction current = iterator.next();
        if (current.isInvokeCustom()) {
          com.debughelper.tools.r8.ir.code.InvokeCustom invokeCustom = current.asInvokeCustom();
          com.debughelper.tools.r8.graph.DexCallSite callSite = invokeCustom.getCallSite();
          com.debughelper.tools.r8.graph.DexType[] newParameters = new com.debughelper.tools.r8.graph.DexType[callSite.methodProto.parameters.size()];
          for (int i = 0; i < callSite.methodProto.parameters.size(); i++) {
            newParameters[i] = graphLense.lookupType(callSite.methodProto.parameters.values[i]);
          }
          DexProto newMethodProto =
              appInfo.dexItemFactory.createProto(
                  graphLense.lookupType(callSite.methodProto.returnType), newParameters);
          com.debughelper.tools.r8.graph.DexMethodHandle newBootstrapMethod = rewriteDexMethodHandle(method,
              callSite.bootstrapMethod);
          List<com.debughelper.tools.r8.graph.DexValue> newArgs = callSite.bootstrapArgs.stream().map(
              (arg) -> {
                if (arg instanceof com.debughelper.tools.r8.graph.DexValue.DexValueMethodHandle) {
                  return new com.debughelper.tools.r8.graph.DexValue.DexValueMethodHandle(
                      rewriteDexMethodHandle(method, ((com.debughelper.tools.r8.graph.DexValue.DexValueMethodHandle) arg).value));
                }
                return arg;
              })
              .collect(Collectors.toList());

          if (!newMethodProto.equals(callSite.methodProto)
              || newBootstrapMethod != callSite.bootstrapMethod
              || !newArgs.equals(callSite.bootstrapArgs)) {
            DexCallSite newCallSite =
                appInfo.dexItemFactory.createCallSite(
                    callSite.methodName, newMethodProto, newBootstrapMethod, newArgs);
            com.debughelper.tools.r8.ir.code.InvokeCustom newInvokeCustom = new InvokeCustom(newCallSite, invokeCustom.outValue(),
                invokeCustom.inValues());
            iterator.replaceCurrentInstruction(newInvokeCustom);
          }
        } else if (current.isInvokeMethod()) {
          com.debughelper.tools.r8.ir.code.InvokeMethod invoke = current.asInvokeMethod();
          com.debughelper.tools.r8.graph.DexMethod invokedMethod = invoke.getInvokedMethod();
          com.debughelper.tools.r8.graph.DexType invokedHolder = invokedMethod.getHolder();
          if (!invokedHolder.isClassType()) {
            continue;
          }
          com.debughelper.tools.r8.graph.DexMethod actualTarget = graphLense.lookupMethod(invokedMethod, method, invoke.getType());
          com.debughelper.tools.r8.ir.code.Invoke.Type invokeType = getInvokeType(invoke, actualTarget, invokedMethod, method);
          if (actualTarget != invokedMethod || invoke.getType() != invokeType) {
            com.debughelper.tools.r8.ir.code.Invoke newInvoke = com.debughelper.tools.r8.ir.code.Invoke.create(invokeType, actualTarget, null,
                    invoke.outValue(), invoke.inValues());
            iterator.replaceCurrentInstruction(newInvoke);
            // Fix up the return type if needed.
            if (actualTarget.proto.returnType != invokedMethod.proto.returnType
                && newInvoke.outValue() != null) {
              Value newValue = code.createValue(newInvoke.outType(), invoke.getLocalInfo());
              newInvoke.outValue().replaceUsers(newValue);
              com.debughelper.tools.r8.ir.code.CheckCast cast =
                  new com.debughelper.tools.r8.ir.code.CheckCast(
                      newValue,
                      newInvoke.outValue(),
                      graphLense.lookupType(invokedMethod.proto.returnType));
              cast.setPosition(current.getPosition());
              iterator.add(cast);
              // If the current block has catch handlers split the check cast into its own block.
              if (newInvoke.getBlock().hasCatchHandlers()) {
                iterator.previous();
                iterator.split(code, 1, blocks);
              }
            }
          }
        } else if (current.isInstanceGet()) {
          com.debughelper.tools.r8.ir.code.InstanceGet instanceGet = current.asInstanceGet();
          com.debughelper.tools.r8.graph.DexField field = instanceGet.getField();
          com.debughelper.tools.r8.graph.DexField actualField = graphLense.lookupField(field);
          if (actualField != field) {
            com.debughelper.tools.r8.ir.code.InstanceGet newInstanceGet =
                new InstanceGet(
                    instanceGet.getType(), instanceGet.dest(), instanceGet.object(), actualField);
            iterator.replaceCurrentInstruction(newInstanceGet);
          }
        } else if (current.isInstancePut()) {
          com.debughelper.tools.r8.ir.code.InstancePut instancePut = current.asInstancePut();
          com.debughelper.tools.r8.graph.DexField field = instancePut.getField();
          com.debughelper.tools.r8.graph.DexField actualField = graphLense.lookupField(field);
          if (actualField != field) {
            com.debughelper.tools.r8.ir.code.InstancePut newInstancePut =
                new InstancePut(
                    instancePut.getType(), actualField, instancePut.object(), instancePut.value());
            iterator.replaceCurrentInstruction(newInstancePut);
          }
        } else if (current.isStaticGet()) {
          com.debughelper.tools.r8.ir.code.StaticGet staticGet = current.asStaticGet();
          com.debughelper.tools.r8.graph.DexField field = staticGet.getField();
          com.debughelper.tools.r8.graph.DexField actualField = graphLense.lookupField(field);
          if (actualField != field) {
            com.debughelper.tools.r8.ir.code.StaticGet newStaticGet =
                new StaticGet(staticGet.getType(), staticGet.dest(), actualField);
            iterator.replaceCurrentInstruction(newStaticGet);
          }
        } else if (current.isStaticPut()) {
          com.debughelper.tools.r8.ir.code.StaticPut staticPut = current.asStaticPut();
          com.debughelper.tools.r8.graph.DexField field = staticPut.getField();
          com.debughelper.tools.r8.graph.DexField actualField = graphLense.lookupField(field);
          if (actualField != field) {
            com.debughelper.tools.r8.ir.code.StaticPut newStaticPut =
                new StaticPut(staticPut.getType(), staticPut.inValue(), actualField);
            iterator.replaceCurrentInstruction(newStaticPut);
          }
        } else if (current.isCheckCast()) {
          com.debughelper.tools.r8.ir.code.CheckCast checkCast = current.asCheckCast();
          com.debughelper.tools.r8.graph.DexType newType = graphLense.lookupType(checkCast.getType());
          if (newType != checkCast.getType()) {
            com.debughelper.tools.r8.ir.code.CheckCast newCheckCast =
                new CheckCast(makeOutValue(checkCast, code), checkCast.object(), newType);
            iterator.replaceCurrentInstruction(newCheckCast);
          }
        } else if (current.isConstClass()) {
          com.debughelper.tools.r8.ir.code.ConstClass constClass = current.asConstClass();
          com.debughelper.tools.r8.graph.DexType newType = graphLense.lookupType(constClass.getValue());
          if (newType != constClass.getValue()) {
            com.debughelper.tools.r8.ir.code.ConstClass newConstClass = new ConstClass(makeOutValue(constClass, code), newType);
            iterator.replaceCurrentInstruction(newConstClass);
          }
        } else if (current.isInstanceOf()) {
          com.debughelper.tools.r8.ir.code.InstanceOf instanceOf = current.asInstanceOf();
          com.debughelper.tools.r8.graph.DexType newType = graphLense.lookupType(instanceOf.type());
          if (newType != instanceOf.type()) {
            com.debughelper.tools.r8.ir.code.InstanceOf newInstanceOf = new InstanceOf(makeOutValue(instanceOf, code),
                instanceOf.value(), newType);
            iterator.replaceCurrentInstruction(newInstanceOf);
          }
        } else if (current.isInvokeNewArray()) {
          com.debughelper.tools.r8.ir.code.InvokeNewArray newArray = current.asInvokeNewArray();
          com.debughelper.tools.r8.graph.DexType newType = graphLense.lookupType(newArray.getArrayType());
          if (newType != newArray.getArrayType()) {
            com.debughelper.tools.r8.ir.code.InvokeNewArray newNewArray = new InvokeNewArray(newType, makeOutValue(newArray, code),
                newArray.inValues());
            iterator.replaceCurrentInstruction(newNewArray);
          }
        } else if (current.isNewArrayEmpty()) {
          com.debughelper.tools.r8.ir.code.NewArrayEmpty newArrayEmpty = current.asNewArrayEmpty();
          com.debughelper.tools.r8.graph.DexType newType = graphLense.lookupType(newArrayEmpty.type);
          if (newType != newArrayEmpty.type) {
            com.debughelper.tools.r8.ir.code.NewArrayEmpty newNewArray = new NewArrayEmpty(makeOutValue(newArrayEmpty, code),
                newArrayEmpty.size(), newType);
            iterator.replaceCurrentInstruction(newNewArray);
          }
        } else if (current.isNewInstance()) {
            com.debughelper.tools.r8.ir.code.NewInstance newInstance= current.asNewInstance();
          DexType newClazz = graphLense.lookupType(newInstance.clazz);
            if (newClazz != newInstance.clazz) {
              com.debughelper.tools.r8.ir.code.NewInstance newNewInstance =
                  new NewInstance(newClazz, makeOutValue(newInstance, code));
              iterator.replaceCurrentInstruction(newNewInstance);
            }
          }
      }
    }
    assert code.isConsistentSSA();
  }

  private com.debughelper.tools.r8.graph.DexMethodHandle rewriteDexMethodHandle(
          com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.graph.DexMethodHandle methodHandle) {
    if (methodHandle.isMethodHandle()) {
      com.debughelper.tools.r8.graph.DexMethod invokedMethod = methodHandle.asMethod();
      com.debughelper.tools.r8.graph.DexMethod actualTarget =
          graphLense.lookupMethod(invokedMethod, method, methodHandle.type.toInvokeType());
      if (actualTarget != invokedMethod) {
        com.debughelper.tools.r8.graph.DexClass clazz = appInfo.definitionFor(actualTarget.holder);
        com.debughelper.tools.r8.graph.DexMethodHandle.MethodHandleType newType = methodHandle.type;
        if (clazz != null
            && (newType.isInvokeInterface() || newType.isInvokeInstance())) {
          newType = clazz.accessFlags.isInterface()
              ? com.debughelper.tools.r8.graph.DexMethodHandle.MethodHandleType.INVOKE_INTERFACE
              : com.debughelper.tools.r8.graph.DexMethodHandle.MethodHandleType.INVOKE_INSTANCE;
        }
        return new com.debughelper.tools.r8.graph.DexMethodHandle(newType, actualTarget);
      }
    } else {
      com.debughelper.tools.r8.graph.DexField field = methodHandle.asField();
      DexField actualField = graphLense.lookupField(field);
      if (actualField != field) {
        return new com.debughelper.tools.r8.graph.DexMethodHandle(methodHandle.type, actualField);
      }
    }
    return methodHandle;
  }

  private com.debughelper.tools.r8.ir.code.Invoke.Type getInvokeType(
      InvokeMethod invoke,
      com.debughelper.tools.r8.graph.DexMethod actualTarget,
      DexMethod originalTarget,
      com.debughelper.tools.r8.graph.DexEncodedMethod invocationContext) {
    // We might move methods from interfaces to classes and vice versa. So we have to support
    // fixing the invoke kind, yet only if it was correct to start with.
    if (invoke.isInvokeVirtual() || invoke.isInvokeInterface()) {
      // Get the invoke type of the actual definition.
      com.debughelper.tools.r8.graph.DexClass newTargetClass = appInfo.definitionFor(actualTarget.holder);
      if (newTargetClass == null) {
        return invoke.getType();
      }
      com.debughelper.tools.r8.graph.DexClass originalTargetClass = appInfo.definitionFor(originalTarget.holder);
      if (originalTargetClass != null
          && (originalTargetClass.isInterface() ^ (invoke.getType() == com.debughelper.tools.r8.ir.code.Invoke.Type.INTERFACE))) {
        // The invoke was wrong to start with, so we keep it wrong. This is to ensure we get
        // the IncompatibleClassChangeError the original invoke would have triggered.
        return newTargetClass.accessFlags.isInterface() ? com.debughelper.tools.r8.ir.code.Invoke.Type.VIRTUAL : com.debughelper.tools.r8.ir.code.Invoke.Type.INTERFACE;
      }
      return newTargetClass.accessFlags.isInterface() ? com.debughelper.tools.r8.ir.code.Invoke.Type.INTERFACE : com.debughelper.tools.r8.ir.code.Invoke.Type.VIRTUAL;
    }
    if (options.enableClassMerging && invoke.isInvokeSuper()) {
      if (actualTarget.getHolder() == invocationContext.method.getHolder()) {
        DexClass targetClass = appInfo.definitionFor(actualTarget.holder);
        if (targetClass == null) {
          return invoke.getType();
        }

        // If the super class A of the enclosing class B (i.e., invocationContext.method.holder)
        // has been merged into B during vertical class merging, and this invoke-super instruction
        // was resolving to a method in A, then the target method has been changed to a direct
        // method and moved into B, so that we need to use an invoke-direct instruction instead of
        // invoke-super.
        //
        // At this point, we have an invoke-super instruction where the static target is the
        // enclosing class. However, such an instruction could occur even if a subclass has never
        // been merged into the enclosing class. Therefore, to determine if vertical class merging
        // has been applied, we look if there is a direct method with the right signature, and only
        // return Type.DIRECT in that case.
        DexEncodedMethod method = targetClass.lookupDirectMethod(actualTarget);
        if (method != null) {
          // The target method has been moved from the super class into the sub class during class
          // merging such that we now need to use an invoke-direct instruction.
          return com.debughelper.tools.r8.ir.code.Invoke.Type.DIRECT;
        }
      }
    }
    return invoke.getType();
  }
}
