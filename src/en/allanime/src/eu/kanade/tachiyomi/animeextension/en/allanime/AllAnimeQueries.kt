package eu.kanade.tachiyomi.animeextension.en.allanime

fun buildQuery(queryAction: () -> String): String {
    return queryAction()
        .trimIndent()
        .replace("%", "$")
}

val POPULAR_QUERY: String = buildQuery {
    """
        query(
                %type: VaildPopularTypeEnumType!
                %size: Int!
                %page: Int
                %dateRange: Int
            ) {
            queryPopular(
                type: %type
                size: %size
                dateRange: %dateRange
                page: %page
            ) {
                total
                recommendations {
                    anyCard {
                        _id
                        name
                        thumbnail
                        englishName
                        nativeName
                        slugTime
                    }
                }
            }
        }
    """
}

val SEARCH_QUERY: String = buildQuery {
    """
        query(
            %search: SearchInput
            %limit: Int
            %page: Int
            %translationType: VaildTranslationTypeEnumType
            %countryOrigin: VaildCountryOriginEnumType
        ) {
            shows(
                search: %search
                limit: %limit
                page: %page
                translationType: %translationType
                countryOrigin: %countryOrigin
            ) {
                pageInfo {
                    total
                }
                edges {
                    _id
                    name
                    thumbnail
                    englishName
                    nativeName
                    slugTime
                }
            }
        }
    """
}

val DETAILS_QUERY = buildQuery {
    """
        query (%_id: String!) {
            show(
                _id: %_id
            ) {
                thumbnail
                description
                type
                season
                score
                genres
                status
                studios
            }
        }
    """
}

val EPISODES_QUERY = buildQuery {
    """
        query (%_id: String!) {
            show(
                _id: %_id
            ) {
                _id
                availableEpisodesDetail
            }
        }
    """
}

val STREAMS_QUERY = buildQuery {
    """
        query(
            %showId: String!,
            %translationType: VaildTranslationTypeEnumType!,
            %episodeString: String!
        ) {
            episode(
                showId: %showId
                translationType: %translationType
                episodeString: %episodeString
            ) {
                sourceUrls
            }
        }
    """
}
