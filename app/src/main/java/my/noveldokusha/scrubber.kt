package my.noveldokusha

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode
import java.io.PrintWriter
import java.io.StringWriter
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.*

fun String.urlEncode(): String = URLEncoder.encode(this, "utf-8")

suspend fun Connection.getIO(): Document = withContext(Dispatchers.IO) { get() }
suspend fun Connection.postIO(): Document = withContext(Dispatchers.IO) { post() }
suspend fun Connection.executeIO(): Connection.Response = withContext(Dispatchers.IO) { execute() }
suspend fun String.urlEncodeAsync(): String = withContext(Dispatchers.IO) { this@urlEncodeAsync.urlEncode() }

fun Connection.addUserAgent(): Connection =
	this.userAgent("Mozilla/5.0 (X11; U; Linux i586; en-US; rv:1.7.3) Gecko/20040924 Epiphany/1.4.4 (Ubuntu)")

fun Connection.addHeaderRequest() = this.header("x-requested-with", "XMLHttpRequest")!!

sealed class Response<T>
{
	class Success<T>(val data: T) : Response<T>()
	class Error<T>(val message: String) : Response<T>()
}

object scrubber
{
	private val sourcesList: Set<source_interface> = source::class.nestedClasses.map { it.objectInstance as source_interface }.toSet()
	val sourcesListCatalog: Set<source_interface.catalog> by lazy { sourcesList.filterIsInstance<source_interface.catalog>().toSet() }
	val databasesList: Set<database_interface> = database::class.nestedClasses.map { it.objectInstance as database_interface }.toSet()
	
	fun getCompatibleSource(url: String): source_interface? = sourcesList.find { url.startsWith(it.baseUrl) }
	fun getCompatibleSourceCatalog(url: String): source_interface.catalog? = sourcesListCatalog.find { url.startsWith(it.baseUrl) }
	fun getCompatibleDatabase(url: String): database_interface? = databasesList.find { url.startsWith(it.baseUrl) }
	
	interface source_interface
	{
		val name: String
		val baseUrl: String
		
		// Transform current url to preferred url
		fun transformChapterUrl(url: String): String = url
		
		suspend fun getChapterText(doc: Document): String
		
		interface base : source_interface
		interface catalog : source_interface
		{
			val catalogUrl: String
			
			suspend fun getChapterList(doc: Document): List<ChapterMetadata>
			suspend fun getCatalogList(index: Int): Response<List<BookMetadata>>
			suspend fun getCatalogSearch(index: Int, input: String): Response<List<BookMetadata>>
		}
	}
	
	interface database_interface
	{
		val id: String
		val name: String
		val baseUrl: String
		
		val searchGenresCache get() = DataCache_DatabaseSearchGenres(id)
		
		suspend fun getSearchGenres(): Response<Map<String, String>>
		suspend fun getSearch(index: Int, input: String): Response<List<BookMetadata>>
		suspend fun getSearchAdvanced(
			index: Int,
			genresIncludedId: List<String>,
			genresExcludedId: List<String>
		): Response<List<BookMetadata>>
		
		data class BookAuthor(val name: String, val url: String?)
		data class BookData(
			val title: String,
			val description: String,
			val alternativeTitles: List<String>,
			val authors: List<BookAuthor>,
			val tags: List<String>,
			val genres: List<String>,
			val bookType: String,
			val relatedBooks: List<BookMetadata>,
			val similarRecommended: List<BookMetadata>
		)
		
		fun getBookData(doc: Document): BookData
	}
	
	fun getNodeTextTransversal(node: org.jsoup.nodes.Node): List<String>
	{
		if (node is TextNode)
		{
			val text = node.text().trim()
			return if (text.isEmpty()) listOf() else listOf(text)
		}
		return node.childNodes().flatMap { childNode -> getNodeTextTransversal(childNode) }
	}
	
	object source
	{
		/**
		 * Novel main page (chapter list) example:
		 * https://lightnovelstranslations.com/the-sage-summoned-to-another-world/
		 * Chapter url example:
		 * https://lightnovelstranslations.com/the-sage-summoned-to-another-world/the-sage-summoned-to-another-world-volume-1-chapter-1/
		 */
		object LightNovelsTranslations : source_interface.catalog
		{
			override val name = "Light Novel Translations"
			override val baseUrl = "https://lightnovelstranslations.com/"
			override val catalogUrl = "https://lightnovelstranslations.com/"
			
			override suspend fun getChapterText(doc: Document): String
			{
				return doc.selectFirst(".page, .type-page, .status-publish, .hentry").selectFirst(".entry-content").run {
					this.select("#textbox").remove()
					getNodeTextTransversal(this)
				}.joinToString("\n\n")
			}
			
			override suspend fun getChapterList(doc: Document): List<ChapterMetadata>
			{
				return doc
					.select(".su-spoiler-content")
					.select(".su-u-clearfix")
					.select(".su-u-trim")
					.select("a[href]")
					.map {
						
						val url = it.attr("href")
						val decoded_url = URLDecoder.decode(url, "UTF-8").removeSuffix("/")
						
						val title: String = Regex(""".+/(.+)$""").find(decoded_url)?.destructured?.run {
							
							val title = this.component1().replace("-", " ").capitalize(Locale.ROOT)
							
							Regex("""^(\w+) (\d+) (\S.*)$""").find(title)?.destructured?.let { m ->
								val (prefix, number, name) = m
								"""$prefix $number - ${name.capitalize(Locale.ROOT)}"""
							} ?: title
						} ?: "** Can't get chapter title :("
						
						ChapterMetadata(title = title, url = url)
					}
			}
			
			override suspend fun getCatalogList(index: Int): Response<List<BookMetadata>>
			{
				val page = index + 1
				if (page > 1)
					return Response.Success(listOf())
				
				return tryConnect {
					fetchDoc(catalogUrl)
						.selectFirst("#prime_nav")
						.children()
						.subList(1, 4)
						.flatMap { it.select("a") }
						.filter {
							val url = it.attr("href")
							val text = it.text()
							return@filter url != "#" &&
							              !url.endsWith("-illustrations/") &&
							              !url.endsWith("-illustration/") &&
							              !url.endsWith("-illustration-page/") &&
							              text != "Novel Illustrations" &&
							              text != "Novels Illustrations"
						}
						.map { BookMetadata(title = it.text(), url = it.attr("href")) }
						.let { Response.Success(it) }
				}
			}
			
			override suspend fun getCatalogSearch(index: Int, input: String): Response<List<BookMetadata>>
			{
				if (input.isBlank() || index > 0)
					return Response.Success(listOf())
				
				val url = "https://lightnovelstranslations.com/?order=DESC&orderby=relevance&s=${input.urlEncode()}"
				return tryConnect {
					fetchDoc(url)
						.selectFirst(".jetpack-search-filters-widget__filter-list")
						.select("a")
						.map {
							val (name) = Regex("""^.*category_name=(.*)$""").find(it.attr("href"))!!.destructured
							BookMetadata(title = it.text(), url = "https://lightnovelstranslations.com/${name}/")
						}.let { Response.Success(it) }
				}
			}
		}
		
		/**
		 * Novel main page (chapter list) example:
		 * https://www.readlightnovel.org/goat-of-all-ghouls-1
		 * Chapter url example:
		 * https://www.readlightnovel.org/goat-of-all-ghouls-1/chapter-1
		 */
		object ReadLightNovel : source_interface.catalog
		{
			override val name = "Read Light Novel"
			override val baseUrl = "https://www.readlightnovel.org"
			override val catalogUrl = "https://www.readlightnovel.org/novel-list"
			
			override suspend fun getChapterText(doc: Document): String
			{
				doc.selectFirst(".chapter-content3 > .desc").let {
					it.select("script").remove()
					it.select("a").remove()
					it.select(".ads-title").remove()
					it.select(".hidden").remove()
					return getNodeTextTransversal(it).joinToString("\n\n")
				}
			}
			
			override suspend fun getChapterList(doc: Document): List<ChapterMetadata>
			{
				return doc.select(".chapter-chs").select("a").map { ChapterMetadata(title = it.text(), url = it.attr("href")) }
			}
			
			val catalogIndex by lazy { ("ABCDEFGHIJKLMNOPQRSTUVWXYZ").split("") }
			
			override suspend fun getCatalogList(index: Int): Response<List<BookMetadata>>
			{
				val url = if (index == 0) catalogUrl
				else
				{
					val letter = catalogIndex.elementAtOrNull(index - 1) ?: return Response.Success(listOf())
					"$catalogUrl/$letter"
				}
				
				return tryConnect {
					fetchDoc(url)
						.selectFirst(".list-by-word-body")
						.child(0)
						.children()
						.map { it.selectFirst("a") }
						.map { BookMetadata(title = it.text(), url = it.attr("href")) }
						.let { Response.Success(it) }
				}
			}
			
			override suspend fun getCatalogSearch(index: Int, input: String): Response<List<BookMetadata>>
			{
				if (input.isBlank() || index > 0)
					return Response.Success(listOf())
				
				return tryConnect {
					Jsoup.connect("https://www.readlightnovel.org/search/autocomplete")
						.addUserAgent()
						.addHeaderRequest()
						.data("q", input)
						.postIO()
						.select("a")
						.map { BookMetadata(title = it.text(), url = it.attr("href")) }
						.let { Response.Success(it) }
				}
			}
		}
		
		/**
		 * Novel main page (chapter list) example:
		 * https://readnovelfull.com/i-was-a-sword-when-i-reincarnated.html
		 * Chapter url example:
		 * https://readnovelfull.com/i-was-a-sword-when-i-reincarnated.html
		 */
		object ReadNovelFull : source_interface.catalog
		{
			override val name = "Read Novel Full"
			override val baseUrl = "https://readnovelfull.com"
			override val catalogUrl = "https://readnovelfull.com/most-popular-novel"
			
			override suspend fun getChapterText(doc: Document): String
			{
				doc.selectFirst("#chr-content").let {
					return getNodeTextTransversal(it).joinToString("\n\n")
				}
			}
			
			override suspend fun getChapterList(doc: Document): List<ChapterMetadata>
			{
				val id = doc.selectFirst("#rating").attr("data-novel-id")
				return Jsoup.connect("https://readnovelfull.com/ajax/chapter-archive")
					.addUserAgent()
					.addHeaderRequest()
					.data("novelId", id)
					.getIO()
					.select("a")
					.map { ChapterMetadata(title = it.text(), url = baseUrl + it.attr("href")) }
			}
			
			override suspend fun getCatalogList(index: Int): Response<List<BookMetadata>>
			{
				val page = index + 1
				var url = catalogUrl
				if (page > 1) url += "?page=$page"
				return tryConnect {
					fetchDoc(url)
						.selectFirst("#list-page")
						.select(".row")
						.map { it.selectFirst("a") }
						.map { BookMetadata(title = it.text(), url = baseUrl + it.attr("href")) }
						.let { Response.Success(it) }
				}
			}
			
			override suspend fun getCatalogSearch(index: Int, input: String): Response<List<BookMetadata>>
			{
				if (input.isBlank() || index > 0)
					return Response.Success(listOf())
				
				return tryConnect {
					fetchDoc("https://readnovelfull.com/search?keyword=${input.urlEncodeAsync()}")
						.selectFirst(".col-novel-main, .archive")
						.select(".novel-title")
						.map { it.selectFirst("a") }
						.map { BookMetadata(title = it.text(), url = baseUrl + it.attr("href")) }
						.let { Response.Success(it) }
				}
			}
		}
		
		/**
		 * Novel main page (chapter list) example:
		 * https://www.divinedaolibrary.com/category/the-undead-king-of-the-palace-of-darkness/
		 * Chapter url example:
		 * https://www.divinedaolibrary.com/the-undead-king-of-the-palace-of-darkness-chapter-22-the-merciful-grim-reaper/
		 */
		object DivineDaoLibrary : source_interface.base
		{
			override val name = "Divine Dao Library"
			override val baseUrl = "https://www.divinedaolibrary.com/"
			//			override val catalogUrl = "https://www.centinni.com/novel/"
			
			override suspend fun getChapterText(doc: Document): String
			{
				return doc.selectFirst(".entry-content")
					.also { it.select("a").remove() }
					.let { getNodeTextTransversal(it).joinToString("\n\n") }
			}
		}
		
		/**
		 * Novel main page (chapter list) example:
		 * https://www.novelupdates.com/series/mushoku-tensei-old-dragons-tale/
		 * Chapter url example:
		 * (redirected url) Doesn't have chapters, assume it redirects to different website
		 */
		object NovelUpdates : source_interface.catalog
		{
			override val name = "Novel Updates"
			override val baseUrl = "https://www.novelupdates.com/"
			override val catalogUrl = "https://www.novelupdates.com/novelslisting/?sort=7&order=1&status=1"
			
			override suspend fun getChapterText(doc: Document): String
			{
				TODO("NOT SUPPOSED TO EVER BE CALLED")
			}
			
			override suspend fun getChapterList(doc: Document): List<ChapterMetadata>
			{
				return Jsoup.connect("https://www.novelupdates.com/wp-admin/admin-ajax.php")
					.addUserAgent()
					.addHeaderRequest()
					.data("action", "nd_getchapters")
					.data("mygrr", doc.selectFirst("#grr_groups").attr("value"))
					.data("mygroupfilter", "")
					.data("mypostid", doc.selectFirst("#mypostid").attr("value"))
					.postIO()
					.select("a")
					.asSequence()
					.filter { it.hasAttr("data-id") }
					.map {
						val title = it.selectFirst("span").attr("title")
						val url = "https:" + it.attr("href")
						ChapterMetadata(title = title, url = url)
					}.toList().reversed()
			}
			
			override suspend fun getCatalogList(index: Int): Response<List<BookMetadata>>
			{
				val page = index + 1
				var url = "$catalogUrl?st=1"
				if (page > 1) url += "&pg=$page"
				
				return tryConnect {
					fetchDoc(url)
						.select(".search_title")
						.map { it.selectFirst("a") }
						.map { BookMetadata(title = it.text(), url = it.attr("href")) }
						.let { Response.Success(it) }
				}
			}
			
			override suspend fun getCatalogSearch(index: Int, input: String): Response<List<BookMetadata>>
			{
				if (input.isBlank() || index > 0)
					return Response.Success(listOf())
				
				return tryConnect {
					Jsoup.connect("https://www.novelupdates.com/?s=${input.urlEncode()}")
						.addUserAgent()
						.getIO()
						.select(".search_body_nu")
						.select(".search_title")
						.select("a")
						.map { BookMetadata(title = it.text(), url = it.attr("href")) }
						.let { Response.Success(it) }
				}
			}
		}
		
		/**
		 * Novel main page (chapter list) example:
		 * Doesn't have main page
		 * Chapter url example: (redirected)
		 * https://www.reddit.com/r/mushokutensei/comments/g50ry7/translation_old_dragons_tale_chapter_1_dragon_and/
		 */
		object Reddit : source_interface.base
		{
			override val name = "Reddit"
			override val baseUrl = "https://www.reddit.com/"
			
			override fun transformChapterUrl(url: String): String
			{
				return url.replaceFirst(baseUrl, "https://old.reddit.com/")
			}
			
			override suspend fun getChapterText(doc: Document): String
			{
				return doc.selectFirst(".linklisting")
					.selectFirst(".usertext-body, .may-blank-within, .md-container")
					.let {
						it.select("table").remove()
						it.select("blockquote").remove()
						getNodeTextTransversal(it).joinToString("\n\n")
					}
			}
		}
		
		/**
		 * Novel main page (chapter list) example:
		 * Doesn't have main page
		 * Chapter url example: (redirected)
		 * https://rtd.moe/kumo-desu-ga/kumo-desu-ga-nani-ka-final-battle-%E2%91%A3/
		 */
		object RaisingTheDead : source_interface.base
		{
			override val name = "Raising The Dead"
			override val baseUrl = "https://rtd.moe/"
			
			override suspend fun getChapterText(doc: Document): String
			{
				return doc.selectFirst("#content")
					.let {
						it.select("div").remove()
						getNodeTextTransversal(it).joinToString("\n\n")
					}
			}
		}
		
		/**
		 * Novel main page (chapter list) example:
		 * Doesn't have main page
		 * Chapter url example: (redirected)
		 * https://rtd.moe/kumo-desu-ga/kumo-desu-ga-nani-ka-final-battle-%E2%91%A3/
		 */
		object Hoopla2017 : source_interface.base
		{
			override val name = "hoopla2017"
			override val baseUrl = "https://hoopla2017.wordpress.com/"
			
			override suspend fun getChapterText(doc: Document): String
			{
				val title = getNodeTextTransversal(doc.selectFirst(".entry-title"))
				val body = getNodeTextTransversal(doc.selectFirst(".entry-content"))
				
				return (title + body).joinToString("\n\n")
			}
		}
	}
	
	object database
	{
		/**
		 * Novel main page example:
		 * https://www.novelupdates.com/series/mushoku-tensei/
		 */
		object NovelUpdates : database_interface
		{
			override val id = "novel_updates"
			override val name = "Novel Updates"
			override val baseUrl = "https://www.novelupdates.com/"
			
			override suspend fun getSearchGenres(): Response<Map<String, String>>
			{
				return searchGenresCache.fetch {
					tryConnect {
						fetchDoc("https://www.novelupdates.com/series-finder/")
							.select(".genreme")
							.associate { it.text().trim() to it.attr("genreid")!! }
							.let { Response.Success(it) }
					}
				}
			}
			
			override suspend fun getSearch(index: Int, input: String): Response<List<BookMetadata>>
			{
				val page = index + 1
				val pagePath = if (page > 1) "page/$page/" else ""
				val url = "https://www.novelupdates.com/$pagePath?s=${input.urlEncode()}&post_type=seriesplans"
				
				return tryConnect("page: $page\nurl: $url") {
					fetchDoc(url)
						.select(".search_title")
						.map { it.selectFirst("a") }
						.map { BookMetadata(it.text(), it.attr("href")) }
						.let { Response.Success(it) }
				}
			}
			
			override suspend fun getSearchAdvanced(index: Int, genresIncludedId: List<String>, genresExcludedId: List<String>):
					Response<List<BookMetadata>>
			{
				val page = index + 1
				
				var url = "https://www.novelupdates.com/series-finder/?sf=1"
				if (genresIncludedId.isNotEmpty()) url += "&gi=${genresIncludedId.joinToString(",")}&mgi=and"
				if (genresExcludedId.isNotEmpty()) url += "&ge=${genresExcludedId.joinToString(",")}"
				url += "&sort=sdate&order=desc"
				if (page > 1) url += "&pg=$page"
				
				return tryConnect("page: $page\nurl: $url") {
					fetchDoc(url)
						.select(".search_title")
						.map { it.selectFirst("a") }
						.map { BookMetadata(it.text(), it.attr("href")) }
						.let { Response.Success(it) }
				}
			}
			
			override fun getBookData(doc: Document): database_interface.BookData
			{
				val relatedBooks = doc
					.select("h5")
					.find { it.hasClass("seriesother") && it.text() == "Related Series" }!!
					.nextElementSiblings().asSequence()
					.takeWhile { elem -> !elem.`is`("h5") }
					.filter { it.`is`("a") }
					.map { BookMetadata(it.text(), it.attr("href")) }.toList()
				
				val similarRecommended = doc
					.select("h5")
					.find { it.hasClass("seriesother") && it.text() == "Recommendations" }!!
					.nextElementSiblings().asSequence()
					.takeWhile { elem -> !elem.`is`("h5") }
					.filter { it.`is`("a") }
					.map { BookMetadata(it.text(), it.attr("href")) }.toList()
				
				val authors = doc
					.selectFirst("#showauthors")
					.select("a")
					.map { database_interface.BookAuthor(name = it.text(), url = it.attr("href")) }
				
				return database_interface.BookData(
					title = doc.selectFirst(".seriestitlenu").text(),
					description = getNodeTextTransversal(doc.selectFirst("#editdescription")).joinToString("\n\n"),
					alternativeTitles = getNodeTextTransversal(doc.selectFirst("#editassociated")),
					relatedBooks = relatedBooks,
					similarRecommended = similarRecommended,
					bookType = doc.selectFirst(".genre, .type").text(),
					genres = doc.selectFirst("#seriesgenre").select("a").map { it.text() },
					tags = doc.selectFirst("#showtags").select("a").map { it.text() },
					authors = authors
				)
			}
		}
		
		/**
		 * Novel main page example:
		 * https://www.novelupdates.com/series/mushoku-tensei/
		 */
		object BakaUpdates : database_interface
		{
			override val id = "baka_updates"
			override val name = "Baka-Updates"
			override val baseUrl = "https://www.mangaupdates.com/"
			
			fun String.removeNovelTag() = this.removeSuffix("(Novel)").trim()
			
			override suspend fun getSearchGenres(): Response<Map<String, String>>
			{
				return searchGenresCache.fetch {
					tryConnect {
						fetchDoc("https://www.mangaupdates.com/series.html?act=genresearch")
							.select(".p-1.col-6.text")
							.map { it.text().trim() }
							.associateWith { it.replace(" ", "+") }
							.let { Response.Success(it) }
					}
				}
			}
			
			override suspend fun getSearch(index: Int, input: String): Response<List<BookMetadata>>
			{
				val page = index + 1
				val settings = mutableListOf<String>().apply {
					if (page > 1) add("page=$page")
					add("display=list")
					add("perpage=50")
					add("type=novel")
					add("search=${input.urlEncode()}")
				}
				val url = "https://www.mangaupdates.com/series.html?" + settings.joinToString("&")
				
				return tryConnect("page: $page\nurl: $url") {
					fetchDoc(url)
						.select("div.col-6.py-1.py-md-0.text")
						.map { it.selectFirst("a[href]") }
						.map { BookMetadata(it.text().removeNovelTag(), it.attr("href")) }
						.let { Response.Success(it) }
				}
			}
			
			override suspend fun getSearchAdvanced(index: Int, genresIncludedId: List<String>, genresExcludedId: List<String>):
					Response<List<BookMetadata>>
			{
				val page = index + 1
				val settings = mutableListOf<String>().apply {
					if (page > 1) add("page=$page")
					add("display=list")
					if (genresIncludedId.isNotEmpty()) add("genre=" + genresIncludedId.joinToString("_"))
					if (genresExcludedId.isNotEmpty()) add("exclude_genre=" + genresExcludedId.joinToString("_"))
					add("type=novel")
					add("perpage=50")
				}
				val url = "https://www.mangaupdates.com/series.html?" + settings.joinToString("&")
				
				return tryConnect("page: $page\nurl: $url") {
					fetchDoc(url)
						.select("div.col-6.py-1.py-md-0.text")
						.map { it.selectFirst("a[href]") }
						.map { BookMetadata(it.text().removeNovelTag(), it.attr("href")) }
						.let { Response.Success(it) }
				}
			}
			
			override fun getBookData(doc: Document): database_interface.BookData
			{
				fun entry(header: String) = doc.selectFirst("div.sCat > b:containsOwn($header)").parent().nextElementSibling()
				
				val relatedBooks = entry("Category Recommendations")
					.select("a[href]")
					.map { BookMetadata(it.text().removeNovelTag(), "https://www.mangaupdates.com/" + it.attr("href")) }
					.toList()
				
				val similarRecommended = entry("Recommendations")
					.select("a[href]")
					.map { BookMetadata(it.text().removeNovelTag(), "https://www.mangaupdates.com/" + it.attr("href")) }
					.toList()
				
				val authors = entry("Author\\(s\\)")
					.select("a")
					.map { database_interface.BookAuthor(name = it.text(), url = it.attr("href")) }
				
				val description = entry("Description").let {
					it.selectFirst("[id=div_desc_more]") ?: it.selectFirst("div")
				}.also { it.select("a").remove() }
					.let { getNodeTextTransversal(it) }
					.joinToString("\n\n")
				
				val tags = entry("Categories")
					.select("li > a")
					.map { it.text() }
				
				return database_interface.BookData(
					title = doc.selectFirst(".releasestitle.tabletitle").text().removeNovelTag(),
					description = description,
					alternativeTitles = getNodeTextTransversal(entry("Associated Names")),
					relatedBooks = relatedBooks,
					similarRecommended = similarRecommended,
					bookType = entry("Type").text(),
					genres = entry("Genre").select("a").map { it.text() },
					tags = tags,
					authors = authors
				)
			}
		}
	}
}

suspend fun downloadChapter(chapterUrl: String): Response<String>
{
	return tryConnect {
		val con = Jsoup.connect(chapterUrl)
			.addUserAgent()
			.followRedirects(true)
			.timeout(2 * 60 * 1000)
			.referrer("http://www.google.com")
			.header("Content-Language", "en-US")
			.executeIO()
		
		val realUrl = con.url().toString()
		val source = scrubber.getCompatibleSource(realUrl) ?: return@tryConnect {
			val errorMessage = """
				Unable to load chapter from url:
				$chapterUrl
				
				Redirect url:
				$realUrl
				
				Source not supported
			""".trimIndent()
			Response.Error<String>(errorMessage)
		}()
		
		val doc = fetchDoc(source.transformChapterUrl(realUrl))
		val body = source.getChapterText(doc)
		Response.Success(body)
	}
}

suspend fun fetchChaptersList(bookUrl: String, tryCache: Boolean = true): Response<List<bookstore.Chapter>>
{
	if (tryCache) bookstore.bookChapter.chapters(bookUrl).let {
		if (it.isNotEmpty()) return Response.Success(it)
	}
	
	// Return if can't find compatible scrubber for url
	val scrap = scrubber.getCompatibleSourceCatalog(bookUrl) ?: return Response.Error(
		"Incompatible source\n\nCan't find compatible source for:\n$bookUrl"
	)
	
	return tryConnect {
		val doc = fetchDoc(bookUrl)
		scrap.getChapterList(doc)
			.map { bookstore.Chapter(title = it.title, url = it.url, bookUrl = bookUrl) }
			.let {
				bookstore.bookChapter.insert(it)
				Response.Success(bookstore.bookChapter.chapters(bookUrl))
			}
	}
}

suspend fun <T> tryConnect(extraErrorInfo: String = "", call: suspend () -> Response<T>): Response<T> = try
{
	call()
}
catch (e: SocketTimeoutException)
{
	Response.Error("Timeout error.\n\n${if (extraErrorInfo.isEmpty()) "" else "Info:\n$extraErrorInfo\n\n"}Message:\n${e.message}")
}
catch (e: Exception)
{
	val stacktrace = StringWriter().apply { e.printStackTrace(PrintWriter(this)) }
	Response.Error("Unknown error.\n\n${if (extraErrorInfo.isEmpty()) "" else "Info:\n$extraErrorInfo\n\n"}Message:\n${e.message}\n\nStacktrace:\n$stacktrace")
}

suspend fun fetchDoc(url: String, timeoutMilliseconds: Int = 2 * 60 * 1000): Document
{
	return Jsoup.connect(url)
		.timeout(timeoutMilliseconds)
		.addUserAgent()
		.referrer("http://www.google.com")
		.header("Content-Language", "en-US")
		.header("Accept", "text/html")
		.header("Accept-Encoding", "gzip,deflate")
		.getIO()
}

class BooksFetchIterator(
	private val coroutineScope: CoroutineScope,
	private var fn: (suspend (index: Int) -> Response<List<BookMetadata>>)
)
{
	enum class STATE
	{ IDLE, LOADING, CONSUMED }
	
	private var state = STATE.IDLE
	private var booksCount: Int = 0
	private var index = 0
	private var job: Job? = null
	
	val onSuccess = MutableLiveData<Response.Success<List<BookMetadata>>>()
	val onCompleted = MutableLiveData<Unit>()
	val onCompletedEmpty = MutableLiveData<Unit>()
	val onError = MutableLiveData<Response.Error<List<BookMetadata>>>()
	val onFetching = MutableLiveData<Boolean>()
	val onReset = MutableLiveData<Unit>()
	
	fun setFunction(fn: (suspend (index: Int) -> Response<List<BookMetadata>>))
	{
		this.fn = fn
	}
	
	fun reset()
	{
		state = STATE.IDLE
		booksCount = 0
		index = 0
		job?.cancel()
		onReset.value = Unit
	}
	
	fun removeAllObservers(owner: LifecycleOwner)
	{
		listOf(onSuccess, onCompleted, onCompletedEmpty, onError, onFetching).forEach {
			it.removeObservers(owner)
		}
	}
	
	fun fetchTrigger(trigger: () -> Boolean)
	{
		if (state == STATE.IDLE && trigger())
			fetchNext()
	}
	
	fun fetchNext()
	{
		if (state != STATE.IDLE) return
		state = STATE.LOADING
		
		job = coroutineScope.launch(Dispatchers.Main) {
			onFetching.value = true
			val res = withContext(Dispatchers.IO) { fn(index) }
			onFetching.value = false
			if (!isActive) return@launch
			when (res)
			{
				is Response.Success ->
				{
					if (res.data.isEmpty())
					{
						state = STATE.CONSUMED
						if (booksCount == 0)
							onCompletedEmpty.value = Unit
						else
							onCompleted.value = Unit
					}
					else
					{
						state = STATE.IDLE
						booksCount += res.data.size
						onSuccess.value = res
					}
				}
				is Response.Error ->
				{
					state = STATE.CONSUMED
					onError.value = res
					if (booksCount == 0)
						onCompletedEmpty.value = Unit
					else
						onCompleted.value = Unit
				}
			}
			index += 1
		}
	}
}
