/*
 *  Copyright 2014 Rodrigo Agerri

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package es.ehu.si.ixa.pipe.nerc;

import ixa.kaflib.KAFDocument;
import ixa.kaflib.Term;
import ixa.kaflib.WF;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.util.Span;
import es.ehu.si.ixa.pipe.nerc.dict.Dictionaries;

/**
 * Annotation class of ixa-pipe-nerc.
 * 
 * @author ragerri
 * @version 2014/06/25
 * 
 */
public class Annotate {

  /**
   * The name factory.
   */
  private NameFactory nameFactory;
  /**
   * The NameFinder to do the annotation. Usually the statistical.
   */
  private NameFinder nameFinder;
  /**
   * The dictionaries.
   */
  private Dictionaries dictionaries;
  /**
   * The dictionary name finder.
   */
  private DictionariesNameFinder dictFinder;
  /**
   * The NameFinder Lexer for rule-based name finding.
   */
  private NumericNameFinder numericLexerFinder;
  /**
   * True if the name finder is statistical.
   */
  private boolean statistical;
  /**
   * Activates post processing of statistical name finder with dictionary name
   * finders.
   */
  private boolean postProcess;
  /**
   * Activates name finding using dictionaries only.
   */
  private boolean dictTag;
  /**
   * Activates name finding using {@code NameFinderLexer}s.
   */
  private boolean lexerFind;

  /**
   * @param properties the properties
   * @param beamsize the beamsize for decoding
   * @throws IOException the io thrown 
   */
  public Annotate(final Properties properties, int beamsize) throws IOException {

    if (properties.getProperty("model").equalsIgnoreCase("default")) {
      System.err.println("Backing off to default model!");
    }
    nameFactory = new NameFactory();
    dictionaryOptions(properties);
    nonDictOptions(properties);
    statisticalOptions(properties);
  }
  
  
  /**
   * Generates the right options for dictionary-based NER tagging:
   * Dictionary features by means of the {@link StatisticalNameFinder} 
   * or using the {@link DictionaryNameFinder} or a combination of
   * those with the {@link NumericNameFinder}.
   * 
   * @param lang
   * @param model
   * @param features
   * @param beamsize
   * @param dictOption
   * @param dictPath
   * @param ruleBasedOption
   * @throws IOException 
   */
  //TODO surely we can simplify this?
  private void dictionaryOptions(Properties properties) throws IOException {
    String dictPath = properties.getProperty("dictPath");
    String ruleBasedOption = properties.getProperty("ruleBasedOption");
    String dictOption = properties.getProperty("dictOption");
    String features = properties.getProperty("features");
    Integer beamsize = Integer.parseInt(properties.getProperty("beamsize"));
    
    if (!dictPath.equals(CLI.DEFAULT_DICT_PATH)) {
      if (!ruleBasedOption.equals(CLI.DEFAULT_LEXER)) {
        lexerFind = true;
      }
      dictionaries = new Dictionaries(dictPath);
      if (!dictOption.equals(CLI.DEFAULT_DICT_OPTION)) {
        dictFinder = new DictionariesNameFinder(dictionaries, nameFactory);
        if (dictOption.equalsIgnoreCase("tag")) {
          dictTag = true;
          postProcess = false;
          statistical = false;
        }
        else if (dictOption.equalsIgnoreCase("post")) {
          if (features.equalsIgnoreCase("dict")) {
            nameFinder = new StatisticalNameFinder(properties, beamsize, dictionaries, nameFactory);
          } else {
            nameFinder = new StatisticalNameFinder(properties, beamsize, nameFactory);
          }
          statistical = true;
          postProcess = true;
          dictTag = false;
        }
      } else {
        statistical = true;
        nameFinder = new StatisticalNameFinder(properties, beamsize, dictionaries, nameFactory);
        dictTag = false;
        postProcess = false;
      }
    }
  }
  
  /**
   * Generates the right options when dictionary-related name finders are not used.
   * 
   * @param lang the language
   * @param model the model
   * @param features the features
   * @param beamsize the beamsize for decoding
   * @param dictPath the directory containing the dictionaries
   * @param ruleBasedOption the lexer option
   */
  private void nonDictOptions (Properties properties) {
    String dictPath = properties.getProperty("dictPath");
    String ruleBasedOption = properties.getProperty("ruleBasedOption");
    Integer beamsize = Integer.parseInt(properties.getProperty("beamsize"));
    
    if (dictPath.equals(CLI.DEFAULT_DICT_PATH) && !ruleBasedOption.equals(CLI.DEFAULT_LEXER)) {
      nameFinder = new StatisticalNameFinder(properties, beamsize, nameFactory);
      lexerFind = true;
      statistical = true;
      dictTag = false;
      postProcess = false;
    }
  }
  
  /**
   * Generates the right options when dictionary-related name finders are not used.
   * 
   * @param lang the language
   * @param model the model
   * @param features the features
   * @param beamsize the beamsize for decoding
   * @param dictPath the directory containing the dictionaries
   * @param ruleBasedOption the lexer option
   */
  private void statisticalOptions (Properties properties) {
    String dictPath = properties.getProperty("dictPath");
    String dictOption = properties.getProperty("dictOption");
    String ruleBasedOption = properties.getProperty("ruleBasedOption");
    Integer beamsize = Integer.parseInt(properties.getProperty("beamsize"));
    
    if (dictPath.equals(CLI.DEFAULT_DICT_PATH) && dictOption.equals(CLI.DEFAULT_DICT_OPTION) && ruleBasedOption.equals(CLI.DEFAULT_DICT_PATH)) {
      nameFinder = new StatisticalNameFinder(properties, beamsize, nameFactory);
      lexerFind = false;
      statistical = true;
      dictTag = false;
      postProcess = false;
    }
  }

  /**
   * Classify Named Entities and write them to a {@link KAFDocument} using
   * statistical models, post-processing and/or dictionaries only.
   * 
   * @param kaf
   *          the kaf document to be used for annotation
   * @throws IOException
   *           throws exception if problems with the kaf document
   */
  public final void annotateNEsToKAF(final KAFDocument kaf) throws IOException {

    List<Span> allSpans = new ArrayList<Span>();
    List<List<WF>> sentences = kaf.getSentences();
    for (List<WF> sentence : sentences) {
      String[] tokens = new String[sentence.size()];
      String[] tokenIds = new String[sentence.size()];
      for (int i = 0; i < sentence.size(); i++) {
        tokens[i] = sentence.get(i).getForm();
        tokenIds[i] = sentence.get(i).getId();
      }
      if (statistical) {
        allSpans = nameFinder.nercToSpans(tokens);
      }
      if (postProcess) {
        List<Span> dictSpans = dictFinder.nercToSpansExact(tokens);
        SpanUtils.postProcessDuplicatedSpans(allSpans, dictSpans);
        SpanUtils.concatenateSpans(allSpans, dictSpans);
      }
      if (dictTag) {
        allSpans = dictFinder.nercToSpansExact(tokens);
      }
      if (lexerFind) {
        String sentenceText = StringUtils.getStringFromTokens(tokens);
        StringReader stringReader = new StringReader(sentenceText);
        BufferedReader sentenceReader = new BufferedReader(stringReader);
        numericLexerFinder = new NumericNameFinder(sentenceReader, nameFactory);
        List<Span> numericSpans = numericLexerFinder.nercToSpans(tokens);
        SpanUtils.concatenateSpans(allSpans, numericSpans);
      }
      Span[] allSpansArray = NameFinderME.dropOverlappingSpans(allSpans
          .toArray(new Span[allSpans.size()]));
      List<Name> names = new ArrayList<Name>();
      if (statistical) {
        names = nameFinder.getNamesFromSpans(allSpansArray, tokens);
      } else {
        names = dictFinder.getNamesFromSpans(allSpansArray, tokens);
      }
      for (Name name : names) {
        Integer startIndex = name.getSpan().getStart();
        Integer endIndex = name.getSpan().getEnd();
        List<Term> nameTerms = kaf.getTermsFromWFs(Arrays.asList(Arrays
            .copyOfRange(tokenIds, startIndex, endIndex)));

        List<List<Term>> references = new ArrayList<List<Term>>();
        references.add(nameTerms);
        kaf.createEntity(name.getType(), references);
      }
    }
  }

  /**
   * Construct a {@link DictionaryNameFinder} for each of the dictionaries in
   * the directory provided.
   * 
   * @param dictPath
   *          the directory containing the dictionaries
   * @param nameFactory
   *          the factory to construct the names
   * @return
   */
  public final DictionariesNameFinder createDictNameFinder(
      final String dictPath, final NameFactory nameFactory) {
    Dictionaries dict = new Dictionaries(dictPath);
    DictionariesNameFinder dictNameFinder = new DictionariesNameFinder(dict,
        nameFactory);
    return dictNameFinder;
  }
}
