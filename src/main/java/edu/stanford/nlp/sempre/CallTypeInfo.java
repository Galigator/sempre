package edu.stanford.nlp.sempre;

import java.util.List;

// Type information for each function in CallFormula.
public class CallTypeInfo
{
	public final String func;
	public final List<SemType> argTypes;
	public final SemType retType;

	public CallTypeInfo(final String func_, final List<SemType> argTypes_, final SemType retType_)
	{
		func = func_;
		argTypes = argTypes_;
		retType = retType_;
	}
}
