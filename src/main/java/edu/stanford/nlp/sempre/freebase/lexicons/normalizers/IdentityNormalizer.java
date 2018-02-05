package edu.stanford.nlp.sempre.freebase.lexicons.normalizers;

public class IdentityNormalizer implements EntryNormalizer
{

	@Override
	public String normalize(final String str)
	{
		return str;
	}

}
