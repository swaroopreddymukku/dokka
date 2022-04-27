package org.jetbrains.dokka.base.transformers.pages.sourcelinks

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import org.jetbrains.dokka.DokkaConfiguration
import org.jetbrains.dokka.DokkaConfiguration.DokkaSourceSet
import org.jetbrains.dokka.analysis.DescriptorDocumentableSource
import org.jetbrains.dokka.analysis.PsiDocumentableSource
import org.jetbrains.dokka.base.DokkaBase
import org.jetbrains.dokka.base.translators.documentables.PageContentBuilder
import org.jetbrains.dokka.links.DRI
import org.jetbrains.dokka.model.*
import org.jetbrains.dokka.pages.*
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.plugability.plugin
import org.jetbrains.dokka.plugability.querySingle
import org.jetbrains.dokka.transformers.pages.PageTransformer
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithSource
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.ifNotEmpty
import java.io.File

class SourceLinksTransformer(val context: DokkaContext) : PageTransformer {

    private val builder : PageContentBuilder =  PageContentBuilder(
        context.plugin<DokkaBase>().querySingle { commentsToContentConverter },
        context.plugin<DokkaBase>().querySingle { signatureProvider },
        context.logger
    )

    override fun invoke(input: RootPageNode) =
        input.transformContentPagesTree { node ->
            when (node) {
                is WithDocumentables -> {
                    val sources = node.documentables.filterIsInstance<WithSources>()
                        .associate { (it as Documentable).dri to resolveSources(it) }
                    if (sources.isNotEmpty())
                        node.modified(content = transformContent(node.content, sources))
                    else
                        node
                }
                else -> node
            }
        }

    private fun getSourceLinks() = context.configuration.sourceSets
        .flatMap { it.sourceLinks.map { sl -> SourceLink(sl, it) } }

    private fun resolveSources(documentable: WithSources) = documentable.sources
        .mapNotNull { entry ->
            getSourceLinks().find { File(entry.value.path).startsWith(it.path) && it.sourceSetData == entry.key }?.let {
                    entry.key to
                    entry.value.toLink(it)
            }
        }

    private fun DocumentableSource.toLink(sourceLink: SourceLink): String {
        val sourcePath = File(this.path).canonicalPath.replace("\\", "/")
        val sourceLinkPath = File(sourceLink.path).canonicalPath.replace("\\", "/")

        val lineNumber = when (this) {
            is DescriptorDocumentableSource -> this.descriptor
                .cast<DeclarationDescriptorWithSource>()
                .source.getPsi()
                ?.lineNumber()
            is PsiDocumentableSource -> this.psi.lineNumber()
            else -> null
        }
        return sourceLink.url +
                sourcePath.split(sourceLinkPath)[1] +
                sourceLink.lineSuffix +
                "${lineNumber ?: 1}"
    }
    
    private fun PsiElement.lineNumber(): Int? {
        val doc = PsiDocumentManager.getInstance(project).getDocument(containingFile)
        // IJ uses 0-based line-numbers; external source browsers use 1-based
        return doc?.getLineNumber(textRange.startOffset)?.plus(1)
    }

    private fun ContentNode.signatureGroupOrNull() =
        (this as? ContentGroup)?.takeIf { it.dci.kind == ContentKind.Symbol }

    private fun transformContent(
        contentNode: ContentNode, sources: Map<DRI, List<Pair<DokkaSourceSet, String>>>
    ): ContentNode =
        contentNode.signatureGroupOrNull()?.let { cg ->
            sources[cg.dci.dri.singleOrNull()]?.let { sourceLinks ->
                sourceLinks.filter { it.first.sourceSetID in cg.sourceSets.sourceSetIDs }.ifNotEmpty {
                    cg.copy(children = cg.children + sourceLinks.map {
                        buildContentLink(
                            cg.dci.dri.first(),
                            it.first,
                            it.second
                        )
                    })
                }
            }
        } ?: when (contentNode) {
            is ContentComposite -> contentNode.transformChildren { transformContent(it, sources) }
            else -> contentNode
        }

    private fun buildContentLink(dri: DRI, sourceSet: DokkaSourceSet, link: String) = builder.contentFor(
        dri,
        setOf(sourceSet),
        ContentKind.Source,
        setOf(TextStyle.RightAligned)
    ) {
        text("(")
        link("source", link)
        text(")")
    }
}

data class SourceLink(val path: String, val url: String, val lineSuffix: String?, val sourceSetData: DokkaSourceSet) {
    constructor(sourceLinkDefinition: DokkaConfiguration.SourceLinkDefinition, sourceSetData: DokkaSourceSet) : this(
        sourceLinkDefinition.localDirectory,
        sourceLinkDefinition.remoteUrl.toExternalForm(),
        sourceLinkDefinition.remoteLineSuffix,
        sourceSetData
    )
}