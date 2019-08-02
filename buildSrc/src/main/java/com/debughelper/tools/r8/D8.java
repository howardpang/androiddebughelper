// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8;

import static com.debughelper.tools.r8.D8Command.USAGE_MESSAGE;

import com.debughelper.tools.r8.dex.ApplicationReader;
import com.debughelper.tools.r8.dex.ApplicationWriter;
import com.debughelper.tools.r8.dex.Marker;
import com.debughelper.tools.r8.dex.Marker.Tool;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DexApplication;
import com.debughelper.tools.r8.ir.conversion.IRConverter;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.origin.CommandLineOrigin;
import com.debughelper.tools.r8.utils.AndroidApp;
import com.debughelper.tools.r8.utils.CfgPrinter;
import com.debughelper.tools.r8.utils.ExceptionUtils;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.ThreadUtils;
import com.debughelper.tools.r8.utils.Timing;
import com.debughelper.tools.r8.utils.VersionProperties;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

/**
 * The D8Adapter dex compiler.
 *
 * <p>D8Adapter performs modular compilation to DEX bytecode. It supports compilation of Java bytecode and
 * debughelper DEX bytecode to DEX bytecode including merging a mix of these input formats.
 *
 * <p>The D8Adapter dexer API is intentionally limited and should "do the right thing" given a command. If
 * this API does not suffice please contact the D8Adapter/R8 team.
 *
 * <p>The compiler is invoked by calling {@link #run(D8Command) D8Adapter.run} with an appropriate {@link
 * D8Command}. For example:
 *
 * <pre>
 *   D8Adapter.run(D8Command.builder()
 *       .addProgramFiles(inputPathA, inputPathB)
 *       .setOutput(outputPath, OutputMode.DexIndexed)
 *       .build());
 * </pre>
 *
 * The above reads the input files denoted by {@code inputPathA} and {@code inputPathB}, compiles
 * them to DEX bytecode (compiling from Java bytecode for such inputs and merging for DEX inputs),
 * and then writes the result to the directory or zip archive specified by {@code outputPath}.
 */
@Keep
public final class D8 {

  private D8() {}

  /**
   * Main API entry for the D8Adapter dexer.
   *
   * @param command D8Adapter command.
   */
  public static void run(D8Command command) throws CompilationFailedException {
    AndroidApp app = command.getInputApp();
    InternalOptions options = command.getInternalOptions();
    ExecutorService executor = ThreadUtils.getExecutorService(options);
    ExceptionUtils.withD8CompilationHandler(
        command.getReporter(),
        () -> {
          try {
            run(app, options, executor);
          } finally {
            executor.shutdown();
          }
        });
  }

  /**
   * Main API entry for the D8Adapter dexer with a externally supplied executor service.
   *
   * @param command D8Adapter command.
   * @param executor executor service from which to get threads for multi-threaded processing.
   */
  public static void run(D8Command command, ExecutorService executor)
      throws CompilationFailedException {
    AndroidApp app = command.getInputApp();
    InternalOptions options = command.getInternalOptions();
    ExceptionUtils.withD8CompilationHandler(
        command.getReporter(),
        () -> {
          run(app, options, executor);
        });
  }

  private static void run(String[] args) throws CompilationFailedException {
    D8Command command = D8Command.parse(args, CommandLineOrigin.INSTANCE).build();
    if (command.isPrintHelp()) {
      System.out.println(D8Command.USAGE_MESSAGE);
      return;
    }
    if (command.isPrintVersion()) {
      Version.printToolVersion("D8Adapter");
      return;
    }
    InternalOptions options = command.getInternalOptions();
    AndroidApp app = command.getInputApp();
    ExceptionUtils.withD8CompilationHandler(options.reporter, () -> runForTesting(app, options));
  }

  /**
   * Command-line entry to D8Adapter.
   *
   * See {@link D8Command#USAGE_MESSAGE} or run {@code d8 --help} for usage information.
   */
  public static void main(String[] args) {
    if (args.length == 0) {
      System.err.println(D8Command.USAGE_MESSAGE);
      System.exit(ExceptionUtils.STATUS_ERROR);
    }
    ExceptionUtils.withMainProgramHandler(() -> run(args));
  }

  static void runForTesting(AndroidApp inputApp, InternalOptions options) throws IOException {
    ExecutorService executor = ThreadUtils.getExecutorService(options);
    try {
      run(inputApp, options, executor);
    } finally {
      executor.shutdown();
    }
  }

  // Compute the marker to be placed in the main dex file.
  static Marker getMarker(InternalOptions options) {
    if (options.hasMarker()) {
      return options.getMarker();
    }
    if (options.testing.dontCreateMarkerInD8) {
      return null;
    }
    Marker marker = new Marker(Tool.D8)
        .setVersion(Version.LABEL)
        .setMinApi(options.minApiLevel);
    if (Version.isDev()) {
      marker.setSha1(VersionProperties.INSTANCE.getSha());
    }
    return marker;
  }

  private static void run(AndroidApp inputApp, InternalOptions options, ExecutorService executor)
      throws IOException {
    Timing timing = new Timing("D8Adapter");
    try {
      // Disable global optimizations.
      options.enableMinification = false;
      options.enableInlining = false;
      options.enableClassInlining = false;
      options.outline.enabled = false;

      DexApplication app = new ApplicationReader(inputApp, options, timing).read(executor);
      AppInfo appInfo = new AppInfo(app);
      app = optimize(app, appInfo, options, timing, executor);

      // If a method filter is present don't produce output since the application is likely partial.
      if (options.hasMethodsFilter()) {
        System.out.println("Finished compilation with method filter: ");
        options.methodsFilter.forEach((m) -> System.out.println("  - " + m));
      }
      Marker marker = getMarker(options);
      new ApplicationWriter(
              app,
              options,
              marker == null ? null : Collections.singletonList(marker),
              null,
              NamingLens.getIdentityLens(),
              null,
              null)
          .write(executor);
      options.printWarnings();
    } catch (ExecutionException e) {
      R8.unwrapExecutionException(e);
      throw new AssertionError(e); // unwrapping method should have thrown
    } finally {
      options.signalFinishedToConsumers();
      // Dump timings.
      if (options.printTimes) {
        timing.report();
      }
    }
  }

  static DexApplication optimize(
      DexApplication application,
      AppInfo appInfo,
      InternalOptions options,
      Timing timing,
      ExecutorService executor)
      throws IOException, ExecutionException {
    final CfgPrinter printer = options.printCfg ? new CfgPrinter() : null;

    IRConverter converter = new IRConverter(appInfo, options, timing, printer);
    application = converter.convertToDex(application, executor);

    if (options.printCfg) {
      if (options.printCfgFile == null || options.printCfgFile.isEmpty()) {
        System.out.print(printer.toString());
      } else {
        try (OutputStreamWriter writer = new OutputStreamWriter(
            new FileOutputStream(options.printCfgFile),
            StandardCharsets.UTF_8)) {
          writer.write(printer.toString());
        }
      }
    }
    return application;
  }
}
