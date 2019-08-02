// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.shaking;

import com.debughelper.tools.r8.shaking.ProguardAccessFlags;
import com.debughelper.tools.r8.shaking.ProguardClassNameList;
import com.debughelper.tools.r8.shaking.ProguardClassSpecification;
import com.debughelper.tools.r8.shaking.ProguardClassType;
import com.debughelper.tools.r8.shaking.ProguardConfigurationRule;
import com.debughelper.tools.r8.shaking.ProguardMemberRule;
import com.debughelper.tools.r8.shaking.ProguardTypeMatcher;

import java.util.List;

public class ProguardAssumeNoSideEffectRule extends ProguardConfigurationRule {

  public static class Builder extends ProguardClassSpecification.Builder {

    private Builder() {}

    public ProguardAssumeNoSideEffectRule build() {
      return new ProguardAssumeNoSideEffectRule(classAnnotation, classAccessFlags,
          negatedClassAccessFlags, classTypeNegated, classType, classNames, inheritanceAnnotation,
          inheritanceClassName, inheritanceIsExtends, memberRules);
    }
  }

  private ProguardAssumeNoSideEffectRule(
      com.debughelper.tools.r8.shaking.ProguardTypeMatcher classAnnotation,
      com.debughelper.tools.r8.shaking.ProguardAccessFlags classAccessFlags,
      ProguardAccessFlags negatedClassAccessFlags,
      boolean classTypeNegated,
      ProguardClassType classType,
      ProguardClassNameList classNames,
      com.debughelper.tools.r8.shaking.ProguardTypeMatcher inheritanceAnnotation,
      ProguardTypeMatcher inheritanceClassName,
      boolean inheritanceIsExtends,
      List<ProguardMemberRule> memberRules) {
    super(classAnnotation, classAccessFlags, negatedClassAccessFlags, classTypeNegated, classType,
        classNames, inheritanceAnnotation, inheritanceClassName, inheritanceIsExtends, memberRules);
  }

  /**
   * Create a new empty builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public boolean applyToLibraryClasses() {
    return true;
  }

  @Override
  String typeString() {
    return "assumenosideeffects";
  }
}
