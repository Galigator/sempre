package edu.stanford.nlp.sempre.freebase.test;

import static org.testng.AssertJUnit.assertEquals;

import edu.stanford.nlp.sempre.freebase.Stemmer;
import org.testng.annotations.Test;

public class StemmerTest
{
	@Test
	public void simpleStem()
	{
		final Stemmer stemmer = new Stemmer();
		assertEquals("box", stemmer.stem("boxes"));
		assertEquals("creat", stemmer.stem("created"));
		assertEquals("citi", stemmer.stem("cities"));
	}
}
