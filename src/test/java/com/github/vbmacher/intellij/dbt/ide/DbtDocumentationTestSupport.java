package com.github.vbmacher.intellij.dbt.ide;

import com.intellij.model.Pointer;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.documentation.impl.ImplKt;

/**
 * Bridge Scala 2 tests to the platform's Kotlin documentation result type.
 */
public final class DbtDocumentationTestSupport {
    public static String html(Pointer<? extends DocumentationTarget> pointer) {
        return ImplKt.computeDocumentationBlocking(pointer).getHtml();
    }
}
