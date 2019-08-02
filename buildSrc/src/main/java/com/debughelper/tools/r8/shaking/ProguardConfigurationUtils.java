// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.shaking;

import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItem;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.shaking.ProguardClassNameList;
import com.debughelper.tools.r8.shaking.ProguardConfigurationParser.IdentifierPatternWithWildcards;
import com.debughelper.tools.r8.shaking.ProguardIdentifierNameStringRule;
import com.debughelper.tools.r8.shaking.ProguardKeepRuleType;
import com.debughelper.tools.r8.shaking.ProguardMemberRule;
import com.debughelper.tools.r8.shaking.ProguardMemberType;
import com.debughelper.tools.r8.shaking.ProguardTypeMatcher;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ProguardConfigurationUtils {
  public static ProguardKeepRule buildDefaultInitializerKeepRule(DexClass clazz) {
    ProguardKeepRule.Builder builder = ProguardKeepRule.builder();
    builder.setType(com.debughelper.tools.r8.shaking.ProguardKeepRuleType.KEEP);
    builder.getModifiersBuilder().setAllowsObfuscation(true);
    builder.getModifiersBuilder().setAllowsOptimization(true);
    builder.getClassAccessFlags().setPublic();
    builder.setClassType(ProguardClassType.CLASS);
    com.debughelper.tools.r8.shaking.ProguardClassNameList.Builder classNameListBuilder = com.debughelper.tools.r8.shaking.ProguardClassNameList.builder();
    classNameListBuilder.addClassName(false, com.debughelper.tools.r8.shaking.ProguardTypeMatcher.create(clazz.type));
    builder.setClassNames(classNameListBuilder.build());
    if (clazz.hasDefaultInitializer()) {
      com.debughelper.tools.r8.shaking.ProguardMemberRule.Builder memberRuleBuilder = com.debughelper.tools.r8.shaking.ProguardMemberRule.builder();
      memberRuleBuilder.setRuleType(com.debughelper.tools.r8.shaking.ProguardMemberType.INIT);
      memberRuleBuilder.setName(IdentifierPatternWithWildcards.withoutWildcards("<init>"));
      memberRuleBuilder.setArguments(ImmutableList.of());
      builder.getMemberRules().add(memberRuleBuilder.build());
    }
    return builder.build();
  }

  public static ProguardKeepRule buildFieldKeepRule(DexClass clazz, DexEncodedField field) {
    assert clazz.type == field.field.getHolder();
    ProguardKeepRule.Builder builder = ProguardKeepRule.builder();
    builder.setType(com.debughelper.tools.r8.shaking.ProguardKeepRuleType.KEEP_CLASS_MEMBERS);
    builder.getModifiersBuilder().setAllowsObfuscation(true);
    builder.getModifiersBuilder().setAllowsOptimization(true);
    builder.getClassAccessFlags().setPublic();
    if (clazz.isInterface()) {
      builder.setClassType(ProguardClassType.INTERFACE);
    } else {
      builder.setClassType(ProguardClassType.CLASS);
    }
    builder.setClassNames(
        com.debughelper.tools.r8.shaking.ProguardClassNameList.singletonList(com.debughelper.tools.r8.shaking.ProguardTypeMatcher.create(clazz.type)));
    com.debughelper.tools.r8.shaking.ProguardMemberRule.Builder memberRuleBuilder = com.debughelper.tools.r8.shaking.ProguardMemberRule.builder();
    memberRuleBuilder.setRuleType(com.debughelper.tools.r8.shaking.ProguardMemberType.FIELD);
    memberRuleBuilder.getAccessFlags().setFlags(field.accessFlags);
    memberRuleBuilder.setName(
        IdentifierPatternWithWildcards.withoutWildcards(field.field.name.toString()));
    memberRuleBuilder.setTypeMatcher(com.debughelper.tools.r8.shaking.ProguardTypeMatcher.create(field.field.type));
    builder.getMemberRules().add(memberRuleBuilder.build());
    return builder.build();
  }

  public static ProguardKeepRule buildMethodKeepRule(DexClass clazz, DexEncodedMethod method) {
    assert clazz.type == method.method.getHolder();
    ProguardKeepRule.Builder builder = ProguardKeepRule.builder();
    builder.setType(ProguardKeepRuleType.KEEP_CLASS_MEMBERS);
    builder.getModifiersBuilder().setAllowsObfuscation(true);
    builder.getModifiersBuilder().setAllowsOptimization(true);
    builder.getClassAccessFlags().setPublic();
    if (clazz.isInterface()) {
      builder.setClassType(ProguardClassType.INTERFACE);
    } else {
      builder.setClassType(ProguardClassType.CLASS);
    }
    builder.setClassNames(
        com.debughelper.tools.r8.shaking.ProguardClassNameList.singletonList(com.debughelper.tools.r8.shaking.ProguardTypeMatcher.create(clazz.type)));
    com.debughelper.tools.r8.shaking.ProguardMemberRule.Builder memberRuleBuilder = com.debughelper.tools.r8.shaking.ProguardMemberRule.builder();
    memberRuleBuilder.setRuleType(com.debughelper.tools.r8.shaking.ProguardMemberType.METHOD);
    memberRuleBuilder.getAccessFlags().setFlags(method.accessFlags);
    memberRuleBuilder.setName(
        IdentifierPatternWithWildcards.withoutWildcards(method.method.name.toString()));
    memberRuleBuilder.setTypeMatcher(com.debughelper.tools.r8.shaking.ProguardTypeMatcher.create(method.method.proto.returnType));
    List<com.debughelper.tools.r8.shaking.ProguardTypeMatcher> arguments = Arrays.stream(method.method.proto.parameters.values)
        .map(com.debughelper.tools.r8.shaking.ProguardTypeMatcher::create)
        .collect(Collectors.toList());
    memberRuleBuilder.setArguments(arguments);
    builder.getMemberRules().add(memberRuleBuilder.build());
    return builder.build();
  }

  public static com.debughelper.tools.r8.shaking.ProguardIdentifierNameStringRule buildIdentifierNameStringRule(DexItem item) {
    assert item instanceof DexField || item instanceof DexMethod;
    com.debughelper.tools.r8.shaking.ProguardIdentifierNameStringRule.Builder builder = ProguardIdentifierNameStringRule.builder();
    com.debughelper.tools.r8.shaking.ProguardMemberRule.Builder memberRuleBuilder = ProguardMemberRule.builder();
    DexType holderType;
    if (item instanceof DexField) {
      DexField field = (DexField) item;
      holderType = field.getHolder();
      memberRuleBuilder.setRuleType(com.debughelper.tools.r8.shaking.ProguardMemberType.FIELD);
      memberRuleBuilder.setName(
          IdentifierPatternWithWildcards.withoutWildcards(field.name.toString()));
      memberRuleBuilder.setTypeMatcher(com.debughelper.tools.r8.shaking.ProguardTypeMatcher.create(field.type));
    } else {
      DexMethod method = (DexMethod) item;
      holderType = method.getHolder();
      memberRuleBuilder.setRuleType(ProguardMemberType.METHOD);
      memberRuleBuilder.setName(
          IdentifierPatternWithWildcards.withoutWildcards(method.name.toString()));
      memberRuleBuilder.setTypeMatcher(com.debughelper.tools.r8.shaking.ProguardTypeMatcher.create(method.proto.returnType));
      List<com.debughelper.tools.r8.shaking.ProguardTypeMatcher> arguments = Arrays.stream(method.proto.parameters.values)
          .map(com.debughelper.tools.r8.shaking.ProguardTypeMatcher::create)
          .collect(Collectors.toList());
      memberRuleBuilder.setArguments(arguments);
    }
    if (holderType.isInterface()) {
      builder.setClassType(ProguardClassType.INTERFACE);
    } else {
      builder.setClassType(ProguardClassType.CLASS);
    }
    builder.setClassNames(
        ProguardClassNameList.singletonList(ProguardTypeMatcher.create(holderType)));
    builder.getMemberRules().add(memberRuleBuilder.build());
    return builder.build();
  }
}
