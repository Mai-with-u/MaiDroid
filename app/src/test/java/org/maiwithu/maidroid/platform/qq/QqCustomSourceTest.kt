package org.maiwithu.maidroid.platform.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QqCustomSourceTest {
    @Test
    fun `parses github repository url variants`() {
        val plain = parseQqCustomSource(QqClient.NapCat, " https://github.com/owner/NapCatQQ/ ")
        val repoPlain = (plain as QqSourceParseResult.Valid).source as QqCustomSource.Repository
        assertEquals("https://github.com/owner/NapCatQQ", repoPlain.repoBaseUrl)
        assertNull(repoPlain.pinnedTag)
        assertEquals("owner/NapCatQQ", repoPlain.gitHubRepoSlug)

        val gitSuffix = parseQqCustomSource(QqClient.SnowLuma, "https://github.com/owner/SnowLuma.git")
        val repoGit = (gitSuffix as QqSourceParseResult.Valid).source as QqCustomSource.Repository
        assertEquals("https://github.com/owner/SnowLuma", repoGit.repoBaseUrl)
        assertEquals("owner/SnowLuma", repoGit.gitHubRepoSlug)

        val withReleases = parseQqCustomSource(QqClient.NapCat, "https://www.github.com/owner/NapCatQQ/releases")
        val repoReleases = (withReleases as QqSourceParseResult.Valid).source as QqCustomSource.Repository
        assertEquals("https://github.com/owner/NapCatQQ", repoReleases.repoBaseUrl)
        assertNull(repoReleases.pinnedTag)
        assertEquals("owner/NapCatQQ", repoReleases.gitHubRepoSlug)

        val withTree = parseQqCustomSource(QqClient.SnowLuma, "https://github.com/owner/SnowLuma/tree/main")
        val repoTree = (withTree as QqSourceParseResult.Valid).source as QqCustomSource.Repository
        assertEquals("https://github.com/owner/SnowLuma", repoTree.repoBaseUrl)
        assertNull(repoTree.pinnedTag)
    }

    @Test
    fun `extracts pinned tag from releases tag url`() {
        val napcat = parseQqCustomSource(QqClient.NapCat, "https://github.com/owner/NapCatQQ/releases/tag/v4.18.42")
        val repo = (napcat as QqSourceParseResult.Valid).source as QqCustomSource.Repository
        assertEquals("https://github.com/owner/NapCatQQ", repo.repoBaseUrl)
        assertEquals("v4.18.42", repo.pinnedTag)
        assertEquals("owner/NapCatQQ", repo.gitHubRepoSlug)

        val nestedTag = parseQqCustomSource(QqClient.NapCat, "https://github.com/owner/NapCatQQ/releases/tag/archive/v4.18.42")
        val repoNested = (nestedTag as QqSourceParseResult.Valid).source as QqCustomSource.Repository
        assertEquals("archive/v4.18.42", repoNested.pinnedTag)
    }

    @Test
    fun `supports third-party git hosts and direct archive links`() {
        val gitCode = parseQqCustomSource(QqClient.SnowLuma, "https://gitcode.com/owner/SnowLuma.git")
        val repoGitCode = (gitCode as QqSourceParseResult.Valid).source as QqCustomSource.Repository
        assertEquals("https://gitcode.com/owner/SnowLuma", repoGitCode.repoBaseUrl)
        assertNull(repoGitCode.gitHubRepoSlug)

        val napCatZip = parseQqCustomSource(QqClient.NapCat, "https://example.com/files/NapCat.Shell.zip?token=1")
        assertTrue((napCatZip as QqSourceParseResult.Valid).source is QqCustomSource.DirectArchive)

        val snowLumaTarGz = parseQqCustomSource(QqClient.SnowLuma, "https://example.com/SnowLuma-v2.4.2-linux-arm64.tar.gz")
        assertTrue((snowLumaTarGz as QqSourceParseResult.Valid).source is QqCustomSource.DirectArchive)

        val snowLumaTgz = parseQqCustomSource(QqClient.SnowLuma, "https://example.com/snowluma.tgz")
        assertTrue((snowLumaTgz as QqSourceParseResult.Valid).source is QqCustomSource.DirectArchive)

        // 大写协议头与 www.github.com 应归一化为小写 https://github.com/...，确保镜像候选生成器可匹配
        val normalizedArchive = parseQqCustomSource(
            QqClient.NapCat,
            "HTTPS://www.github.com/owner/NapCatQQ/releases/download/v1.0/NapCat.Shell.zip?token=a%20b"
        ) as QqSourceParseResult.Valid
        assertEquals(
            "https://github.com/owner/NapCatQQ/releases/download/v1.0/NapCat.Shell.zip?token=a%20b",
            normalizedArchive.source.rawUrl
        )
    }

    @Test
    fun `rejects mismatched archive extensions and malformed urls`() {
        assertTrue(parseQqCustomSource(QqClient.NapCat, "") is QqSourceParseResult.Invalid)
        assertTrue(parseQqCustomSource(QqClient.NapCat, "ftp://example.com/NapCat.Shell.zip") is QqSourceParseResult.Invalid)
        assertTrue(parseQqCustomSource(QqClient.NapCat, "https://github.com/owner") is QqSourceParseResult.Invalid)
        assertTrue(parseQqCustomSource(QqClient.NapCat, "https://user:pass@github.com/owner/NapCatQQ") is QqSourceParseResult.Invalid)
        assertTrue(parseQqCustomSource(QqClient.NapCat, "https://github.com/owner/NapCatQQ extra") is QqSourceParseResult.Invalid)
        assertTrue(parseQqCustomSource(QqClient.NapCat, "https://example.com/SnowLuma.tar.gz") is QqSourceParseResult.Invalid)
        assertTrue(parseQqCustomSource(QqClient.SnowLuma, "https://example.com/NapCat.Shell.zip") is QqSourceParseResult.Invalid)
        assertTrue(
            parseQqCustomSource(QqClient.NapCat, "https://github.com/owner/NapCatQQ/releases/download/v1.0/readme.txt")
                is QqSourceParseResult.Invalid
        )
    }

    @Test
    fun `snowluma doc url resolves only for repository sources`() {
        assertEquals(
            "https://github.com/owner/SnowLuma/blob/HEAD/EULA.md",
            snowLumaDocUrl("https://github.com/owner/SnowLuma", "EULA.md")
        )
        assertEquals(
            "https://github.com/owner/SnowLuma/blob/v2.4.2/PRIVACY.md",
            snowLumaDocUrl("https://github.com/owner/SnowLuma/releases/tag/v2.4.2", "PRIVACY.md")
        )
        assertNull(snowLumaDocUrl("https://example.com/SnowLuma.tar.gz", "EULA.md"))
        assertNull(snowLumaDocUrl("", "EULA.md"))
    }
}
