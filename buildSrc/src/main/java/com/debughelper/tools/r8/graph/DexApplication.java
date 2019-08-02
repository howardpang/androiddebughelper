// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.dex.ApplicationReader.ProgramClassConflictResolver;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DirectMappedDexApplication;
import com.debughelper.tools.r8.ProgramResourceProvider;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.naming.ClassNameMapper;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.ProgramClassCollection;
import com.debughelper.tools.r8.utils.Timing;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public abstract class DexApplication {

  // Maps type into class, may be used concurrently.
  final com.debughelper.tools.r8.utils.ProgramClassCollection programClasses;

  public final ImmutableList<com.debughelper.tools.r8.ProgramResourceProvider> programResourceProviders;

  public final ImmutableSet<com.debughelper.tools.r8.graph.DexType> mainDexList;
  public final String deadCode;

  private final com.debughelper.tools.r8.naming.ClassNameMapper proguardMap;

  public final com.debughelper.tools.r8.utils.Timing timing;

  public final com.debughelper.tools.r8.graph.DexItemFactory dexItemFactory;

  // Information on the lexicographically largest string referenced from code.
  public final DexString highestSortingString;

  /**
   * Constructor should only be invoked by the DexApplication.Builder.
   */
  DexApplication(
      com.debughelper.tools.r8.naming.ClassNameMapper proguardMap,
      com.debughelper.tools.r8.utils.ProgramClassCollection programClasses,
      ImmutableList<com.debughelper.tools.r8.ProgramResourceProvider> programResourceProviders,
      ImmutableSet<com.debughelper.tools.r8.graph.DexType> mainDexList,
      String deadCode,
      com.debughelper.tools.r8.graph.DexItemFactory dexItemFactory,
      DexString highestSortingString,
      com.debughelper.tools.r8.utils.Timing timing) {
    assert programClasses != null;
    this.proguardMap = proguardMap;
    this.programClasses = programClasses;
    this.programResourceProviders = programResourceProviders;
    this.mainDexList = mainDexList;
    this.deadCode = deadCode;
    this.dexItemFactory = dexItemFactory;
    this.highestSortingString = highestSortingString;
    this.timing = timing;
  }

  public abstract Builder<?> builder();

  // Reorder classes randomly. Note that the order of classes in program or library
  // class collections should not matter for compilation of valid code and when running
  // with assertions enabled we reorder the classes randomly to catch possible issues.
  // Also note that the order may add to non-determinism in reporting errors for invalid
  // code, but this non-determinism exists even with the same order of classes since we
  // may process classes concurrently and fail-fast on the first error.
  private <T> boolean reorderClasses(List<T> classes) {
    if (!InternalOptions.DETERMINISTIC_DEBUGGING) {
      Collections.shuffle(classes);
    }
    return true;
  }

  public List<com.debughelper.tools.r8.graph.DexProgramClass> classes() {
    programClasses.forceLoad(type -> true);
    List<com.debughelper.tools.r8.graph.DexProgramClass> classes = programClasses.getAllClasses();
    assert reorderClasses(classes);
    return classes;
  }

  public Iterable<com.debughelper.tools.r8.graph.DexProgramClass> classesWithDeterministicOrder() {
    programClasses.forceLoad(type -> true);
    List<com.debughelper.tools.r8.graph.DexProgramClass> classes = programClasses.getAllClasses();
    // To keep the order deterministic, we sort the classes by their type, which is a unique key.
    classes.sort((a, b) -> a.type.slowCompareTo(b.type));
    return classes;
  }

  public abstract DexClass definitionFor(com.debughelper.tools.r8.graph.DexType type);

  public com.debughelper.tools.r8.graph.DexProgramClass programDefinitionFor(com.debughelper.tools.r8.graph.DexType type) {
    DexClass clazz = programClasses.get(type);
    return clazz == null ? null : clazz.asProgramClass();
  }

  @Override
  public abstract String toString();

  public com.debughelper.tools.r8.naming.ClassNameMapper getProguardMap() {
    return proguardMap;
  }

  public abstract static class Builder<T extends Builder<T>> {
    // We handle program class collection separately from classpath
    // and library class collections. Since while we assume program
    // class collection should always be fully loaded and thus fully
    // represented by the map (making it easy, for example, adding
    // new or removing existing classes), classpath and library
    // collections will be considered monolithic collections.

    final List<com.debughelper.tools.r8.graph.DexProgramClass> programClasses;

    final List<com.debughelper.tools.r8.ProgramResourceProvider> programResourceProviders = new ArrayList<>();

    public final com.debughelper.tools.r8.graph.DexItemFactory dexItemFactory;
    com.debughelper.tools.r8.naming.ClassNameMapper proguardMap;
    final com.debughelper.tools.r8.utils.Timing timing;

    DexString highestSortingString;
    String deadCode;
    final Set<com.debughelper.tools.r8.graph.DexType> mainDexList = Sets.newIdentityHashSet();
    private final Collection<com.debughelper.tools.r8.graph.DexProgramClass> synthesizedClasses;

    public Builder(com.debughelper.tools.r8.graph.DexItemFactory dexItemFactory, com.debughelper.tools.r8.utils.Timing timing) {
      this.programClasses = new ArrayList<>();
      this.dexItemFactory = dexItemFactory;
      this.timing = timing;
      this.deadCode = null;
      this.synthesizedClasses = new ArrayList<>();
    }

    abstract T self();

    public Builder(DexApplication application) {
      programClasses = application.programClasses.getAllClasses();
      addProgramResourceProviders(application.programResourceProviders);
      proguardMap = application.getProguardMap();
      timing = application.timing;
      highestSortingString = application.highestSortingString;
      dexItemFactory = application.dexItemFactory;
      mainDexList.addAll(application.mainDexList);
      deadCode = application.deadCode;
      synthesizedClasses = new ArrayList<>();
    }

    public synchronized T setProguardMap(ClassNameMapper proguardMap) {
      assert this.proguardMap == null;
      this.proguardMap = proguardMap;
      return self();
    }

    public synchronized T replaceProgramClasses(List<com.debughelper.tools.r8.graph.DexProgramClass> newProgramClasses) {
      assert newProgramClasses != null;
      this.programClasses.clear();
      this.programClasses.addAll(newProgramClasses);
      return self();
    }

    public synchronized T addProgramResourceProviders(
        List<ProgramResourceProvider> programResourceProviders) {
      if (programResourceProviders != null) {
        this.programResourceProviders.addAll(programResourceProviders);
      }
      return self();
    }

    public T appendDeadCode(String deadCodeAtAnotherRound) {
      if (deadCodeAtAnotherRound == null) {
        return self();
      }
      if (this.deadCode == null) {
        this.deadCode = deadCodeAtAnotherRound;
        return self();
      }
      // Concatenate existing deadCode info with next round.
      this.deadCode += deadCodeAtAnotherRound;
      return self();
    }

    public synchronized T setHighestSortingString(DexString value) {
      highestSortingString = value;
      return self();
    }

    public synchronized T addProgramClass(com.debughelper.tools.r8.graph.DexProgramClass clazz) {
      programClasses.add(clazz);
      return self();
    }

    public synchronized T addSynthesizedClass(
            com.debughelper.tools.r8.graph.DexProgramClass synthesizedClass, boolean addToMainDexList) {
      assert synthesizedClass.isProgramClass() : "All synthesized classes must be program classes";
      addProgramClass(synthesizedClass);
      synthesizedClasses.add(synthesizedClass);
      if (addToMainDexList && !mainDexList.isEmpty()) {
        mainDexList.add(synthesizedClass.type);
      }
      return self();
    }

    public Collection<com.debughelper.tools.r8.graph.DexProgramClass> getProgramClasses() {
      return programClasses;
    }

    public Collection<DexProgramClass> getSynthesizedClasses() {
      return synthesizedClasses;
    }

    public Set<com.debughelper.tools.r8.graph.DexType> getMainDexList() {
      return mainDexList;
    }

    public Builder<T> addToMainDexList(Collection<DexType> mainDexList) {
      this.mainDexList.addAll(mainDexList);
      return this;
    }

    public abstract DexApplication build();
  }

  public static LazyLoadedDexApplication.Builder builder(com.debughelper.tools.r8.graph.DexItemFactory factory, com.debughelper.tools.r8.utils.Timing timing) {
    return builder(factory, timing, ProgramClassCollection::resolveClassConflictImpl);
  }

  public static LazyLoadedDexApplication.Builder builder(
          DexItemFactory factory, Timing timing, ProgramClassConflictResolver resolver) {
    return new LazyLoadedDexApplication.Builder(resolver, factory, timing);
  }

  public com.debughelper.tools.r8.graph.DirectMappedDexApplication asDirect() {
    throw new Unreachable("Cannot use a LazyDexApplication where a DirectDexApplication is"
        + " expected.");
  }

  public abstract DirectMappedDexApplication toDirect();
}
