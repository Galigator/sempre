package edu.stanford.nlp.sempre.freebase.test;

import static org.testng.AssertJUnit.assertEquals;

import edu.stanford.nlp.sempre.freebase.lexicons.normalizers.PrepDropNormalizer;
import org.testng.annotations.Test;

/**
 * Simple test for normalization (exercise)
 * 
 * @author jonathan
 */

public class PrepDropNormalizerTest
{

	@Test
	public void normalization()
	{
		final PrepDropNormalizer normalizer = new PrepDropNormalizer();
		assertEquals("interested", normalizer.normalize("interested in"));
		assertEquals("interested", normalizer.normalize("interested at"));
		assertEquals("blow up", normalizer.normalize("blow up in"));
		assertEquals("blow up the", normalizer.normalize("blow up the to"));
	}
}
