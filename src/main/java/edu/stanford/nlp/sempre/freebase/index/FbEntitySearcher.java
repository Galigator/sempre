package edu.stanford.nlp.sempre.freebase.index;

import fig.basic.LogInfo;
import fig.basic.StopWatch;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

public class FbEntitySearcher
{

	private final QueryParser queryParser;
	private final IndexSearcher indexSearcher;
	private int numOfDocs = 50;
	private final String searchStrategy;

	public FbEntitySearcher(final String indexDir, final int numOfDocs, final String searchingStrategy) throws IOException
	{

		LogInfo.begin_track("Constructing Searcher");
		if (!searchingStrategy.equals("exact") && !searchingStrategy.equals("inexact"))
			throw new RuntimeException("Bad searching strategy: " + searchingStrategy);
		searchStrategy = searchingStrategy;

		queryParser = new QueryParser(Version.LUCENE_44, FbIndexField.TEXT.fieldName(), searchingStrategy.equals("exact") ? new KeywordAnalyzer() : new StandardAnalyzer(Version.LUCENE_44));
		LogInfo.log("Opening index dir: " + indexDir);
		final IndexReader indexReader = DirectoryReader.open(FSDirectory.open(new File(indexDir)));
		indexSearcher = new IndexSearcher(indexReader);
		LogInfo.log("Opened index with " + indexReader.numDocs() + " documents.");

		this.numOfDocs = numOfDocs;
		LogInfo.end_track();
	}

	public synchronized List<Document> searchDocs(String question) throws IOException, ParseException
	{

		final List<Document> res = new LinkedList<>();
		if (searchStrategy.equals("exact"))
			question = "\"" + question + "\"";

		final ScoreDoc[] hits = getHits(question);

		for (final ScoreDoc hit : hits)
		{
			final int docId = hit.doc;
			final Document doc = indexSearcher.doc(docId);
			res.add(doc);
		}
		return res;
	}

	private ScoreDoc[] getHits(final String question) throws IOException, ParseException
	{
		final Query luceneQuery = queryParser.parse(question);
		final ScoreDoc[] hits = indexSearcher.search(luceneQuery, numOfDocs).scoreDocs;
		return hits;
	}

	public static void main(final String[] args) throws IOException, ParseException
	{

		final Pattern quit = Pattern.compile("quit|exit|q|bye", Pattern.CASE_INSENSITIVE);
		final FbEntitySearcher searcher = new FbEntitySearcher(args[0], 10000, args[1]);
		final BufferedReader is = new BufferedReader(new InputStreamReader(System.in));
		final StopWatch watch = new StopWatch();
		while (true)
		{
			System.out.print("Search> ");
			final String question = is.readLine().trim();
			if (quit.matcher(question).matches())
			{
				System.out.println("Quitting.");
				break;
			}
			if (question.equals(""))
				continue;

			watch.reset();
			watch.start();
			final List<Document> docs = searcher.searchDocs(question);
			watch.stop();
			for (final Document doc : docs)
				LogInfo.log("Mid: " + doc.get(FbIndexField.MID.fieldName()) + "\t" + "id: " + doc.get(FbIndexField.ID.fieldName()) + "\t" + "types: " + doc.get(FbIndexField.TYPES.fieldName()) + "\t" + "Name: " + doc.get(FbIndexField.TEXT.fieldName()) + "\t" + "Popularity: " + doc.get(FbIndexField.POPULARITY.fieldName()));
			LogInfo.logs("Number of docs: %s, Time: %s", docs.size(), watch);
		}
	}
}
