// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.dex.IndexedItemCollection;
import com.debughelper.tools.r8.dex.MixedSectionCollection;
import com.debughelper.tools.r8.graph.DexAnnotation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Predicate;

public class DexAnnotationSet extends CachedHashValueDexItem {

  private static final int UNSORTED = 0;
  private static final DexAnnotationSet THE_EMPTY_ANNOTATIONS_SET =
      new DexAnnotationSet(new com.debughelper.tools.r8.graph.DexAnnotation[0]);

  public final com.debughelper.tools.r8.graph.DexAnnotation[] annotations;
  private int sorted = UNSORTED;

  public DexAnnotationSet(com.debughelper.tools.r8.graph.DexAnnotation[] annotations) {
    this.annotations = annotations;
  }

  public static DexAnnotationSet empty() {
    return THE_EMPTY_ANNOTATIONS_SET;
  }

  @Override
  public int computeHashCode() {
    return Arrays.hashCode(annotations);
  }

  @Override
  public boolean computeEquals(Object other) {
    if (other instanceof DexAnnotationSet) {
      DexAnnotationSet o = (DexAnnotationSet) other;
      return Arrays.equals(annotations, o.annotations);
    }
    return false;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems,
      DexMethod method, int instructionOffset) {
    DexItem.collectAll(indexedItems, annotations);
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    mixedItems.add(this);
    DexItem.collectAll(mixedItems, annotations);
  }

  public boolean isEmpty() {
    return annotations.length == 0;
  }

  public void sort() {
    if (sorted != UNSORTED) {
      assert sorted == sortedHashCode();
      return;
    }
    Arrays.sort(annotations, (a, b) -> a.annotation.type.compareTo(b.annotation.type));
    for (com.debughelper.tools.r8.graph.DexAnnotation annotation : annotations) {
      annotation.annotation.sort();
    }
    sorted = hashCode();
  }

  public com.debughelper.tools.r8.graph.DexAnnotation getFirstMatching(DexType type) {
    for (com.debughelper.tools.r8.graph.DexAnnotation annotation : annotations) {
      if (annotation.annotation.type == type) {
        return annotation;
      }
    }
    return null;
  }

  public DexAnnotationSet getWithout(DexType annotationType) {
    int index = 0;
    for (com.debughelper.tools.r8.graph.DexAnnotation annotation : annotations) {
      if (annotation.annotation.type == annotationType) {
        com.debughelper.tools.r8.graph.DexAnnotation[] reducedArray = new com.debughelper.tools.r8.graph.DexAnnotation[annotations.length - 1];
        System.arraycopy(annotations, 0, reducedArray, 0, index);
        if (index < reducedArray.length) {
          System.arraycopy(annotations, index + 1, reducedArray, index, reducedArray.length - index);
        }
        return new DexAnnotationSet(reducedArray);
      }
      ++index;
    }
    return this;
  }

  private int sortedHashCode() {
    int hashCode = hashCode();
    return hashCode == UNSORTED ? 1 : hashCode;
  }

  public DexAnnotationSet getWithAddedOrReplaced(com.debughelper.tools.r8.graph.DexAnnotation newAnnotation) {

    // Check existing annotation for replacement.
    int index = 0;
    for (com.debughelper.tools.r8.graph.DexAnnotation annotation : annotations) {
      if (annotation.annotation.type == newAnnotation.annotation.type) {
        com.debughelper.tools.r8.graph.DexAnnotation[] modifiedArray = annotations.clone();
        modifiedArray[index] = newAnnotation;
        return new DexAnnotationSet(modifiedArray);
      }
      ++index;
    }

    // No existing annotation, append.
    com.debughelper.tools.r8.graph.DexAnnotation[] extendedArray = new com.debughelper.tools.r8.graph.DexAnnotation[annotations.length + 1];
    System.arraycopy(annotations, 0, extendedArray, 0, annotations.length);
    extendedArray[annotations.length] = newAnnotation;
    return new DexAnnotationSet(extendedArray);
  }

  public DexAnnotationSet keepIf(Predicate<com.debughelper.tools.r8.graph.DexAnnotation> filter) {
    ArrayList<com.debughelper.tools.r8.graph.DexAnnotation> filtered = null;
    for (int i = 0; i < annotations.length; i++) {
      com.debughelper.tools.r8.graph.DexAnnotation annotation = annotations[i];
      if (filter.test(annotation)) {
        if (filtered != null) {
          filtered.add(annotation);
        }
      } else {
        if (filtered == null) {
          filtered = new ArrayList<>(annotations.length);
          for (int j = 0; j < i; j++) {
            filtered.add(annotations[j]);
          }
        }
      }
    }
    if (filtered == null) {
      return this;
    } else if (filtered.isEmpty()) {
      return DexAnnotationSet.empty();
    } else {
      return new DexAnnotationSet(filtered.toArray(new DexAnnotation[filtered.size()]));
    }
  }
}
