package `in`.kkkev.jjidea.vcs.merge

import com.intellij.testFramework.junit5.TestApplication
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("platform")
@TestApplication
class ResolutionContentTest {
    @Test
    fun `excludes existing edits before and after resolved conflict`() {
        val parent = "first\nkeep\n<<<<<<<\nours\n=======\ntheirs\n>>>>>>>\nkeep\nlast\n"
        val before = parent.replace("first", "user-first").replace("last", "user-last")
        val after = before.replace("<<<<<<<\nours\n=======\ntheirs\n>>>>>>>\n", "merged\n")

        isolateResolution(parent, before, after) shouldBe "first\nkeep\nmerged\nkeep\nlast\n"
    }

    @Test
    fun `maps resolution after existing insertion and deletion`() {
        val parent = "remove\nkeep\nkeep2\nconflict\nend\n"
        val before = "extra\nextra2\nkeep\nkeep2\nconflict\nend\n"
        val after = before.replace("conflict", "resolved")

        isolateResolution(parent, before, after) shouldBe "remove\nkeep\nkeep2\nresolved\nend\n"
    }

    @Test
    fun `does not carry existing inserted content into resolution insertion`() {
        val parent = "first\nkeep\nend\n"
        val before = "user-first\nkeep\nend\n"
        val after = "user-first\nkeep\nresolved\nend\n"

        isolateResolution(parent, before, after) shouldBe "first\nkeep\nresolved\nend\n"
    }

    @Test
    fun `isolates deletion and preserves no final newline`() {
        val parent = "first\nkeep\ndelete\nend"
        val before = "user-first\nkeep\ndelete\nend"
        val after = "user-first\nkeep\nend"

        isolateResolution(parent, before, after) shouldBe "first\nkeep\nend"
    }

    @Test
    fun `applies multiple resolutions without shifting subsequent ranges`() {
        val parent = "user\nkeep\nconflict1\nkeep2\nconflict2\nend\n"
        val before = parent.replace("user", "user-edit")
        val after = before.replace("conflict1", "merged\nextra").replace("conflict2", "merged2")

        isolateResolution(parent, before, after) shouldBe "user\nkeep\nmerged\nextra\nkeep2\nmerged2\nend\n"
    }

    @Test
    fun `preserves CRLF and trailing newline`() {
        val parent = "first\r\nkeep\r\nconflict\r\nend\r\n"
        val before = parent.replace("first", "user-first")
        val after = before.replace("conflict", "resolved")

        isolateResolution(parent, before, after) shouldBe "first\r\nkeep\r\nresolved\r\nend\r\n"
    }

    @Test
    fun `refuses merging over an existing user edit`() {
        isolateResolution("first\nconflict\n", "first\nuser-conflict\n", "first\nresolved\n") shouldBe null
    }

    @Test
    fun `refuses resolution touching an existing insertion boundary`() {
        isolateResolution("keep\nconflict\n", "keep\nuser\nconflict\n", "keep\nuser\nresolved\n") shouldBe null
    }

    @Test
    fun `refuses new insertion touching existing changed line`() {
        isolateResolution("first\nend\n", "user-first\nend\n", "user-first\nresolved\nend\n") shouldBe null
    }

    @Test
    fun `unchanged merge keeps parent and no preexisting edits keeps whole resolution`() {
        isolateResolution("parent", "user", "user") shouldBe "parent"
        isolateResolution("parent", "parent", "resolved") shouldBe "resolved"
    }
}
