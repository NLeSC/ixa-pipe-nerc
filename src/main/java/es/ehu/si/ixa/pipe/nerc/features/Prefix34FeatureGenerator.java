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
package es.ehu.si.ixa.pipe.nerc.features;


import java.util.List;

	public class Prefix34FeatureGenerator extends FeatureGeneratorAdapter {

	  private static final int PREFIX_LENGTH = 4;
	  
	  public static String[] getPrefixes(String lex) {
	    String[] prefs = new String[PREFIX_LENGTH];
	    for (int li = 3, ll = PREFIX_LENGTH; li < ll; li++) {
	      prefs[li] = lex.substring(0, Math.min(li + 1, lex.length()));
	    }
	    return prefs;
	  }
	  
	  public void createFeatures(List<String> features, String[] tokens, int index,
	      String[] previousOutcomes) {
	    String[] prefs = Prefix34FeatureGenerator.getPrefixes(tokens[index]);
	    for (String pref : prefs) {
	      features.add("pre=" + pref);
	    }
	  }
	}

	

