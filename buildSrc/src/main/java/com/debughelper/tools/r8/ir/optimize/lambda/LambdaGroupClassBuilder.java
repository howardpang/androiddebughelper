// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize.lambda;

import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroup;
import com.debughelper.tools.r8.graph.ClassAccessFlags;
import com.debughelper.tools.r8.graph.DexAnnotationSet;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexTypeList;
import com.debughelper.tools.r8.graph.EnclosingMethodAttribute;
import com.debughelper.tools.r8.graph.InnerClassAttribute;
import com.debughelper.tools.r8.origin.SynthesizedOrigin;

import java.util.List;

// Encapsulates lambda group class building logic and separates
// it from the rest of lambda group functionality.
public abstract class LambdaGroupClassBuilder<T extends LambdaGroup> {
  protected final T group;
  protected final com.debughelper.tools.r8.graph.DexItemFactory factory;
  protected final String origin;

  protected LambdaGroupClassBuilder(T group, DexItemFactory factory, String origin) {
    this.group = group;
    this.factory = factory;
    this.origin = origin;
  }

  public final com.debughelper.tools.r8.graph.DexProgramClass synthesizeClass() {
    com.debughelper.tools.r8.graph.DexType groupClassType = group.getGroupClassType();
    com.debughelper.tools.r8.graph.DexType superClassType = getSuperClassType();

    return new DexProgramClass(
        groupClassType,
        null,
        new SynthesizedOrigin(origin, getClass()),
        buildAccessFlags(),
        superClassType,
        buildInterfaces(),
        factory.createString(origin),
        buildEnclosingMethodAttribute(),
        buildInnerClasses(),
        buildAnnotations(),
        buildStaticFields(),
        buildInstanceFields(),
        buildDirectMethods(),
        buildVirtualMethods(),
        factory.getSkipNameValidationForTesting());
  }

  protected abstract DexType getSuperClassType();

  protected abstract ClassAccessFlags buildAccessFlags();

  protected abstract EnclosingMethodAttribute buildEnclosingMethodAttribute();

  protected abstract List<InnerClassAttribute> buildInnerClasses();

  protected abstract DexAnnotationSet buildAnnotations();

  protected abstract com.debughelper.tools.r8.graph.DexEncodedMethod[] buildVirtualMethods();

  protected abstract DexEncodedMethod[] buildDirectMethods();

  protected abstract com.debughelper.tools.r8.graph.DexEncodedField[] buildInstanceFields();

  protected abstract DexEncodedField[] buildStaticFields();

  protected abstract DexTypeList buildInterfaces();
}
