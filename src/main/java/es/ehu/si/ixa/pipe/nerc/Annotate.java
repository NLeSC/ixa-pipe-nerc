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

import ixa.kaflib.Entity;
import ixa.kaflib.KAFDocument;
import ixa.kaflib.Term;
import ixa.kaflib.WF;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import opennlp.tools.util.Span;
import opennlp.tools.util.TrainingParameters;
import es.ehu.si.ixa.pipe.nerc.dict.Dictionaries;
import es.ehu.si.ixa.pipe.nerc.train.InputOutputUtils;
import es.ehu.si.ixa.pipe.nerc.train.NameClassifier;

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
   * @param properties
   *          the properties
   * @param beamsize
   *          the beamsize for decoding
   * @throws IOException
   *           the io thrown
   */
  public Annotate(final Properties properties, TrainingParameters params)
      throws IOException {

    nameFactory = new NameFactory();
    annotateOptions(properties, params);
  }

  /**
   * Generates the right options for dictionary-based NER tagging: Dictionary
   * features by means of the {@link StatisticalNameFinder} or using the
   * {@link DictionaryNameFinder} or a combination of those with the
   * {@link NumericNameFinder}.
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
  // TODO surely we can simplify this?
  private void annotateOptions(Properties properties,
      TrainingParameters params) throws IOException {
    
    String ruleBasedOption = properties.getProperty("ruleBasedOption");
    String dictFeature = InputOutputUtils.getDictionaryFeatures(params);
    String dictOption = InputOutputUtils.getDictOption(params);
    
    if (dictFeature.equals("yes")) {
      if (!ruleBasedOption.equals(CLI.DEFAULT_LEXER)) {
        lexerFind = true;
      }
      String dictPath = InputOutputUtils.getDictPath(params);
      dictionaries = new Dictionaries(dictPath);
      if (!dictOption.equals(CLI.DEFAULT_DICT_OPTION)) {
        dictFinder = new DictionariesNameFinder(dictionaries, nameFactory);
        if (dictOption.equalsIgnoreCase("tag")) {
          dictTag = true;
          postProcess = false;
          statistical = false;
        } else if (dictOption.equalsIgnoreCase("post")) {
          nameFinder = new StatisticalNameFinder(properties, params, nameFactory);
          statistical = true;
          postProcess = true;
          dictTag = false;
        }
      } else {
        nameFinder = new StatisticalNameFinder(properties, params, nameFactory);
        statistical = true;
        dictTag = false;
        postProcess = false;
      }
    }
    else if (!ruleBasedOption.equals(CLI.DEFAULT_LEXER)) {
      lexerFind = true;
      statistical = true;
      dictTag = false;
      postProcess = false;
      nameFinder = new StatisticalNameFinder(properties, params, nameFactory);
    }
    else {
      lexerFind = false;
      statistical = true;
      dictTag = false;
      postProcess = false;
      nameFinder = new StatisticalNameFinder(properties, params, nameFactory);
    }
  }

  /**
   * Classify Named Entities creating the entities layer in the {@link KAFDocument} using
   * statistical models, post-processing and/or dictionaries only.
   * 
   * @param kaf
   *          the kaf document to be used for annotation
   * @throws IOException
   *           throws exception if problems with the kaf document
   */
  public final void annotateNEs(final KAFDocument kaf) throws IOException {

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
      Span[] allSpansArray = NameClassifier.dropOverlappingSpans(allSpans
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
        ixa.kaflib.Span<Term> neSpan = KAFDocument.newTermSpan(nameTerms);
        List<ixa.kaflib.Span<Term>> references = new ArrayList<ixa.kaflib.Span<Term>>();
        references.add(neSpan);
        Entity neEntity = kaf.newEntity(references);
        neEntity.setType(name.getType());
      }
    }
  }
  
  /**
   * Output annotation as NAF.
   * @param kaf the naf document
   * @return the string containing the naf document
   */
  public final String annotateNEsToKAF(KAFDocument kaf) {
    return kaf.toString();
  }
  
  /**
   * Enumeration class for CoNLL 2003 BIO format
   */
  private static enum BIO {
      BEGIN("B-"), IN("I-"), OUT("O");
      String tag;
      BIO(String tag) {
          this.tag = tag;
      }
      public String toString() {
          return this.tag;
      }
  }
  
  /**
   * Output Conll2003 format.
   * @param kaf the kaf document
   * @return the annotated named entities in conll03 format
   */
  public String annotateNEsToCoNLL2003(KAFDocument kaf) {
    List<Entity> namedEntityList = kaf.getEntities();
    Map<String, Integer> entityToSpanSize = new HashMap<String, Integer>();
    Map<String, String> entityToType = new HashMap<String, String>();
    for (Entity ne : namedEntityList) {
      List<ixa.kaflib.Span<Term>> entitySpanList = ne.getSpans();
      for (ixa.kaflib.Span<Term> spanTerm : entitySpanList) {
        Term neTerm = spanTerm.getFirstTarget();
        //create map from term Id to Entity span size
        entityToSpanSize.put(neTerm.getId(), spanTerm.size());
        //create map from term Id to Entity type
        entityToType.put(neTerm.getId(), ne.getType());
      }
    }
    
    List<List<WF>> sentences = kaf.getSentences();
    StringBuilder sb = new StringBuilder();
    for (List<WF> sentence : sentences) {
      int index = 1;
      int sentNumber = sentence.get(0).getSent();
      List<Term> sentenceTerms = kaf.getSentenceTerms(sentNumber);
      boolean previousIsEntity = false;
      
      for (int i = 0; i < sentenceTerms.size(); i++) {
        Term thisTerm = sentenceTerms.get(i);
        //if term is inside an entity span then annotate B-I entities
        if (entityToSpanSize.get(thisTerm.getId()) != null) {
          int neSpanSize = entityToSpanSize.get(thisTerm.getId());
          String neClass = entityToType.get(thisTerm.getId());
          String neType = this.convertToConLLTypes(neClass);
          //if Entity span is multi token
          if (neSpanSize > 1) {
            for (int j = 0; j < neSpanSize; j++) {
              thisTerm = sentenceTerms.get(i + j);
              sb.append(thisTerm.getForm());
              sb.append("\t");
              sb.append(thisTerm.getLemma());
              sb.append("\t");
              sb.append(thisTerm.getMorphofeat());
              sb.append("\t");
              if (j == 0 && previousIsEntity) {
                sb.append(BIO.BEGIN.toString());
              } else {
                sb.append(BIO.IN.toString());
              }
              sb.append(neType);
              sb.append("\n");
              index++;
            }
          } else {
            sb.append(thisTerm.getForm());
            sb.append("\t");
            sb.append(thisTerm.getLemma());
            sb.append("\t");
            sb.append(thisTerm.getMorphofeat());
            sb.append("\t");
            if (previousIsEntity) {
              sb.append(BIO.BEGIN.toString());
            } else {
              sb.append(BIO.IN.toString());
            }
            sb.append(neType);
            sb.append("\n");
            index++;
          }
          previousIsEntity = true;
          i += neSpanSize -1;
        } else {
          sb.append(thisTerm.getForm());
          sb.append("\t");
          sb.append(thisTerm.getLemma());
          sb.append("\t");
          sb.append(thisTerm.getMorphofeat());
          sb.append("\t");
          sb.append(BIO.OUT);
          sb.append("\n");
          index++;
          previousIsEntity = false;
        }
      }
      sb.append("\n");//end of sentence
    }
    return sb.toString();
  }
  
  /**
   * Output Conll2002 format.
   * @param kaf the kaf document
   * @return the annotated named entities in conll03 format
   */
  public String annotateNEsToCoNLL2002(KAFDocument kaf) {
    List<Entity> namedEntityList = kaf.getEntities();
    Map<String, Integer> entityToSpanSize = new HashMap<String, Integer>();
    Map<String, String> entityToType = new HashMap<String, String>();
    for (Entity ne : namedEntityList) {
      List<ixa.kaflib.Span<Term>> entitySpanList = ne.getSpans();
      for (ixa.kaflib.Span<Term> spanTerm : entitySpanList) {
        Term neTerm = spanTerm.getFirstTarget();
        entityToSpanSize.put(neTerm.getId(), spanTerm.size());
        entityToType.put(neTerm.getId(), ne.getType());
      }
    }
    
    List<List<WF>> sentences = kaf.getSentences();
    StringBuilder sb = new StringBuilder();
    for (List<WF> sentence : sentences) {
      int index = 1;
      int sentNumber = sentence.get(0).getSent();
      List<Term> sentenceTerms = kaf.getSentenceTerms(sentNumber);
      boolean previousIsEntity = false;
      
      for (int i = 0; i < sentenceTerms.size(); i++) {
        Term thisTerm = sentenceTerms.get(i);
        
        if (entityToSpanSize.get(thisTerm.getId()) != null) {
          int neSpanSize = entityToSpanSize.get(thisTerm.getId());
          String neClass = entityToType.get(thisTerm.getId());
          String neType = convertToConLLTypes(neClass);
          if (neSpanSize > 1) {
            for (int j = 0; j < neSpanSize; j++) {
              thisTerm = sentenceTerms.get(i + j);
              sb.append(thisTerm.getForm());
              sb.append("\t");
              sb.append(thisTerm.getLemma());
              sb.append("\t");
              sb.append(thisTerm.getMorphofeat());
              sb.append("\t");
              if (j == 0 || previousIsEntity) {
                sb.append(BIO.BEGIN.toString());
              } else {
                sb.append(BIO.IN.toString());
              }
              sb.append(neType);
              sb.append("\n");
              index++;
            }
          } else {
            sb.append(thisTerm.getForm());
            sb.append("\t");
            sb.append(thisTerm.getLemma());
            sb.append("\t");
            sb.append(thisTerm.getMorphofeat());
            sb.append("\t");
            sb.append(BIO.BEGIN.toString());
            sb.append(neType);
            sb.append("\n");
            index++;
          }
          previousIsEntity = true;
          i += neSpanSize -1;
        } else {
          sb.append(thisTerm.getForm());
          sb.append("\t");
          sb.append(thisTerm.getLemma());
          sb.append("\t");
          sb.append(thisTerm.getMorphofeat());
          sb.append("\t");
          sb.append(BIO.OUT);
          sb.append("\n");
          index++;
          previousIsEntity = false;
        }
      }
      sb.append("\n");//end of sentence
    }
    return sb.toString();
  }

  /**
   * Convert Entity class annotation to CoNLL formats.
   * @param neType named entity class
   * @return the converted string
   */
  public String convertToConLLTypes(String neType) {
    String conllType = null;
    if (neType.startsWith("PER") || 
        neType.startsWith("ORG") || 
        neType.startsWith("LOC") ||
        neType.startsWith("GPE")) {
      conllType = neType.substring(0, 3);
    } else if (neType.equalsIgnoreCase("MISC")) {
      conllType = neType;
    }
    return conllType;
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
