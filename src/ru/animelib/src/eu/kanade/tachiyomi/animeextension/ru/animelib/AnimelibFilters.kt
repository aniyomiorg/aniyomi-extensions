package eu.kanade.tachiyomi.animeextension.ru.animelib

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimelibFilters {

    open class TriStateFilterList(name: String, values: List<AnimeFilter.TriState>) : AnimeFilter.Group<AnimeFilter.TriState>(name, values)
    class TriFilterVal(name: String) : AnimeFilter.TriState(name)

    class GenresFilter : TriStateFilterList("Жанр", AnimelibFiltersData.GENRES.map { TriFilterVal(it.first) })

    private inline fun <reified R> AnimeFilterList.getFirst(): R = first { it is R } as R

    private inline fun <reified R> AnimeFilterList.parseTriFilter(options: Array<Pair<String, String>>): IncludeExcludeParams {
        return (getFirst<R>() as TriStateFilterList).state
            .filterNot { it.isIgnored() }
            .map { filter -> filter.state to options.find { it.first == filter.name }!!.second }
            .groupBy { it.first }
            .let { dict ->
                val included = dict[AnimeFilter.TriState.STATE_INCLUDE]?.map { it.second }.orEmpty()
                val excluded = dict[AnimeFilter.TriState.STATE_EXCLUDE]?.map { it.second }.orEmpty()
                IncludeExcludeParams(included, excluded)
            }
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)
    class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    class FormatFilter : CheckBoxFilterList("Формат", AnimelibFiltersData.FORMATS.map { CheckBoxVal(it.first) })
    class PegiFilter : CheckBoxFilterList("Возрастной рейтинг", AnimelibFiltersData.PEGI.map { CheckBoxVal(it.first) })
    class OngoingFilter : CheckBoxFilterList("Статус тайтла", AnimelibFiltersData.ONGOING_STATUS.map { CheckBoxVal(it.first) })

    private inline fun <reified R> AnimeFilterList.parseCheckbox(options: Array<Pair<String, String>>): List<String> {
        return (getFirst<R>() as CheckBoxFilterList).state
            .asSequence()
            .filter { it.state }
            .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
            .toList()
    }

    class SortFilter : AnimeFilter.Sort(
        "Сортировать по",
        AnimelibFiltersData.ORDERS.map { it.first }.toTypedArray(),
        Selection(0, false),
    )

    val FILTER_LIST get() = AnimeFilterList(
        GenresFilter(),

        PegiFilter(),
        FormatFilter(),
        OngoingFilter(),

        SortFilter(),
    )

    data class IncludeExcludeParams(
        val include: List<String> = emptyList(),
        var exclude: List<String> = emptyList(),
    )

    data class FilterSearchParams(
        val genres: IncludeExcludeParams = IncludeExcludeParams(),
        val format: List<String> = emptyList(),
        val pegi: List<String> = emptyList(),
        val ongoingStatus: List<String> = emptyList(),

        val sortOrder: String = AnimelibFiltersData.ORDERS[0].second,
        val sortDirection: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val sortDirection = filters.getFirst<SortFilter>().state?.let {
            if (it.ascending) "asc" else ""
        } ?: ""

        val sortOrder = filters.getFirst<SortFilter>().state?.let {
            AnimelibFiltersData.ORDERS[it.index].second
        } ?: ""

        return FilterSearchParams(
            filters.parseTriFilter<GenresFilter>(AnimelibFiltersData.GENRES),
            filters.parseCheckbox<FormatFilter>(AnimelibFiltersData.FORMATS),
            filters.parseCheckbox<PegiFilter>(AnimelibFiltersData.PEGI),
            filters.parseCheckbox<OngoingFilter>(AnimelibFiltersData.ONGOING_STATUS),
            sortOrder,
            sortDirection,
        )
    }

    private object AnimelibFiltersData {
        val FORMATS = arrayOf(
            Pair("TV сериалы", "16"),
            Pair("Фильмы", "17"),
            Pair("Короткометражное", "18"),
            Pair("Спешл", "19"),
            Pair("OVA", "20"),
            Pair("ONA", "21"),
            Pair("Клип", "22"),
        )

        val GENRES = arrayOf(
            Pair("Арт", "32"),
            Pair("Безумие", "91"),
            Pair("Боевик", "34"),
            Pair("Боевые искусства", "35"),
            Pair("Вампиры", "36"),
            Pair("Военное", "89"),
            Pair("Гарем", "37"),
            Pair("Гендерная интрига", "38"),
            Pair("Героическое фэнтези", "39"),
            Pair("Демоны", "81"),
            Pair("Детектив", "40"),
            Pair("Детское", "88"),
            Pair("Дзёсэй", "41"),
            Pair("Драма", "43"),
            Pair("Игра", "44"),
            Pair("Исекай", "79"),
            Pair("История", "45"),
            Pair("Киберпанк", "46"),
            Pair("Кодомо", "76"),
            Pair("Комедия", "47"),
            Pair("Космос", "83"),
            Pair("Магия", "85"),
            Pair("Махо-сёдзё", "48"),
            Pair("Машины", "90"),
            Pair("Меха", "49"),
            Pair("Мистика", "50"),
            Pair("Музыка", "80"),
            Pair("Научная фантастика", "51"),
            Pair("Омегаверс", "77"),
            Pair("Пародия", "86"),
            Pair("Повседневность", "52"),
            Pair("Полиция", "82"),
            Pair("Постапокалиптика", "53"),
            Pair("Приключения", "54"),
            Pair("Психология", "55"),
            Pair("Романтика", "56"),
            Pair("Самурайский боевик", "57"),
            Pair("Сверхъестественное", "58"),
            Pair("Сёдзё", "59"),
            Pair("Сёдзё-ай", "60"),
            Pair("Сёнэн", "61"),
            Pair("Сёнэн-ай", "62"),
            Pair("Спорт", "63"),
            Pair("Супер сила", "87"),
            Pair("Сэйнэн", "64"),
            Pair("Трагедия", "65"),
            Pair("Триллер", "66"),
            Pair("Ужасы", "67"),
            Pair("Фантастика", "68"),
            Pair("Фэнтези", "69"),
            Pair("Хентай", "84"),
            Pair("Школа", "70"),
            Pair("Эротика", "71"),
            Pair("Этти", "72"),
            Pair("Юри", "73"),
            Pair("Яой", "74"),
        )

        val PEGI = arrayOf(
            Pair("Нет", "0"),
            Pair("6+", "1"),
            Pair("12+", "2"),
            Pair("16+", "3"),
            Pair("18+", "4"),
            Pair("18+ (RX)", "5"),
        )

        val ONGOING_STATUS = arrayOf(
            Pair("Онгоинг", "1"),
            Pair("Завершён", "2"),
            Pair("Анонс", "3"),
            Pair("Приостановлен", "4"),
            Pair("Выпуск прекращён", "5"),
        )

        val ORDERS = arrayOf(
            Pair("Популярности", "rating_score"),
            Pair("Рейтингу", "rate_avg"),
            Pair("Просмотрам", "views"),
            Pair("Количеству эпизодов", "episodes_count"),
            Pair("Дате обновления", "last_episode_at"),
            Pair("Дате добавления", "created_at"),
            Pair("Названию (A-Z)", "name"),
            Pair("Названию (А-Я)", "rus_name"),
        )
    }
}
