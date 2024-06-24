@file:Suppress("FunctionName")

package my.noveldokusha.tooling.local_database.migrations

import androidx.sqlite.db.SupportSQLiteDatabase

internal fun MigrationsList.readLightNovelDomainChange_1_today(
    it: SupportSQLiteDatabase
) {
    // readlightnovel source changed its domain to "today"
    val old1 = "www.readlightnovel.org"
    val old2 = "www.readlightnovel.me"
    val new = "www.readlightnovel.today"
    fun replace(columnName: String) =
        """SET $columnName = REPLACE($columnName, REPLACE($columnName, "$old1", "$new"), "$old2", "$new")"""

    fun like(columnName: String) =
        """($columnName LIKE "%$old1%" OR $columnName LIKE "%$old2%")"""
    it.execSQL(
        """
            UPDATE Book
                ${replace("url")},
                ${replace("coverImageUrl")},
            WHERE
                ${like("chapterUrl")};
        """.trimIndent()
    )
    it.execSQL(
        """
            UPDATE Chapter
                ${replace("url")},
                ${replace("bookUrl")},
            WHERE
                ${like("bookUrl")};
        """.trimIndent()
    )
    it.execSQL(
        """
            UPDATE ChapterBody
                ${replace("url")},
            WHERE
                ${like("url")};
        """.trimIndent()
    )
}

internal fun MigrationsList.readLightNovelDomainChange_2_meme(
    it: SupportSQLiteDatabase,
) = this.readLightNovelDomainChange(
    it,
    oldDomain = "today",
    newDomain = "meme",
)

private fun MigrationsList.readLightNovelDomainChange(
    it: SupportSQLiteDatabase,
    oldDomain: String,
    newDomain: String,
) {
    // readlightnovel source changed its domain to "newDomain"
    fun replace(columnName: String) =
        """SET $columnName = REPLACE($columnName, "$oldDomain", "$newDomain")"""

    fun like(columnName: String) =
        """($columnName LIKE "%$oldDomain%")"""
    it.execSQL(
        """
            UPDATE Book
                ${replace("url")},
                ${replace("coverImageUrl")},
            WHERE
                ${like("chapterUrl")};
        """.trimIndent()
    )
    it.execSQL(
        """
            UPDATE Chapter
                ${replace("url")},
                ${replace("bookUrl")},
            WHERE
                ${like("bookUrl")};
        """.trimIndent()
    )
    it.execSQL(
        """
            UPDATE ChapterBody
                ${replace("url")},
            WHERE
                ${like("url")};
        """.trimIndent()
    )
}