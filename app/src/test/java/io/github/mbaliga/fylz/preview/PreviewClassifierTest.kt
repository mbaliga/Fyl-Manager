package io.github.mbaliga.fylz.preview

import io.github.mbaliga.fylz.model.PreviewKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewClassifierTest {
    @Test
    fun recognizesAgentMarkdownFiles() {
        assertEquals(PreviewKind.MARKDOWN, PreviewClassifier.classify("CLAUDE.md", "text/plain"))
        assertEquals(PreviewKind.MARKDOWN, PreviewClassifier.classify("AGENTS.md", "text/markdown"))
    }

    @Test
    fun recognizesStructuredTextAndArchives() {
        assertEquals(PreviewKind.TEXT, PreviewClassifier.classify("run.jsonl", "application/octet-stream"))
        assertEquals(PreviewKind.TEXT, PreviewClassifier.classify("changes.patch", "application/octet-stream"))
        assertEquals(PreviewKind.ARCHIVE, PreviewClassifier.classify("bundle.7z", "application/octet-stream"))
    }

    @Test
    fun recognizesExtensionlessAgentInstructionFilesAsMarkdown() {
        assertEquals(PreviewKind.MARKDOWN, PreviewClassifier.classify("AGENTS", "text/plain"))
        assertEquals(PreviewKind.MARKDOWN, PreviewClassifier.classify("README", "text/plain"))
    }

    @Test
    fun recognizesCommonAgentAndDeveloperArtifactsFromNames() {
        assertEquals(PreviewKind.TEXT, PreviewClassifier.classify("Dockerfile", "application/octet-stream"))
        assertEquals(PreviewKind.TEXT, PreviewClassifier.classify("notebook.ipynb", "application/octet-stream"))
        assertEquals(PreviewKind.TEXT, PreviewClassifier.classify(".env.local", "application/octet-stream"))
        assertEquals(PreviewKind.TEXT, PreviewClassifier.classify("diagram.svg", "image/svg+xml"))
    }

    @Test
    fun archiveExtensionWinsOverAnIncorrectGenericTextMime() {
        assertEquals(PreviewKind.ARCHIVE, PreviewClassifier.classify("bundle.zip", "text/plain"))
    }
}
