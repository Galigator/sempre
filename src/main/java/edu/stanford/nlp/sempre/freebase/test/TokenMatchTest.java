package edu.stanford.nlp.sempre.freebase.test;

import static org.testng.AssertJUnit.assertEquals;

import edu.stanford.nlp.sempre.freebase.lexicons.TokenLevelMatchFeatures;
import edu.stanford.nlp.stats.Counter;
import java.util.Arrays;
import org.testng.annotations.Test;

public class TokenMatchTest
{

	@Test
	public void tokenMatch()
	{
		final String[] text = new String[] { "what", "tv", "program", "have", "hugh", "laurie", "create" };
		final String[] pattern = new String[] { "program", "create" };
		final Counter<String> match = TokenLevelMatchFeatures.extractTokenMatchFeatures(Arrays.asList(text), Arrays.asList(pattern), true);
		assertEquals(0.5, match.getCount("prefix"), 0.00001);
		assertEquals(0.5, match.getCount("suffix"), 0.00001);
	}
}
