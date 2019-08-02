// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.utils;

import com.debughelper.tools.r8.DexIndexedConsumer;
import com.debughelper.tools.r8.DexIndexedConsumer.ForwardingConsumer;
import com.debughelper.tools.r8.utils.AndroidApp;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.BaseCompilerCommand;
import com.debughelper.tools.r8.ClassFileConsumer;
import com.debughelper.tools.r8.DexFilePerClassFileConsumer;
import com.debughelper.tools.r8.DiagnosticsHandler;
import com.debughelper.tools.r8.ProgramConsumer;
import com.debughelper.tools.r8.StringConsumer;
import com.debughelper.tools.r8.origin.Origin;

import it.unimi.dsi.fastutil.ints.Int2ReferenceAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceSortedMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

public class AndroidAppConsumers {

  private final com.debughelper.tools.r8.utils.AndroidApp.Builder builder = com.debughelper.tools.r8.utils.AndroidApp.builder();
  private boolean closed = false;

  private com.debughelper.tools.r8.ProgramConsumer programConsumer = null;
  private com.debughelper.tools.r8.StringConsumer proguardMapConsumer = null;

  public AndroidAppConsumers() {
    // Nothing to do.
  }

  public AndroidAppConsumers(BaseCompilerCommand.Builder builder) {
    builder.setProgramConsumer(wrapProgramConsumer(builder.getProgramConsumer()));
  }

  public AndroidAppConsumers(InternalOptions options) {
    options.programConsumer = wrapProgramConsumer(options.programConsumer);
    options.proguardMapConsumer = wrapProguardMapConsumer(options.proguardMapConsumer);
  }

  public com.debughelper.tools.r8.ProgramConsumer wrapProgramConsumer(ProgramConsumer consumer) {
    assert programConsumer == null;
    if (consumer instanceof com.debughelper.tools.r8.ClassFileConsumer) {
      wrapClassFileConsumer((com.debughelper.tools.r8.ClassFileConsumer) consumer);
    } else if (consumer instanceof com.debughelper.tools.r8.DexIndexedConsumer) {
      wrapDexIndexedConsumer((com.debughelper.tools.r8.DexIndexedConsumer) consumer);
    } else if (consumer instanceof com.debughelper.tools.r8.DexFilePerClassFileConsumer) {
      wrapDexFilePerClassFileConsumer((com.debughelper.tools.r8.DexFilePerClassFileConsumer) consumer);
    } else {
      // TODO(zerny): Refine API to disallow running without a program consumer.
      assert consumer == null;
      wrapDexIndexedConsumer(null);
    }
    assert programConsumer != null;
    return programConsumer;
  }

  public com.debughelper.tools.r8.StringConsumer wrapProguardMapConsumer(com.debughelper.tools.r8.StringConsumer consumer) {
    assert proguardMapConsumer == null;
    if (consumer != null) {
      proguardMapConsumer =
          new StringConsumer.ForwardingConsumer(consumer) {
            @Override
            public void accept(String string, com.debughelper.tools.r8.DiagnosticsHandler handler) {
              super.accept(string, handler);
              builder.setProguardMapOutputData(string);
            }
          };
    }
    return proguardMapConsumer;
  }

  public com.debughelper.tools.r8.DexIndexedConsumer wrapDexIndexedConsumer(com.debughelper.tools.r8.DexIndexedConsumer consumer) {
    assert programConsumer == null;
    com.debughelper.tools.r8.DexIndexedConsumer wrapped =
        new com.debughelper.tools.r8.DexIndexedConsumer.ForwardingConsumer(consumer) {

          // Sort the files by id so that their order is deterministic. Some tests depend on this.
          private Int2ReferenceSortedMap<DescriptorsWithContents> files =
              new Int2ReferenceAVLTreeMap<>();

          @Override
          public void accept(
              int fileIndex, byte[] data, Set<String> descriptors, com.debughelper.tools.r8.DiagnosticsHandler handler) {
            super.accept(fileIndex, data, descriptors, handler);
            addDexFile(fileIndex, data, descriptors);
          }

          @Override
          public void finished(com.debughelper.tools.r8.DiagnosticsHandler handler) {
            super.finished(handler);
            if (!closed) {
              closed = true;
              files.forEach((v, d) -> builder.addDexProgramData(d.contents, d.descriptors));
              files = null;
            } else {
              assert getDataResourceConsumer() != null;
            }
          }

          synchronized void addDexFile(int fileIndex, byte[] data, Set<String> descriptors) {
            files.put(fileIndex, new DescriptorsWithContents(descriptors, data));
          }
        };
    programConsumer = wrapped;
    return wrapped;
  }

  public com.debughelper.tools.r8.DexFilePerClassFileConsumer wrapDexFilePerClassFileConsumer(
      com.debughelper.tools.r8.DexFilePerClassFileConsumer consumer) {
    assert programConsumer == null;
    com.debughelper.tools.r8.DexFilePerClassFileConsumer wrapped =
        new DexFilePerClassFileConsumer.ForwardingConsumer(consumer) {

          // Sort the files by their name for good measure.
          private TreeMap<String, DescriptorsWithContents> files = new TreeMap<>();

          @Override
          public void accept(
              String primaryClassDescriptor,
              byte[] data,
              Set<String> descriptors,
              com.debughelper.tools.r8.DiagnosticsHandler handler) {
            super.accept(primaryClassDescriptor, data, descriptors, handler);
            addDexFile(primaryClassDescriptor, data, descriptors);
          }

          synchronized void addDexFile(
              String primaryClassDescriptor, byte[] data, Set<String> descriptors) {
            files.put(primaryClassDescriptor, new DescriptorsWithContents(descriptors, data));
          }

          @Override
          public void finished(com.debughelper.tools.r8.DiagnosticsHandler handler) {
            super.finished(handler);
            if (!closed) {
              closed = true;
              files.forEach((v, d) -> builder.addDexProgramData(d.contents, d.descriptors, v));
              files = null;
            } else {
              assert getDataResourceConsumer() != null;
            }
          }
        };
    programConsumer = wrapped;
    return wrapped;
  }

  public com.debughelper.tools.r8.ClassFileConsumer wrapClassFileConsumer(com.debughelper.tools.r8.ClassFileConsumer consumer) {
    assert programConsumer == null;
    com.debughelper.tools.r8.ClassFileConsumer wrapped =
        new ClassFileConsumer.ForwardingConsumer(consumer) {

          private List<DescriptorsWithContents> files = new ArrayList<>();

          @Override
          public void accept(byte[] data, String descriptor, com.debughelper.tools.r8.DiagnosticsHandler handler) {
            super.accept(data, descriptor, handler);
            addClassFile(data, descriptor);
          }

          synchronized void addClassFile(byte[] data, String descriptor) {
            files.add(new DescriptorsWithContents(Collections.singleton(descriptor), data));
          }

          @Override
          public void finished(DiagnosticsHandler handler) {
            super.finished(handler);
            if (!closed) {
              closed = true;
              files.forEach(
                  d -> builder.addClassProgramData(d.contents, Origin.unknown(), d.descriptors));
              files = null;
            } else {
              assert getDataResourceConsumer() != null;
            }
          }
        };
    programConsumer = wrapped;
    return wrapped;
  }

  public AndroidApp build() {
    assert closed;
    return builder.build();
  }

  private static class DescriptorsWithContents {

    final Set<String> descriptors;
    final byte[] contents;

    private DescriptorsWithContents(Set<String> descriptors, byte[] contents) {
      this.descriptors = descriptors;
      this.contents = contents;
    }
  }
}
