// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.kotlin;

import com.debughelper.tools.r8.kotlin.KotlinInfo;

public class KotlinClass extends KotlinInfo {
  @Override
  public Kind getKind() {
    return Kind.Class;
  }

  @Override
  public boolean isClass() {
    return true;
  }

  @Override
  public KotlinClass asClass() {
    return this;
  }

  KotlinClass() {
  }
}
