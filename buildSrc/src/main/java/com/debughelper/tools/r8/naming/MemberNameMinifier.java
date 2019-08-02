// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.naming;

import com.debughelper.tools.r8.graph.AppInfoWithSubtyping;
import com.debughelper.tools.r8.graph.CachedHashValueDexItem;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.naming.NamingState;
import com.debughelper.tools.r8.shaking.RootSetBuilder.RootSet;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.Reporter;
import com.debughelper.tools.r8.shaking.RootSetBuilder;
import com.google.common.collect.ImmutableList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

abstract class MemberNameMinifier<MemberType, StateType extends CachedHashValueDexItem> {

  protected final AppInfoWithSubtyping appInfo;
  protected final RootSetBuilder.RootSet rootSet;
  protected final ImmutableList<String> dictionary;

  protected final Map<MemberType, DexString> renaming = new IdentityHashMap<>();
  protected final Map<DexType, com.debughelper.tools.r8.naming.NamingState<StateType, ?>> states = new IdentityHashMap<>();
  protected final com.debughelper.tools.r8.naming.NamingState<StateType, ?> globalState;
  protected final boolean useUniqueMemberNames;
  protected final boolean overloadAggressively;
  protected final Reporter reporter;

  MemberNameMinifier(AppInfoWithSubtyping appInfo, RootSetBuilder.RootSet rootSet, InternalOptions options) {
    this.appInfo = appInfo;
    this.rootSet = rootSet;
    this.dictionary = options.proguardConfiguration.getObfuscationDictionary();
    this.useUniqueMemberNames = options.proguardConfiguration.isUseUniqueClassMemberNames();
    this.overloadAggressively = options.proguardConfiguration.isOverloadAggressively();
    this.reporter = options.reporter;
    this.globalState = com.debughelper.tools.r8.naming.NamingState.createRoot(
        appInfo.dexItemFactory, dictionary, getKeyTransform(), useUniqueMemberNames);
  }

  abstract Function<StateType, ?> getKeyTransform();

  protected com.debughelper.tools.r8.naming.NamingState<StateType, ?> computeStateIfAbsent(
      DexType type, Function<DexType, com.debughelper.tools.r8.naming.NamingState<StateType, ?>> f) {
    return useUniqueMemberNames ? globalState : states.computeIfAbsent(type, f);
  }

  protected com.debughelper.tools.r8.naming.NamingState<StateType, ?> getState(DexType type) {
    return useUniqueMemberNames ? globalState : states.get(type);
  }
}
