package com.roifewu.orgenda.ui;

import io.noties.prism4j.Prism4j;
import io.noties.prism4j.annotations.PrismBundle;

@PrismBundle(includeAll = true, grammarLocatorClassName = ".CodeGrammarLocator")
public final class CodeGrammars {
    public static final Prism4j PARSER = new Prism4j(new CodeGrammarLocator());
    private CodeGrammars() {}
}
