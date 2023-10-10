package eu.kanade.tachiyomi.animeextension.ru.anilibriatv.filter

import android.util.Log
import eu.kanade.tachiyomi.animeextension.ru.anilibriatv.dto.TeamFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.api.get

class AniLibriaTVApiV3Filter(
    val baseUrl: String,
    val client: OkHttpClient,
    val apiheaders: Headers,
) {
    // ============================== Base ==============================

    private val json = Json { ignoreUnknownKeys = true }

    open class TriStateFilterList(name: String, val vals: Array<String>) :
        AnimeFilter.Group<TriState>(name, vals.map(::TriStateVal))
    private class TriStateVal(name: String) : TriState(name)

    open class CheckBoxFilterList(name: String, val pairs: Array<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { CheckBoxVal(it.first, false) })
    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    fun getFilterList() =
        AnimeFilterList(
            AnimeFilter.Header("Базовый поиск"),
            OrderList(orderNames),
            YearsFilter(),
            SeasonFilter(),
            GenresFilter(),
            StatusFilter(),
            AnimeFilter.Separator(),
            AnimeFilter.Header("Продвинутый поиск"),
            TitleTypesFilter(),
            VoiceActorsFilter(),
            TranslatorsFilter(),
            EditorsFilter(),
            DecoratorsFilter(),
            TimingFilter(),
        )

    // ============================== Title Name ==============================

    private inner class TitleFilter : AnimeFilter.Text("Название", "")

    // ============================== Years ==============================

    private inner class YearsFilter : CheckBoxFilterList("Год", yearsList)
    private val yearsList = getYearsFilter()

    private fun getYearsFilter(): Array<Pair<String, String>> {
        val res =
            client.newCall(GET("$baseUrl/years", apiheaders))
                .execute()
                .use { it.body.string() }
                .let { json.decodeFromString<List<Int>>(it) }
                .map { Pair(it.toString(), it.toString()) }
                .reversed()

        Log.d("getYearsFilter", res.toString())
        return res.toTypedArray()
    }

    // ============================== Genres ==============================

    private inner class GenresFilter : CheckBoxFilterList("Жанр", genresList)
    private val genresList = getGenresFilter()

    private fun getGenresFilter(): Array<Pair<String, String>> {
        val res =
            client.newCall(GET("$baseUrl/genres", apiheaders))
                .execute()
                .use { it.body.string() }
                .let { json.decodeFromString<List<String>>(it) }
                .map { Pair(it.toString().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }, it.toString()) }
                .reversed()

        Log.d("getGenresFilter", res.toString())
        return res.toTypedArray()
    }

    // ============================== Команда ==============================

    private inner class VoiceActorsFilter : CheckBoxFilterList("Озвучка", teamList.voice.map { Pair(it, it) }.toTypedArray())
    private inner class TranslatorsFilter : CheckBoxFilterList("Перевод", teamList.translator.map { Pair(it, it) }.toTypedArray())
    private inner class EditorsFilter : CheckBoxFilterList("Монтаж", teamList.editing.map { Pair(it, it) }.toTypedArray())
    private inner class DecoratorsFilter : CheckBoxFilterList("Декор", teamList.decor.map { Pair(it, it) }.toTypedArray())
    private inner class TimingFilter : CheckBoxFilterList("Тайминг", teamList.timing.map { Pair(it, it) }.toTypedArray())
    private val teamList = getTeamFilter()

    private fun getTeamFilter(): TeamFilter {
        val res =
            client.newCall(GET("$baseUrl/team", apiheaders))
                .execute()
                .use { it.body.string() }
                .let { json.decodeFromString<TeamFilter>(it) }

        Log.d("getTeamFilter", res.toString())
        return res
    }

    // ============================== Title Type ==============================

    private inner class TitleTypesFilter : CheckBoxFilterList("Тип тайтла", titleTypesList)
    private val titleTypesList = arrayOf(
        Pair("Фильм", "0"),
        Pair("TV", "1"),
        Pair("OVA", "2"),
        Pair("ONA", "3"),
        Pair("Спешл", "4"),
        Pair("WEB", "5"),
    )

    // ============================== Season ==============================

    private inner class SeasonFilter : CheckBoxFilterList("Сезон", seasonList)
    private val seasonList = arrayOf(
        Pair("Зима", "1"),
        Pair("Весна", "2"),
        Pair("Лето", "3"),
        Pair("Осень", "4"),
    )

    // ============================== Status ==============================

    private inner class StatusFilter : CheckBoxFilterList("Статус - не предусмотрено апи (не используется)", statusList)
    private val statusList = arrayOf(
        Pair("В работе", "1"),
        Pair("Завершен", "2"),
        Pair("Скрыт", "3"),
        Pair("Неонгоинг", "4"),
    )

    // ============================== Order ==============================

    private data class Order(val name: String, val id: String)
    private class OrderList(Orders: Array<String>) : AnimeFilter.Select<String>("Порядок", Orders)
    private val orderNames = getOrder().map { it.name }.toTypedArray()
    private fun getOrder() = listOf(
        Order("Новенькое", "new"),
        Order("Популярное", "popular"),
    )

    // ============================== Filter Builder ==============================

    public fun getSearchParameters(page: Int, query: String, filters: AnimeFilterList): String {
        var basestring = "$baseUrl/title/search"
        var searchStr = ""
        var sortStr = ""
        var yearStr = ""
        var seasonStr = ""
        var genresStr = ""
        var statusStr = ""
        var titleTypeStr = ""

        var voiceActorsStr = ""
        var translatorsStr = ""
        var editorsStr = ""
        var decoratorsStr = ""
        var timingStr = ""

        var pageStr = "&page=" + page.toString()
        var itemsPPStr = "&items_per_page=8"

        if (query.isNotEmpty()) {
            searchStr = "&search=" + query
        }

        filters.forEach { filter ->
            when (filter) {
                is OrderList -> { // ---Order
                    // sortStr = if (getOrder()[filter.state].id == "new") "updated" else "in_favorites"
                    if (getOrder()[filter.state].id == "new") {
                        sortStr = "&order_by=updated"
                    } else {
                        sortStr = "&order_by=in_favorites"
                    }
                }
                is YearsFilter -> { // ---Year
                    filter.state.forEach { Year ->
                        if (Year.state) {
                            if (yearStr.isNotEmpty()) {
                                yearStr = yearStr + "," + Year.name
                            } else {
                                yearStr = "&year=" + Year.name
                            }
                        }
                    }
                }
                is SeasonFilter -> { // ---Season
                    filter.state.forEach { Season ->
                        if (Season.state) {
                            val seasonId = seasonList.find { it.first == Season.name }?.second
                            if (seasonId != null) {
                                if (seasonStr.isNotEmpty()) {
                                    seasonStr = seasonStr + "," + seasonId
                                } else {
                                    seasonStr = "&season_code=" + seasonId
                                }
                            }
                        }
                    }
                }
                is GenresFilter -> { // ---Genres
                    filter.state.forEach { Genre ->
                        if (Genre.state) {
                            if (genresStr.isNotEmpty()) {
                                genresStr = genresStr + "," + Genre.name
                            } else {
                                genresStr = "&genres=" + Genre.name
                            }
                        }
                    }
                }
                is StatusFilter -> { // ---Status
                    filter.state.forEach { Status ->
                        if (Status.state) {
                            val statusId = statusList.find { it.first == Status.name }?.second
                            if (statusId != null) {
                                if (statusStr.isNotEmpty()) {
                                    statusStr = statusStr + "," + statusId
                                } else {
                                    statusStr = "&status=" + statusId
                                }
                            }
                        }
                    }
                }

                // Advanced Filter

                is TitleTypesFilter -> { // ---Title Type
                    filter.state.forEach { TitleType ->
                        if (TitleType.state) {
                            val titleTypeId = titleTypesList.find { it.first == TitleType.name }?.second
                            if (titleTypeId != null) {
                                if (titleTypeStr.isNotEmpty()) {
                                    titleTypeStr = titleTypeStr + "," + titleTypeId
                                } else {
                                    titleTypeStr = "&type=" + titleTypeId
                                }
                            }
                        }
                    }
                }
                is VoiceActorsFilter -> { // ---Voice Actors
                    filter.state.forEach { VoiceActor ->
                        if (VoiceActor.state) {
                            if (voiceActorsStr.isNotEmpty()) {
                                voiceActorsStr = voiceActorsStr + "," + VoiceActor.name
                            } else {
                                voiceActorsStr = "&voice=" + VoiceActor.name
                            }
                        }
                    }
                }
                is TranslatorsFilter -> { // ---Translators
                    filter.state.forEach { Translator ->
                        if (Translator.state) {
                            if (translatorsStr.isNotEmpty()) {
                                translatorsStr = translatorsStr + "," + Translator.name
                            } else {
                                translatorsStr = "&translator=" + Translator.name
                            }
                        }
                    }
                }
                is EditorsFilter -> { // ---Editors
                    filter.state.forEach { Editor ->
                        if (Editor.state) {
                            if (editorsStr.isNotEmpty()) {
                                editorsStr = editorsStr + "," + Editor.name
                            } else {
                                editorsStr = "&editing=" + Editor.name
                            }
                        }
                    }
                }
                is DecoratorsFilter -> { // ---Decorators
                    filter.state.forEach { Decorator ->
                        if (Decorator.state) {
                            if (decoratorsStr.isNotEmpty()) {
                                decoratorsStr = decoratorsStr + "," + Decorator.name
                            } else {
                                decoratorsStr = "&decor=" + Decorator.name
                            }
                        }
                    }
                }
                is TimingFilter -> { // ---Timing
                    filter.state.forEach { Timing ->
                        if (Timing.state) {
                            if (timingStr.isNotEmpty()) {
                                timingStr = timingStr + "," + Timing.name
                            } else {
                                timingStr = "&timing=" + Timing.name
                            }
                        }
                    }
                }

                else -> {}
            }
        }
        // $statusStr not used by api v3 at 10.10.2023
        var totalstring = "$basestring$searchStr$sortStr$yearStr$seasonStr$genresStr$titleTypeStr$voiceActorsStr$translatorsStr$editorsStr$decoratorsStr$timingStr$pageStr$itemsPPStr"

        if (totalstring.contains("search&")) {
            totalstring = totalstring.replace("search&", "search?")
        }

        return totalstring
    }
}
