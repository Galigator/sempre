package edu.stanford.nlp.sempre.test;

import static org.testng.AssertJUnit.assertEquals;

import edu.stanford.nlp.sempre.Grammar;
import fig.basic.LogInfo;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Attempt to load all grammars to test for validity.
 *
 * @author Yushi Wang
 */

public class GrammarValidityTest
{
	private final String[] dataPaths = new String[] { "data/", "freebase/", "tables/", "regex/", "overnight/" };

	@Test(groups = { "grammar" })
	public void readGrammars()
	{
		try
		{
			final List<String> successes = new ArrayList<>(), failures = new ArrayList<>();
			for (final String dataPath : dataPaths)
				Files.walk(Paths.get(dataPath)).forEach(filePath ->
				{
					try
					{
						if (filePath.toString().toLowerCase().endsWith(".grammar"))
						{
							final Grammar test = new Grammar();
							LogInfo.logs("Reading grammar file: %s", filePath.toString());
							test.read(filePath.toString());
							LogInfo.logs("Finished reading", filePath.toString());
							successes.add(filePath.toString());
						}
					}
					catch (final Exception ex)
					{
						failures.add(filePath.toString());
					}
				});
			LogInfo.begin_track("Following grammar tests passed:");
			for (final String path : successes)
				LogInfo.logs("%s", path);
			LogInfo.end_track();
			LogInfo.begin_track("Following grammar tests failed:");
			for (final String path : failures)
				LogInfo.logs("%s", path);
			LogInfo.end_track();
			assertEquals(0, failures.size());
		}
		catch (final Exception ex)
		{
			LogInfo.logs(ex.toString());
		}
	}
}
