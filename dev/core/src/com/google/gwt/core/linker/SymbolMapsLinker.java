/*
 * Copyright 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.core.linker;

import com.google.gwt.core.ext.LinkerContext;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.linker.AbstractLinker;
import com.google.gwt.core.ext.linker.ArtifactSet;
import com.google.gwt.core.ext.linker.CompilationResult;
import com.google.gwt.core.ext.linker.EmittedArtifact;
import com.google.gwt.core.ext.linker.EmittedArtifact.Visibility;
import com.google.gwt.core.ext.linker.LinkerOrder;
import com.google.gwt.core.ext.linker.LinkerOrder.Order;
import com.google.gwt.core.ext.linker.SelectionProperty;
import com.google.gwt.core.ext.linker.Shardable;
import com.google.gwt.core.ext.linker.SoftPermutation;
import com.google.gwt.core.ext.linker.SymbolData;
import com.google.gwt.core.ext.linker.SyntheticArtifact;
import com.google.gwt.dev.util.Util;
import com.google.gwt.dev.util.collect.HashMap;
import com.google.gwt.dev.util.log.speedtracer.CompilerEventType;
import com.google.gwt.dev.util.log.speedtracer.SpeedTracerLogger;
import com.google.gwt.dev.util.log.speedtracer.SpeedTracerLogger.Event;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.regex.Pattern;

/**
 * This Linker exports the symbol maps associated with each compilation result as a private file.
 * The names of the symbol maps files are computed by appending {@value #STRONG_NAME_SUFFIX} to the
 * value returned by {@link CompilationResult#getStrongName()}.
 */
@LinkerOrder(Order.POST)
@Shardable
public class SymbolMapsLinker extends AbstractLinker {

  public static final String MAKE_SYMBOL_MAPS = "compiler.useSymbolMaps";

  /**
   * Artifact to represent a sourcemap file to be processed by SymbolMapsLinker.
   */
  public static class SourceMapArtifact extends SyntheticArtifact {

    // This pattern should match sourceMapFilenameForFragment.
    public static final Pattern isSourceMapFile = Pattern.compile("sourceMap[0-9]+\\.json$");

    private int permutationId;
    private int fragment;
    private byte[] js;

    private final String sourceRoot;

    public SourceMapArtifact(int permutationId, int fragment, byte[] js, String sourceRoot) {
      super(SymbolMapsLinker.class, permutationId + '/' + sourceMapFilenameForFragment(fragment), js);
      this.permutationId = permutationId;
      this.fragment = fragment;
      this.js = js;
      this.sourceRoot = sourceRoot;
    }

    public int getFragment() {
      return fragment;
    }

    public int getPermutationId() {
      return permutationId;
    }

    /**
     * The base URL for Java filenames in the sourcemap.
     * (We need to reapply this after edits.)
     */
    public String getSourceRoot() {
      return sourceRoot;
    }

    public static String sourceMapFilenameForFragment(int fragment) {
      // If this changes, update isSourceMapFile.
      return "sourceMap" + fragment + ".json";
    }
  }

  /**
   * This value is appended to the strong name of the CompilationResult to form the symbol map's
   * filename.
   */
  public static final String STRONG_NAME_SUFFIX = ".symbolMap";

  public static String propertyMapToString(
      Map<SelectionProperty, String> propertyMap) {
    StringWriter writer = new StringWriter();
    PrintWriter pw = new PrintWriter(writer);
    printPropertyMap(pw, propertyMap);
    pw.flush();
    return writer.toString();
  }

  private static void printPropertyMap(PrintWriter pw,
      Map<SelectionProperty, String> map) {
    boolean needsComma = false;
    for (Map.Entry<SelectionProperty, String> entry : map.entrySet()) {
      if (needsComma) {
        pw.print(" , ");
      } else {
        needsComma = true;
      }

      pw.print("'");
      pw.print(entry.getKey().getName());
      pw.print("' : '");
      pw.print(entry.getValue());
      pw.print("'");
    }
  }

  @Override
  public String getDescription() {
    return "Export CompilationResult symbol maps";
  }

  /**
   * Included to support legacy non-shardable subclasses.
   */
  @Override
  public ArtifactSet link(TreeLogger logger, LinkerContext context,
      ArtifactSet artifacts) throws UnableToCompleteException {
    return link(logger, context, artifacts, true);
  }

  @Override
  public ArtifactSet link(TreeLogger logger, LinkerContext context,
      ArtifactSet artifacts, boolean onePermutation)
      throws UnableToCompleteException {

    if (onePermutation) {
      artifacts = new ArtifactSet(artifacts);
      Map<Integer, String> permMap = new HashMap<Integer, String>();

      Event writeSymbolMapsEvent =
          SpeedTracerLogger.start(CompilerEventType.WRITE_SYMBOL_MAPS);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      for (CompilationResult result : artifacts.find(CompilationResult.class)) {

        boolean makeSymbolMaps = true;

        for (SoftPermutation perm : result.getSoftPermutations()) {
          for (Entry<SelectionProperty, String> propMapEntry : perm.getPropertyMap().entrySet()) {
            if (propMapEntry.getKey().getName().equals(MAKE_SYMBOL_MAPS)) {
              makeSymbolMaps = Boolean.valueOf(propMapEntry.getValue());
            }
          }
        }

        permMap.put(result.getPermutationId(), result.getStrongName());

        if (makeSymbolMaps) {
          PrintWriter pw = new PrintWriter(out);
          doWriteSymbolMap(logger, result, pw);
          pw.close();

          doEmitSymbolMap(logger, artifacts, result, out);
          out.reset();
        }
      }
      writeSymbolMapsEvent.end();

      Event writeSourceMapsEvent =
          SpeedTracerLogger.start(CompilerEventType.WRITE_SOURCE_MAPS);
      for (SourceMapArtifact se : artifacts.find(SourceMapArtifact.class)) {
        // filename is permutation_id/sourceMap<fragmentNumber>.json
        String sourceMapString = Util.readStreamAsString(se.getContents(logger));
        String strongName = permMap.get(se.getPermutationId());
        String partialPath = strongName + "_sourceMap" + se.getFragment() + ".json";
        artifacts.add(emitSourceMapString(logger, sourceMapString, partialPath));
        artifacts.remove(se);
      }
      writeSourceMapsEvent.end();
    }
    return artifacts;
  }

  /**
   * Override to change the manner in which the symbol map is emitted.
   */
  protected void doEmitSymbolMap(TreeLogger logger, ArtifactSet artifacts,
      CompilationResult result, ByteArrayOutputStream out)
      throws UnableToCompleteException {
    EmittedArtifact symbolMapArtifact = emitBytes(logger, out.toByteArray(),
        result.getStrongName() + STRONG_NAME_SUFFIX);
    // TODO: change to Deploy when possible
    symbolMapArtifact.setVisibility(Visibility.LegacyDeploy);
    artifacts.add(symbolMapArtifact);
  }

  /**
   * Override to change the format of the symbol map.
   *
   * @param logger the logger to write to
   * @param result the compilation result
   * @param pw     the output PrintWriter
   * @throws UnableToCompleteException if an error occurs
   */
  protected void doWriteSymbolMap(TreeLogger logger, CompilationResult result,
      PrintWriter pw) throws UnableToCompleteException {
    pw.println("# { " + result.getPermutationId() + " }");

    for (SortedMap<SelectionProperty, String> map : result.getPropertyMap()) {
      pw.print("# { ");
      printPropertyMap(pw, map);
      pw.println(" }");
    }

    pw.println("# jsName, jsniIdent, className, memberName, sourceUri, sourceLine, fragmentNumber");
    StringBuilder sb = new StringBuilder(1024);
    char[] buf = new char[1024];
    for (SymbolData symbol : result.getSymbolMap()) {
      sb.append(symbol.getSymbolName());

      sb.append(',');
      String jsniIdent = symbol.getJsniIdent();
      if (jsniIdent != null) {
        sb.append(jsniIdent);
      }
      sb.append(',');
      sb.append(symbol.getClassName());
      sb.append(',');
      String memberName = symbol.getMemberName();
      if (memberName != null) {
        sb.append(memberName);
      }
      sb.append(',');
      String sourceUri = symbol.getSourceUri();
      if (sourceUri != null) {
        sb.append(sourceUri);
      }
      sb.append(',');
      sb.append(symbol.getSourceLine());
      sb.append(',');
      sb.append(symbol.getFragmentNumber());
      sb.append('\n');

      int sbLen = sb.length();
      if (buf.length < sbLen) {
        int bufLen = buf.length;
        while (bufLen < sbLen) {
          bufLen <<= 1;
        }
        buf = new char[bufLen];
      }
      sb.getChars(0, sbLen, buf, 0);
      pw.write(buf, 0, sbLen);
      sb.setLength(0);
    }
  }

  protected SyntheticArtifact emitSourceMapString(TreeLogger logger, String contents,
      String partialPath) throws UnableToCompleteException {
    SyntheticArtifact emArt = emitString(logger, contents, partialPath);
    emArt.setVisibility(Visibility.LegacyDeploy);
    return emArt;
  }
}
