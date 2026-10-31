package com.github.vbmacher.intellij.dbt.ide;

import com.intellij.lang.parameterInfo.ParameterInfoHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext;

/**
 * Java preserves the platform mock's raw generic method signature for Scala fixture callers.
 */
public final class DbtParameterInfoCapture extends MockCreateParameterInfoContext {
    public int anchor = -1;
    public PsiElement hinted;

    public DbtParameterInfoCapture(Editor editor, PsiFile file) {
        super(editor, file);
    }

    @Override
    public void showHint(PsiElement element, int offset, ParameterInfoHandler handler) {
        anchor = offset;
        hinted = element;
    }
}
