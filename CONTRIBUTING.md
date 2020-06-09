# Contributing

## Prerequisites

Before you start, please note that the ability to use following technologies is **required** and that existing contributors will not actively teach them to you.

- Basic [Android development](https://developer.android.com/)
- [Kotlin](https://kotlinlang.org/)
- Web scraping
    - [HTML](https://developer.mozilla.org/en-US/docs/Web/HTML)
    - [CSS selectors](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_Selectors)
    - [OkHttp](https://square.github.io/okhttp/)
    - [JSoup](https://jsoup.org/)

### Tools

- [Android Studio](https://developer.android.com/studio)
- Emulator or phone with developer options enabled and a recent version of Tachiyomi installed


## Writing an extension

The quickest way to get started is to copy an existing extension's folder structure and renaming it as needed. Of course, that also means that there's plenty of existing extensions that you can reference as you go!

### Setting up a new Gradle module

Make sure that your new extension's `build.gradle` file follows the following structure:

```gradle
apply plugin: 'com.android.application'
apply plugin: 'kotlin-android'

ext {
    appName = 'Tachiyomi: My source name'
    pkgNameSuffix = 'lang.mysourcename'
    extClass = '.MySourceName'
    extVersionCode = 1
    libVersion = '1.2'
}

apply from: "$rootDir/common.gradle"
```

| Field | Description |
| ----- | ----------- |
| `appName` | The name of the Android application. Should be prefixed with `Tachiyomi: `. |
| `pkgNameSuffix` | A unique suffix added to `eu.kanade.tachiyomi.extension`. The language and the site name should be enough. Remember your extension code implementation must be placed in this package. |
| `extClass` | Points to the class that implements `Source`. You can use a relative path starting with a dot (the package name is the base path). This is used to find and instantiate the source(s). |
| `extVersionCode` | The extension version code. This must be a positive integer and incremented with any change to the code. |
| `libVersion` | The version of the [extensions library](https://github.com/tachiyomiorg/extensions-lib) used. |

The extension's version name is based off of `libVersion` and `extVersionCode`. With the example used above, the version of the catalogue would be `1.2.1`.

### Core dependencies

#### Extension API

Extensions rely on [extensions-lib](https://github.com/tachiyomiorg/extensions-lib), which provides some interfaces and stubs from the [app](https://github.com/inorichi/tachiyomi) for compilation purposes. The actual implementations can be found [here](https://github.com/inorichi/tachiyomi/tree/dev/app/src/main/java/eu/kanade/tachiyomi/source). Referencing the actual implementation will help with understanding extensions' call flow.

#### Duktape stub

[`duktape-stub`](https://github.com/inorichi/tachiyomi-extensions/tree/master/lib/duktape-stub) provides stubs for using Duktape functionality without pulling in the full library. Functionality is bundled into the main Tachiyomi app.

```
dependencies {
    compileOnly project(':duktape-stub')
}
```

#### Rate limiting library

[`lib-ratelimit`](https://github.com/inorichi/tachiyomi-extensions/tree/master/lib/ratelimit) is a library for adding rate limiting functionality as an [OkHttp interceptor](https://square.github.io/okhttp/interceptors/).

```
dependencies {
    implementation project(':lib-ratelimit')
}
```

#### Additional dependencies

You may find yourself needing additional functionality and wanting to add more dependencies to your `build.gradle` file. Since extensions are run within the main Tachiyomi app, you can make use of [its dependencies](https://github.com/inorichi/tachiyomi/blob/master/app/build.gradle).

For example, an extension that needs Gson could add the following:

```
dependencies {
    compileOnly 'com.google.code.gson:gson:2.8.2'
}
```

Notice that we're using `compileOnly` instead of `implementation`, since the app already contains it. You could use `implementation` instead for a new dependency, or you prefer not to rely on whatever the main app has at the expense of app size.

Note that using `compileOnly` restricts you to versions that must be compatible with those used in Tachiyomi v0.8.5+ for proper backwards compatibility.

### Extension call flow

- **Extension Main Class**
    - The extension Main class which is refrenced and defined by `extClass`(inside `build.gradle`) should be inherited from either `SourceFactory` or one of `Source` children: `HttpSource` or `ParsedHttpSource`.
    - `SourceFactory` is used to expose multiple `Source`s, only use it when there's **minor difference** between your target sources or they are essentially mirrors to the same website.
    - `HttpSource` as in it's name is for a online http(s) source, but `ParsedHttpSource` has a good model of work which makes writing scrapers for normal aggregator websites much easier and streamlined. (again, you can find the implementation of the stubs in the app as mentioned above)  
- The app starts by finding your extension and reads these variables:

| Field | Description |
| ----- | ----------- |
| `name` | Name to your target source as displayed in the `sources` tab inside the app |
| `id` | identifier of your source, automatically set from `HttpSource`. It should only be manually set if you need to copy an existing autogenerated ID. | 
| `supportsLatest` | if `true` the app adds a `latest` button to your extension |
| `baseUrl` | base URL of the target source without any trailing slashes |
| `lang` | as the documentation says "An ISO 639-1 compliant language code (two letters in lower case).", it will be used to catalog your extension |
 
- **Popular Manga**
    - When user presses on the source name or the `Browse` button on the sources tab, the app calls `fetchPopularManga` with `page=1`,  and it returns a `MangasPage` and will continue to call it for next pages, when the user scrolls the manga list and more results must be fetched(until you pass `MangasPage.hasNextPage` as `false` which marks the end of the found manga list)
    - While passing magnas here you should at least set `url`, `title` and *`thumbnail_url`; `url` must be unique since it's used to index mangas in the DataBase.(this information will be cached and you will have a chance to update them when `fetchMangaDetails` is called later).
    - You may not set `thumbnail_url`, which will make the app call `fetchMangaDetails` over every single manga to show the cover, so it's better to set the thumbnail cover in `fetchPopularManga` if possible. The same is true with latest and search.
- **Latest Manga**
    - If `supportsLatest` is set to true the app shows a `Latest` button in front for your extension `name` and when the user taps on it, the app will call `fetchLatestUpdates` and the rest of the flow is similar to what happens with `fetchPopularManga`.
    - If `supportsLatest` is set to false no `Latest` button will be shown and `fetchLatestUpdates` and subsequent methods will never be called.
- **Manga Search**
    - `getFilterList` will be called to get all filters and filter types. **TODO: explain more about `Filter`**
    - when the user searches inside the app, `fetchSearchManga` will be called and the rest of the flow is similar to what happens with `fetchPopularManga`.
- **Manga Details**
    - When user taps on a manga and opens it's information Activity `fetchMangaDetails` and `fetchChapterList` will be called the resulting information will be cached.
    - `fetchMangaDetails` is called to update a manga's details from when it vas initialized earlier(you may want to parse a manga details page here and fill the rest of the fields)
        - Note: During a backup, only `url` and `title` are stored, and to restore the rest of the manga data the app calls `fetchMangaDetails`. so you need to fill the rest of the fields, specially `thumbnail_url`.
   - `fetchChapterList` is called to display the chapter list, you want to return a reversed list here(last chapter, first index in the list)
- **Chapter**
    - After a chapter list for the manga is fetched, `prepareNewChapter` will be called, after that the chapter will be saved in the app's DataBase and later if the chapter list changes the app will loose any references to the chapter(but chapter files will still be in the device storage)
- **Chapter Pages**
    - When user opens a chapter, `fetchPageList` will be called and it will return a list of `Page`
    - While a chapter is open the reader will call `fetchImageUrl` to get URLs for each page of the manga

### Misc notes

- Some time while you are writing code, you may find no use for some inherited methods, if so just override them and throw exceptions: `throw Exception("Not used")`
- You probably will find `getUrlWithoutDomain` useful when parsing the target source URLs.
- If possible try to stick to the general workflow from`ParsedHttpSource` and `HttpSource`, breaking them may cause you more headache than necessary.
-  When reading the code documentation it helps to follow the subsequent called methods in the the default implementation from the `app`, while trying to grasp the general workflow.



## Running

To aid in local development, you can use the following run configuration to launch an extension:

![](https://i.imgur.com/STy0UFY.png)

If you're running a Preview or debug build of Tachiyomi:

```
-W -S -n eu.kanade.tachiyomi.debug/eu.kanade.tachiyomi.ui.main.MainActivity -a eu.kanade.tachiyomi.SHOW_CATALOGUES
```

And for a release build of Tachiyomi:

```
-W -S -n eu.kanade.tachiyomi/eu.kanade.tachiyomi.ui.main.MainActivity -a eu.kanade.tachiyomi.SHOW_CATALOGUES
```

### Debugging

Directly debugging your extension (i.e steping through the extension code) is not possible due to the way that extension code is loaded into the app. However, logs printed from extensions (via [`Logcat`](https://developer.android.com/studio/debug/am-logcat)) do work.


## Building

APKs can be created in Android Studio via `Build > Build Bundle(s) / APK(s) > Build APK(s)` or `Build > Generate Signed Bundle / APK`.
