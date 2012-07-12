package com.yahoo.glimmer.indexing.generator;

/*
 * Copyright (c) 2012 Yahoo! Inc. All rights reserved.
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is 
 *  distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and limitations under the License.
 *  See accompanying LICENSE file.
 */

import it.unimi.dsi.io.WordReader;
import it.unimi.dsi.lang.MutableString;

import java.io.IOException;
import java.util.HashSet;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.Mapper;

import com.yahoo.glimmer.indexing.RDFDocument;
import com.yahoo.glimmer.indexing.RDFDocumentFactory;

public class DocumentMapper extends Mapper<LongWritable, RDFDocument, TermOccurrencePair, Occurrence> {
    static final int ALIGNMENT_INDEX = -1; // special index for alignments

    enum Counters {
	FAILED_PARSING, INDEXED_OCCURRENCES, NEGATIVE_PREDICATE_ID, NUMBER_OF_RECORDS
    }

    private String[] fields;

    protected void setup(org.apache.hadoop.mapreduce.Mapper<LongWritable, RDFDocument, TermOccurrencePair, Occurrence>.Context context) throws IOException,
	    InterruptedException {
	Configuration conf = context.getConfiguration();
	fields = RDFDocumentFactory.getFieldsFromConf(conf);
    };

    @Override
    public void map(LongWritable key, RDFDocument doc, Context context) throws IOException, InterruptedException {
	if (doc == null || doc.getSubject() == null) {
	    // Failed parsing
	    context.getCounter(Counters.FAILED_PARSING).increment(1);
	    System.out.println("Document failed parsing");
	    return;
	}

	// Collect the keys (term+index) of this document
	HashSet<TermOccurrencePair> keySet = new HashSet<TermOccurrencePair>();

	// used for counting # of docs per term
	Occurrence fakeDocOccurrrence = new Occurrence(null, doc.getId());

	// Iterate over all indices
	for (int i = 0; i < fields.length; i++) {

	    String fieldName = fields[i];
	    if (fieldName.startsWith("NOINDEX")) {
		continue;
	    }

	    // Iterate in parallel over the words of the indices
	    MutableString term = new MutableString("");
	    MutableString nonWord = new MutableString("");
	    WordReader termReader = doc.content(i);
	    int position = 0;

	    while (termReader.next(term, nonWord)) {
		// Read next property as well
		if (term != null) {

		    // Report progress
		    context.setStatus(fields[i] + "=" + term.substring(0, Math.min(term.length(), 50)));

		    // Create an occurrence at the next position
		    Occurrence occ = new Occurrence(doc.getId(), position);
		    context.write(new TermOccurrencePair(term.toString(), i, occ), occ);

		    // Create fake occurrences for each term (this will be
		    // used for counting # of docs per term
		    keySet.add(new TermOccurrencePair(term.toString(), i, fakeDocOccurrrence));

		    position++;
		    context.getCounter(Counters.INDEXED_OCCURRENCES).increment(1);

		    if (doc.getIndexType() == RDFDocumentFactory.IndexType.VERTICAL) {
			// Create an entry in the alignment index
			// int predicateID =
			// ResourcesHashLoader.lookup(fieldName).intValue();
			// if (predicateID < 0) {
			// System.err.println("Negative predicateID for URI: " +
			// fieldName);
			// context.getCounter(Counters.NEGATIVE_PREDICATE_ID).increment(1);
			// }
			int predicateID = i;
			Occurrence predicateOcc = new Occurrence(predicateID, null);
			// TODO Why not add to keySet?
			context.write(new TermOccurrencePair(term.toString(), ALIGNMENT_INDEX, predicateOcc), predicateOcc);
			Occurrence fakePredicateOccurrrence = new Occurrence(null, predicateID);
			keySet.add(new TermOccurrencePair(term.toString(), ALIGNMENT_INDEX, fakePredicateOccurrrence));
		    }
		} else {
		    System.out.println("Nextterm is null");
		}
	    }
	}

	context.getCounter(Counters.NUMBER_OF_RECORDS).increment(1);

	for (TermOccurrencePair term : keySet) {
	    context.write(term, term.getOccurrence());
	}
    }
}