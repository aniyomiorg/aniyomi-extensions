# AniLibria API – v3.0.14

> ⚠️ v2 DEPRECATED
> **В версии v3 роуты претерпели серьезные изменения**
> Хотя поддержка старых роутов и осталась, рекомендуется в новых проектах использовать новые пути.

> ⚠️ Внимание
> **Перечитайте секцию [Полезное](#полезное) дабы у вас ничего не сломалось между релизами.**
> После выхода версии v2.13.0 поддержка всех предыдущих версий API до v2.12.0 была прекращена!

- [**RestAPI**](#restapi) – *Документация по RestAPI*
- [**WebSocket**](#websocket) – *Документация по WebSocket*

## RestAPI
```
http(s)://api.anilibria.tv/v3/
```

# Список методов:

## Открытые методы
- [**GET title**](#-title) – *Получить информацию о тайтле*
- [**GET title/list**](#-titlelist) – *Получить информацию о нескольких тайтлах сразу*
- [**GET title/updates**](#-titleupdates) – *Список тайтлов, отсортированные по времени добавления нового релиза*
- [**GET title/changes**](#-titlechanges) – *Список тайтлов, отсортированные по времени изменения*
- [**GET title/schedule**](#-titleschedule) – *Расписание выхода тайтлов, отсортированное по дням недели*
- [**GET title/random**](#-titlerandom) – *Возвращает случайный тайтл из базы*
- [**GET title/search**](#-titlesearch) – *Возвращает список найденных по фильтрам тайтлов*
- [**GET title/search/advanced**](#-titlesearchadvanced) – *Поиск информации по продвинутым фильтрам с поддержкой сортировки*
- [**GET title/franchises**](#-titlefranchises) – *Получить информацию о франшизе по ID тайтла*
- [**GET youtube**](#-youtube) – *Информация о вышедших роликах на наших YouTube каналах в хронологическом порядке*
- [**GET feed**](#-feed) – *Список обновлений тайтлов и роликов на наших YouTube каналах в хронологическом порядке*
- [**GET years**](#-years) – *Возвращает список годов выхода доступных тайтлов по возрастанию*
- [**GET genres**](#-genres) – *Возвращает список всех жанров по алфавиту*
- [**GET team**](#-team) – *Возвращает список участников команды, когда-либо существовавших на проекте.*
- [**GET torrent/seed_stats**](#-torrentseed_stats) – *Возвращает список пользователей и их статистику на трекере.*
- [**GET torrent/rss**](#-torrentrss) – *Возвращает список обновлений на сайте в одном из форматов RSS ленты*
- [**GET franchise/list**](#-franchiselist) – *Возвращает список всех франшиз*

## Пользовательские методы, требующие авторизации
- [**GET user**](#-user) – *Получить информацию об аккаунте пользователя*
- [**GET user/favorites**](#-userfavorites) – *Возвращает список избранных тайтлов пользователя*
- [**PUT user/favorites**](#-userfavorites-add) – *Добавляет тайтл в список избранных*
- [**DELETE user/favorites**](#-userfavorites-remove) – *Удаляет тайтл из списка избранных*
search
# Описание методов: 

## • title
Получить информацию о тайтле по id или коду

```js
GET /v3/title
DEPRECATED - GET /v2/getTitle
```

### Все доступные параметры
| Параметр | Тип | Описание | По умолчанию |
| - | - | - | - |
| id | int | ID тайтла | |
| code | string | Код тайтла | |
| torrent_id | int | ID торрент файла | |
| filter | string, ... | Список значений, которые будут в ответе | |
| remove | string, ... | Список значений, которые будут удалены из ответа | |
| [include](#include) | string, ... | Список типов файлов, которые будут возвращены в виде base64 строки [подробнее](#include) | |
| [description_type](#description_type) | string | Тип получаемого описания, [подробнее](#description_type) | plain |
| playlist_type | string | Формат получаемого списка серий, `object` или `array` | object |

*В параметрах `filter` и `remove` можно указать полный путь до ключа, который вы хотите оставить или удалить, например: `names.alternative` или `team.voice[0]`. С версии 2.8 появилась возможность получать значения одного ключа во всех объектах в массиве, например: torrents.list[\*].torrent_id*

### Примеры запросов
```js
/v3/title?id=9000
```
```js
/v3/title?code=kizumonogatari-iii-reiketsu-hen
```

### Пример ответа
```json
{
  "id": 9000,
  "code": "kizumonogatari-iii-reiketsu-hen",
  "names": {
    "ru": "Истории Ран. Часть 3: Холодная кровь",
    "en": "Kizumonogatari III: Reiketsu-hen",
    "alternative": null
  },
  "franchises": [
    {
      "franchise": {
        "id": "00651e7c-edaa-46b8-a095-804cc428f69d",
        "name": "Истории монстров"
      },
      "releases": [
        {
          "id": 8896,
          "code": "bakemonogatari",
          "ordinal": 1,
          "names": {
            "ru": "Истории Монстров",
            "en": "Bakemonogatari",
            "alternative": null
          }
        },
        ...
      ]
    }
  ],
  "announce": "Релиз завершен!",
  "status": {
    "string": "Завершен",
    "code": 2
  },
  "posters": {
    "small": {
      "url": "/storage/releases/posters/9000/NBPPaSwgJrcoO4eg__f003bb6841ce26560a643491c197878f.jpg",
      "raw_base64_file": null
    },
    "medium": {
      "url": "/storage/releases/posters/9000/NBPPaSwgJrcoO4eg__f003bb6841ce26560a643491c197878f.jpg",
      "raw_base64_file": null
    },
    "original": {
      "url": "/storage/releases/posters/9000/NBPPaSwgJrcoO4eg__f003bb6841ce26560a643491c197878f.jpg",
      "raw_base64_file": null
    }
  },
  "updated": 1624984055,
  "last_change": 1671901871,
  "type": {
    "full_string": "Фильм, 83 мин.",
    "code": 0,
    "string": "MOVIE",
    "episodes": null,
    "length": 83
  },
  "genres": [
    "Вампиры",
    "Детектив",
    "Сверхъестественное",
    "Экшен"
  ],
  "team": {
    "voice": [
      "BStrong",
      "Gomer",
      "MyAska",
      "SlivciS"
    ],
    "translator": [
      "Sesha_Rim",
      "Yukigawa"
    ],
    "editing": [
      "Aero"
    ],
    "decor": [
      "Helge"
    ],
    "timing": [
      "im4x"
    ]
  },
  "season": {
    "string": "зима",
    "code": 1,
    "year": 2017,
    "week_day": 0
  },
  "description": "Заключительная часть из трилогии полнометражных фильмов «Истории Ран».\r\n\r\nПосле боёв с тремя охотниками (Драматургия, Эпизод, Гильотина) Арараги получил все части тела Киссшот и готов их ей вернуть, чтобы снова стать человеком. Но остаётся много вопросов: как Киссшот сделает его человеком? Как трое охотников победили Киссшот на пике её силы? Зачем на самом деле приехал Мэмэ Ошино? И как в итоге продолжатся отношения Арараги и Ханэкавы?",
  "in_favorites": 1141,
  "blocked": {
    "blocked": false,
    "bakanim": false
  },
  "player": {
    "alternative_player": null,
    "host": "cache.libria.fun",
    "episodes": {
      "first": 1,
      "last": 1,
      "string": "1-1"
    },
    "list": {
      "1": {
        "episode": 1,
        "name": null,
        "uuid": "95db068f-789e-11ec-ae92-0242ac120002",
        "created_timestamp": 1624983774,
        "preview": null,
        "skips": {
          "opening": [
            
          ],
          "ending": [
            
          ]
        },
        "hls": {
          "fhd": "/videos/media/ts/9000/1/1080/7f3c1729ebd24b93d4e0918510004606.m3u8",
          "hd": "/videos/media/ts/9000/1/720/e313ea7c883c81fba86547414ec18b5e.m3u8",
          "sd": "/videos/media/ts/9000/1/480/8a7f4d218433f5a5fee1c6f5a02d278e.m3u8"
        }
      }
    },
    "rutube": {
      
    }
  },
  "torrents": {
    "episodes": {
      "first": 1,
      "last": 1,
      "string": "1-1"
    },
    "list": [
      {
        "torrent_id": 15808,
        "episodes": {
          "first": 1,
          "last": 1,
          "string": "Фильм"
        },
        "quality": {
          "string": "BDRip 1080p",
          "type": "BDRip",
          "resolution": "1080p",
          "encoder": "h264",
          "lq_audio": null
        },
        "leechers": 0,
        "seeders": 22,
        "downloads": 2852,
        "total_size": 4801286259,
        "size_string": "4.8 GB",
        "url": "/public/torrent/download.php?id=15808",
        "magnet": "magnet:?xt=urn:btih:769359d20c645989c338a1646f7c2dd6d44b8652&tr=http%3A%2F%2Ftr.libria.fun%3A2710%2Fannounce",
        "uploaded_timestamp": 1624979760,
        "hash": "769359d20c645989c338a1646f7c2dd6d44b8652",
        "metadata": null,
        "raw_base64_file": null
      },
      {
        "torrent_id": 15810,
        "episodes": {
          "first": 1,
          "last": 1,
          "string": "Фильм"
        },
        "quality": {
          "string": "BDRip 1080p HEVC",
          "type": "BDRip",
          "resolution": "1080p",
          "encoder": "h265",
          "lq_audio": null
        },
        "leechers": 0,
        "seeders": 31,
        "downloads": 3329,
        "total_size": 864734373,
        "size_string": "864.73 MB",
        "url": "/public/torrent/download.php?id=15810",
        "magnet": "magnet:?xt=urn:btih:00b80dfc7d920f3160ca3105c7d54d594f157dc7&tr=http%3A%2F%2Ftr.libria.fun%3A2710%2Fannounce",
        "uploaded_timestamp": 1624989064,
        "hash": "00b80dfc7d920f3160ca3105c7d54d594f157dc7",
        "metadata": null,
        "raw_base64_file": null
      }
    ]
  }
}
```
***

## • title/list 
Получить информацию о тайтле по id или коду 
```js
GET /v3/title/list 
DEPRECATED - GET /v2/getTitles
```

### Все доступные параметры
| Параметр | Тип | Описание | По умолчанию |
| - | - | - | - |
| id_list | string, ... | Список ID тайтлов | |
| code_list | string, ... | Список кодов тайтла | |
| torrent_id_list | string, ... | Список ID торрент файлов | |
| filter | string, ... | Список значений, которые будут в ответе | |
| remove | string, ... | Список значений, которые будут удалены из ответа | |
| [include](#include) | string, ... | Список типов файлов, которые будут возвращены в виде base64 строки [подробнее](#include) | |
| [description_type](#description_type) | string | Тип получаемого описания, [подробнее](#description_type) | plain |
| playlist_type | string | Формат получаемого списка серий, `object` или `array` | object |
| page | int | Номер страницы | |
| items_per_page | int | Количество элементов на странице | |

*В параметрах `filter` и `remove` можно указать полный путь до ключа, который вы хотите оставить или удалить, например: `names.alternative` или `team.voice[0]`. С версии 2.8 появилась возможность получать значения одного ключа во всех объектах в массиве, например: torrents.list[\*].torrent_id*

### Примеры запросов
```js
/v3/title/list ?id_list=8500,8644&filter=posters,type,status,player.list.24
```
```js
/v3/title/list ?code_list=nanatsu-no-taizai-kamigami-no-gekirin
```

### Пример ответа
```json
[
    [Возвращаемые поля идентичны /title],
    ...
]
```
[Подробнее о возвращаемых значениях](#возвращаемые-значения-при-запросе-информации-о-тайтле)
***


## • title/updates
Получить список последних обновлений тайтлов
* обновлением у нас считается момент когда релиз полностью готов, к примеру когда серия вышла в торренте и плеере и уже успешно залита.

```js
GET /v3/title/updates
DEPRECATED - GET /v2/getUpdates
```

### Все доступные параметры

| Параметр | Тип | Описание | По умолчанию |
| - | - | - | - |
| filter | string, ... | Список значений, которые будут в ответе | |
| remove | string, ... | Список значений, которые будут удалены из ответа | |
| [include](#include) | string, ... | Список типов файлов, которые будут возвращены в виде base64 строки [подробнее](#include) | |
| [limit](#limit) | int | Количество объектов в ответе | 5 |
| since | int | Список тайтлов, у которых время обновления больше указанного timestamp | |
| [description_type](#description_type) | string | Тип получаемого описания, [подробнее](#description_type) | plain |
| playlist_type | string | Формат получаемого списка серий, `object` или `array` | object |
| after | int | Удаляет первые n записей из выдачи | |
| page | int | Номер страницы | |
| items_per_page | int | Количество элементов на странице | |

*В параметрах `filter` и `remove` можно указать полный путь до ключа, который вы хотите оставить или удалить, например: `names.alternative` или `team.voice[0]`. С версии 2.8 появилась возможность получать значения одного ключа во всех объектах в массиве, например: torrents.list[\*].torrent_id*

### Примеры запросов
```js
/v3/title/updates?filter=posters,type,status&limit=5
```
```js
/v3/title/updates?since=1590233417
```
### Пример ответа

```json
{
  "list": [
     [Возвращаемые поля идентичны /title],
     ...
  ],
  "pagination": {
    "pages": 271,
    "current_page": 0,
    "items_per_page": 5,
    "total_items": 1355
  }
}
```
[Подробнее о возвращаемых значениях](#возвращаемые-значения-при-запросе-информации-о-тайтле)
***

## • title/changes
Получить список последних изменений тайтлов

```js
GET /v3/title/changes
DEPRECATED - GET /v2/getChanges
```

### Все доступные параметры
| Параметр | Тип | Описание | По умолчанию |
| - | - | - | - |
| filter | string, ... | Список значений, которые будут в ответе | |
| remove | string, ... | Список значений, которые будут удалены из ответа | |
| [include](#include) | string, ... | Список типов файлов, которые будут возвращены в виде base64 строки [подробнее](#include) | |
| [limit](#limit) | int | Количество объектов в ответе | 5 |
| since | int | Список тайтлов, у которых время обновления больше указанного timestamp | |
| [description_type](#description_type) | string | Тип получаемого описания, [подробнее](#description_type) | plain |
| playlist_type | string | Формат получаемого списка серий, `object` или `array` | object |
| after | int | Удаляет первые n записей из выдачи | |
| page | int | Номер страницы | |
| items_per_page | int | Количество элементов на странице | |

*В параметрах `filter` и `remove` можно указать полный путь до ключа, который вы хотите оставить или удалить, например: `names.alternative` или `team.voice[0]`. С версии 2.8 появилась возможность получать значения одного ключа во всех объектах в массиве, например: torrents.list[\*].torrent_id*

### Примеры запросов
```js
/v3/title/changes?filter=posters,type,status&limit=5
```
```js
/v3/title/changes?since=1590233417
```
### Пример ответа

```json
{
  "list": [
     [Возвращаемые поля идентичны /title],
     ...
  ],
  "pagination": {
    "pages": 271,
    "current_page": 0,
    "items_per_page": 5,
    "total_items": 1355
  }
}
```
[Подробнее о возвращаемых значениях](#возвращаемые-значения-при-запросе-информации-о-тайтле)
***


## • title/schedule
Получить список последних обновлений тайтлов

```js
GET /v3/title/schedule
DEPRECATED - GET /v2/getSchedule
```

### Все доступные параметры
| Параметр | Тип | Описание | По умолчанию |
| - | - | - | - |
| filter | string, ... | Список значений, которые будут в ответе | |
| remove | string, ... | Список значений, которые будут удалены из ответа | |
| [include](#include) | string, ... | Список типов файлов, которые будут возвращены в виде base64 строки [подробнее](#include) | |
| days | string, ... | Список дней недели на которые нужно расписание | |
| [description_type](#description_type) | string | Тип получаемого описания, [подробнее](#description_type) | plain |
| playlist_type | string | Формат получаемого списка серий, `object` или `array` | object |

*В параметрах `filter` и `remove` можно указать полный путь до ключа, который вы хотите оставить или удалить, например: `names.alternative` или `team.voice[0]`. С версии 2.8 появилась возможность получать значения одного ключа во всех объектах в массиве, например: torrents.list[\*].torrent_id*

> Счет дней недели идет с понедельника, где 0 - Понедельник, а 6 - Воскресенье.

### Примеры запросов
```js
/v3/title/schedule?filter=posters,type,status
```
```js
/v3/title/schedule?days=5,6
```
### Пример ответа

```json
[
    {
        "day": 5,
        "list": [
            [Возвращаемые поля идентичны /title],
            ...
        ]
    },{
        "day": 6,
        "list": [
            [Возвращаемые поля идентичны /title],
            ...
        ]
    }
]
```
***


## • title/random
Возвращает случайный тайтл из базы

```js
GET /v3/title/random
DEPRECATED - GET /v2/getRandomTitle
```

### Все доступные параметры
| Параметр | Тип | Описание | По умолчанию |
| - | - | - | - |
| filter | string, ... | Список значений, которые будут в ответе | |
| remove | string, ... | Список значений, которые будут удалены из ответа | |
| [include](#include) | string, ... | Список типов файлов, которые будут возвращены в виде base64 строки [подробнее](#include) | |
| [description_type](#description_type) | string | Тип получаемого описания, [подробнее](#description_type) | plain |
| playlist_type | string | Формат получаемого списка серий, `object` или `array` | object |

*В параметрах `filter` и `remove` можно указать полный путь до ключа, который вы хотите оставить или удалить, например: `names.alternative` или `team.voice[0]`. С версии 2.8 появилась возможность получать значения одного ключа во всех объектах в массиве, например: torrents.list[\*].torrent_id*

### Примеры запросов
```js
/v3/title/random
```
### Пример ответа

```json
{
    [Возвращаемые поля идентичны /title],
}
```
[Подробнее о возвращаемых значениях](#возвращаемые-значения-при-запросе-информации-о-тайтле)
***


## • youtube
Информация о вышедших роликах на наших YouTube каналах в хронологическом порядке

```js
GET /v3/youtube
DEPRECATED - GET /v2/getYouTube
```

### Все доступные параметры
| Параметр | Тип | Описание | По умолчанию |
| - | - | - | - |
| filter | string, ... | Список значений, которые будут в ответе | |
| remove | string, ... | Список значений, которые будут удалены из ответа | |
| [limit](#limit) | int | Количество объектов в ответе | 5 |
| since | int | Список видеороликов, у которых время обновления больше указанного timestamp | |
| after | int | Удаляет первые n записей из выдачи | |
| page | int | Номер страницы | |
| items_per_page | int | Количество элементов на странице | |

*В параметрах `filter` и `remove` можно указать полный путь до ключа, который вы хотите оставить или удалить, например: `names.alternative` или `team.voice[0]`. С версии 2.8 появилась возможность получать значения одного ключа во всех объектах в массиве, например: torrents.list[\*].torrent_id*

### Примеры запросов
```js
/v3/youtube?limit=10
```
### Пример ответа

```json
[
    {
        "id": 10861,
        "title": "АНИМЕ Своя игра с АниЛибрией (Люпин, Шарон, Зозя, Сахарочек, Рокетту, Никанор47)",
        "preview": {
      		 "src": "/storage/media/videos/previews/525/Jje8XoIFerhG78N4.jpg",
     		 "thumbnail": "/storage/media/videos/previews/525/Jje8XoIFerhG78N4__3fcc93cd365a86b027f995ba19d79934.jpg"
  		},
        "youtube_id": "rvhfqzXXZaU",
        "comments": 29,
        "views": 7911,
        "timestamp": 1656844874
    },
    ...
}
```
[Подробнее о возвращаемых значениях](#возвращаемые-значения-при-запросе-информации-о-youtube-ролике)
***


## • feed
Список обновлений тайтлов и роликов на наших YouTube каналах в хронологическом порядке

```js
GET /v3/feed
DEPRECATED - GET /v2/getFeed
```

### Все доступные параметры
| Параметр | Тип | Описание | По умолчанию |
| - | - | - | - |
| filter | string, ... | Список значений, которые будут в ответе | |
| remove | string, ... | Список значений, которые будут удалены из ответа | |
| [include](#include) | string, ... | Список типов файлов, которые будут возвращены в виде base64 строки [подробнее](#include) | |
| [limit](#limit) | int | Количество объектов в ответе | 5 |
| since | int | Список тайтлов, у которых время обновления больше указанного timestamp | |
| [description_type](#description_type) | string | Тип получаемого описания, [подробнее](#description_type) | plain |
| playlist_type | string | Формат получаемого списка серий, `object` или `array` | object |
| after | int | Удаляет первые n записей из выдачи | |
| page | int | Номер страницы | |
| items_per_page | int | Количество элементов на странице | |

*В параметрах `filter` и `remove` можно указать полный путь до ключа, который вы хотите оставить или удалить, например: `names.alternative` или `team.voice[0]`. С версии 2.8 появилась возможность получать значения одного ключа во всех объектах в массиве, например: torrents.list[\*].torrent_id*

### Примеры запросов
```js
/v3/feed?limit=10
```
### Пример ответа

```json
[    
    {
        "youtube": {
            [Возвращаемые поля идентичны /youtube]
        }
    },
    {
        "title": {
            [Возвращаемые поля идентичны /title]
        }
    },
    ...
]
```
[Подробнее о возвращаемых значениях title](#возвращаемые-значения-при-запросе-информации-о-тайтле)
[Подробнее о возвращаемых значениях youtube](#Возвращаемые значения при запросе информации о YouTube ролике)

***


## • years
Возвращает список годов выхода доступных тайтлов отсортированный по возрастанию

```js
GET /v3/years
DEPRECATED - GET /v2/getYears
```

### Примеры запросов
```js
/v3/years
```
### Пример ответа

```json
[
    1996,
    1998,
    2001,
    2003,
   ...
]
```
***


## • genres
Возвращает список жанров доступных тайтлов отсортированный по алфавиту

```js
GET /v3/genres
DEPRECATED - GET /v2/getGenres
```

### Примеры запросов
```js
/v3/genres
```

### Пример ответа

```json
[
    "боевые искусства",
    "вампиры",
    "демоны",
    ...
]
```
***


## • title/search
Возвращает список найденных по фильтрам тайтлов

```js
GET /v3/title/search
DEPRECATED - GET /v2/searchTitles
```

### Все доступные параметры
| Параметр | Тип | Описание | По умолчанию |
| - | - | - | - |
| search | string, ... | Поиск по именам и описанию | |
| year | string, ... | Список годов выхода | |
| type | string, ... | Список типов через запятую | |
| season_code | string, ... | Список сезонов, [подробнее](#season) | |
| genres | string, ... | Список жанров | |
| team | string, ... | Поиск по всем командам, список ников через запятую | |
| voice | string, ... | Список ников через запятую | |
| translator | string, ... | Список ников через запятую | |
| editing | string, ... | Список ников через запятую | |
| decor | string, ... | Список ников через запятую | |
| timing | string, ... | Список ников через запятую | |
| filter | string, ... | Список значений, которые будут в ответе | |
| remove | string, ... | Список значений, которые будут удалены из ответа | |
| [include](#include) | string, ... | Список типов файлов, которые будут возвращены в виде base64 строки [подробнее](#include) | |
| [description_type](#description_type) | string | Тип получаемого описания, [подробнее](#description_type) | plain |
| playlist_type | string | Формат получаемого списка серий, `object` или `array` | object |
| [limit](#limit) | int | Количество объектов в ответе | 5 |
| after | int | Удаляет первые n записей из выдачи | |
| order_by | string | Ключ, по которому будет происходить сортировка результатов | |
| sort_direction | int | Направление сортировки. 0 - По возрастанию, 1 - По убыванию | 0 |
| page | int | Номер страницы | |
| items_per_page | int | Количество элементов на странице | |

### Примеры запросов
```js
/v3/title/search?search=cудьба апокреф&voice=Amikiri,Silv,Hekomi&filter=id,names,team,genres[0]&limit=10
```
> Поиск идет по неточному совпадению, так что опечатки допустимы.

### Пример ответа

```json
{
    [Возвращаемые поля идентичны /title]
}
```
***

## • title/search/advanced
Возвращает список найденных по фильтрам тайтлов

```js
GET /v3/title/search/advanced
DEPRECATED - GET /v2/advancedSearch
```

### Все доступные параметры
| Параметр | Тип | Описание | По умолчанию |
| - | - | - | - |
| [query](#query) | string | **Обязательный параметр** Фильтр, по которому будет идти выборка, [подробнее](#query) | |
| [simple_query](#simple_query) | string | **Обязательный параметр** Фильтр, по которому будет идти выборка, [подробнее](#simple_query) | |
| filter | string, ... | Список значений, которые будут в ответе | |
| remove | string, ... | Список значений, которые будут удалены из ответа | |
| [include](#include) | string, ... | Список типов файлов, которые будут возвращены в виде base64 строки [подробнее](#include) | |
| [description_type](#description_type) | string | Тип получаемого описания, [подробнее](#description_type) | plain |
| playlist_type | string | Формат получаемого списка серий, `object` или `array` | object |
| [limit](#limit) | int | Количество объектов в ответе, [подробнее](#limit) | 5 |
| after | int | Удаляет первые n записей из выдачи | |
| order_by | string | Ключ, по которому будет происходить сортировка результатов | |
| sort_direction | int | Направление сортировки. 0 - По возрастанию, 1 - По убыванию | 0 |
| page | int | Номер страницы | |
| items_per_page | int | Количество элементов на странице | |

### Примеры запросов
```js
/v3/title/search/advanced?query={season.code} == 1 and {season.year} == 2020&filter=id,names,in_favorites&order_by=in_favorites&sort_direction=0
/v3/title/search/advanced?simple_query=status.code==1
```

### Пример ответа

```json
{
    [Возвращаемые поля идентичны /title]
}
```
***

## • title/franchises
Получить информацию о франшизе по ID тайтла

```js
GET /v3/title/franchises
```

### Все доступные параметры
| Параметр | Тип | Описание | По умолчанию |
| - | - | - | - |
| id | int | ID тайтла | |
| filter | string, ... | Список значений, которые будут в ответе | |
| remove | string, ... | Список значений, которые будут удалены из ответа | |

### Примеры запросов
```js
/v3/title/franchises?id=8500
```

### Пример ответа

```json
{
    "franchise": {
      "id": "e2fd07c2-6119-4520-98d4-2e9fb0e606a6",
      "name": "Семь смертных грехов"
    },
    "releases": [
      {
        "id": 428,
        "code": "nanatsu-no-taizai-the-seven-deadly-sins-sem-smertnykh-grekhov",
        "ordinal": 1,
        "names": {
          "ru": "Семь смертных грехов",
          "en": "Nanatsu no Taizai",
          "alternative": null
        }
      },
      ...
    ]
  }
]
```
***


## • team
Возвращает список участников команды когда-либо существовавших на проекте.

```js
GET /v3/team
DEPRECATED - GET /v2/getTeam
```

### Примеры запросов
```js
/v3/team
```

### Пример ответа

```json
{
    "voice": [...],
    "translator": [...],
    "editing": [...],
    "decor": [...],
    "timing": [...]
}
```

## • torrent/seed_stats
Возвращает топ пользователей по количеству загруженного и скачанного через наш торрент трекер.

```js
GET /v3/torrent/seed_stats
DEPRECATED - GET /v2/getSeedStats
```

### Все доступные параметры
| Параметр | Тип | Описание | По умолчанию |
| - | - | - | - |
| users | string, ... | Статистика по имени пользователя | |
| filter | string, ... | Список значений, которые будут в ответе | |
| remove | string, ... | Список значений, которые будут удалены из ответа | |
| [limit](#limit) | int | Количество объектов в ответе | 5 |
| after | int | Удаляет первые n записей из выдачи | |
| sort_by | string | По какому полю производить сортировку, допустимые значения: downloaded, uploaded, user | |
| order | int | Направление сортировки 0 - DESC, 1 - ASC | |
| page | int | Номер страницы | |
| items_per_page | int | Количество элементов на странице | |

### Примеры запросов
```js
/v3/torrent/seed_stats?users=AniLibriaBot
```

### Пример ответа

```json
[
    {
        "downloaded": 72110162198,
        "uploaded": 1163165762554,
        "user": "T1MOX4"
    }
]
```

## • torrent/rss
Возвращает список обновлений на сайте в одном из форматов RSS ленты

```js
GET /v3/torrent/rss
DEPRECATED - GET /v2/getRSS
```

### Все доступные параметры
| Параметр | Тип | Описание | По умолчанию |
| - | - | - | - |
| [rss_type](#rss_type) | string | Предпочитаемый формат вывода | rss |
| [session](#session) | string | Уникальный идентификатор сессии пользователя | |
| [limit](#limit) | int | Количество объектов в ответе | 10 |
| since | int | Список тайтлов, у которых время обновления больше указанного timestamp | |
| after | int | Удаляет первые n записей из выдачи | |

* Если указан верный параметр session, то загрузка торрентов будет происходить от имени вашего аккаунта, и вам будет начисляться статистика.
* В случае если ключ указан неверно торрент клинт будет возвращать ошибку о неправильном формате торрента.

### Примеры запросов
```js
/v3/torrent/rss?rss_type=atom&limit=5
```

### Пример ответа

```xml
<rss version="2.0">
    <channel>
        <title>Самое свежее на AniLibria.TV</title>
        <link>https://anilibria.tv/</link>
        <description>Самое свежие релизы AniLibria.TV</description>
        <lastBuildDate>Wed, 07 Apr 2021 17:59:24 GMT</lastBuildDate>
        <docs>https://validator.w3.org/feed/docs/rss2.html</docs>
        <generator>AniLibria API v2.11.2</generator>
        <language>ru</language>
        <item>
            <title>
            <![CDATA[ Золотое божество 3 / Golden Kamuy 3 | 1-8 [WEBRip 1080p HEVC] ]]>
            </title>
            <link>https://www.anilibria.tv/release/golden-kamuy-3.html</link>
            <guid>12848</guid>
            <pubDate>Tue, 08 Dec 2020 13:50:54 GMT</pubDate>
            <description>
            <![CDATA[ Сугимото отправляется на поиски Асирпы в Карафуто. Также в этом сезоне мы увидим русских солдат, бой с росомахами, путешествующие труппы, харакири, разборки самураев и самое главное — мы узнаем историю и истинные мотивы отца Асирпы. Произойдёт много событий, которые приведут к закрытию некоторых сюжетных арок. ]]>
            </description>
            <enclosure length="2258151277" type="application/x-bittorrent" url="https://static.anilibria.tv/upload/torrents/12848.torrent"/>
        </item>
        ...
    </channel>
</rss>
```

## • franchise/list
Возвращает список всех франшиз

```js
GET /v3/franchise/list
```

### Все доступные параметры
| Параметр | Тип | Описание | По умолчанию |
| - | - | - | - |
| filter | string, ... | Список значений, которые будут в ответе | |
| remove | string, ... | Список значений, которые будут удалены из ответа | |
| [limit](#limit) | int | Количество объектов в ответе | 5 |
| after | int | Удаляет первые n записей из выдачи | |
| page | int | Номер страницы | |
| items_per_page | int | Количество элементов на странице | |

### Примеры запросов
```js
/v3/franchise/list?page=2
```

### Пример ответа

```json
{
  "list": [
    {
      "franchise": {
        "id": "cedc6f71-7fba-401f-903c-a1255db2d6d1",
        "name": "Альдноа.Зеро"
      },
      "releases": [
        {
          "id": 390,
          "code": "aldnoah-zero-aldnoa-zero",
          "ordinal": 1,
          "names": {
            "ru": "Альдноа.Зеро",
            "en": "Aldnoah.Zero",
            "alternative": null
          }
        },
        ...
  ],
  "pagination": {
    "pages": 37,
    "current_page": 2,
    "items_per_page": 5,
    "total_items": 182
  }
}
```

## Пользовательские методы, для которых нужна авторизация

## • user/favorites
Возвращает список избранных тайтлов пользователя

```js
GET /v3/user/favorites
DEPRECATED - GET /v2/getFavorites
```

### Все доступные параметры
| Параметр | Тип | Описание | Обязательный | По умолчанию |
| - | - | - | - | - |
| [session](#session) | string | Уникальный идентификатор сессии пользователя | + | |
| filter | string, ... | Список значений, которые будут в ответе | | |
| remove | string, ... | Список значений, которые будут удалены из ответа | | |
| [include](#include) | string, ... | Список типов файлов, которые будут возвращены в виде base64 строки [подробнее](#include) | | |
| [description_type](#description_type) | string | Тип получаемого описания, [подробнее](#description_type) | | plain |
| playlist_type | string | Формат получаемого списка серий, `object` или `array` | | object |
| [limit](#limit) | int | Количество объектов в ответе | | 5 |
| after | int | Удаляет первые n записей из выдачи | | |
| page | int | Номер страницы | | |
| items_per_page | int | Количество элементов на странице | | |

*В параметрах `filter` и `remove` можно указать полный путь до ключа, который вы хотите оставить или удалить, например: `names.alternative` или `team.voice[0]`. С версии 2.8 появилась возможность получать значения одного ключа во всех объектах в массиве, например: torrents.list[\*].torrent_id*

### Примеры запросов
```js
/v3/user/favorites?session=qwertyqwertyqwerty1234567890
```

### Пример ответа

```json
[
    [Возвращаемые поля идентичны /title],
    ...
]
```
[Подробнее о возвращаемых значениях](#возвращаемые-значения-при-запросе-информации-о-тайтле)

## • user
Возвращает информацию об аккаунте пользователя

```js
GET /v3/user
DEPRECATED - GET /v2/getUser
```

### Все доступные параметры
     Параметр | Тип | Описание | Обязательный
| - | - | - | - |
| [session](#session) | string | Уникальный идентификатор сессии пользователя | + |
| filter | string, ... | Список значений которые будут в ответе | |
| remove | string, ... | Список значений которые будут удалены из ответа | |

*В параметрах `filter` и `remove` можно указать полный путь до ключа который вы хотите оставить или удалить, например `names.alternative` или `team.voice[0]`, с версии 2.8. появилась возможность получать значения одного ключа во всех объектах в массиве, например: torrents.list[\*].torrent_id*

### Примеры запросов
```js
/v3/user?session=qwertyqwertyqwerty1234567890
```

### Пример ответа
```json
{
    "login": "AniLibriaBot",
    "nickname": "AniLibriaBot",
    "email": "bot@anilibria.tv",
    "avatar_original": "/",
    "avatar_thumbnail": "/",
    "vk_id": "/",
    "patreon_id": "/",
}
```
[Подробнее о возвращаемых значениях](#возвращаемые-значения-при-запросе-информации-о-пользователе)



## • user/favorites (add)
Добавить тайтл в список избранных

```js
PUT /v3/user/favorites
DEPRECATED - PUT /v2/addFavorite
```

### Все доступные параметры
| Параметр | Тип | Описание | Обязательный |
| - | - | - | - |
| [session](#session) | string | Уникальный идентификатор сессии пользователя | + |
| title_id | int | ID тайтла который вы хотите добавить | + |


### Примеры запросов
```js
/v3/user/favorites/add?session=qwertyqwertyqwerty1234567890&title_id=8500
```

### Пример ответа

```json
{
    "success": true
}
```

## • user/favorites (remove)
Удалить тайтл из списка избранных

```js
DELETE /v3/user/favorites
DEPRECATED - DELETE /v2/delFavorite
```

### Все доступные параметры
| Параметр | Тип | Описание | Обязательный |
| - | - | - | - |
| [session](#session) | string | Уникальный идентификатор сессии пользователя | + |
| title_id | int | ID тайтла который вы хотите удалить | + |


### Примеры запросов
```js
/v3/user/favorites?session=qwertyqwertyqwerty1234567890&title_id=8500
```

### Пример ответа

```json
{
    "success": true
}
```

### Возвращаемые значения при запросе информации о тайтле

id **int**  – ID тайтла  
code **string** – Код тайтла, используется для создания ссылки  
[names](#names) **object** – Названия тайтла.  
[posters](#posters) **object** – Информация о постере  
updated **int** – Timestamp последнего обновления тайтла (обычно тайтл обновляют при выходе новых релизов)  
last_change **int** – Timestamp последнего изменения тайтла (Например описания, или анонса)  
[status](#status) **object** – Статус тайтла  
[type](#type) **object** – Типа тайтла  
genres **array[string]** – Список жанров  
[team](#team) **object** – Ники членов команды работавших над тайтлом  
[season](#season) **object** – Сезон, год выхода, и день недели из расписания когда выходят новые серии  
year **int** – Год выпуска тайтла  
week_day **int** – День недели, когда выходят новые релизы  
description **string** – Описание тайтла в указанном в [description_type](#description_type) формате  
[franchises](#franchises) **array[object]** – Список франшиз в которых состоит тайтл (может быть несколько)
[blocked](#blocked) **object** – Информация о блокировках тайтла  
[player](#player) **object** – Информация о сериях в плеере  
[torrents](#torrents) **object** – Информация о торрент файлах

### Возвращаемые значения при запросе информации о youtube ролике

id **int**  – ID записи в базе  
title **string** – Название видео ролика  
[preview](#preview) **object** – Ссылка на превью к ролику  
youtube_id **string** – ID видео на YouTube (Легко форматируется в https://youtu.be/{youtube_id})  
timestamp **int** – Timestamp времени добавления в базу  
comments **int** - Количество комментариев у ролика
views **int** - Количество просмотров у ролика

#### preview:
src **string** – Превью видеоролика в полном размере
thumbnail **string** – Превью видеоролика в маленьком размере

### Возвращаемые значения при запросе информации о пользователе
login **string**  – Логин пользователя
nickname **string** – Имя пользователя
email **string** – EMail пользователя
avatar_original **string** – Путь к аватару пользователя
avatar_thumbnail **string** – Путь к превью аватара пользователя
vk_id **string** – ID аккаунта VK
patreon_id **string** – ID аккаунта Patreon

>*В случае отсутствия какой-то информации значение поля будет `null` для строк, пустой массив для массивов, и 0 для чисел*
>*Такое редко, но бывает.*

### Описание возвращаемых объектов

#### names:
> ru **string** – Русское название тайтла  
> en **string** – Английское название тайтла  
> alternative **string** – Альтернативное название

#### posters:
> [small](#poster) **object** – Постер маленького размера  
> [medium](#poster) **object** – Постер среднего размера  
> [original](#poster) **object** – Оригинальный и самый большой постер

##### poster:
> url **string** – Относительный url на постер  
> raw_base64_file **string** – Постер в base64 формате (если запрошен в параметре [include](#include))

#### episodes:
> string **string** – Количество серий в виде строки  
> first **int** – Первая серия  
> last **int** – Последняя серия

#### status:
> string **string** – Статус тайтла в виде строки  
> code **int** – Статус тайтла в виде числа
>> 1 – В работе  
>> 2 – Завершен  
>> 3 – Скрыт  
>> 4 – Неонгоинг

#### type:
> full_string **string** – Тип тайтла целиком в виде строки (как это указано на сайте)  
> string **string** – Тип тайтла в виде строки  
> episodes **int** – Ожидаемое количество серий  
> length **string** – Длительность серий  
> code **int** – Тип тайтла в виде числа 
>> 0 – Фильм  
>> 1 – TV  
>> 2 – OVA  
>> 3 – ONA  
>> 4 – Спешл
>> 5 - WEB

#### team:
> voice **array[string]** – Список войсеров работавших над озвучкой.  
> translator **array[string]** – Список участников команды работавших над переводом.  
> editing **array[string]** – Список участников команды работавших над субтитрами.  
> decor **array[string]** – Список участников команды работавших над оформлением.  
> timing **array[string]** – Список участников команды работавших над таймингом.

#### season:
> year **int** – Список войсеров работавших над озвучкой  
> week_day **int** – День недели. Счет дней недели идет с понедельника, где 0 - Понедельник, а 6 - Воскресенье.  
> string **string** – Название сезона в котором вышел тайтл.  
> code **int** – Код сезона в котором вышел тайтл.
>> 1 - Зима  
>> 2 - Весна  
>> 3 - Лето  
>> 4 - Осень

#### franchises:
*Содержит массив объектов с информацией о франшизах.*
> [franchise](#franchise) **object** – Объект с описанием франшизы
> [releases](#releases) **array[object]** – Объект со списком тайтлов которые входят в франшизу.

##### franchise:
> id **string** – UUID франшизы.
> name **string** – Имя франшизы.

##### releases:
> id **int**  – ID тайтла
> code **string** – Код тайтла, используется для создания ссылки  
> ordinal **int** – Порядковый номер в списке
> [names](#names) **object** – Названия тайтла

#### blocked:
> blocked **bool** – Тайтл заблокирован на территории РФ.  
> bakanim **bool** – Тайтл заблокирован из-за жалобы Wakanim.

***

#### player:
> alternative_player **string** – Ссылка на альтернативный плеер.  
> [host](#host) **object** – Имя сервера для построения ссылок на поток.   
> [list](#listplayer) **object** – Список релизов тайтла со ссылками на просмотр и загрузку.  
> [rutube](#rutube) **object** – Список серий загруженных на рутуб 
> [episodes](#episodes) **object** – Количество вышедших в плеере серий

##### host:
> hls **string** – Ссылка без домена на альтернативный плеер  
Эти имена служат для того, чтобы использовать их как часть ссылки к файлу.
Например: `https://cache.libria.fun/videos/media/ts/9284/3/720/4cc228b26307888f9cb0091ff233a6e3.m3u8` позволит скачать указанный файл с сервера.

##### list (player):
> episode **float** – Номер серии  
> name **string** - Имя серии
> uuid **string** - Уникальный идентификатор серии
> created_timestamp **int** – Время создания/изменения плейлиста в формате unix timestamp  
> preview **string** - Ссылка без домена на превью серии  
> skips **array[int]** - Массив чисел с временем для пропуска опенинга и эндинга  
> [hls](#hls) **object** – Объект, содержащий ссылки на потоковое воспроизведение в разном качестве

##### rutube:
> episode **float** – Номер серии  
> created_timestamp **int** – Время создания/изменения эпизода в формате unix timestamp  
> rutube_id **string** – Идентификатор серии в системе rutube


###### hls:
> fhd **string** – Ссылка без домена на потоковое воспроизведение в Full-HD качестве  
> hd **string** – Ссылка без домена на потоковое воспроизведение в HD качестве  
> sd **string** – Ссылка без домена на потоковое воспроизведение в SD качестве

***

#### torrents:
> [episodes](#episodes) **object** – Серии содержащиеся в файле  
> list **array[object]** – Массив объектов с информацией о торрент файлах

##### list:
*Содержит массив объектов с информацией о торрент файлах.*
> torrent_id **int** – ID торрент файла  
> [episodes](#episodes) **object** – Серии содержащиеся в файле  
> [quality](#quality) **object** – Информации о разрешении, кодировщике и типе релиза  
> leechers **int** – Количество личеров (личей)  
> seeders **int** – Количество сидеров (сидов)  
> downloads **int** – Количество загрузок файла  
> total_size **int** – Размер файлов в торренте в байтах
> size_string **string** – Размер файлов в торренте в человекочитаемом формате
> url **string** – Ссылка на торрент файл без домена  
> magnet **string** – Магнитная ссылка для скачивания торрента.
> uploaded_timestamp **int** – Время загрузки торрента в формате unix timestamp  
> raw_base64_file **string** – Торрент файл в base64 формате (если запрошен в параметре   [include](#include))  
> [metadata](#metadata) **object** – Объект, содержащий метаданные торрент файла
> hash **string** - Хеш торрент файла 

###### quality:
> string **string** – Полная строка с описание качества и типа релиза  
> type **string** – Тип релиза WEBRip, HDRip и  тд...  
> resolution **int** – Разрешение одной стороны изображения. Например 720  
> encoder **string** – Строка указывающая на кодировщик используемый для кодирования   релиза, h264 или h265  
> lq_audio **bool** – Используется ли аудио дорожка с пониженным битрейтом (Для экономии размера файла)

###### metadata:
> hash **string** – Хеш торрент файла  
> name **string** – Имя тайтла в торрент файле  
> announce **array[string]** – Массив строк содержащий список трекеров  
> created_timestamp **int** – Время создания торрента в формате unix timestamp  
> [files_list](#files_list) **array[object]** – Массив объектов содержащий список файлов в торренте

###### files_list:
> file **string** – Имя файла  
> size **int** – Размер файла в байтах  
> size_string **string** – Размер файлов в торренте в человекочитаемом формате
> offset **int64** – Смещение в байтах относительно предыдущего файла

***

#### description_type:
> html – Описание тайтла в виде html (в том виде в каком оно на сайте)  
> plain – Описание тайтла в виде текста без дополнительного форматирования  
> no_view_order – Описание тайтла в виде текста без дополнительного форматирования и порядка просмотра

#### include:
*Это полезно в случае если вы не хотите делать много запросов, такая конструкция позволяет получить все необходимое в одном запросе*
> raw_poster – Добавить постер в base64 формате в ответ  
> raw_torrent – Добавить торрент файлы в base64 формате в ответ  
> torrent_meta - Добавить в ответ метаданные торрента (список файлов, их размер, трекер)

#### limit:
*Количество объектов в ответе*  
Любое положительное число, или -1, чтобы получить все результаты.

#### rss_type:
Предпочитаемый формат вывода RSS ленты

| Название | Описание |
| - | - |
| rss | RSS 2.0 (По умолчанию) |
| atom | Atom 1.0 |
| json | JSON Feed 1.0 |

#### pagination:
> pages - всего страниц
> current_page - текущая страница
> items_per_page - элементов на странице
> total_items - всего элементов

#### query:
Ключи объектов должны быть закрыты в фигурные скобки, например `{names.ru}`

***

### Поддерживаемые операции

#### Операции сравнения

| Операция | Описание |
| - | - |
| `x == y` | Равно |
| `x != y` | Не равно |
| `x < y` | Меньше чем |
| `x <= y` | Меньше чем или равно |
| `x > y` | Больше чем |
| `x >= y` | Больше чем или равно |
| `x ~= y` | Регулярное выражение |
| `x in (a, b, c)` | Эквивалент (x == a or x == b or x == c) |
| `x not in (a, b, c)` | Эквивалент (x != a and x != b and x != c) |

#### Логические операции

| Операция | Описание |
| - | - |
| `x and y` | Логическое И |
| `x or y` | Логическое ИЛИ |
| `not x` | Логическое НЕ |
| `if x then y else  z` | Условие x, истина y, ложь z |
| `( x )` | Оператор приоритета, например `(x == y or y < 10) and z)` |

#### Математические операции

| Операция | Описание |
| - | - |
| `x + y` | Сложение |
| `x - y` | Вычитание |
| `x * y` | Умножение |
| `x / y` | Деление |
| `x % y` | Модуль |
| `x ^ y` | Степень |

#### Операции с объектами и массивами

| Операция | Описание |
| - | - |
| `(a, b, c)` | Массив |
| `a in b` | Массив a является подмножеством массива b |
| `x of y` | Свойство x объекта y |

#### Встроенные функции

| Функция | Описание |
| - | - |
| `abs(x)` | Абсолютное значение |
| `ceil(x)` | Округление float числа вверх |
| `empty(x)` | True если x undefined, null, пустой массив или строка |
| `exists(x)` | True если x не undefined или null | |
| `floor(x)` | Округление float числа вниз |
| `log(x)` | Натуральный логарифм |
| `log2(x)` | Логарифм по основанию 2 |
| `log10(x)` | Логарифм по основанию 10 |
| `max(a, b, c...)` | Максимальное число из указанных (число аргументов может быть любым) |
| `min(a, b, c...)` | Минимальное число из указанных (число аргументов может быть любым) |
| `round(x)` | Простое округление float |
| `sqrt(x)` | Квадратный корень |
| `len(x)` | Длина массива или строки |
| `randomInt(min, max)` | Случайное число в указанном диапазоне |

#### simple_query:
Упрощенная и более быстрая альтернатива query для прямого сравнения.
Прямое сравнение ключа, путь к ключу нужно указывать без скобок как в query, например 
Допустим только параметр прямого сравнения через ==
`simple_query=status.code==null`
`simple_query=player.series.last==1`

### Получение session id:

Для получения ключа, нужно отправить POST запрос на адрес  
`https://www.anilibria.tv/public/login.php`

| Параметр | Тип | Описание | Обязательный |
| - | - | - | - |
| mail | string | Логин или электронная почта от аккаунта | + |
| passwd | string | Пароль от аккаунта | + |

После успешной авторизации в ответе будет значение с ключом `sessionId`, а в cookies `PHPSESSID`, оба значения одинаковы.

Стоит помнить:
* Авторизация может пропасть в любой момент, т.к. время жизни сессии ограничено

***

### Возможные коды ошибок
```json
{
    "error": { "code": 500, "message": "Internal Server Error!" }
}
```

Возникает в случае непредвиденной внутренней ошибки сервера.
> Все 5хх ошибки возвращают статус код 500, хотя в теле ответа будет указан настоящий код.

```json
{
    "error": { "code": 412, "message": "Unknown parameters: code, id" }
}
```

Возникает в случае если передать неизвестный параметр в запросе.

```json
{
    "error": { "code": 404, "message": "Title "test" not found!" }
}
```
Возникает в случае если запрошеный тайтл отсутствует в базе.  
и другие...

>В случае ошибки HTTP Status дублирует код из **error.code** (Исключение 5хх ошибки)
>В случае успешного выполнения запроса будет возвращен HTTP Status:  **200**
***

### Полезное
1. Версию API можно узнать в заголовке `API-Version` и сравнить ее с версией вашей документации, чтоб узнать актуальна ли документация.
2. Версии меняются по принципу <Major>.<Minor>.<Patch> где Major обозначает очень крупные изменения в коде проекта без обратной совместимости, Minor обозначает существенные изменения в API с частичной обратной совместимостью, и Patch исправление различных ошибок, не влияет на совместимость.
3. Имеется поддержка версионирования и для продакшен релизов следует указывать минорный патч, дабы при обновлении у вас ничего не сломалось.
Таким образом теперь можно использовать либо последнюю версию с последними исправлениями, либо старую минорную со старой схемой которая не будет обновляться и меняться со временем.
Например, сейчас можно использовать ссылки следующего формата:
>Ссылка на актуальную мажорную версию API - https://api.anilibria.tv/v2/...
>Ссылка на минорный патч со всеми исправлениями (рекомендуется к использованию в продакшене) - https://api.anilibria.tv/v2.13/...
>Ссылка на патч (можно не использовать так как ссылка на патч будет вести на актуальный минор) - https://api.anilibria.tv/v2.13.15/...
* Версионирование не работает в вебсокетах, там всегда схема latest билда.
4. Полезно использовать фильтры при запросе информации о множестве тайтлов, к примеру: исключив информацию о плеере, вы существенно сэкономите время ответа, если она вам не нужна.

## WebSocket

### Подключение

```js
ws(s)://api.anilibria.tv/v3/ws/
```

или

```js
ws(s)://api.anilibria.tv/v3/webSocket/
```

> **ВебСокет каждые 30 секунд отправляет ping пакет, который проверяет соединение.**

### Уведомления

Основной объект уведомлений имеет следующую структуру:
type **string** - Тип уведомления.  
data **object** - Объект с данными

#### title_update

При обновлении какой-либо информации о тайтле Веб Сокет отправляет всем клиентам строку в JSON формате:

```json
{
    "type": "title_update",
    "data": {
        "title": {
            [Возвращаемые поля идентичны /title]
        },
        "diff": {
            [Те ключи и значения, что были изменены, добавлены или удалены]
        }
    }
}
```

##### Возвращаемые значения

title **object** – Объект, содержащий все поля из /title  
diff **object** – Объект, содержащий информацию о том, какие данные были изменены, добавлены или удалены.

#### playlist_update

При обновлении плейлиста тайтла, что происходит при добавлении или перезаливе релиза, ВебСокет отправляет всем клиентам строку в формате:

```json
{
    "type": "playlist_update",
    "data": {
        "id": 8700,
        "player": {объект плеера},
        "updated_episode": {объект плейлиста},
        "episode": "2",
        "diff": {Те ключи и значения, что были изменены, добавлены или удалены},
        "reupload": false
    }
}
```

##### Возвращаемые значения

id **int** – ID обновленного тайтла.  
[player](#player) **object** – Объект, содержащий информация о плеере.  
updated_episode **object** - Объект, содержащий все поля из объекта [playlist](#playlist).  
episode **int** – Номер вышедшего или перезалитого релиза.  
diff **object** – Объект, содержащий информацию о том, какие данные были изменены, добавлены или удалены.  
reupload **bool** - Означает, перезалив это или нет.

#### encode_start
При начале кодирования серии в плеер, что происходит при добавлении или перезаливе релиза, ВебСокет отправляет всем клиентам строку в формате:
```json
{
    "type": "encode_start",
    "data": {
        "id": "8700",
        "episode": "4",
        "resolution": "480",
        "quality": "sd",
        "isReupload": false
    }
}
```

##### Возвращаемые значения
id **string** – ID обновленного тайтла.  
episode **string** – Номер вышедшего или перезалитого релиза.  
resolution **string** - Разрешение, в котором была скодирована серия.  
quality **string** – Качество, в котором кодируется серия (одно из значений [hls](#hls)).
isReupload **bool** - Означает, перезалив это или нет.

#### encode_end

Когда серия успешно скодирована в определённом качестве, ВебСокет отправляет всем клиентам строку в формате:

```json
{
    "type": "encode_end",
    "data": {
        "id": "8700",
        "episode": "4",
        "resolution": "480",
        "quality": "sd"
    }
}
```

##### Возвращаемые значения

id **string** – ID обновленного тайтла.  
episode **string** – Номер вышедшего или перезалитого релиза.  
resolution **string** - Разрешение, в котором была скодирована серия.  
quality **string** – Качество, в котором стала доступна серия (одно из значений [hls](#hls)).

#### encode_progress

На каждые 5% кодирования в определённом качестве, Веб Сокет будет отправлять клиентам строку в формате:

```json
{
    "type": "encode_progress",
    "data": {
        "id": "8700",
        "episode": "4",
        "resolution": "480",
        "quality": "sd",
        "encoded_percent": "25"
    }
}
```

##### Возвращаемые значения
id **string** – ID обновленного тайтла.  
episode **string** – Номер вышедшего или перезалитого релиза.  
resolution **string** - Разрешение, в котором была скодирована серия.  
quality **string** – Качество, в котором кодируется серия (одно из значений [hls](#hls)).  
encoded_percent **string** – процент кодирования.


#### encode_finish
Когда серия успешно скодирована во всех качествах, ВебСокет отправляет клиентам строку в формате:

```json
{
 	"type": "encode_finish",
    "data": {
   	 	"id": "8700",
    	"episode": "4"
	}
}
```

##### Возвращаемые значения

id **string** – ID обновленного тайтла.  
episode **string** – Номер вышедшего или перезалитого релиза.

#### torrent_update

При обновлении информации о торренте, Веб Сокет будет отправлять клиентам строку в формате:

```json
{
    "type": "torrent_update",
    "data": {
        "id": "9215",
        "torrents": {
            "episodes": {
                "first": 1,
                "last": 1,
                "string": "1-1"
            },
            "list": [
                {
                    "torrent_id": 19973,
                    "episodes": {
                        "first": 1,
                        "last": 1,
                        "string": "1"
                    },
                    "quality": {
                        "string": "WEBRip 1080p",
                        "type": "WEBRip",
                        "resolution": "1080p",
                        "encoder": "h264",
                        "lq_audio": null
                    },
                    "leechers": 2,
                    "seeders": 25,
                    "downloads": 32,
                    "total_size": 547428705,
                    "url": "/public/torrent/download.php?id=19973",
                    "uploaded_timestamp": 1657158936,
                    "hash": "51a8800ca1a6486b352227a37da0c5d3dbba59c7",
                    "metadata": null,
                    "raw_base64_file": null
                },
                ...
            ]
        },
        "updated_torrent_id": 19973,
        "diff": {
            "list": {
                "0": {
                    "torrent_id": 19972,
                    "leechers": 4,
                    "seeders": 18,
                    "downloads": 23,
                    "total_size": 547426413,
                    "url": "/public/torrent/download.php?id=19972",
                    "uploaded_timestamp": 1657156714,
                    "hash": "f3d72484bdc41265bd025dbbb24ea8bcb63a1abf"
                }
            }
        },
    }
}
```

##### Возвращаемые значения

id **string** – ID обновленного тайтла.  
[torrents](#torrents) **object** – Информация о торрент файлах.  
updated_torrent_id **int** - ID обновлённого торрента.  
diff **object** – Объект, содержащий информацию о том, какие данные были изменены, добавлены или удалены.  

### Подписка на уведомления

По умолчанию, при подключении, клиент подписывается на все уведомления сразу, но после добавления подписки, вебсокет будет отправлять только те уведомления, которые соответствуют заданым фильтрам.

```json
{
    "subscribe": {
        "data": {
            "title": {
                "season": {
                    "year": 2022
                }
            }
        }
        // Тут могут быть любые поля и значения, которые отправляет вебсокет.
    }
}
```

#### Пример ответа

```json
{
    "subscribe": "success",
    "subscription_id": 0
}
```

Можно отправить несколько запросов с подпиской, тогда вебсокет будет отправлять уведомление при совпадении с одним из них.  

В качестве значения также можно указать `*`, что будет соответствовать любому значению указанного поля.  
Таким образом, к примеру, можно подписаться на получение уведомлений только о выходе всех серий начиная с 12.

```json
{
    "subscribe": {
        "data": {
            "title": {
                "player": {
                    "playlist": {
                        "12": *
                    }
                }
            }
        }
    }
}
```

#### Фильтры

При добавлении подписки можно указать какие поля вебсокет будет вам отправлять.  
Работает это так же, как и при указании фильтров к GET запросам.  
Например:

```json
{
    "subscribe": {
        "data": {
            "title": {
                "season": {
                    "year": 2022
                }
            }
        }
    },
    "filter": "names,season",
    "remove": "names.en"
}
```

***

# Полезное для разработчиков:
Типы для typescript к версии V3 - https://gitlab.com/anilibria/anilibria-types