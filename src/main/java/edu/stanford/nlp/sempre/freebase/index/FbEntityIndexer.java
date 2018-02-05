package edu.stanford.nlp.sempre.freebase.index;

import edu.stanford.nlp.io.IOUtils;
import fig.basic.LogInfo;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.store.SimpleFSDirectory;
import org.apache.lucene.util.Version;

public class FbEntityIndexer
{

	private final IndexWriter indexer;
	private final String nameFile;

	public FbEntityIndexer(final String namefile, final String outputDir, final String indexingStrategy) throws IOException
	{

		if (!indexingStrategy.equals("exact") && !indexingStrategy.equals("inexact"))
			throw new RuntimeException("Bad indexing strategy: " + indexingStrategy);

		final IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_44, indexingStrategy.equals("exact") ? new KeywordAnalyzer() : new StandardAnalyzer(Version.LUCENE_44));
		config.setOpenMode(OpenMode.CREATE);
		config.setRAMBufferSizeMB(256.0);
		indexer = new IndexWriter(new SimpleFSDirectory(new File(outputDir)), config);

		nameFile = namefile;
	}

	/**
	 * Index the datadump file
	 *
	 * @throws IOException
	 */
	public void index() throws IOException
	{

		LogInfo.begin_track("Indexing");
		final BufferedReader reader = IOUtils.getBufferedFileReader(nameFile);
		String line;
		int indexed = 0;
		while ((line = reader.readLine()) != null)
		{

			final String[] tokens = line.split("\t");

			final String mid = tokens[0];
			final String id = tokens[1];
			if (id.startsWith("fb:user.") || id.startsWith("fb:base."))
				continue;
			final String popularity = tokens[2];
			final String text = tokens[3].toLowerCase();

			// add to index
			final Document doc = new Document();
			doc.add(new StringField(FbIndexField.MID.fieldName(), mid, Field.Store.YES));
			doc.add(new StringField(FbIndexField.ID.fieldName(), id, Field.Store.YES));
			doc.add(new StoredField(FbIndexField.POPULARITY.fieldName(), popularity));
			doc.add(new TextField(FbIndexField.TEXT.fieldName(), text, Field.Store.YES));
			if (tokens.length > 4)
				doc.add(new StoredField(FbIndexField.TYPES.fieldName(), tokens[4]));
			indexer.addDocument(doc);
			indexed++;

			if (indexed % 1000000 == 0)
				LogInfo.log("Number of lines: " + indexed);
		}
		reader.close();
		LogInfo.log("Indexed lines: " + indexed);

		indexer.close();
		LogInfo.log("Done");
		LogInfo.end_track("Indexing");
	}

	public static void main(final String[] args) throws IOException
	{
		final FbEntityIndexer fbni = new FbEntityIndexer(args[0], args[1], args[2]);
		fbni.index();
	}
}
