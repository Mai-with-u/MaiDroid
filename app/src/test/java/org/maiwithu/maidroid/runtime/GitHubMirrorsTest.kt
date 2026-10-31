package org.maiwithu.maidroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubMirrorsTest {
    @Test
    fun `github urls can go through every mirror and direct`() {
        val url = "https://github.com/Mai-with-u/MaiDroid/releases/download/v0.1.2/MaiDroid_v0.1.2.apk"
        val candidates = GitHubMirrors.candidatesFor(url)

        assertEquals(GitHubMirrors.ALL.size, candidates.size)
        assertEquals(1, candidates.count { it.isDirect })
        assertEquals(GitHubMirrors.DIRECT_NAME, candidates.single { it.isDirect }.name)
        assertTrue(candidates.any { it.name == "ghfast.top" })
        assertEquals("https://ghfast.top/$url", candidates.single { it.name == "ghfast.top" }.url(url))
        assertEquals(url, candidates.single { it.isDirect }.url(url))
    }

    @Test
    fun `api and raw hosts are mirrored but other hosts are direct only`() {
        assertEquals(
            GitHubMirrors.ALL.size,
            GitHubMirrors.candidatesFor("https://api.github.com/repos/Mai-with-u/MaiDroid/releases/latest").size
        )
        assertEquals(GitHubMirrors.ALL.size, GitHubMirrors.candidatesFor("https://raw.githubusercontent.com/a/b/c").size)
        assertEquals(1, GitHubMirrors.candidatesFor("http://127.0.0.1:8000/releases/latest").size)
        assertEquals(1, GitHubMirrors.candidatesFor("not a url").size)
    }

    @Test
    fun `container shell functions list the same mirrors`() {
        val shell = GitHubMirrors.shellFunctions()

        assertTrue(shell.contains("'ghfast.top|https://ghfast.top'"))
        assertTrue(shell.contains("'${GitHubMirrors.DIRECT_NAME}|'"))
    }
}
