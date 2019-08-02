// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.optimize;

import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItem;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.ConstNumber;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.InstancePut;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionIterator;
import com.debughelper.tools.r8.ir.code.InvokeMethod;
import com.debughelper.tools.r8.ir.code.StaticGet;
import com.debughelper.tools.r8.ir.code.StaticPut;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.debughelper.tools.r8.shaking.Enqueuer;
import com.debughelper.tools.r8.shaking.ProguardMemberRule;

public class MemberValuePropagation {

  private final Enqueuer.AppInfoWithLiveness appInfo;

  private enum RuleType {
    NONE,
    ASSUME_NO_SIDE_EFFECTS,
    ASSUME_VALUES
  }

  private static class ProguardMemberRuleLookup {

    final RuleType type;
    final com.debughelper.tools.r8.shaking.ProguardMemberRule rule;

    ProguardMemberRuleLookup(RuleType type, com.debughelper.tools.r8.shaking.ProguardMemberRule rule) {
      this.type = type;
      this.rule = rule;
    }
  }

  public MemberValuePropagation(Enqueuer.AppInfoWithLiveness appInfo) {
    this.appInfo = appInfo;
  }

  private ProguardMemberRuleLookup lookupMemberRule(DexItem item) {
    com.debughelper.tools.r8.shaking.ProguardMemberRule rule = appInfo.noSideEffects.get(item);
    if (rule != null) {
      return new ProguardMemberRuleLookup(RuleType.ASSUME_NO_SIDE_EFFECTS, rule);
    }
    rule = appInfo.assumedValues.get(item);
    if (rule != null) {
      return new ProguardMemberRuleLookup(RuleType.ASSUME_VALUES, rule);
    }
    return null;
  }

  private com.debughelper.tools.r8.ir.code.Instruction constantReplacementFromProguardRule(
          com.debughelper.tools.r8.shaking.ProguardMemberRule rule, com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.code.Instruction instruction) {
    // Check if this value can be assumed constant.
    com.debughelper.tools.r8.ir.code.Instruction replacement = null;
    com.debughelper.tools.r8.ir.code.ValueType valueType = instruction.outValue().outType();
    if (rule != null && rule.hasReturnValue() && rule.getReturnValue().isSingleValue()) {
      assert valueType != com.debughelper.tools.r8.ir.code.ValueType.OBJECT;
      com.debughelper.tools.r8.ir.code.Value value = code.createValue(valueType, instruction.getLocalInfo());
      replacement = new com.debughelper.tools.r8.ir.code.ConstNumber(value, rule.getReturnValue().getSingleValue());
    }
    if (replacement == null &&
        rule != null && rule.hasReturnValue() && rule.getReturnValue().isField()) {
      com.debughelper.tools.r8.graph.DexField field = rule.getReturnValue().getField();
      assert com.debughelper.tools.r8.ir.code.ValueType.fromDexType(field.type) == valueType;
      com.debughelper.tools.r8.graph.DexEncodedField staticField = appInfo.lookupStaticTarget(field.clazz, field);
      if (staticField != null) {
        com.debughelper.tools.r8.ir.code.Value value = code.createValue(valueType, instruction.getLocalInfo());
        replacement = staticField.getStaticValue().asConstInstruction(false, value);
      } else {
        throw new CompilationError(field.clazz.toSourceString() + "." + field.name.toString() +
            " used in assumevalues rule does not exist.");
      }
    }
    return replacement;
  }

  private void setValueRangeFromProguardRule(ProguardMemberRule rule, com.debughelper.tools.r8.ir.code.Value value) {
    if (rule.hasReturnValue() && rule.getReturnValue().isValueRange()) {
      assert !rule.getReturnValue().isSingleValue();
      value.setValueRange(rule.getReturnValue().getValueRange());
    }
  }

  private void replaceInstructionFromProguardRule(RuleType ruleType, com.debughelper.tools.r8.ir.code.InstructionIterator iterator,
                                                  com.debughelper.tools.r8.ir.code.Instruction current, com.debughelper.tools.r8.ir.code.Instruction replacement) {
    if (ruleType == RuleType.ASSUME_NO_SIDE_EFFECTS) {
      iterator.replaceCurrentInstruction(replacement);
    } else {
      if (current.outValue() != null) {
        assert replacement.outValue() != null;
        current.outValue().replaceUsers(replacement.outValue());
      }
      replacement.setPosition(current.getPosition());
      iterator.add(replacement);
    }
  }

  /**
   * Replace invoke targets and field accesses with constant values where possible.
   * <p>
   * Also assigns value ranges to values where possible.
   */
  public void rewriteWithConstantValues(IRCode code, com.debughelper.tools.r8.graph.DexType callingContext) {
    InstructionIterator iterator = code.instructionIterator();
    while (iterator.hasNext()) {
      com.debughelper.tools.r8.ir.code.Instruction current = iterator.next();
      if (current.isInvokeMethod()) {
        InvokeMethod invoke = current.asInvokeMethod();
        DexMethod invokedMethod = invoke.getInvokedMethod();
        DexType invokedHolder = invokedMethod.getHolder();
        if (!invokedHolder.isClassType()) {
          continue;
        }
        // TODO(70550443): Maybe check all methods here.
        com.debughelper.tools.r8.graph.DexEncodedMethod definition = appInfo
            .lookup(invoke.getType(), invokedMethod, callingContext);

        // Process invokes marked as having no side effects.
        boolean invokeReplaced = false;
        ProguardMemberRuleLookup lookup = lookupMemberRule(definition);
        if (lookup != null) {
          if (lookup.type == RuleType.ASSUME_NO_SIDE_EFFECTS
              && (invoke.outValue() == null || !invoke.outValue().isUsed())) {
            iterator.remove();
            invokeReplaced = true;
          } else if (invoke.outValue() != null && invoke.outValue().isUsed()) {
            // Check to see if a constant value can be assumed.
            com.debughelper.tools.r8.ir.code.Instruction replacement =
                constantReplacementFromProguardRule(lookup.rule, code, invoke);
            if (replacement != null) {
              replaceInstructionFromProguardRule(lookup.type, iterator, current, replacement);
              invokeReplaced = true;
            } else {
              // Check to see if a value range can be assumed.
              setValueRangeFromProguardRule(lookup.rule, current.outValue());
            }
          }
        }

        // If no Proguard rule could replace the instruction check for knowledge about the
        // return value.
        if (!invokeReplaced && invoke.outValue() != null) {
          DexEncodedMethod target = invoke.computeSingleTarget(appInfo);
          if (target != null) {
            if (target.getOptimizationInfo().neverReturnsNull()) {
              invoke.outValue().markNeverNull();
            }
            if (target.getOptimizationInfo().returnsConstant()) {
              long constant = target.getOptimizationInfo().getReturnedConstant();
              ValueType valueType = invoke.outType();
              Value value = code.createValue(valueType);
              com.debughelper.tools.r8.ir.code.Instruction knownConstReturn = new ConstNumber(value, constant);
              invoke.outValue().replaceUsers(value);
              knownConstReturn.setPosition(invoke.getPosition());
              iterator.add(knownConstReturn);
            }
          }
        }
      } else if (current.isInstancePut()) {
        InstancePut instancePut = current.asInstancePut();
        com.debughelper.tools.r8.graph.DexField field = instancePut.getField();
        com.debughelper.tools.r8.graph.DexEncodedField target = appInfo.lookupInstanceTarget(field.getHolder(), field);
        if (target != null) {
          // Remove writes to dead (i.e. never read) fields.
          if (!isFieldRead(target, false) && instancePut.object().isNeverNull()) {
            iterator.remove();
          }
        }
      } else if (current.isStaticGet()) {
        StaticGet staticGet = current.asStaticGet();
        com.debughelper.tools.r8.graph.DexField field = staticGet.getField();
        Instruction replacement = null;
        com.debughelper.tools.r8.graph.DexEncodedField target = appInfo.lookupStaticTarget(field.getHolder(), field);
        ProguardMemberRuleLookup lookup = null;
        if (target != null) {
          // Check if a this value is known const.
          replacement = target.valueAsConstInstruction(appInfo, staticGet.dest());
          if (replacement == null) {
            lookup = lookupMemberRule(target);
            if (lookup != null) {
              replacement = constantReplacementFromProguardRule(lookup.rule, code, staticGet);
            }
          }
          if (replacement == null) {
            // If no const replacement was found, at least store the range information.
            if (lookup != null) {
              setValueRangeFromProguardRule(lookup.rule, staticGet.dest());
            }
          }
          if (replacement != null) {
            // Ignore assumenosideeffects for fields.
            if (lookup != null && lookup.type == RuleType.ASSUME_VALUES) {
              replaceInstructionFromProguardRule(lookup.type, iterator, current, replacement);
            } else {
              iterator.replaceCurrentInstruction(replacement);
            }
          }
        }
      } else if (current.isStaticPut()) {
        StaticPut staticPut = current.asStaticPut();
        DexField field = staticPut.getField();
        com.debughelper.tools.r8.graph.DexEncodedField target = appInfo.lookupStaticTarget(field.getHolder(), field);
        if (target != null) {
          // Remove writes to dead (i.e. never read) fields.
          if (!isFieldRead(target, true)) {
            iterator.remove();
          }
        }
      }
    }
    assert code.isConsistentSSA();
  }

  private boolean isFieldRead(DexEncodedField field, boolean isStatic) {
    if (appInfo.fieldsRead.contains(field.field)
        || appInfo.isPinned(field.field)) {
      return true;
    }
    // For library classes we don't know whether a field is read.
    DexClass holder = appInfo.definitionFor(field.field.clazz);
    return holder == null || holder.isLibraryClass();
  }
}
