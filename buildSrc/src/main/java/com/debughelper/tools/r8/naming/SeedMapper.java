// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.naming;

import static com.debughelper.tools.r8.utils.DescriptorUtils.javaTypeToDescriptor;

import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.naming.ClassNameMapper;
import com.debughelper.tools.r8.naming.ClassNaming;
import com.debughelper.tools.r8.naming.ClassNamingForMapApplier;
import com.debughelper.tools.r8.naming.MemberNaming.Signature;
import com.debughelper.tools.r8.naming.ProguardMap;
import com.debughelper.tools.r8.naming.ProguardMapReader;
import com.google.common.collect.ImmutableMap;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Mappings read from the given ProGuard map.
 * <p>
 * The main differences of this against {@link com.debughelper.tools.r8.naming.ClassNameMapper} and
 * {@link ClassNameMapper#getObfuscatedToOriginalMapping()} are:
 *   1) the key is the original descriptor, not the obfuscated java name. Thus, it is much easier
 *   to look up what mapping to apply while traversing {@link DexType}s; and
 *   2) the value is {@link com.debughelper.tools.r8.naming.ClassNamingForMapApplier}, another variant of {@link ClassNaming},
 *   which also uses original {@link Signature} as a key, instead of renamed {@link Signature}.
 */
public class SeedMapper implements com.debughelper.tools.r8.naming.ProguardMap {

  static class Builder extends ProguardMap.Builder {
    final ImmutableMap.Builder<String, com.debughelper.tools.r8.naming.ClassNamingForMapApplier.Builder> mapBuilder;

    private Builder() {
      mapBuilder = ImmutableMap.builder();
    }

    @Override
    com.debughelper.tools.r8.naming.ClassNamingForMapApplier.Builder classNamingBuilder(String renamedName, String originalName) {
      String originalDescriptor = javaTypeToDescriptor(originalName);
      com.debughelper.tools.r8.naming.ClassNamingForMapApplier.Builder classNamingBuilder =
          com.debughelper.tools.r8.naming.ClassNamingForMapApplier.builder(javaTypeToDescriptor(renamedName), originalDescriptor);
      mapBuilder.put(originalDescriptor, classNamingBuilder);
      return classNamingBuilder;
    }

    @Override
    SeedMapper build() {
      return new SeedMapper(mapBuilder.build());
    }
  }

  static Builder builder() {
    return new Builder();
  }

  private static SeedMapper seedMapperFromInputStream(InputStream in) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    try (com.debughelper.tools.r8.naming.ProguardMapReader proguardReader = new ProguardMapReader(reader)) {
      SeedMapper.Builder builder = SeedMapper.builder();
      proguardReader.parse(builder);
      return builder.build();
    }
  }

  public static SeedMapper seedMapperFromFile(Path path) throws IOException {
    return seedMapperFromInputStream(Files.newInputStream(path));
  }

  private final ImmutableMap<String, com.debughelper.tools.r8.naming.ClassNamingForMapApplier> mappings;

  private SeedMapper(Map<String, com.debughelper.tools.r8.naming.ClassNamingForMapApplier.Builder> mappings) {
    ImmutableMap.Builder<String, com.debughelper.tools.r8.naming.ClassNamingForMapApplier> builder = ImmutableMap.builder();
    for(Map.Entry<String, com.debughelper.tools.r8.naming.ClassNamingForMapApplier.Builder> entry : mappings.entrySet()) {
      builder.put(entry.getKey(), entry.getValue().build());
    }
    this.mappings = builder.build();
  }

  @Override
  public boolean hasMapping(DexType type) {
    return mappings.containsKey(type.descriptor.toString());
  }

  @Override
  public ClassNamingForMapApplier getClassNaming(DexType type) {
    return mappings.get(type.descriptor.toString());
  }
}
